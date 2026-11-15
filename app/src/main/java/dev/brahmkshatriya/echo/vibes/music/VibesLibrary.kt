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
    /** Shared with friends: id of the server copy every member edits (see CollabPlaylists). */
    val collabId: String? = null,
    val collabVersion: Long = 0,
)

@Serializable
private data class LibraryFile(
    val playlists: List<VibesPlaylist> = emptyList(),
    val liked: List<SongRef> = emptyList(),
    val plays: Map<String, Int> = emptyMap(),
    val recent: List<SongRef> = emptyList(),
    val searches: List<String> = emptyList(),
    val artists: List<FollowedArtist> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    /** Last time each library item was opened or played ("pl:<id>", "al:<id>", "ar:<id>"): the "Recenti" order. */
    val opened: Map<String, Long> = emptyMap(),
    val albumsMigrated: Boolean = false,
    /** Sync with the account: when each item was last added/changed ("pl:<id>", "like:<key>", "al:<id>", "ar:<id>", "fo:<id>"). */
    val stamps: Map<String, Long> = emptyMap(),
    /** Sync: when items were removed (so a removal on one device removes it everywhere). */
    val removed: Map<String, Long> = emptyMap(),
)

/** The part of the library shared by all the user's devices (phone, PC) through their account. */
@Serializable
data class LibrarySyncDoc(
    val playlists: List<VibesPlaylist> = emptyList(),
    val liked: List<SongRef> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val folders: List<LibraryFolder> = emptyList(),
    val artists: List<FollowedArtist> = emptyList(),
    val stamps: Map<String, Long> = emptyMap(),
    val removed: Map<String, Long> = emptyMap(),
)

/** An album saved to the library. It is not a playlist: it can't be edited, it is the album as released. */
@Serializable
data class SavedAlbum(
    val id: Long, val title: String, val artist: String = "", val cover: String? = null,
    val artistId: Long = 0, val savedAt: Long = System.currentTimeMillis(),
)

/** A folder in the library: groups playlists ("pl:<id>" keys). */
@Serializable
data class LibraryFolder(val id: String = UUID.randomUUID().toString(), val name: String, val items: List<String> = emptyList())

/** An artist the user follows: Vibes tells them when something new comes out. */
@Serializable
data class FollowedArtist(val id: Long, val name: String, val picture: String? = null, val lastSeen: String = "")

/**
 * The user's own Vibes library, kept in a JSON file: playlists, liked songs, play counts and
 * recently played songs. Play counts feed the minigames' difficulty.
 */
class VibesLibrary(context: Context) {
    /** A change to a shared playlist, sent to the server so every member gets it. */
    sealed interface CollabOp {
        data class Add(val song: SongRef) : CollabOp
        data class Remove(val key: String) : CollabOp
        data class Songs(val songs: List<SongRef>) : CollabOp
        data class Meta(val name: String? = null, val emoji: String? = null, val color: String? = null,
                        val setCover: Boolean = false, val cover: String? = null) : CollabOp
        data object Leave : CollabOp
    }

    /** Set by CollabPlaylists. */
    @Volatile var onCollabOp: ((String, CollabOp) -> Unit)? = null
    private fun shared(id: String, op: CollabOp) { playlist(id)?.collabId?.let { c -> onCollabOp?.invoke(c, op) } }

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

    private val _albums = MutableStateFlow(state.value.albums)
    val albums = _albums.asStateFlow()
    private val _folders = MutableStateFlow(state.value.folders)
    val folders = _folders.asStateFlow()
    private val _opened = MutableStateFlow(state.value.opened)
    val opened = _opened.asStateFlow()

    // saved albums (read-only)
    fun isAlbumSaved(id: Long) = _albums.value.any { it.id == id }
    fun saveAlbum(a: SavedAlbum) = change { s -> s.copy(albums = listOf(a) + s.albums.filter { it.id != a.id }) }
    fun removeAlbum(id: Long) = change { s -> s.copy(albums = s.albums.filter { it.id != id }) }

