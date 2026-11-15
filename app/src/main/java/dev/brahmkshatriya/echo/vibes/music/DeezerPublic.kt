package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Deezer's public API (no login): 30 s previews, popularity rank, charts and search. Used by the
 * minigames, which need exact clip timing and must not disturb the user's real queue.
 */
class DeezerPublic(private val http: OkHttpClient) {

    @Serializable
    data class GameTrack(
        val id: Long,
        val title: String,
        val artist: String,
        val artistId: Long,
        val cover: String?,
        val preview: String,
        /** Deezer popularity, 0..~1,000,000 */
        val rank: Int,
        val album: String = "",
        /** Seconds (0 = unknown). */
        val duration: Int = 0,
        val albumId: Long = 0,
    ) {
        /** Full display title as Deezer writes it, e.g. "goosebumps - Travis Scott". */
        val fullTitle get() = "$title – $artist"

        fun toSongRef() = SongRef(
            ext = MusicEngine.DEEZER, id = id.toString(), title = title, artist = artist,
            cover = cover, album = album.ifBlank { null }, duration = duration.takeIf { it > 0 }?.let { it * 1000L },
        )
    }

    private suspend fun get(url: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else Supa.json.parseToJsonElement(r.body.string()).jsonObject
            }
        }.getOrNull()
    }

    private fun JsonObject.toGameTrack(): GameTrack? {
        val preview = this["preview"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val artist = this["artist"]?.jsonObject
        val album = this["album"]?.jsonObject
        return GameTrack(
            id = this["id"]?.jsonPrimitive?.longOrNull ?: return null,
            title = this["title"]?.jsonPrimitive?.contentOrNull ?: return null,
            artist = artist?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
            artistId = artist?.get("id")?.jsonPrimitive?.longOrNull ?: 0,
            cover = album?.get("cover_xl")?.jsonPrimitive?.contentOrNull
                ?: album?.get("cover_big")?.jsonPrimitive?.contentOrNull,
            preview = preview,
            rank = this["rank"]?.jsonPrimitive?.intOrNull ?: 0,
            album = album?.get("title")?.jsonPrimitive?.contentOrNull ?: "",
            duration = this["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            albumId = album?.get("id")?.jsonPrimitive?.longOrNull ?: 0,
        )
    }

    private fun list(o: JsonObject?) =
        o?.get("data")?.jsonArray?.mapNotNull { (it as? JsonObject)?.toGameTrack() }.orEmpty()

    suspend fun search(q: String, limit: Int = 8): List<GameTrack> {
        if (q.isBlank()) return emptyList()
        return list(get("https://api.deezer.com/search?limit=$limit&q=" + URLEncoder.encode(q, "UTF-8")))
    }

    suspend fun track(id: String): GameTrack? = get("https://api.deezer.com/track/$id")?.toGameTrack()

    /** Global chart (very popular songs). */
    suspend fun chart(limit: Int = 100): List<GameTrack> =
        list(get("https://api.deezer.com/chart/0/tracks?limit=$limit"))

    /** A Deezer editorial playlist (e.g. genre or decade mixes) for less popular picks. */
    suspend fun playlist(id: Long, limit: Int = 100): List<GameTrack> =
        list(get("https://api.deezer.com/playlist/$id/tracks?limit=$limit"))

    data class ArtistInfo(val id: Long, val name: String, val picture: String?, val fans: Int)

    suspend fun searchArtists(q: String, limit: Int = 12): List<ArtistInfo> {
        if (q.isBlank()) return emptyList()
        return get("https://api.deezer.com/search/artist?limit=$limit&q=" + URLEncoder.encode(q, "UTF-8"))
            ?.get("data")?.jsonArray?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                ArtistInfo(
                    o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    o["picture_xl"]?.jsonPrimitive?.contentOrNull ?: o["picture_big"]?.jsonPrimitive?.contentOrNull,
                    o["nb_fan"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }.orEmpty()
    }

    /** Beats per minute of a track (for the "breathing" cover), 0 if unknown. */
    suspend fun bpm(id: Long): Float =
        get("https://api.deezer.com/track/$id")?.get("bpm")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f

    /** Fresh preview for a story (stored preview links expire after a while). */
    suspend fun freshPreview(dz: Long?, s: SongRef): String? =
        dz?.let { runCatching { track(it.toString()) }.getOrNull()?.preview?.ifBlank { null } } ?: previewFor(s)

    /** Deezer id of a song (for fresh previews later). */
    suspend fun deezerId(s: SongRef): Long? =
        if (s.ext == MusicEngine.DEEZER && s.id.all(Char::isDigit)) s.id.toLong() else match(s.title, s.artist)?.id

    /** 30 s preview of any song (for stories). */
    suspend fun previewFor(s: SongRef): String? {
        val byId = if (s.ext == MusicEngine.DEEZER && s.id.all(Char::isDigit)) runCatching { track(s.id) }.getOrNull() else null
        return (byId ?: match(s.title, s.artist))?.preview?.ifBlank { null }
    }

    /** Main genre of a track, from its album ("Rap/Hip Hop", "Pop", …). */
    suspend fun trackGenre(trackId: Long): String? {
        val albumId = get("https://api.deezer.com/track/$trackId")?.get("album")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: return null
        val album = get("https://api.deezer.com/album/$albumId") ?: return null
        return album["genres"]?.jsonObject?.get("data")?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
    }

    suspend fun artistTop(artistId: Long, limit: Int = 10): List<GameTrack> =
        list(get("https://api.deezer.com/artist/$artistId/top?limit=$limit"))

    /** Deezer's radio of an artist: songs by that artist and similar ones (public, no login). */
    suspend fun artistRadio(artistId: Long, limit: Int = 40): List<GameTrack> =
        list(get("https://api.deezer.com/artist/$artistId/radio?limit=$limit"))

    suspend fun related(artistId: Long): List<Pair<Long, String>> =
        get("https://api.deezer.com/artist/$artistId/related?limit=20")?.get("data")?.jsonArray
            ?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                id to (o["name"]?.jsonPrimitive?.contentOrNull ?: "")
            }.orEmpty()

    data class PlaylistInfo(val id: Long, val title: String, val picture: String?, val owner: String, val count: Int)

    private fun JsonObject.toPlaylistInfo(): PlaylistInfo? {
        val id = this["id"]?.jsonPrimitive?.longOrNull ?: return null
        return PlaylistInfo(
            id = id,
            title = this["title"]?.jsonPrimitive?.contentOrNull ?: "",
            picture = this["picture_xl"]?.jsonPrimitive?.contentOrNull ?: this["picture_big"]?.jsonPrimitive?.contentOrNull,
            owner = (this["user"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                ?: (this["creator"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
            count = this["nb_tracks"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    suspend fun searchPlaylists(q: String, limit: Int = 10): List<PlaylistInfo> =
        get("https://api.deezer.com/search/playlist?limit=$limit&q=" + URLEncoder.encode(q, "UTF-8"))
            ?.get("data")?.jsonArray?.mapNotNull { (it as? JsonObject)?.toPlaylistInfo() }.orEmpty()

    /** Best editorial playlist for a genre/mood query (prefers Deezer's own playlists). */
    suspend fun genrePlaylist(q: String): PlaylistInfo? {
        val list = searchPlaylists(q, 15).filter { it.count >= 15 }
        return list.firstOrNull { it.owner.contains("deezer", true) } ?: list.firstOrNull()
    }

    suspend fun playlistInfo(id: Long): PlaylistInfo? = get("https://api.deezer.com/playlist/$id")?.toPlaylistInfo()

    /** All tracks of a public playlist (follows pagination). */
    suspend fun playlistTracks(id: Long, max: Int = 500): List<GameTrack> {
        val out = mutableListOf<GameTrack>()
        var index = 0
        while (out.size < max) {
            val page = list(get("https://api.deezer.com/playlist/$id/tracks?limit=100&index=$index"))
            if (page.isEmpty()) break
            out += page; index += 100
            if (page.size < 100) break
        }
        return out.take(max)
    }

    data class AlbumInfo(
        val title: String, val cover: String?, val tracks: List<GameTrack>,
        val id: Long = 0, val artist: String = "", val artistId: Long = 0, val date: String = "",
        val type: String = "album", val label: String = "", val fans: Int = 0, val durationSec: Int = 0,
    )

    /** What the PC "now playing" panel shows: album, release, credits. */
    data class TrackDetails(
        val id: Long, val title: String, val artist: String, val artistId: Long,
        val albumId: Long, val album: String, val cover: String?, val releaseDate: String,
        val bpm: Float, val durationSec: Int, val rank: Int,
        /** name to role ("Main", "Featured", ...) */
        val contributors: List<Pair<String, String>>,
    )

    suspend fun trackDetails(id: Long): TrackDetails? {
        val o = get("https://api.deezer.com/track/$id") ?: return null
        if (o["error"] != null) return null
        val album = o["album"]?.jsonObject
        val artist = o["artist"]?.jsonObject
        return TrackDetails(
            id = id,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
            artist = artist?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
            artistId = artist?.get("id")?.jsonPrimitive?.longOrNull ?: 0,
            albumId = album?.get("id")?.jsonPrimitive?.longOrNull ?: 0,
            album = album?.get("title")?.jsonPrimitive?.contentOrNull ?: "",
            cover = album?.get("cover_xl")?.jsonPrimitive?.contentOrNull,
            releaseDate = o["release_date"]?.jsonPrimitive?.contentOrNull ?: "",
            bpm = o["bpm"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
            durationSec = o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            rank = o["rank"]?.jsonPrimitive?.intOrNull ?: 0,
            contributors = o["contributors"]?.jsonArray?.mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                val n = c["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                n to (c["role"]?.jsonPrimitive?.contentOrNull ?: "")
            }.orEmpty(),
        )
    }

    data class AlbumBrief(val id: Long, val title: String, val cover: String?, val date: String, val type: String, val artist: String = "")

    private fun JsonObject.toAlbumBrief(artist: String = ""): AlbumBrief? = AlbumBrief(
        this["id"]?.jsonPrimitive?.longOrNull ?: return null,
        this["title"]?.jsonPrimitive?.contentOrNull ?: return null,
        this["cover_xl"]?.jsonPrimitive?.contentOrNull ?: this["cover_big"]?.jsonPrimitive?.contentOrNull,
        this["release_date"]?.jsonPrimitive?.contentOrNull ?: "",
        this["record_type"]?.jsonPrimitive?.contentOrNull ?: "album",
        this["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: artist,
    )

    /** Most popular artists right now (onboarding questionnaire). */
    suspend fun chartArtists(limit: Int = 40): List<ArtistInfo> =
        get("https://api.deezer.com/chart/0/artists?limit=$limit")?.get("data")?.jsonArray?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            ArtistInfo(o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null, o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                o["picture_xl"]?.jsonPrimitive?.contentOrNull ?: o["picture_big"]?.jsonPrimitive?.contentOrNull, o["nb_fan"]?.jsonPrimitive?.intOrNull ?: 0)
        }.orEmpty()

    suspend fun searchAlbums(q: String, limit: Int = 20): List<AlbumBrief> =
        get("https://api.deezer.com/search/album?limit=$limit&q=" + URLEncoder.encode(q, "UTF-8"))
            ?.get("data")?.jsonArray?.mapNotNull { (it as? JsonObject)?.toAlbumBrief() }.orEmpty()

    suspend fun artistInfo(id: Long): ArtistInfo? {
        val o = get("https://api.deezer.com/artist/$id") ?: return null
        if (o["error"] != null) return null
        return ArtistInfo(id, o["name"]?.jsonPrimitive?.contentOrNull ?: return null,
            o["picture_xl"]?.jsonPrimitive?.contentOrNull, o["nb_fan"]?.jsonPrimitive?.intOrNull ?: 0)
    }

    /** Albums, EPs and singles, newest first. */
    suspend fun artistAlbums(id: Long, name: String = ""): List<AlbumBrief> =
        get("https://api.deezer.com/artist/$id/albums?limit=100")?.get("data")?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.toAlbumBrief(name) }.orEmpty()
            .sortedByDescending { it.date }

    /** Deezer editorial new releases (newest first). */
    suspend fun newReleases(): List<AlbumBrief> =
        get("https://api.deezer.com/editorial/0/releases?limit=100")?.get("data")?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.toAlbumBrief() }.orEmpty()
            .sortedByDescending { it.date }

    suspend fun album(id: Long): AlbumInfo? {
        val o = get("https://api.deezer.com/album/$id") ?: return null
        val cover = o["cover_xl"]?.jsonPrimitive?.contentOrNull
        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: ""
        val tracks = o["tracks"]?.jsonObject?.get("data")?.jsonArray?.mapNotNull { e ->
            (e as? JsonObject)?.toGameTrack()?.let { if (it.cover == null) it.copy(cover = cover) else it }
        }.orEmpty()
        val artist = o["artist"]?.jsonObject
        return AlbumInfo(
            title, cover, tracks, id = id,
            artist = artist?.get("name")?.jsonPrimitive?.contentOrNull ?: tracks.firstOrNull()?.artist.orEmpty(),
            artistId = artist?.get("id")?.jsonPrimitive?.longOrNull ?: 0,
            date = o["release_date"]?.jsonPrimitive?.contentOrNull ?: "",
            type = o["record_type"]?.jsonPrimitive?.contentOrNull ?: "album",
            label = o["label"]?.jsonPrimitive?.contentOrNull ?: "",
            fans = o["fans"]?.jsonPrimitive?.intOrNull ?: 0,
            durationSec = o["duration"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /** Resolves short links (link.deezer.com, spotify.link…) to the final URL. */
    suspend fun resolveUrl(url: String): String = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute()
                .use { it.request.url.toString() }
        }.getOrDefault(url)
    }

    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36").build())
                .execute().use { if (it.isSuccessful) it.body.string() else null }
        }.getOrNull()
    }

    /** Finds the Deezer track for a title + artist from another service. */
    suspend fun match(title: String, artist: String): GameTrack? {
        val clean = title.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "").trim()
        val firstArtist = artist.split(",", "&", " feat", " ft.").first().trim()
        return search("artist:\"$firstArtist\" track:\"$clean\"", 3).firstOrNull()
            ?: search("$clean $firstArtist", 3).firstOrNull()
    }
}
