package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.music.DeezerPublic.GameTrack
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic.PlaylistInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer

/**
 * Music genres for Browse and for search: typing "pop" must give pop songs, not songs called
 * "Pop". Each genre is backed by Deezer's best editorial playlist for its query.
 */
data class Genre(
    val name: String,
    val query: String,
    val colors: Pair<Long, Long>,
    val aliases: List<String> = emptyList(),
    val browse: Boolean = true,
)

object Genres {
    val all = listOf(
        Genre("Pop", "pop hits", 0xFFE8115B to 0xFFFF6A3D, listOf("pop music")),
        Genre("Rap italiano", "rap italiano", 0xFF7B2FF7 to 0xFFC2187A, listOf("rap ita", "hip hop italiano", "rap italia")),
        Genre("Hip Hop", "hip hop hits", 0xFFBA5D07 to 0xFFE1A30A, listOf("hiphop", "rap", "hip-hop")),
        Genre("Trap", "trap hits", 0xFF3A0CA3 to 0xFFFF2E97, listOf("trap italiana", "trap music")),
        Genre("Drill", "drill", 0xFF1C1C1C to 0xFF7A1C1C, listOf("uk drill", "drill italiana")),
        Genre("Rock", "rock classics", 0xFFE91429 to 0xFF8C1932, listOf("rock music", "classic rock")),
        Genre("Indie", "indie hits", 0xFF608108 to 0xFF1E3264, listOf("indie italiano", "alternative", "indie rock")),
        Genre("Elettronica", "electronic hits", 0xFF0D73EC to 0xFF27856A, listOf("electronic", "edm", "dance", "elettronica")),
        Genre("R&B", "r&b hits", 0xFFDC148C to 0xFF8D67AB, listOf("rnb", "r and b", "rhythm and blues")),
        Genre("Lo-fi", "lofi beats", 0xFF503750 to 0xFF477D95, listOf("lofi", "lo fi", "lo-fi hip hop")),
        Genre("Latin", "latin hits", 0xFFE1118C to 0xFFF59B23, listOf("latino", "musica latina")),
        Genre("Reggaeton", "reggaeton hits", 0xFFF59B23 to 0xFFE91429, listOf("reggaetón", "perreo")),
        Genre("Anni 80", "80s hits", 0xFF8D67AB to 0xFF1E3264, listOf("80s", "anni ottanta", "eighties")),
        Genre("Anni 90", "90s hits", 0xFF27856A to 0xFF8D67AB, listOf("90s", "anni novanta")),
        Genre("Anni 2000", "2000s hits", 0xFFE8115B to 0xFF1E3264, listOf("2000s", "duemila")),
        Genre("Chill", "chill hits", 0xFF27856A to 0xFF148A08, listOf("relax", "rilassante")),
        Genre("Allenamento", "workout", 0xFF777777 to 0xFFE8115B, listOf("workout", "gym", "palestra")),
        Genre("Jazz", "jazz classics", 0xFF1E3264 to 0xFFBA5D07, listOf("jazz music"), browse = false),
        Genre("Metal", "metal hits", 0xFF1C1C1C to 0xFF777777, listOf("heavy metal"), browse = false),
        Genre("Techno", "techno", 0xFF0B4062 to 0xFF1C1C1C, browse = false),
        Genre("House", "house music hits", 0xFF0D73EC to 0xFFE8115B, listOf("house music"), browse = false),
        Genre("Punk", "punk rock", 0xFFE91429 to 0xFF1C1C1C, listOf("pop punk"), browse = false),
        Genre("Country", "country hits", 0xFFBA5D07 to 0xFF608108, browse = false),
        Genre("K-Pop", "k-pop hits", 0xFFE879F9 to 0xFF35D0F5, listOf("kpop", "k pop"), browse = false),
        Genre("Classica", "classical essentials", 0xFF8D6E63 to 0xFF1E3264, listOf("classical", "musica classica"), browse = false),
        Genre("Soul", "soul classics", 0xFFC2410C to 0xFF6B1F4F, listOf("soul music", "funk"), browse = false),
        Genre("Italiana", "musica italiana hits", 0xFF148A08 to 0xFFE91429, listOf("musica italiana", "canzoni italiane", "pop italiano"), browse = false),
    )

    private fun norm(s: String) = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9&]+"), " ").trim()

    /** The genre the user means, if the whole query is a genre name. */
    fun match(query: String): Genre? {
        val q = norm(query)
        if (q.isEmpty()) return null
        return all.firstOrNull { g -> norm(g.name) == q || g.aliases.any { norm(it) == q } }
    }

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Pair<PlaylistInfo?, List<GameTrack>>>()

    suspend fun playlist(deezer: DeezerPublic, g: Genre): PlaylistInfo? = mutex.withLock {
        cache[g.name]?.first ?: deezer.genrePlaylist(g.query).also { cache[g.name] = it to (cache[g.name]?.second ?: emptyList()) }
    }

    suspend fun tracks(deezer: DeezerPublic, g: Genre): List<GameTrack> {
        mutex.withLock { cache[g.name]?.second?.takeIf { it.isNotEmpty() }?.let { return it } }
        val p = playlist(deezer, g) ?: return emptyList()
        val list = deezer.playlistTracks(p.id, 120)
        mutex.withLock { cache[g.name] = p to list }
        return list
    }
}
