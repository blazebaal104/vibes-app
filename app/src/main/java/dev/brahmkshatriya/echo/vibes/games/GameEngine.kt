package dev.brahmkshatriya.echo.vibes.games

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic.GameTrack
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.music.VibesLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer

enum class Difficulty(val label: String, val minRank: Int, val maxRank: Int) {
    EASY("Facile", 850_000, Int.MAX_VALUE),
    MEDIUM("Medio", 700_000, 850_000),
    HARD("Difficile", 500_000, 700_000),
    IMPOSSIBLE("Impossibile", 0, 500_000);

    fun next() = entries.getOrNull(ordinal + 1)

    companion object {
        fun ofRank(rank: Int) = entries.first { rank >= it.minRank && rank < it.maxRank }
    }
}

/** Clip lengths of "Indovina il brano": every wrong guess or skip unlocks the next one. */
val clipSteps = listOf(100L, 1_000L, 2_000L, 5_000L, 10_000L)

/**
 * Picks songs for the games. Difficulty mixes two things, as the user asked:
 *  - songs from your own library: the ones you play most are Easy, the ones you barely play are hard;
 *  - songs you may never have played, by Deezer popularity (rank): global hits are Easy, deep cuts
 *    from artists related to your taste get rarer and rarer up to Impossible.
 */
/** Which songs a game uses: anything (personalised), one artist, or one genre. */
@kotlinx.serialization.Serializable
data class GameSource(val kind: String = RANDOM, val id: Long = 0, val name: String = "", val image: String? = null) {
    val label get() = when (kind) { ARTIST, GENRE -> name; else -> "Casuale" }
    companion object {
        const val RANDOM = "random"; const val ARTIST = "artist"; const val GENRE = "genre"
        val Random = GameSource()
    }
}

class TrackPicker(private val deezer: DeezerPublic, private val library: VibesLibrary) {
    private val mutex = Mutex()
    private val pool = mutableMapOf<Difficulty, MutableList<GameTrack>>()
    private val personal = mutableMapOf<Difficulty, MutableList<GameTrack>>()
    private val used = mutableSetOf<Long>()
    private var built = false

    private fun add(map: MutableMap<Difficulty, MutableList<GameTrack>>, t: GameTrack, d: Difficulty = Difficulty.ofRank(t.rank)) {
        val list = map.getOrPut(d) { mutableListOf() }
        if (list.none { it.id == t.id }) list += t
    }

    private suspend fun build() = coroutineScope {
        if (built) return@coroutineScope
        // 1) personal songs (only Deezer ones have public previews), split by how much you play them
        val known = library.knownSongs().filter { it.first.ext == MusicEngine.DEEZER && it.first.id.all(Char::isDigit) }
            .sortedByDescending { it.second }.take(60)
        val resolved = known.map { (s, plays) -> async { deezer.track(s.id)?.let { it to plays } } }.awaitAll().filterNotNull()
        if (resolved.isNotEmpty()) {
            val q = (resolved.size / 4).coerceAtLeast(1)
            resolved.forEachIndexed { i, (t, _) ->
                val byPlays = Difficulty.entries[(i / q).coerceAtMost(3)]
                // a hit you never play is still easier than an obscure song: take the easier of the two
                val byRank = Difficulty.ofRank(t.rank)
                add(personal, t, if (byRank.ordinal < byPlays.ordinal) byRank else byPlays)
            }
        }
        // 2) discovery: global chart + artists related to the ones you listen to
        deezer.chart(100).forEach { add(pool, it) }
        val seeds = (resolved.map { it.first.artistId } + pool.values.flatten().map { it.artistId })
            .filter { it > 0 }.distinct().shuffled().take(8)
        val related = seeds.map { async { deezer.related(it) } }.awaitAll().flatten().distinctBy { it.first }.shuffled().take(14)
        related.map { (id, _) -> async { deezer.artistTop(id, 12) } }.awaitAll().flatten().forEach { add(pool, it) }
        built = true
    }

    private val billionCache = mutableMapOf<String, GameTrack?>()

    /** EASY = a random song from the Billions Club (1B+ Spotify streams), resolved on Deezer. */
    private suspend fun pickBillion(): GameTrack? = withContext(Dispatchers.IO) {
        repeat(8) {
            val (title, artist) = Billions.songs.random()
            val key = "$title|$artist"
            val t = if (billionCache.containsKey(key)) billionCache[key]
            else runCatching { deezer.match(title, artist) }.getOrNull().also { billionCache[key] = it }
            if (t != null && t.id !in used && t.preview.isNotBlank()) return@withContext t
        }
        null
    }

