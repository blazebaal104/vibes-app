package dev.brahmkshatriya.echo.vibes.music

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
data class VibesPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String = "🎵",
    val color: String = "#B84DFF",
    val songs: List<SongRef> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Set when saved from a friend's share. */
    val from: String? = null,
    /** Cover image (imported playlists keep their original artwork). */
    val cover: String? = null,
)

@Serializable
private data class LibraryFile(
    val playlists: List<VibesPlaylist> = emptyList(),
    val liked: List<SongRef> = emptyList(),
    val plays: Map<String, Int> = emptyMap(),
    val recent: List<SongRef> = emptyList(),
    val searches: List<String> = emptyList(),
    val artists: List<FollowedArtist> = emptyList(),
)

/** An artist the user follows: Vibes tells them when something new comes out. */
@Serializable
data class FollowedArtist(val id: Long, val name: String, val picture: String? = null, val lastSeen: String = "")

/**
 * The user's own Vibes library, kept in a JSON file: playlists, liked songs, play counts and
 * recently played songs. Play counts feed the minigames' difficulty.
 */
class VibesLibrary(context: Context) {
    private val file = File(context.filesDir, "vibes_library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Supa.json

    private val state = MutableStateFlow(load())

    private val _playlists = MutableStateFlow(state.value.playlists)
    val playlists = _playlists.asStateFlow()
    private val _liked = MutableStateFlow(state.value.liked)
    val liked = _liked.asStateFlow()
    private val _recent = MutableStateFlow(state.value.recent)
    val recent = _recent.asStateFlow()
    private val _searches = MutableStateFlow(state.value.searches)
    val searches = _searches.asStateFlow()
    val plays get() = state.value.plays
    private val _artists = MutableStateFlow(state.value.artists)
    val artists = _artists.asStateFlow()

    fun isFollowingArtist(id: Long) = _artists.value.any { it.id == id }
    fun followArtist(a: FollowedArtist) = change { s -> s.copy(artists = s.artists.filter { it.id != a.id } + a) }
    fun unfollowArtist(id: Long) = change { s -> s.copy(artists = s.artists.filter { it.id != id }) }
    fun setArtistSeen(id: Long, date: String) = change { s -> s.copy(artists = s.artists.map { if (it.id == id) it.copy(lastSeen = date) else it }) }

    private fun load(): LibraryFile = runCatching {
        json.decodeFromString(LibraryFile.serializer(), file.readText())
    }.getOrDefault(LibraryFile())

    private fun change(block: (LibraryFile) -> LibraryFile) {
        state.update(block)
        val s = state.value
        _playlists.value = s.playlists; _liked.value = s.liked
        _recent.value = s.recent; _searches.value = s.searches; _artists.value = s.artists
        scope.launch {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(LibraryFile.serializer(), state.value))
                tmp.renameTo(file)
            }
        }
    }

    // playlists
    fun createPlaylist(
        name: String, emoji: String, color: String, songs: List<SongRef> = emptyList(),
        from: String? = null, cover: String? = null,
    ): VibesPlaylist {
        val p = VibesPlaylist(name = name.trim().ifEmpty { "La mia playlist" }, emoji = emoji, color = color, songs = songs, from = from, cover = cover)
        change { it.copy(playlists = listOf(p) + it.playlists) }
        return p
    }

    fun renamePlaylist(id: String, name: String, emoji: String, color: String) = change { s ->
        s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(name = name, emoji = emoji, color = color) else it })
    }

    fun setPlaylistCover(id: String, cover: String?) = change { s ->
        s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(cover = cover) else it })
    }

    fun deletePlaylist(id: String) = change { s -> s.copy(playlists = s.playlists.filter { it.id != id }) }

    fun addToPlaylist(id: String, song: SongRef) = change { s ->
        s.copy(playlists = s.playlists.map { p ->
            if (p.id == id && p.songs.none { it.key == song.key }) p.copy(songs = p.songs + song) else p
        })
    }

    fun removeFromPlaylist(id: String, key: String) = change { s ->
        s.copy(playlists = s.playlists.map { p -> if (p.id == id) p.copy(songs = p.songs.filter { it.key != key }) else p })
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) = change { s ->
        s.copy(playlists = s.playlists.map { p ->
            if (p.id != id || from !in p.songs.indices || to !in p.songs.indices) p
            else p.copy(songs = p.songs.toMutableList().apply { add(to, removeAt(from)) })
        })
    }

    fun playlist(id: String) = _playlists.value.firstOrNull { it.id == id }

    // likes
    fun isLiked(key: String) = _liked.value.any { it.key == key }
    fun toggleLike(song: SongRef) = change { s ->
        if (s.liked.any { it.key == song.key }) s.copy(liked = s.liked.filter { it.key != song.key })
        else s.copy(liked = listOf(song) + s.liked)
    }

    // listening
    fun recordPlay(song: SongRef) = change { s ->
        s.copy(
            plays = s.plays + (song.key to ((s.plays[song.key] ?: 0) + 1)),
            recent = (listOf(song) + s.recent.filter { it.key != song.key }).take(50),
        )
    }

    fun playCount(key: String) = state.value.plays[key] ?: 0

    fun addSearch(q: String) = change { s ->
        s.copy(searches = (listOf(q.trim()) + s.searches.filter { !it.equals(q.trim(), true) }).take(12))
    }

    fun clearSearches() = change { it.copy(searches = emptyList()) }

    /** Every song the user has around (playlists, likes, recent) with how often they played it. */
    fun knownSongs(): List<Pair<SongRef, Int>> {
        val s = state.value
        val all = (s.playlists.flatMap { it.songs } + s.liked + s.recent).distinctBy { it.key }
        return all.map { it to (s.plays[it.key] ?: 0) }
    }
}
