package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.async
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
class Importer(private val deezer: DeezerPublic, private val library: VibesLibrary) {

    data class Progress(val done: Int, val total: Int, val found: Int, val stage: String)
    data class Source(val name: String, val cover: String?, val items: List<Pair<String, String>>?, val deezerTracks: List<DeezerPublic.GameTrack>?)

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
            url.contains("music.apple.com") || url.contains("youtube.com") || url.contains("youtu.be") ->
                throw IllegalArgumentException("Per ora si possono importare link di Spotify e Deezer")
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
