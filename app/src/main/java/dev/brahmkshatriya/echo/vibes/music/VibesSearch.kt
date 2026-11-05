package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.backend.SongRef
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
import kotlin.math.ln

/**
 * Spotify-style search on Deezer's public catalogue: songs, artists, albums, playlists in parallel,
 * cleaned up (no duplicates, no fake/tiny artist profiles), ranked by relevance + popularity, plus
 * a single "top result".
 */
class VibesSearch(private val deezer: DeezerPublic) {
    sealed interface Top {
        data class ArtistTop(val a: DeezerPublic.ArtistInfo) : Top
        data class SongTop(val s: SongRef, val index: Int) : Top
    }

    data class Result(
        val top: Top?,
        val songs: List<SongRef>,
        val artists: List<DeezerPublic.ArtistInfo>,
        val albums: List<DeezerPublic.AlbumBrief>,
        val playlists: List<DeezerPublic.PlaylistInfo>,
    ) {
        val isEmpty get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
    }

    private val cache = object : LinkedHashMap<String, Result>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 30
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** 0..1 how well [name] matches what was typed. */
    private fun nameScore(name: String, q: String): Double {
        val n = norm(name); val t = norm(q)
        return when {
            n == t -> 1.0
            n.startsWith(t) || t.startsWith(n) -> 0.85
            n.split(" ").any { it.startsWith(t) } -> 0.7
            t.split(" ").all { w -> n.contains(w) } -> 0.6
            n.contains(t) -> 0.5
            else -> 0.0
        }
    }

    suspend fun search(query: String): Result = coroutineScope {
        val q = query.trim()
        cache[q.lowercase()]?.let { return@coroutineScope it }
        val tracksD = async { runCatching { deezer.search(q, 40) }.getOrDefault(emptyList()) }
        val artistsD = async { runCatching { deezer.searchArtists(q, 30) }.getOrDefault(emptyList()) }
        val albumsD = async { runCatching { deezer.searchAlbums(q, 25) }.getOrDefault(emptyList()) }
        val playlistsD = async { runCatching { deezer.searchPlaylists(q, 15) }.getOrDefault(emptyList()) }

        // ---- artists: one per name (the most followed), real profiles only, best match first
        val artists = artistsD.await()
            .groupBy { norm(it.name) }.values.map { same -> same.maxBy { it.fans } }
            .map { it to nameScore(it.name, q) }
            .filter { (a, sc) -> sc > 0 && (a.fans >= 1_000 || (sc >= 1.0 && a.fans >= 50)) }
            .sortedByDescending { (a, sc) -> sc * 3 + ln(a.fans.toDouble() + 1) / 4 }
            .map { it.first }
            .take(12)

        // ---- songs: no duplicates (same title + artist), relevance from Deezer + popularity
        val tracks = tracksD.await()
        val songs = tracks.withIndex()
            .distinctBy { (_, t) -> norm(t.title).substringBefore(" feat").substringBefore(" ft ") + "|" + norm(t.artist) }
            .sortedByDescending { (i, t) -> (40 - i) / 40.0 * 2 + t.rank / 1_000_000.0 + nameScore(t.title, q) + nameScore(t.artist, q) * 0.5 }
            .map { it.value.toSongRef() }
            .take(30)

        val albums = albumsD.await().distinctBy { norm(it.title) + "|" + norm(it.artist) }
            .sortedByDescending { nameScore(it.title, q) + nameScore(it.artist, q) * 0.6 }.take(15)
        val playlists = playlistsD.await().filter { it.count >= 5 }.take(12)

        // ---- top result: the artist if the query IS (or starts like) their name, else the best song
        val bestArtist = artists.firstOrNull()
        val top: Top? = when {
            bestArtist != null && nameScore(bestArtist.name, q) >= 0.85 && bestArtist.fans >= 5_000 -> Top.ArtistTop(bestArtist)
            songs.isNotEmpty() -> Top.SongTop(songs.first(), 0)
            bestArtist != null -> Top.ArtistTop(bestArtist)
            else -> null
        }
        Result(top, songs, artists, albums, playlists).also { if (!it.isEmpty) cache[q.lowercase()] = it }
    }
}
