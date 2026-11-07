package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * "Dischi": real records to sample, like Tracklib. They come from the Internet Archive's 78 rpm
 * collections (Great 78 Project), filtered to recordings made up to 1925, which are in the public
 * domain in the US (Music Modernization Act: 1923-1925 recordings entered it 2024-2026), so they
 * can be chopped and used freely. Jazz, blues, ragtime, gospel, orchestras, solo instruments,
 * voices: no generic 808/drum one-shots or random sound effects any more.
 * Sources checked from CI on 2026-11-07 (search + mp3 download work without any key).
 */
class WebSamples(private val http: OkHttpClient) {
    data class WebSample(
        val id: String, val name: String, val pack: String, val url: String, val sizeKb: Long,
        val bpm: Int?, val key: String?,
        val artist: String = "", val year: Int? = null, val seconds: Int? = null,
    )

    /** Category chip -> archive.org terms. */
    val categories: List<Pair<String, String>> = listOf(
        "Jazz" to "(subject:jazz OR subject:\"dance orchestra\" OR title:blues OR subject:foxtrot)",
        "Blues" to "(subject:blues OR title:blues)",
        "Ragtime" to "(subject:ragtime OR title:rag)",
        "Gospel" to "(subject:spiritual OR subject:gospel OR subject:hymn OR subject:sacred)",
        "Orchestra" to "(subject:orchestra OR subject:orchestral OR subject:symphony OR subject:overture)",
        "Piano" to "(subject:piano OR title:piano)",
        "Chitarra" to "(subject:guitar OR subject:banjo OR subject:mandolin OR subject:ukulele)",
        "Fiati" to "(subject:cornet OR subject:trumpet OR subject:saxophone OR subject:clarinet OR subject:trombone OR subject:band)",
        "Archi" to "(subject:violin OR subject:cello OR subject:\"string quartet\" OR subject:strings)",
        "Voci" to "(subject:vocal OR subject:soprano OR subject:tenor OR subject:baritone OR subject:quartet)",
        "Latino" to "(subject:tango OR subject:latin OR subject:rumba OR subject:habanera OR subject:mexican OR subject:spanish)",
        "Valzer" to "(subject:waltz OR title:waltz)",
    )

    private val base = "(collection:georgeblood OR collection:78rpm) AND mediatype:audio AND date:[1890-01-01 TO 1925-12-31]"
    private val cache = mutableMapOf<String, List<WebSample>>()
    private val fileCache = mutableMapOf<String, Pair<String, Int?>?>()

    private suspend fun get(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Vibes/1.0 (Android)").build()).execute().use {
                if (!it.isSuccessful) null else Supa.json.parseToJsonElement(it.body.string()) as? JsonObject
            }
        }.getOrNull()
    }

    private fun esc(s: String) = s.replace(Regex("""[^\p{L}\p{N}\s'-]"""), " ").trim()

    /** Records for a free-text [query] and/or a [category] (one of [categories]); 30 per page. */
    suspend fun search(query: String, category: String? = null, page: Int = 1): List<WebSample> = coroutineScope {
        val ck = "${query.lowercase()}|$category|$page"
        cache[ck]?.let { return@coroutineScope it }
        val parts = mutableListOf(base)
        categories.firstOrNull { it.first == category }?.let { parts += it.second }
        esc(query).split(Regex("\\s+")).filter { it.length > 1 }.forEach { w ->
            parts += "(title:\"$w\" OR creator:\"$w\" OR subject:\"$w\")"
        }
        val q = parts.joinToString(" AND ")
        val url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, "UTF-8") +
                "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=date&sort[]=downloads+desc&rows=30&page=$page&output=json"
        val docs = get(url)?.get("response")?.jsonObject?.get("docs")?.jsonArray.orEmpty()
        val gate = Semaphore(8)
        val out = docs.mapNotNull { it as? JsonObject }.map { d ->
            async {
                gate.withPermit {
                    val id = d["identifier"]?.jsonPrimitive?.contentOrNull ?: return@withPermit null
                    val title = d["title"]?.jsonPrimitive?.contentOrNull ?: return@withPermit null
                    val creator = runCatching { d["creator"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull }.getOrNull()
                        ?: d["creator"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: ""
                    val year = d["date"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull()
                    val (file, secs) = audioFile(id) ?: return@withPermit null
                    WebSample(
                        id = "ia:$id", name = title.trim(), pack = listOfNotNull(creator.ifBlank { null }, year?.toString()).joinToString(" · "),
                        url = "https://archive.org/download/$id/" + file.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") },
                        sizeKb = 0, bpm = null, key = null, artist = creator, year = year, seconds = secs,
                    )
                }
            }
        }.awaitAll().filterNotNull().distinctBy { it.name.lowercase() + it.artist.lowercase() }
        cache[ck] = out
        out
    }

    /** The playable mp3 of a record (the restored transfer when there is one) and its length. */
    private suspend fun audioFile(id: String): Pair<String, Int?>? {
        if (fileCache.containsKey(id)) return fileCache[id]
        val files = get("https://archive.org/metadata/$id")?.get("files")?.jsonArray.orEmpty().mapNotNull { it as? JsonObject }
        val mp3s = files.filter { (it["name"]?.jsonPrimitive?.contentOrNull ?: "").endsWith(".mp3", true) }
            .filter { !(it["name"]?.jsonPrimitive?.contentOrNull ?: "").contains("_sample", true) }
        val pick = mp3s.firstOrNull { (it["name"]?.jsonPrimitive?.contentOrNull ?: "").contains("restored", true) } ?: mp3s.firstOrNull()
        val res = pick?.let {
            val name = it["name"]!!.jsonPrimitive.content
            val len = it["length"]?.jsonPrimitive?.contentOrNull?.let { l ->
                l.toDoubleOrNull()?.toInt() ?: l.split(":").mapNotNull { p -> p.toIntOrNull() }.fold(0) { a, b -> a * 60 + b }
            }
            name to len
        }
        fileCache[id] = res
        return res
    }
}
