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

    suspend fun pick(d: Difficulty): GameTrack? = mutex.withLock {
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

    fun stop() { stopJob?.cancel(); player.pause() }

    fun release() { stopJob?.cancel(); player.release() }
}