    suspend fun pick(d: Difficulty): GameTrack? = mutex.withLock {
        if (d == Difficulty.EASY) pickBillion()?.let { used += it.id; return@withLock it }
        withContext(Dispatchers.IO) { runCatching { build() } }
        fun from(map: Map<Difficulty, List<GameTrack>>, diff: Difficulty) =
            map[diff].orEmpty().filter { it.id !in used }.randomOrNull()
        val usePersonal = (0..9).random() < 4
        val choice = (if (usePersonal) from(personal, d) else null)
            ?: from(pool, d)
            ?: from(personal, d)
            // nothing left at this level: nearest level, easier first
            ?: Difficulty.entries.sortedBy { kotlin.math.abs(it.ordinal - d.ordinal) * 2 + if (it.ordinal > d.ordinal) 1 else 0 }
                .firstNotNullOfOrNull { from(pool, it) ?: from(personal, it) }
        choice?.also { used += it.id }
    }

    private val sourcePools = mutableMapOf<String, List<GameTrack>>()

    private suspend fun sourcePool(src: GameSource): List<GameTrack> {
        val key = "${src.kind}:${src.id}:${src.name}"
        sourcePools[key]?.let { return it }
        val list = when (src.kind) {
            GameSource.ARTIST -> {
                val top = deezer.artistTop(src.id, 100)
                // songs where the artist is only featured are fine, but keep the main artist's first
                top.distinctBy { Matching.normalize(it.title) }
            }
            GameSource.GENRE -> dev.brahmkshatriya.echo.vibes.music.Genres.all.firstOrNull { it.name == src.name }
                ?.let { dev.brahmkshatriya.echo.vibes.music.Genres.tracks(deezer, it) }.orEmpty()
                .distinctBy { Matching.normalize(it.title) + "|" + it.artist }
            else -> emptyList()
        }.sortedByDescending { it.rank }
        if (list.isNotEmpty()) sourcePools[key] = list
        return list
    }

    /** How many playable songs a source has (to warn before starting). */
    suspend fun count(src: GameSource): Int = if (src.kind == GameSource.RANDOM) 100 else mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { sourcePool(src).size }.getOrDefault(0) }
    }

    /**
     * Pick from a chosen artist/genre. Difficulty is relative to that list: the artist's biggest hits
     * are "easy", their deep cuts "impossible".
     */
    suspend fun pick(d: Difficulty, src: GameSource): GameTrack? {
        if (src.kind == GameSource.RANDOM) return pick(d)
        return mutex.withLock {
            val list = withContext(Dispatchers.IO) { runCatching { sourcePool(src) }.getOrDefault(emptyList()) }
            if (list.isEmpty()) return@withLock null
            val q = (list.size / 4.0)
            fun quarter(i: Int) = list.subList((i * q).toInt(), ((i + 1) * q).toInt().coerceAtMost(list.size))
                .filter { it.id !in used }
            val order = Difficulty.entries.sortedBy { kotlin.math.abs(it.ordinal - d.ordinal) * 2 + if (it.ordinal > d.ordinal) 1 else 0 }
            order.firstNotNullOfOrNull { quarter(it.ordinal).randomOrNull() }?.also { used += it.id }
        }
    }

    /** Artists that sound like [artist] (Deezer's related artists): wrong answers that are hard to rule out. */
    suspend fun similarArtists(artistId: Long, artist: String, n: Int): List<String> = withContext(Dispatchers.IO) {
        if (artistId <= 0) return@withContext emptyList()
        runCatching { deezer.related(artistId) }.getOrDefault(emptyList()).map { it.second }
            .filter { it.isNotBlank() && !it.equals(artist, true) }.distinct().shuffled().take(n)
    }

    /** Distinct artists for multiple-choice options. */
    suspend fun otherArtists(except: String, n: Int): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { build() } }
        (pool.values.flatten() + personal.values.flatten()).map { it.artist }
            .filter { !it.equals(except, true) }.distinct().shuffled().take(n)
    }
}

object Matching {
    private val junk = Regex("""\s*[\(\[][^)\]]*[\)\]]""")
    private val feat = Regex("""\s+(feat\.?|ft\.?|featuring|with)\s+.*$""", RegexOption.IGNORE_CASE)
    private val dash = Regex("""\s+-\s+.*(remaster|version|edit|mix|live).*$""", RegexOption.IGNORE_CASE)

    fun normalize(s: String): String {
        var t = s.lowercase()
        t = junk.replace(t, " ")
        t = dash.replace(t, "")
        t = feat.replace(t, "")
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        t = t.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        return t
    }

