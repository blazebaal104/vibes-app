package dev.brahmkshatriya.echo.vibes.music

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Sample library: a catalog of musical samples (melodic loops, chords, vocal chops, pads,
 * instruments; no drum kits, 808s, screams or noise) built weekly in GitHub Actions from
 * Freesound (CC0 / CC-BY only) and published as ONE JSON file. The app downloads it once, keeps it
 * on the phone and searches locally: results appear instantly, also offline. Audio = Freesound CDN
 * previews (start fast). See tools/build_sample_catalog.py.
 */
class WebSamples(private val http: OkHttpClient) {
    @Serializable
    data class Item(
        val id: String, @SerialName("n") val name: String, @SerialName("u") val user: String = "",
        @SerialName("l") val license: String = "", @SerialName("c") val category: String = "",
        @SerialName("t") val tags: List<String> = emptyList(), @SerialName("d") val duration: Double = 0.0,
        @SerialName("b") val bpm: Int? = null, @SerialName("k") val key: String? = null,
        @SerialName("p") val preview: String, @SerialName("r") val rating: Double = 0.0,
        @SerialName("dl") val downloads: Int = 0, val loop: Boolean = false,
    )

    @Serializable
    data class Catalog(val version: Long = 0, val items: List<Item> = emptyList())

    data class WebSample(
        val id: String, val name: String, val pack: String, val url: String, val sizeKb: Long,
        val bpm: Int?, val key: String?, val loop: Boolean = false, val seconds: Int? = null, val category: String = "",
    )

    companion object {
        const val URL = "${Supa.URL}/storage/v1/object/public/vibes-updates/samples/catalog.json"
        private const val MAX_AGE = 24L * 3600_000
    }

    private var catalog: Catalog? = null
    /** Lower-case text of each item (name, tags, category, author), built once per catalog. */
    private var haystack: List<String> = emptyList()
    private val lock = Mutex()
    private var cacheFile: File? = null

    fun init(context: Context) { cacheFile = File(context.filesDir, "sample_catalog.json") }

    /** Categories in the order the catalog was built. */
    val categories: List<Pair<String, String>>
        get() = (catalog?.items?.map { it.category }?.distinct() ?: listOf(
            "Piano", "Chitarra", "Voci", "Pad", "Synth", "Archi", "Fiati", "Soul & Jazz", "Lo-fi", "Bassi", "Plucks & Bells", "Cinematic"
        )).map { it to it }

    /** Loads the catalog: phone copy first (instant), refreshed from the server once a day. */
    suspend fun load(): Catalog = lock.withLock {
        catalog?.let { return it }
        withContext(Dispatchers.IO) {
            val f = cacheFile
            val local = f?.takeIf { it.exists() }?.let { runCatching { Supa.json.decodeFromString<Catalog>(it.readText()) }.getOrNull() }
            val fresh = f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE
            val c = if (local != null && fresh) local else runCatching {
                http.newCall(Request.Builder().url(URL).build()).execute().use { res ->
                    if (!res.isSuccessful) error("catalog ${res.code}")
                    val text = res.body.string()
                    Supa.json.decodeFromString<Catalog>(text).also { f?.writeText(text) }
                }
            }.getOrElse { local ?: throw it }
            catalog = c
            haystack = c.items.map { s -> (s.name + " " + s.tags.joinToString(" ") + " " + s.category + " " + s.user).lowercase() }
            c
        }
    }

    /** Local search: words match name, tags, category or author; bpm/key filters; best first. */
    suspend fun search(query: String, category: String? = null, loopsOnly: Boolean? = null, bpm: IntRange? = null, key: String? = null): List<WebSample> {
        val c = load()
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val hay = haystack
        return c.items.indices.asSequence()
            .filter { i -> words.isEmpty() || words.all { w -> hay[i].contains(w) } }
            .map { c.items[it] }
            .filter { category == null || it.category == category }
            .filter { loopsOnly == null || it.loop == loopsOnly }
            .filter { bpm == null || it.bpm == null || it.bpm in bpm }
            .filter { key == null || it.key?.startsWith(key, true) == true }
            .take(300)
            .map { s ->
                WebSample(
                    id = s.id, name = s.name,
                    pack = listOf(s.category, "di ${s.user}", s.license).filter { it.isNotBlank() }.joinToString(" · "),
                    url = s.preview, sizeKb = 0, bpm = s.bpm, key = s.key, loop = s.loop,
                    seconds = s.duration.toInt(), category = s.category,
                )
            }.toList()
    }
}