    // folders
    fun createFolder(name: String): LibraryFolder {
        val f = LibraryFolder(name = name.trim().ifEmpty { "Nuova cartella" })
        change { s -> s.copy(folders = s.folders + f) }
        return f
    }
    fun renameFolder(id: String, name: String) = change { s -> s.copy(folders = s.folders.map { if (it.id == id) it.copy(name = name) else it }) }
    fun deleteFolder(id: String) = change { s -> s.copy(folders = s.folders.filter { it.id != id }) }
    /** Moves a playlist into [folderId] (null = out of every folder). */
    fun moveToFolder(key: String, folderId: String?) = change { s ->
        s.copy(folders = s.folders.map { f ->
            val without = f.items - key
            if (f.id == folderId) f.copy(items = without + key) else f.copy(items = without)
        })
    }

    /** Remembers that a library item was just opened/played (sort "Recenti"). */
    fun touch(key: String) {
        if ((System.currentTimeMillis() - (_opened.value[key] ?: 0)) < 5_000) return
        change { s -> s.copy(opened = (s.opened + (key to System.currentTimeMillis())).entries.sortedByDescending { it.value }.take(300).associate { it.key to it.value }) }
    }

    /**
     * Albums used to be saved as a copy in a playlist ("💿"). Once, turn those back into saved albums
     * when they are still exactly the album (no songs added by hand).
     */
    suspend fun migrateAlbums(deezer: DeezerPublic) {
        if (state.value.albumsMigrated) return
        val candidates = _playlists.value.filter { it.emoji == "💿" && it.collabId == null && it.from == null && it.songs.isNotEmpty() }
        for (p in candidates) runCatching {
            val artist = p.songs.first().artist.split(", ").first()
            val hit = deezer.searchAlbums("$artist ${p.name}", 10).firstOrNull { it.title.equals(p.name, true) } ?: return@runCatching
            val tracks = deezer.album(hit.id)?.tracks.orEmpty().map { it.id.toString() }.toSet()
            if (tracks.isEmpty() || p.songs.any { it.id !in tracks }) return@runCatching
            saveAlbum(SavedAlbum(hit.id, hit.title, hit.artist.ifBlank { artist }, hit.cover ?: p.cover, savedAt = p.createdAt))
            change { s -> s.copy(playlists = s.playlists.filter { it.id != p.id }) }
        }
        change { it.copy(albumsMigrated = true) }
    }

    fun isFollowingArtist(id: Long) = _artists.value.any { it.id == id }
    fun followArtist(a: FollowedArtist) = change { s -> s.copy(artists = s.artists.filter { it.id != a.id } + a) }
    fun unfollowArtist(id: Long) = change { s -> s.copy(artists = s.artists.filter { it.id != id }) }
    fun setArtistSeen(id: Long, date: String) = change { s -> s.copy(artists = s.artists.map { if (it.id == id) it.copy(lastSeen = date) else it }) }

    private fun load(): LibraryFile = runCatching {
        json.decodeFromString(LibraryFile.serializer(), file.readText())
    }.getOrDefault(LibraryFile())

    /** Set by LibrarySync: the user changed something that other devices should get. */
    @Volatile var onSyncedChange: (() -> Unit)? = null

    /** Marks what changed between [a] and [b] (added/edited = stamp, gone = removed) for the sync. */
    private fun stampDiff(a: LibraryFile, b: LibraryFile): LibraryFile {
        val now = System.currentTimeMillis()
        val stamps = b.stamps.toMutableMap(); val removed = b.removed.toMutableMap()
        fun <T> diff(prefix: String, old: List<T>, new: List<T>, key: (T) -> String, norm: (T) -> Any? = { it }) {
            val o = old.associateBy(key); val n = new.associateBy(key)
            n.forEach { (k, v) -> val before = o[k]; if (before == null || norm(before) != norm(v)) { stamps["$prefix$k"] = now; removed.remove("$prefix$k") } }
            o.keys.filter { it !in n }.forEach { removed["$prefix$it"] = now }
        }
        diff("pl:", a.playlists, b.playlists, { it.id }, { it.copy(songs = it.songs.map { s -> s.copy(data = null) }) })
        diff("like:", a.liked, b.liked, { it.key }, { it.key })
        diff("al:", a.albums, b.albums, { it.id.toString() })
        diff("ar:", a.artists, b.artists, { it.id.toString() }, { it.copy(lastSeen = "") })
        diff("fo:", a.folders, b.folders, { it.id })
        return b.copy(stamps = stamps, removed = removed)
    }

