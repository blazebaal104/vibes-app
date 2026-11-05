package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * Free samples from the web: sample packs and loop collections on the Internet Archive
 * (no API key needed). Each pack is opened and its short audio files become samples.
 */
class WebSamples(private val http: OkHttpClient) {
    data class WebSample(val id: String, val name: String, val pack: String, val url: String, val sizeKb: Long, val bpm: Int?, val key: String?)

    private val cache = mutableMapOf<String, List<WebSample>>()

    private suspend fun get(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Vibes/1.0").build()).execute().use { r ->
                if (!r.isSuccessful) null else Supa.json.parseToJsonElement(r.body.string()).jsonObject
            }
        }.getOrNull()
    }

    private val bpmRe = Regex("""(?i)(\d{2,3})\s?bpm""")
    private val keyRe = Regex("""(?i)(?:^|[\s_\-(])([A-G](?:#|b)?)\s?(m(?:in(?:or)?)?|maj(?:or)?)?(?=$|[\s_\-).])""")

    suspend fun search(query: String): List<WebSample> = coroutineScope {
        val q = query.trim().ifBlank { "drum" }
        cache[q.lowercase()]?.let { return@coroutineScope it }
        val terms = URLEncoder.encode(q, "UTF-8")
        val search = "https://archive.org/advancedsearch.php?q=($terms)+AND+mediatype%3Aaudio+AND+(subject%3Asample*+OR+subject%3Aloop*+OR+title%3Asample*+OR+title%3Aloop*+OR+subject%3A%22sound+effects%22)" +
                "&fl%5B%5D=identifier&fl%5B%5D=title&sort%5B%5D=downloads+desc&rows=8&output=json"
        val docs = get(search)?.get("response")?.jsonObject?.get("docs")?.jsonArray.orEmpty()
        val packs = docs.mapNotNull { d -> (d as? JsonObject)?.let { it["identifier"]?.jsonPrimitive?.contentOrNull to (it["title"]?.jsonPrimitive?.contentOrNull ?: "") } }
            .filter { it.first != null }
        val out = packs.map { (id, title) ->
            async {
                val files = get("https://archive.org/metadata/$id")?.get("files")?.jsonArray.orEmpty()
                files.mapNotNull { f ->
                    val o = f as? JsonObject ?: return@mapNotNull null
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val fmt = o["format"]?.jsonPrimitive?.contentOrNull ?: ""
                    val size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                    val audio = name.endsWith(".wav", true) || name.endsWith(".mp3", true) || name.endsWith(".ogg", true) || name.endsWith(".flac", true) ||
                            fmt.contains("MP3", true) || fmt.contains("WAVE", true)
                    if (!audio || size > 6_000_000 || size == 0L) return@mapNotNull null
                    val clean = name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
                    WebSample(
                        id = "ia:$id/$name", name = clean, pack = title, sizeKb = size / 1024,
                        url = "https://archive.org/download/$id/" + name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") },
                        bpm = bpmRe.find(clean)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 50..220 },
                        key = keyRe.find(clean)?.let { m -> m.groupValues[1].uppercase() + if (m.groupValues[2].startsWith("m", true) && !m.groupValues[2].startsWith("maj", true)) "m" else "" },
                    )
                }.distinctBy { it.name }.take(12)
            }
        }.awaitAll().flatten()
        cache[q.lowercase()] = out
        out
    }
}
