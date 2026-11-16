package dev.brahmkshatriya.echo.vibes.music

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.RankedArtist
import dev.brahmkshatriya.echo.vibes.backend.RankedName
import dev.brahmkshatriya.echo.vibes.backend.RankedSong
import dev.brahmkshatriya.echo.vibes.backend.Records
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import dev.brahmkshatriya.echo.vibes.backend.WeekSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One listening session of one song: when it started and how long it was really heard. */
@Serializable
data class Listen(
    val song: SongRef,
    val at: Long,
    val ms: Long,
    /** Playlist/album the song was played from, if any. */
    val ctx: String? = null,
    val ctxId: String? = null,
    val ctxCover: String? = null,
)

@Serializable
private data class LogFile(
    val listens: List<Listen> = emptyList(),
    /** Deezer album id -> genre name (cached lookups). */
    val genres: Map<String, String> = emptyMap(),
    /** artist name -> picture url (cached lookups). */
    val artistImages: Map<String, String> = emptyMap(),
)

/**
 * Vibes' listening history. Every song is logged with the real time it played (not just "started"),
 * so minutes, top songs, top artists and records are honest. Weekly stats are computed from this.
 */
class ListenLog(context: Context, private val deezer: DeezerPublic) {
    private val file = File(context.filesDir, "vibes_listens.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Supa.json
    private val mutex = Mutex()

    private var data: LogFile = runCatching { json.decodeFromString(LogFile.serializer(), file.readText()) }.getOrDefault(LogFile())
    private val _listens = MutableStateFlow(data.listens)
    val listens = _listens.asStateFlow()

    /**
     * A song counts as "listened" (plays, top songs, minutes) only after a THIRD of it has really
     * played. [length] is the song's real length from the player; if unknown, the song's own
     * duration, else 1 minute (a third of a typical 3-minute song). Returns whether it counted.
     */
    fun add(l: Listen, length: Long = 0L): Boolean {
        val dur = length.takeIf { it > 0 } ?: l.song.duration?.takeIf { it > 0 } ?: 180_000L
        if (l.ms < dur / 3) return false
        data = data.copy(listens = (data.listens + l).takeLast(25_000))
        _listens.value = data.listens
        save()
        return true
    }

    /** Called after [clear], so the account's copy (other devices) is wiped too. */
    var onCleared: (() -> Unit)? = null

    fun clear() {
        data = data.copy(listens = emptyList()); _listens.value = emptyList(); save()
        onCleared?.invoke()
    }

    /** Everything heard on this device, oldest first (the account sync uploads it). */
    fun all(): List<Listen> = data.listens

    /**
     * Listens from the account's other devices (phone + PC): added if not already here, so
     * minutes, plays, tops and records are the account's total. Returns how many were new.
     */
    fun merge(remote: List<Listen>): Int {
        if (remote.isEmpty()) return 0
        val have = data.listens.mapTo(HashSet()) { it.at to it.song.key }
        val fresh = remote.filter { (it.at to it.song.key) !in have }.distinctBy { it.at to it.song.key }
        if (fresh.isEmpty()) return 0
        data = data.copy(listens = (data.listens + fresh).sortedBy { it.at }.takeLast(25_000))
        _listens.value = data.listens
        save()
        return fresh.size
    }

    private fun save() = scope.launch {
        mutex.withLock {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(LogFile.serializer(), data))
                tmp.renameTo(file)
            }
        }
    }

    // ---------------------------------------------------------------- weeks

    private val zone get() = ZoneId.systemDefault()
    fun weekStart(at: Long): LocalDate =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val thisWeek: LocalDate get() = weekStart(System.currentTimeMillis())

    /** All weeks with listening, newest first. */
    fun weeks(): List<LocalDate> = data.listens.map { weekStart(it.at) }.distinct().sortedDescending()
        .let { if (thisWeek in it) it else listOf(thisWeek) + it }

    private fun artistsOf(s: SongRef) = s.artist.split(", ").map { it.trim() }.filter { it.isNotBlank() }

    /** Summary of one week. Genres and artist pictures come from Deezer (cached). */
    suspend fun summary(week: LocalDate): WeekSummary {
        val from = week.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = week.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val list = data.listens.filter { it.at in from until to }
        val before = data.listens.filter { it.at < from }.flatMap { artistsOf(it.song) }.toSet()

        val bySong = list.groupBy { it.song.key }.map { (_, ls) ->
            RankedSong(ls.last().song, ls.size, (ls.sumOf { it.ms } / 60_000).toInt())
        }.sortedWith(compareByDescending<RankedSong> { it.plays }.thenByDescending { it.minutes })

        val artistMs = mutableMapOf<String, Long>()
        list.forEach { l -> artistsOf(l.song).firstOrNull()?.let { artistMs[it] = (artistMs[it] ?: 0) + l.ms } }
        val topArtists = artistMs.entries.sortedByDescending { it.value }.take(10)
        val artists = topArtists.map { (name, ms) -> RankedArtist(name, artistImage(name), (ms / 60_000).toInt()) }

        val playlists = list.filter { it.ctx != null }.groupBy { it.ctxId ?: it.ctx!! }.map { (_, ls) ->
            RankedName(ls.first().ctx!!, (ls.sumOf { it.ms } / 60_000).toInt(), ls.first().ctxCover, ls.first().ctxId)
        }.sortedByDescending { it.minutes }.take(5)

        val genreMs = mutableMapOf<String, Long>()
        val genreSongs = list.groupBy { it.song.key }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }.take(25)
        genreSongs.forEach { (_, ls) -> genreOf(ls.first().song)?.let { g -> genreMs[g] = (genreMs[g] ?: 0) + ls.sumOf { it.ms } } }
        val genres = genreMs.entries.sortedByDescending { it.value }.take(5).map { RankedName(it.key, (it.value / 60_000).toInt()) }

        val days = list.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.distinct().size
        return WeekSummary(
            weekStart = week.toString(),
            minutes = (list.sumOf { it.ms } / 60_000).toInt(),
            plays = list.size,
            artists = artists,
            songs = bySong.take(10),
            playlists = playlists,
            genres = genres,
            newArtists = artistMs.keys.count { it !in before },
            activeDays = days,
        )
    }

    /** All-time records: most plays of one song in a week, most minutes in a week, longest daily streak. */
    fun records(): Records {
        var best = Records()
        data.listens.groupBy { weekStart(it.at) }.forEach { (week, ls) ->
            val mins = (ls.sumOf { it.ms } / 60_000).toInt()
            if (mins > best.mostMinutes) best = best.copy(mostMinutes = mins, mostMinutesWeek = week.toString())
            ls.groupBy { it.song.key }.maxByOrNull { it.value.size }?.let { (_, s) ->
                if (s.size > best.mostPlays) best = best.copy(mostPlays = s.size, mostPlaysSong = s.last().song, mostPlaysWeek = week.toString())
            }
        }
        val days = data.listens.map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.distinct().sorted()
        var streak = 0; var longest = 0; var prev: LocalDate? = null
        days.forEach { d -> streak = if (prev != null && prev!!.plusDays(1) == d) streak + 1 else 1; longest = maxOf(longest, streak); prev = d }
        return best.copy(longestStreakDays = longest)
    }

    // ---------------------------------------------------------------- Deezer lookups (cached)

    private suspend fun artistImage(name: String): String? {
        data.artistImages[name]?.let { return it.ifBlank { null } }
        val img = runCatching {
            deezer.searchArtists(name, 3).firstOrNull { it.name.equals(name, true) }?.picture
                ?: deezer.searchArtists(name, 1).firstOrNull()?.picture
        }.getOrNull()
        data = data.copy(artistImages = data.artistImages + (name to (img ?: "")))
        save()
        return img
    }

    private suspend fun genreOf(s: SongRef): String? {
        val trackId = if (s.ext == MusicEngine.DEEZER && s.id.all(Char::isDigit)) s.id else null
        val cacheKey = "t:" + (trackId ?: (s.title + "|" + s.artist))
        data.genres[cacheKey]?.let { return it.ifBlank { null } }
        val g = runCatching {
            val t = trackId?.let { deezer.track(it) } ?: deezer.match(s.title, s.artist)
            t?.let { deezer.trackGenre(it.id) }
        }.getOrNull()
        data = data.copy(genres = data.genres + (cacheKey to (g ?: "")))
        save()
        return g
    }
}