    fun isCorrect(guess: GameTrack, target: GameTrack): Boolean {
        if (guess.id == target.id) return true
        val sameTitle = normalize(guess.title) == normalize(target.title)
        val artistOk = normalize(guess.artist).let { g -> val a = normalize(target.artist); g == a || g.contains(a) || a.contains(g) }
        return sameTitle && artistOk
    }
}

/** Separate little player for 30 s Deezer previews, so games never touch the real queue. */
class ClipPlayer(context: Context) {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private val scope = MainScope()
    private var stopJob: Job? = null
    private var loadedUrl: String? = null
    var onStateChange: ((Boolean) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { onStateChange?.invoke(isPlaying) }
        })
    }

    fun load(url: String) {
        if (url == loadedUrl) return
        loadedUrl = url
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    /** Plays the first [ms] milliseconds of the loaded preview. */
    fun playClip(ms: Long) {
        stopJob?.cancel()
        player.seekTo(0)
        player.play()
        stopJob = scope.launch {
            // wait until audio actually starts, so 0.1 s is really 0.1 s of sound
            var waited = 0
            while (!player.isPlaying && waited < 4000) { delay(10); waited += 10 }
            delay(ms)
            player.pause()
        }
    }

    fun playFull(onEnd: () -> Unit = {}) {
        stopJob?.cancel()
        player.seekTo(0)
        player.play()
        stopJob = scope.launch {
            while (player.playbackState != Player.STATE_ENDED) delay(250)
            onEnd()
        }
    }

    fun position() = player.currentPosition

    /** Loops the preview from [startMs] (stories: the part of the song the author chose). */
    fun playFrom(startMs: Long, volume: Float = 1f) {
        stopJob?.cancel()
        player.volume = volume
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.seekTo(startMs)
        player.play()
    }

    fun resume() { player.play() }

    fun setVolume(v: Float) { player.volume = v }

    fun stop() { stopJob?.cancel(); player.pause() }

    fun release() { stopJob?.cancel(); player.release() }
}

/**
 * Game audio: the FULL song from its real beginning, played as a plain sound (own ExoPlayer, no media
 * session, no notification, the user's queue is untouched). The start of the track is fetched through
 * the Deezer extension (already decrypted) into a small temp file; if that fails it falls back to
 * the 30 s Deezer preview. Clips stop on the real playback position, not on a timer.
 */
class EngineClip(private val engine: dev.brahmkshatriya.echo.vibes.music.MusicEngine, context: Context, layered: Boolean = false) {
    private val app = context.applicationContext
    private val filter = LayerFilter()
    private val player = if (layered) layeredPlayer(app, filter) else ExoPlayer.Builder(app).build()

    /** "Strati" game: low-pass cutoff in Hz (0 = full song). */
    fun setCutoff(hz: Float) { filter.cutoffHz = hz }
    private val scope = MainScope()
    private var job: Job? = null
    private var prefetch: kotlinx.coroutines.Deferred<androidx.media3.common.MediaItem?>? = null
    private var preview: String? = null
    private var loaded: String? = null
    private var playing = false
    var onStateChange: ((Boolean) -> Unit)? = null

    private fun setPlaying(p: Boolean) { if (p != playing) { playing = p; onStateChange?.invoke(p) } }

    private val dir get() = java.io.File(app.cacheDir, "game_audio").apply { mkdirs() }

    fun load(t: GameTrack) {
        job?.cancel(); player.pause(); setPlaying(false)
        preview = t.preview
        loaded = null
        dir.listFiles()?.forEach { it.delete() }
        prefetch = scope.async(Dispatchers.IO) { runCatching { fetchStart(t) }.getOrNull() }
    }

