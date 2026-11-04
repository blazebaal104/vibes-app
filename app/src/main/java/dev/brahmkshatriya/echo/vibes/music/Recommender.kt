package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic.GameTrack
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Vibes' own recommendations, built from what the user actually does:
 *  - artist weights from play counts, likes and saved playlists;
 *  - Deezer's "related artists" graph around the strongest artists;
 *  - everything the user already knows is filtered out, so suggestions are new but similar.
 */
class Recommender(private val deezer: DeezerPublic, private val library: VibesLibrary) {

    data class Section(val title: String, val subtitle: String?, val songs: List<SongRef>)

    private val mutex = Mutex()
    private var cached: Pair<Long, List<Section>>? = null
    private val artistIds = mutableMapOf<String, Long>()

    suspend fun sections(force: Boolean = false): List<Section> = mutex.withLock {
        val c = cached
        if (!force && c != null && System.currentTimeMillis() - c.first < 30 * 60_000) return c.second
        val res = runCatching { build() }.getOrDefault(emptyList())
        if (res.isNotEmpty()) cached = System.currentTimeMillis() to res
        res
    }

    private fun key(s: String) = s.trim().lowercase()

    private suspend fun build(): List<Section> = coroutineScope {
        val known = library.knownSongs()
        val liked = library.liked.value.map { it.key }.toSet()
        val playlists = library.playlists.value
        if (known.isEmpty()) {
            val chart = deezer.chart(30).map { it.toSongRef() }
            return@coroutineScope listOf(Section("Le più ascoltate ora", "Ascolta qualcosa e i suggerimenti diventeranno tuoi", chart))
        }

        // 1) artist weights
        val weight = mutableMapOf<String, Double>()
        val display = mutableMapOf<String, String>()
        val inPlaylists = playlists.flatMap { it.songs }.map { it.key }.toSet()
        known.forEach { (song, plays) ->
            val artists = song.artist.split(", ").filter { it.isNotBlank() }
            artists.forEachIndexed { i, a ->
                val w = (plays * 2.0 + 1 + (if (song.key in liked) 3 else 0) + (if (song.key in inPlaylists) 2 else 0)) / (i + 1)
                weight[key(a)] = (weight[key(a)] ?: 0.0) + w
                display.putIfAbsent(key(a), a)
            }
        }

        // 2) Deezer ids for the strongest artists (via one of their Deezer tracks)
        val top = weight.entries.sortedByDescending { it.value }.take(8).map { it.key }
        val needIds = top.filter { it !in artistIds }
        needIds.map { a ->
            async {
                val song = known.firstOrNull { (s, _) -> s.ext == MusicEngine.DEEZER && s.id.all(Char::isDigit) && key(s.artist.split(", ").first()) == a }?.first
                val id = song?.let { deezer.track(it.id)?.artistId }
                    ?: deezer.search("artist:\"${display[a] ?: a}\"", 1).firstOrNull()?.artistId
                a to id
            }
        }.awaitAll().forEach { (a, id) -> if (id != null && id > 0) artistIds[a] = id }
        val topIds = top.mapNotNull { a -> artistIds[a]?.let { a to it } }
        if (topIds.isEmpty()) return@coroutineScope emptyList()

        val knownIds = known.map { it.first }.filter { it.ext == MusicEngine.DEEZER }.map { it.id }.toSet()
        val knownTitles = known.map { (s, _) -> key(s.title) + "|" + key(s.artist.split(", ").first()) }.toSet()
        fun fresh(t: GameTrack) = t.id.toString() !in knownIds && (key(t.title) + "|" + key(t.artist)) !in knownTitles

        // 3) related artists around the top ones
        val related = topIds.take(5).map { (a, id) -> async { a to deezer.related(id).take(6) } }.awaitAll()
        val relatedTops = related.flatMap { (a, rel) ->
            rel.mapIndexed { i, (rid, rname) -> Triple(a, rid, rname) to i }
        }.distinctBy { it.first.second }
            .filter { (t, _) -> key(t.third) !in weight }      // artists you don't already play
            .take(18)
            .map { (t, i) -> async { Triple(t, i, deezer.artistTop(t.second, 5).filter(::fresh)) } }
            .awaitAll()

        val sections = mutableListOf<Section>()

        // "Scelti per te": best of everything, scored by how much you like the source artist
        val scored = relatedTops.flatMap { (t, i, tracks) ->
            val base = weight[t.first] ?: 1.0
            tracks.mapIndexed { j, tr -> tr to base / (i + 1) / (j + 1) + tr.rank / 2_000_000.0 }
        }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.id }
        if (scored.isNotEmpty()) sections += Section("Scelti per te", "In base a quello che ascolti", scored.take(20).map { it.toSongRef() })

        // "Perché ascolti X" for the two strongest artists
        topIds.take(2).forEach { (a, _) ->
            val songs = relatedTops.filter { it.first.first == a }.flatMap { it.third.take(3) }.distinctBy { it.id }.shuffled().take(12)
            if (songs.size >= 4) sections += Section("Perché ascolti ${display[a] ?: a}", "Artisti simili", songs.map { it.toSongRef() })
        }

        // "Altro di X": songs you haven't heard by your favourite artist
        topIds.firstOrNull()?.let { (a, id) ->
            val more = deezer.artistTop(id, 15).filter(::fresh).take(10)
            if (more.size >= 3) sections += Section("Altro di ${display[a] ?: a}", "Brani che non hai ancora ascoltato", more.map { it.toSongRef() })
        }

        // "Simile a <playlist>"
        playlists.filter { it.songs.size >= 3 }.maxByOrNull { it.songs.size }?.let { p ->
            val artists = p.songs.flatMap { it.artist.split(", ") }.groupingBy { key(it) }.eachCount()
                .entries.sortedByDescending { it.value }.take(3).map { it.key }
            val ids = artists.mapNotNull { artistIds[it] }
            val songs = ids.map { id -> async { deezer.related(id).take(3) } }.awaitAll().flatten()
                .map { (rid, _) -> async { deezer.artistTop(rid, 3) } }.awaitAll().flatten()
                .filter(::fresh).filter { s -> p.songs.none { it.id == s.id.toString() } }.distinctBy { it.id }.take(12)
            if (songs.size >= 4) sections += Section("Simili a \"${p.name}\"", "Dalla tua playlist", songs.map { it.toSongRef() })
        }
        sections
    }
}