    private fun synced(a: LibraryFile, b: LibraryFile) =
        a.playlists != b.playlists || a.liked != b.liked || a.albums != b.albums || a.folders != b.folders ||
            a.artists.map { it.copy(lastSeen = "") } != b.artists.map { it.copy(lastSeen = "") }

    private fun change(stamp: Boolean = true, block: (LibraryFile) -> LibraryFile) {
        var shared = false
        state.update { old ->
            val new = block(old)
            if (stamp && synced(old, new)) { shared = true; stampDiff(old, new) } else new
        }
        if (shared) onSyncedChange?.invoke()
        val s = state.value
        _playlists.value = s.playlists; _liked.value = s.liked
        _recent.value = s.recent; _searches.value = s.searches; _artists.value = s.artists
        _albums.value = s.albums; _folders.value = s.folders; _opened.value = s.opened
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

    fun renamePlaylist(id: String, name: String, emoji: String, color: String) {
        change { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(name = name, emoji = emoji, color = color) else it }) }
        shared(id, CollabOp.Meta(name = name, emoji = emoji, color = color))
    }

    fun setPlaylistCover(id: String, cover: String?) {
        change { s -> s.copy(playlists = s.playlists.map { if (it.id == id) it.copy(cover = cover) else it }) }
        shared(id, CollabOp.Meta(setCover = true, cover = cover))
    }

    fun deletePlaylist(id: String) {
        shared(id, CollabOp.Leave)
        change { s -> s.copy(playlists = s.playlists.filter { it.id != id }, folders = s.folders.map { it.copy(items = it.items - "pl:$id") }) }
    }

    fun addToPlaylist(id: String, song: SongRef) {
        if (playlist(id)?.songs?.any { it.key == song.key } == true) return
        change { s ->
            s.copy(playlists = s.playlists.map { p ->
                if (p.id == id && p.songs.none { it.key == song.key }) p.copy(songs = p.songs + song) else p
            })
        }
        shared(id, CollabOp.Add(song))
    }

    fun removeFromPlaylist(id: String, key: String) {
        change { s -> s.copy(playlists = s.playlists.map { p -> if (p.id == id) p.copy(songs = p.songs.filter { it.key != key }) else p }) }
        shared(id, CollabOp.Remove(key))
    }

    fun moveInPlaylist(id: String, from: Int, to: Int) {
        change { s ->
            s.copy(playlists = s.playlists.map { p ->
                if (p.id != id || from !in p.songs.indices || to !in p.songs.indices) p
                else p.copy(songs = p.songs.toMutableList().apply { add(to, removeAt(from)) })
            })
        }
        playlist(id)?.let { shared(id, CollabOp.Songs(it.songs)) }
    }

    // ---------------------------------------------------------------- shared playlists (server side copy)

    /** [localId] was just shared: it is now the collaborative playlist [c]. */
    fun linkCollab(localId: String, c: dev.brahmkshatriya.echo.vibes.backend.CollabPlaylist) = change { s ->
        s.copy(playlists = s.playlists.map { if (it.id == localId) it.copy(collabId = c.id, collabVersion = c.version) else it })
    }

    /** A newer version from the server (a friend changed it): update my copy, without sending it back. */
    fun applyRemote(c: dev.brahmkshatriya.echo.vibes.backend.CollabPlaylist) {
        val local = _playlists.value.firstOrNull { it.collabId == c.id } ?: return
        if (c.version <= local.collabVersion) return
        change { s ->
            s.copy(playlists = s.playlists.map {
                if (it.collabId != c.id) it
                else it.copy(name = c.name, emoji = c.emoji, color = c.color, cover = c.cover ?: it.cover?.takeIf { u -> !u.startsWith("http") },
                    songs = c.songs, collabVersion = c.version)
            })
        }
    }

    /**
     * Saving a playlist a friend shared. Already in the library (as the shared one, or as the same
     * playlist: same name and same songs) -> that one becomes the shared version, no duplicate.
     */
    fun adoptCollab(c: dev.brahmkshatriya.echo.vibes.backend.CollabPlaylist, from: String?): VibesPlaylist {
        _playlists.value.firstOrNull { it.collabId == c.id }?.let { applyRemote(c); return playlist(it.id) ?: it }
        val keys = c.songs.map { it.key }.toSet()
        val same = _playlists.value.firstOrNull {
            it.name.trim().equals(c.name.trim(), true) && it.songs.map { s -> s.key }.toSet() == keys
        }
        // it was shared before in another group: it now follows this one
        same?.collabId?.let { old -> onCollabOp?.invoke(old, CollabOp.Leave) }
        val adopted = (same ?: VibesPlaylist(name = c.name)).copy(
            name = c.name, emoji = c.emoji, color = c.color, cover = c.cover ?: same?.cover, songs = c.songs,
            collabId = c.id, collabVersion = c.version, from = same?.from ?: from,
        )
        change { s ->
            if (same != null) s.copy(playlists = s.playlists.map { if (it.id == same.id) adopted else it })
            else s.copy(playlists = listOf(adopted) + s.playlists)
        }
        return adopted
    }

    fun playlist(id: String) = _playlists.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- account sync

    /** What goes to the account (without the heavy per-song playback data). */
    fun syncDoc(): LibrarySyncDoc = state.value.let { s ->
        LibrarySyncDoc(
            s.playlists.map { p -> p.copy(songs = p.songs.map { it.copy(data = null) }) },
            s.liked.map { it.copy(data = null) }, s.albums, s.folders, s.artists, s.stamps, s.removed,
        )
    }

    /** The merged library from the account: replaces the synced part, keeps this device's song data. */
    fun applySync(d: LibrarySyncDoc) = change(stamp = false) { s ->
        val local = (s.playlists.flatMap { it.songs } + s.liked + s.recent).filter { it.data != null }.associateBy { it.key }
        fun SongRef.withData() = if (data == null) local[key] ?: this else this
        s.copy(
            playlists = d.playlists.map { p -> p.copy(songs = p.songs.map { it.withData() }) },
            liked = d.liked.map { it.withData() }, albums = d.albums, folders = d.folders,
            artists = d.artists.map { a -> s.artists.firstOrNull { it.id == a.id }?.let { a.copy(lastSeen = maxOf(a.lastSeen, it.lastSeen)) } ?: a },
            stamps = d.stamps, removed = d.removed,
        )
    }

    // likes
    fun isLiked(key: String) = _liked.value.any { it.key == key }
    fun toggleLike(song: SongRef) = change { s ->
        if (s.liked.any { it.key == song.key }) s.copy(liked = s.liked.filter { it.key != song.key })
        else s.copy(liked = listOf(song) + s.liked)
    }

    // listening
    /** A song started: it goes to "recently played" (not a play yet, see [countPlay]). */
    fun recordPlay(song: SongRef) = change { s ->
        s.copy(recent = (listOf(song) + s.recent.filter { it.key != song.key }).take(50))
    }

    /** A song was really listened to (a third of it): +1 play. */
    fun countPlay(song: SongRef) = change { s ->
        s.copy(plays = s.plays + (song.key to ((s.plays[song.key] ?: 0) + 1)))
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