    /** Resolves the real stream of the track and keeps its first ~2.5 MB (well over 10 s of audio). */
    private suspend fun fetchStart(t: GameTrack): androidx.media3.common.MediaItem? = kotlinx.coroutines.withTimeout(15_000) {
        val ext = engine.extension()
        val song = t.toSongRef()
        val full = ext.getAs<dev.brahmkshatriya.echo.common.clients.TrackClient, dev.brahmkshatriya.echo.common.models.Track> {
            loadTrack(song.toTrack(), false)
        }.getOrThrow()
        val server = full.servers.firstOrNull() ?: return@withTimeout null
        val media = ext.getAs<dev.brahmkshatriya.echo.common.clients.TrackClient, dev.brahmkshatriya.echo.common.models.Streamable.Media> {
            loadStreamableMedia(server, false)
        }.getOrThrow() as? dev.brahmkshatriya.echo.common.models.Streamable.Media.Server ?: return@withTimeout null
        when (val src = media.sources.firstOrNull()) {
            is dev.brahmkshatriya.echo.common.models.Streamable.Source.Raw -> {
                val (input, _) = src.streamProvider!!.provide(0, -1)
                val file = java.io.File(dir, "t_${t.id}.audio")
                input.use { i ->
                    file.outputStream().use { o ->
                        val buf = ByteArray(32 * 1024); var total = 0
                        while (total < 2_500_000) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); total += n }
                    }
                }
                androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file))
            }
            is dev.brahmkshatriya.echo.common.models.Streamable.Source.Http ->
                if (src.decryption != null) null
                else androidx.media3.common.MediaItem.fromUri(src.request.url)
            else -> null
        }
    }

    /** Prepares the full-song start (or the preview) once per track. */
    private suspend fun ensureLoaded() {
        val item = runCatching { kotlinx.coroutines.withTimeout(12_000) { prefetch?.await() } }.getOrNull()
        val key = item?.localConfiguration?.uri?.toString() ?: preview
        if (key == loaded) return
        loaded = key
        player.setMediaItem(item ?: androidx.media3.common.MediaItem.fromUri(preview ?: return))
        player.prepare()
    }

    private suspend fun startFromZero(): Boolean {
        ensureLoaded()
        player.seekTo(0)
        player.play()
        var waited = 0
        while (!player.isPlaying && waited < 8000) { delay(10); waited += 10 }
        return player.isPlaying
    }

    fun playClip(ms: Long) {
        job?.cancel()
        job = scope.launch {
            if (!startFromZero()) { player.pause(); return@launch }
            setPlaying(true)
            while (player.currentPosition < ms && player.isPlaying) delay(5)
            player.pause()
            setPlaying(false)
        }
    }

    fun playFull(onEnd: () -> Unit = {}) {
        job?.cancel()
        job = scope.launch {
            if (!startFromZero()) return@launch
            setPlaying(true)
            while (player.playbackState != androidx.media3.common.Player.STATE_ENDED) delay(250)
            setPlaying(false)
            onEnd()
        }
    }

    fun position(): Long = player.currentPosition

    fun stop() { job?.cancel(); player.pause(); setPlaying(false) }

    fun release() { stop(); prefetch?.cancel(); player.release(); dir.listFiles()?.forEach { it.delete() } }
}

/** "Chi canta?" levels. Harder = less famous songs AND wrong answers that sound alike, less time. */
enum class BlindLevel(val label: String, val emoji: String, val seconds: Int, val points: Float, val hint: String) {
    EASY("Facile", "🙂", 10, 1f, "Grandi successi e artisti molto diversi tra loro"),
    MEDIUM("Intermedio", "😎", 8, 1.5f, "Brani meno ovvi, risposte dello stesso genere"),
    HARD("Difficile", "🔥", 6, 2f, "Pezzi poco noti e quattro artisti che si somigliano");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: MEDIUM

        /**
         * Suggested level from how much you listened in the last 7 days: under 1.5 h easy, under
         * 10 h intermediate, more = difficult. You can still change it before playing.
         */
        fun recommended(): BlindLevel {
            val since = System.currentTimeMillis() - 7L * 24 * 3600_000
            val minutes = runCatching {
                dev.brahmkshatriya.echo.vibes.VibesGraph.listens.listens.value.filter { it.at >= since }.sumOf { it.ms } / 60_000
            }.getOrDefault(0L)
            return when {
                minutes < 90 -> EASY
                minutes < 600 -> MEDIUM
                else -> HARD
            }
        }
    }
}

/** Builds the 8 questions of "Chi canta?" for a level: songs + the 4 artists to choose from. */
object BlindQuiz {
    suspend fun build(picker: TrackPicker, level: BlindLevel): Pair<List<GameTrack>, List<List<String>>> {
        val diffs = when (level) {
            BlindLevel.EASY -> List(4) { Difficulty.EASY } + List(4) { Difficulty.MEDIUM }
            BlindLevel.MEDIUM -> List(4) { Difficulty.MEDIUM } + List(4) { Difficulty.HARD }
            BlindLevel.HARD -> List(4) { Difficulty.HARD } + List(4) { Difficulty.IMPOSSIBLE }
        }
        val tracks = diffs.mapNotNull { picker.pick(it) }
        val options = tracks.map { t ->
            val similar = when (level) {
                BlindLevel.EASY -> 0
                BlindLevel.MEDIUM -> 2
                BlindLevel.HARD -> 3
            }
            val alike = if (similar > 0) picker.similarArtists(t.artistId, t.artist, similar) else emptyList()
            val rest = picker.otherArtists(t.artist, 3 - alike.size + 2).filter { it !in alike }.take(3 - alike.size)
            (alike + rest + t.artist).shuffled()
        }
        return tracks to options
    }
}
