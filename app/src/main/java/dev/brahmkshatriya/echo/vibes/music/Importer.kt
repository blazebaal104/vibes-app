package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.async
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * Imports a playlist or album from another service into Vibes: same name, same cover, same songs
 * (each song is matched to the Deezer catalogue so Vibes can play it).
 *
 * Supported: Deezer links (public API) and Spotify links (the public embed page, which lists up to
 * ~100 tracks without needing a Spotify account).
 */
class Importer(private val deezer: DeezerPublic, private val library: VibesLibrary, private val http: okhttp3.OkHttpClient) {

    data class Progress(val done: Int, val total: Int, val found: Int, val stage: String)
    data class Source(val name: String, val cover: String?, val items: List<Pair<String, String>>?, val deezerTracks: List<DeezerPublic.GameTrack>?)

    /** One song of the source playlist and what Vibes found for it (null = not found). */
    data class PreviewItem(val title: String, val artist: String, val match: SongRef?)
    data class Preview(val service: String, val name: String, val cover: String?, val items: List<PreviewItem>)

    private val youtubeRe = Regex("""(?:youtube\.com|youtu\.be).*[?&]list=([A-Za-z0-9_-]+)""")

    /** Name of the service if the text contains a link Vibes can import, else null. */
    fun detect(raw: String): String? {
        val url = Regex("""https?://\S+""").find(raw)?.value ?: raw
        return when {
            deezerRe.containsMatchIn(url) || url.contains("link.deezer.com") || url.contains("deezer.page.link") -> "Deezer"
            spotifyRe.containsMatchIn(url) || url.contains("spotify.link") -> "Spotify"
            youtubeRe.containsMatchIn(url) -> if (url.contains("music.youtube")) "YouTube Music" else "YouTube"
            else -> null
        }
    }

    /** Reads the playlist and matches every song, WITHOUT saving: the user checks it first. */
    suspend fun preview(rawUrl: String, onProgress: (Progress) -> Unit): Preview {
        var url = Regex("""https?://\S+""").find(rawUrl)?.value ?: rawUrl.trim()
        if (url.contains("link.deezer.com") || url.contains("spotify.link") || url.contains("deezer.page.link"))
            url = deezer.resolveUrl(url)
        onProgress(Progress(0, 0, 0, "Leggo la playlist…"))
        val service = detect(url) ?: throw IllegalArgumentException("Link non riconosciuto: incolla il link di una playlist di Spotify, Deezer o YouTube Music")
        val source = when (service) {
            "Deezer" -> fromDeezer(url)
            "Spotify" -> fromSpotify(url)
            else -> fromYouTube(url)
        }
        val items: List<PreviewItem> = source.deezerTracks?.map { PreviewItem(it.title, it.artist, it.toSongRef()) }
            ?: matchItems(source.items.orEmpty(), onProgress)
        if (items.isEmpty()) throw IllegalStateException("Non ho trovato nessun brano in questa playlist")
        return Preview(service, source.name.ifBlank { "Playlist importata" }, source.cover, items)
    }

    /** Saves the checked songs with the chosen name and cover. */
    fun save(name: String, cover: String?, songs: List<SongRef>): VibesPlaylist {
        val p = library.createPlaylist(name = name.ifBlank { "Playlist importata" }, emoji = "📥", color = "#1ED760", songs = songs.distinctBy { it.key })
        if (cover != null) library.setPlaylistCover(p.id, cover)
        return library.playlist(p.id) ?: p
    }

    private suspend fun matchItems(items: List<Pair<String, String>>, onProgress: (Progress) -> Unit): List<PreviewItem> = coroutineScope {
        val sem = Semaphore(6)
        val done = AtomicInteger(0); val found = AtomicInteger(0)
        onProgress(Progress(0, items.size, 0, "Cerco i brani…"))
        items.map { (title, artist) ->
            async {
                sem.withPermit {
                    val t = runCatching { deezer.match(title, artist) }.getOrNull()
                    if (t != null) found.incrementAndGet()
                    onProgress(Progress(done.incrementAndGet(), items.size, found.get(), "Cerco i brani…"))
                    PreviewItem(title, artist, t?.toSongRef())
                }
            }
        }.awaitAll()
    }

    // ------------------------------------------------------------------ YouTube / YouTube Music

