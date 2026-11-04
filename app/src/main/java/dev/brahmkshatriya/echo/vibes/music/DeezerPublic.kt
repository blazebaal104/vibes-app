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
    ) {
        /** Full display title as Deezer writes it, e.g. "goosebumps - Travis Scott". */
        val fullTitle get() = "$title – $artist"

        fun toSongRef() = SongRef(
            ext = MusicEngine.DEEZER, id = id.toString(), title = title, artist = artist,
            cover = cover, album = album.ifBlank { null },
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

    suspend fun artistTop(artistId: Long, limit: Int = 10): List<GameTrack> =
        list(get("https://api.deezer.com/artist/$artistId/top?limit=$limit"))

    suspend fun related(artistId: Long): List<Pair<Long, String>> =
        get("https://api.deezer.com/artist/$artistId/related?limit=20")?.get("data")?.jsonArray
            ?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                id to (o["name"]?.jsonPrimitive?.contentOrNull ?: "")
            }.orEmpty()
}
