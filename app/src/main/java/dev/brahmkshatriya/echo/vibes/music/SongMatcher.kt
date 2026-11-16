package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the Vibes (Deezer) version of a song from another service, as surely and as fast as it can:
 *
 * 1. shared memory: songs any Vibes user already matched come back at once, no search at all;
 * 2. ISRC code (when the source gives it): the exact same recording;
 * 3. the search of the user's Deezer account (no request limit, best catalogue) when connected;
 * 4. the public Deezer search, several ways: artist + title, title + artist, cleaned title, title alone.
 *
 * Every candidate gets a score (title, artists, duration): the best wins, look-alikes are refused.
 */
class SongMatcher(private val deezer: DeezerPublic, private val supa: Supa?) {

    /** A song to find. */
    data class Want(val title: String, val artist: String, val durationMs: Long? = null, val isrc: String? = null, val swappable: Boolean = false)
    data class Found(val song: SongRef, val score: Double)

    /** Set by the app: search with the user's Deezer account (null / empty when not connected). */
    @Volatile var engineSearch: (suspend (String) -> List<SongRef>)? = null

    private val memory = ConcurrentHashMap<String, SongRef>()

    // ------------------------------------------------------------------ normalising + scoring

    private val featRe = Regex("""(?i)\s*[\(\[]?\s*(feat\.?|ft\.?|featuring|con|with)\s+[^\)\]]*[\)\]]?""")
    private val versionRe = Regex("""(?i)\s*[\(\[][^\)\]]*(remaster|version|versione|edit|mix|radio|mono|stereo|live|acoustic|deluxe|bonus|explicit|clean|from |soundtrack|anniversary)[^\)\]]*[\)\]]""")
    private val dashVersionRe = Regex("""(?i)\s+-\s+.*(remaster|version|versione|edit|mix|radio|mono|stereo|live|acoustic|deluxe|from |single|anniversary|\d{4}).*$""")

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "")
        .replace("&", " and ").replace("$", "s").replace("ø", "o")
        .replace(Regex("""[^a-z0-9\s]"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    /** The title without "feat. X", "(Remastered 2011)", " - Radio Edit"... */
    fun core(title: String): String = title.replace(versionRe, "").replace(dashVersionRe, "").replace(featRe, "").trim().ifBlank { title }

    fun artists(a: String): List<String> = a.split(Regex("""(?i)\s*(,|&|\bx\b|\bfeat\.?|\bft\.?|\band\b|\be\b|/|;)\s*""")).map { norm(it) }.filter { it.isNotBlank() }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }; val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    /** 0..1: same words in any order, or the same text with a few typos. */
    fun sim(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val tx = x.split(" ").toSet(); val ty = y.split(" ").toSet()
        val jac = tx.intersect(ty).size.toDouble() / tx.union(ty).size
        val contain = if (tx.containsAll(ty) || ty.containsAll(tx)) 0.9 * minOf(tx.size, ty.size) / maxOf(tx.size, ty.size) + 0.1 else 0.0
        val ratio = 1.0 - lev(x, y).toDouble() / maxOf(x.length, y.length)
        return maxOf(jac, contain, ratio)
    }

    private val versionWords = Regex("""(?i)\b(remix|live|acoustic|instrumental|karaoke|cover|sped up|slowed|nightcore|demo)\b""")

    fun score(w: Want, s: SongRef): Double {
        val title = maxOf(sim(w.title, s.title), sim(core(w.title), core(s.title)))
        val wa = artists(w.artist); val sa = artists(s.artist)
        val artist = if (wa.isEmpty()) 0.6 else maxOf(sim(w.artist, s.artist), wa.maxOf { a -> sa.maxOfOrNull { b -> sim(a, b) } ?: 0.0 })
        var sc = 0.6 * title + 0.4 * artist
        // a remix / live / acoustic version when the original was asked: not the same song
        val extra = versionWords.findAll(s.title).map { it.value.lowercase() }.toSet() - versionWords.findAll(w.title).map { it.value.lowercase() }.toSet()
        if (extra.isNotEmpty()) sc -= 0.15
        val d1 = w.durationMs; val d2 = s.duration
        if (d1 != null && d1 > 0 && d2 != null && d2 > 0) {
            val diff = kotlin.math.abs(d1 - d2) / 1000.0
            sc += when { diff <= 3 -> 0.05; diff <= 10 -> 0.0; diff <= 30 -> -0.08; else -> -0.2 }
        }
        return sc.coerceIn(0.0, 1.0)
    }

    private fun best(w: Want, list: List<SongRef>): Found? =
        list.map { Found(it, score(w, it)) }.maxByOrNull { it.score }

    // ------------------------------------------------------------------ shared memory (server)

    private fun key(w: Want) = norm(core(w.title)) + "|" + (artists(w.artist).firstOrNull() ?: "")

    /** Loads what other users already matched for these songs (one request per 60 songs). */
    suspend fun prefetch(wants: List<Want>) {
        val supa = supa ?: return
        val keys = wants.flatMap { listOfNotNull(key(it), it.isrc?.let { i -> "isrc:" + i.uppercase() }) }.distinct().filter { !memory.containsKey(it) }
        keys.chunked(60).forEach { chunk ->
            runCatching {
                val list = chunk.joinToString(",") { "\"" + it.replace("\"", "") + "\"" }
                supa.select("vibes_match_cache", "select=key,song&key=in.(" + java.net.URLEncoder.encode(list, "UTF-8") + ")").forEach { e ->
                    val o = e as? JsonObject ?: return@forEach
                    val k = (o["key"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@forEach
                    runCatching { Supa.json.decodeFromJsonElement<SongRef>(o["song"]!!) }.getOrNull()?.let { memory[k] = it }
                }
            }
        }
    }

    private val toSave = ConcurrentHashMap<String, SongRef>()

    /** Shares the new matches with everyone (one request at the end). */
    suspend fun flush() {
        val supa = supa ?: return
        if (toSave.isEmpty()) return
        val rows = buildJsonArray {
            toSave.forEach { (k, s) -> add(buildJsonObject { put("key", k); put("song", Supa.json.encodeToJsonElement(s.copy(data = null))) }) }
        }
        toSave.clear()
        runCatching { supa.upsert("vibes_match_cache", rows) }
    }

    private fun remember(w: Want, s: SongRef) {
        val k = key(w); memory[k] = s; toSave[k] = s
        w.isrc?.let { val ik = "isrc:" + it.uppercase(); memory[ik] = s; toSave[ik] = s }
    }

    // ------------------------------------------------------------------ the search

    /**
     * Finds [w]. [relaxed] = second pass for what the first didn't find: wider searches and a lower bar.
     * Returns null only when nothing good enough exists.
     */
    suspend fun match(w: Want, relaxed: Boolean = false): Found? {
        val accept = if (relaxed) 0.60 else 0.72
        val good = 0.88
        var top: Found? = null
        fun consider(f: Found?) { if (f != null && (top == null || f.score > top!!.score)) top = f }

        // 1. shared memory (checked again with the score: a wrong entry can't slip in)
        (w.isrc?.let { memory["isrc:" + it.uppercase()] } ?: memory[key(w)])?.let { s ->
            val f = Found(s, score(w, s)); if (f.score >= accept) return f
        }
        // 2. ISRC: the same recording
        w.isrc?.takeIf { it.isNotBlank() }?.let { isrc ->
            deezer.byIsrc(isrc)?.toSongRef()?.let { s -> val f = Found(s, maxOf(score(w, s), 0.95)); remember(w, s); return f }
        }
        val title = core(w.title)
        val artistList = w.artist.split(Regex("""\s*(,|&|feat\.?|ft\.?)\s*""")).map { it.trim() }.filter { it.isNotBlank() }
        val first = artistList.firstOrNull().orEmpty()

        // 3. the user's Deezer account (fast, no limit)
        engineSearch?.let { search ->
            for (q in listOf("$title $first", if (relaxed) title else null).filterNotNull()) {
                consider(best(w, runCatching { search(q) }.getOrDefault(emptyList())))
                if ((top?.score ?: 0.0) >= good) break
            }
        }
        // 4. public Deezer search, from the most precise to the widest
        if ((top?.score ?: 0.0) < good) {
            val queries = buildList {
                add("artist:\"$first\" track:\"$title\"")
                add("$title $first")
                if (core(w.title) != w.title) add("${w.title} $first")
                artistList.drop(1).take(2).forEach { add("artist:\"$it\" track:\"$title\"") }
                if (relaxed) { add("track:\"$title\""); add(title) }
            }.distinct()
            for (q in queries) {
                consider(best(w, deezer.searchAny(q, 10).map { it.toSongRef() }))
                if ((top?.score ?: 0.0) >= good) break
            }
        }
        val f = top?.takeIf { it.score >= accept }
            // a written list may be "Title - Artist" instead of "Artist - Title"
            ?: return if (w.swappable) match(w.copy(title = w.artist, artist = w.title, swappable = false), relaxed) else null
        remember(w, f.song)
        return f
    }
}