    private val ytUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private suspend fun ytRequest(req: okhttp3.Request.Builder): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            http.newCall(req.header("User-Agent", ytUa).header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                .header("Cookie", "SOCS=CAI; CONSENT=YES+cb.20210328-17-p0.it+FX+000").build()).execute()
                .use { if (it.isSuccessful) it.body.string() else null }
        }.getOrNull()
    }

    private fun findAll(e: JsonElement, key: String, out: MutableList<JsonObject>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> if (k == key && v is JsonObject) out += v else findAll(v, key, out) }
            is JsonArray -> e.forEach { findAll(it, key, out) }
            else -> {}
        }
    }

    private fun JsonObject.runsText(k: String): String? = (this[k] as? JsonObject)?.let { o ->
        o.s("simpleText") ?: (o["runs"] as? JsonArray)?.joinToString("") { (it as? JsonObject)?.s("text") ?: "" }
    }

    private fun continuationToken(e: JsonElement): String? {
        val list = mutableListOf<JsonObject>(); findAll(e, "continuationCommand", list)
        return list.firstNotNullOfOrNull { it.s("token") }
    }

    /** "Artista - Titolo (Official Video)" / "Titolo" by "Artista - Topic" → (titolo, artista). */
    private fun cleanYt(title: String, channel: String): Pair<String, String> {
        val noise = Regex("""(?i)\s*[\(\[][^)\]]*(official|video|audio|lyric|testo|visualizer|clip|4k|hd|remaster|live|explicit)[^)\]]*[\)\]]""")
        var t = title.replace(noise, "").replace(Regex("""(?i)\s*\|.*$"""), "").trim()
        var a = channel.removeSuffix(" - Topic").replace(Regex("""(?i)vevo$|\s*official$"""), "").trim()
        if (!channel.endsWith(" - Topic") && t.contains(" - ")) {
            val (left, right) = t.split(" - ", limit = 2)
            a = left.trim(); t = right.trim()
        }
        t = t.replace(Regex("""(?i)\s*(ft\.?|feat\.?)\s.*$"""), "").trim()
        return t to a
    }

    private suspend fun fromYouTube(url: String): Source {
        val list = youtubeRe.find(url)!!.groupValues[1]
        val html = ytRequest(okhttp3.Request.Builder().url("https://www.youtube.com/playlist?list=$list&hl=it&gl=IT"))
            ?: throw IllegalStateException("Non riesco a leggere la playlist di YouTube")
        val raw = Regex("""ytInitialData\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("La playlist è privata o YouTube ha cambiato formato")
        val root = Supa.json.parseToJsonElement(raw)
        val key = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""").find(html)?.groupValues?.get(1)
        val version = Regex(""""INNERTUBE_CLIENT_VERSION":"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "2.20240101.00.00"
        val meta = mutableListOf<JsonObject>(); findAll(root, "playlistMetadataRenderer", meta)
        val micro = mutableListOf<JsonObject>(); findAll(root, "microformatDataRenderer", micro)
        val name = meta.firstOrNull()?.s("title") ?: micro.firstOrNull()?.s("title") ?: "Da YouTube Music"
        val items = mutableListOf<Pair<String, String>>()
        var thumb: String? = (micro.firstOrNull()?.get("thumbnail") as? JsonObject)?.let { t ->
            (t["thumbnails"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("url") }?.lastOrNull()
        }
        fun collect(e: JsonElement) {
            val vids = mutableListOf<JsonObject>(); findAll(e, "playlistVideoRenderer", vids)
            vids.forEach { v ->
                val title = v.runsText("title") ?: return@forEach
                val channel = v.runsText("shortBylineText") ?: ""
                items += cleanYt(title, channel)
                if (thumb == null) thumb = (v["thumbnail"] as? JsonObject)?.let { t -> (t["thumbnails"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("url") }?.lastOrNull() }
            }
        }
        collect(root)
        var token = continuationToken(root)
        var pages = 0
        while (token != null && key != null && pages < 15 && items.size < 1000) {
            val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"$version","hl":"it","gl":"IT"}},"continuation":"$token"}"""
            val res = ytRequest(okhttp3.Request.Builder().url("https://www.youtube.com/youtubei/v1/browse?key=$key&prettyPrint=false")
                .post(body.toRequestBody("application/json".toMediaType()))) ?: break
            val json = runCatching { Supa.json.parseToJsonElement(res) }.getOrNull() ?: break
            val before = items.size
            collect(json)
            token = continuationToken(json).takeIf { items.size > before }
            pages++
        }
        if (items.isEmpty()) throw IllegalStateException("Playlist di YouTube vuota o privata")
        return Source(name, thumb?.substringBefore("?"), items, null)
    }

    private val deezerRe = Regex("""deezer\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(playlist|album)/(\d+)""")
    private val spotifyRe = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album)/([A-Za-z0-9]+)""")

    suspend fun import(rawUrl: String, onProgress: (Progress) -> Unit): VibesPlaylist {
        // accept a bare link or a whole "shared from Spotify" text with the link inside
        var url = Regex("""https?://\S+""").find(rawUrl)?.value ?: rawUrl.trim()
        if (url.contains("link.deezer.com") || url.contains("spotify.link") || url.contains("deezer.page.link"))
            url = deezer.resolveUrl(url)
        onProgress(Progress(0, 0, 0, "Leggo la playlist…"))
        val source = when {
            deezerRe.containsMatchIn(url) -> fromDeezer(url)
            spotifyRe.containsMatchIn(url) -> fromSpotify(url)
            youtubeRe.containsMatchIn(url) -> fromYouTube(url)
            url.contains("music.apple.com") -> throw IllegalArgumentException("Apple Music non è ancora supportato: usa Spotify, Deezer o YouTube Music")
            else -> throw IllegalArgumentException("Link non riconosciuto: incolla il link di una playlist o di un album di Spotify o Deezer")
        }
        val songs: List<SongRef> = source.deezerTracks?.map { it.toSongRef() } ?: matchAll(source.items.orEmpty(), onProgress)
        if (songs.isEmpty()) throw IllegalStateException("Non ho trovato nessun brano in questa playlist")
        onProgress(Progress(songs.size, songs.size, songs.size, "Fatto"))
        return library.createPlaylist(
            name = source.name.ifBlank { "Playlist importata" }, emoji = "📥",
            color = "#1ED760", songs = songs.distinctBy { it.key },
        ).let { p ->
            if (source.cover != null) { library.setPlaylistCover(p.id, source.cover); library.playlist(p.id) ?: p } else p
        }
    }

    private suspend fun fromDeezer(url: String): Source {
        val m = deezerRe.find(url)!!
        val id = m.groupValues[2].toLong()
        return if (m.groupValues[1] == "album") {
            val a = deezer.album(id) ?: throw IllegalStateException("Album Deezer non trovato")
            Source(a.title, a.cover, null, a.tracks)
        } else {
            val info = deezer.playlistInfo(id) ?: throw IllegalStateException("Playlist Deezer non trovata (deve essere pubblica)")
            Source(info.title, info.picture, null, deezer.playlistTracks(id, 2000))
        }
    }

    private suspend fun fromSpotify(url: String): Source {
        val m = spotifyRe.find(url)!!
        val type = m.groupValues[1]; val id = m.groupValues[2]
        val html = deezer.fetchText("https://open.spotify.com/embed/$type/$id")
            ?: throw IllegalStateException("Non riesco a leggere la playlist Spotify")
        val raw = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IllegalStateException("Spotify ha cambiato formato: non riesco a leggere la playlist")
        val root = Supa.json.parseToJsonElement(raw)
        val entity = findEntity(root) ?: throw IllegalStateException("Playlist Spotify vuota o privata")
        val name = entity.s("name") ?: entity.s("title") ?: "Da Spotify"
        val cover = (entity["coverArt"] as? JsonObject)?.let { ca ->
            (ca["sources"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("url") }?.lastOrNull()
        } ?: (entity["visualIdentity"] as? JsonObject)?.let { vi ->
            (vi["image"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("url") }?.lastOrNull()
        } ?: oembedCover(url)
        val items = (entity["trackList"] as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = o.s("title") ?: return@mapNotNull null
            title to (o.s("subtitle") ?: "")
        }
        return Source(name, cover, items, null)
    }

    private suspend fun oembedCover(url: String): String? {
        val txt = deezer.fetchText("https://open.spotify.com/oembed?url=" + java.net.URLEncoder.encode(url, "UTF-8")) ?: return null
        return runCatching { (Supa.json.parseToJsonElement(txt) as JsonObject).s("thumbnail_url") }.getOrNull()
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** Depth-first search for the object that holds the track list. */
    private fun findEntity(e: JsonElement): JsonObject? = when (e) {
        is JsonObject -> if (e["trackList"] is JsonArray) e else e.values.firstNotNullOfOrNull { findEntity(it) }
        is JsonArray -> e.firstNotNullOfOrNull { findEntity(it) }
        else -> null
    }

    private suspend fun matchAll(items: List<Pair<String, String>>, onProgress: (Progress) -> Unit): List<SongRef> = coroutineScope {
        val sem = Semaphore(6)
        val done = AtomicInteger(0); val found = AtomicInteger(0)
        onProgress(Progress(0, items.size, 0, "Cerco i brani…"))
        items.map { (title, artist) ->
            async {
                sem.withPermit {
                    val t = runCatching { deezer.match(title, artist) }.getOrNull()
                    if (t != null) found.incrementAndGet()
                    onProgress(Progress(done.incrementAndGet(), items.size, found.get(), "Cerco i brani…"))
                    t?.toSongRef()
                }
            }
        }.awaitAll().filterNotNull()
    }

    @Suppress("unused")
    private fun JsonElement.str() = runCatching { jsonPrimitive.contentOrNull }.getOrNull()
}
