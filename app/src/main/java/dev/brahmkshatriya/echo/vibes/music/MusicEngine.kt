package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.db.models.CurrentUser
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity.Companion.toCurrentUser
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity.Companion.toEntity
import dev.brahmkshatriya.echo.playback.MediaItemUtils.trackRadioPlaceholder
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The only door between the Vibes UI and the Gladix engine: the Deezer extension (login, feeds,
 * search, albums/playlists/artists) and the player (queue, play/pause, seek). Nothing of Gladix's
 * own UI is used.
 */
class MusicEngine(
    private val loader: ExtensionLoader,
    val player: PlayerViewModel,
) {
    companion object {
        const val DEEZER = "deezer"
    }

    data class UiShelf(
        val title: String,
        val tracks: List<Track> = emptyList(),
        val items: List<EchoMediaItem> = emptyList(),
    )

    data class SearchResult(
        val tracks: List<Track> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val albums: List<Album> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
    ) {
        val isEmpty get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
    }

    private val userDao get() = loader.db.userDao()

    suspend fun extension(): MusicExtension {
        val list = loader.music.first { it.isNotEmpty() }
        return list.firstOrNull { it.id == DEEZER } ?: list.first()
    }

    val deezerLoggedIn: Flow<Boolean> = userDao.observeCurrentUser().map { list ->
        list.any { it.type == ExtensionType.MUSIC && it.extId == DEEZER && it.userId != null }
    }

    // ------------------------------------------------------------------ Deezer login

    private suspend fun saveUsers(ext: MusicExtension, users: List<User>) {
        if (users.isEmpty()) throw IllegalStateException("Nessun account trovato")
        val entities = users.map { it.toEntity(ExtensionType.MUSIC, ext.id) }
        userDao.insertUsers(entities)
        userDao.setCurrentUser(entities.first().toCurrentUser())
    }

    suspend fun loginWithPassword(email: String, password: String) = withContext(Dispatchers.IO) {
        val ext = extension()
        val users = ext.getAs<LoginClient.CustomInput, List<User>> {
            onLogin("userPass", mapOf("email" to email.trim(), "pass" to password))
        }.getOrThrow()
        saveUsers(ext, users)
    }

    suspend fun loginWithArl(arl: String) = withContext(Dispatchers.IO) {
        val ext = extension()
        val users = ext.getAs<LoginClient.CustomInput, List<User>> {
            onLogin("manual", mapOf("arl" to arl.trim()))
        }.getOrThrow()
        saveUsers(ext, users)
    }

    suspend fun logoutDeezer() = withContext(Dispatchers.IO) {
        userDao.setCurrentUser(CurrentUser(ExtensionType.MUSIC, DEEZER, null))
    }

    // ------------------------------------------------------------------ browsing

    private fun Shelf.toUi(): UiShelf? = when (this) {
        is Shelf.Lists.Tracks -> UiShelf(title, tracks = list)
        is Shelf.Lists.Items -> {
            val tracks = list.filterIsInstance<Track>()
            if (tracks.size == list.size) UiShelf(title, tracks = tracks)
            else UiShelf(title, items = list.filter { it !is Track })
        }
        is Shelf.Item -> (media as? Track)?.let { UiShelf(title, tracks = listOf(it)) }
            ?: UiShelf(title, items = listOf(media))
        else -> null
    }

    suspend fun home(): List<UiShelf> = withContext(Dispatchers.IO) {
        val ext = extension()
        val feed = ext.getAs<HomeFeedClient, dev.brahmkshatriya.echo.common.models.Feed<Shelf>> { loadHomeFeed() }.getOrThrow()
        feed.pagedDataOfFirst().loadPage(null).data.mapNotNull { it.toUi() }
            .filter { it.tracks.isNotEmpty() || it.items.isNotEmpty() }
    }

    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchResult()
        val ext = extension()
        val feed = ext.getAs<SearchFeedClient, dev.brahmkshatriya.echo.common.models.Feed<Shelf>> { loadSearchFeed(query) }.getOrThrow()
        val shelves = feed.pagedDataOfFirst().loadPage(null).data
        val media = mutableListOf<EchoMediaItem>()
        shelves.forEach { s ->
            when (s) {
                is Shelf.Lists.Tracks -> media += s.list
                is Shelf.Lists.Items -> media += s.list
                is Shelf.Item -> media += s.media
                else -> {}
            }
        }
        SearchResult(
            tracks = media.filterIsInstance<Track>().distinctBy { it.id },
            artists = media.filterIsInstance<Artist>().distinctBy { it.id },
            albums = media.filterIsInstance<Album>().distinctBy { it.id },
            playlists = media.filterIsInstance<Playlist>().distinctBy { it.id },
        )
    }

    /** Full item (bigger cover, counts) + its track list. Artists: their top tracks. */
    suspend fun open(item: EchoMediaItem): Pair<EchoMediaItem, List<Track>> = withContext(Dispatchers.IO) {
        val ext = extension()
        when (item) {
            is Album -> {
                val full = ext.getAs<AlbumClient, Album> { loadAlbum(item) }.getOrDefault(item)
                val tracks = ext.getAs<AlbumClient, List<Track>> {
                    loadTracks(full)?.pagedDataOfFirst()?.loadAll().orEmpty()
                }.getOrDefault(emptyList())
                full to tracks
            }
            is Playlist -> {
                val full = ext.getAs<PlaylistClient, Playlist> { loadPlaylist(item) }.getOrDefault(item)
                val tracks = ext.getAs<PlaylistClient, List<Track>> {
                    loadTracks(full).pagedDataOfFirst().loadPage(null).data
                }.getOrDefault(emptyList())
                full to tracks
            }
            is Artist -> {
                val full = ext.getAs<ArtistClient, Artist> { loadArtist(item) }.getOrDefault(item)
                val tracks = ext.getAs<ArtistClient, List<Track>> {
                    loadFeed(full).pagedDataOfFirst().loadPage(null).data
                        .flatMap { s ->
                            when (s) {
                                is Shelf.Lists.Tracks -> s.list
                                is Shelf.Lists.Items -> s.list.filterIsInstance<Track>()
                                else -> emptyList()
                            }
                        }.distinctBy { it.id }.take(30)
                }.getOrDefault(emptyList())
                full to tracks
            }
            is Track -> item to listOf(item)
            else -> item to emptyList()
        }
    }

    /** Playlists saved in the user's Deezer account (shown in Vibes' Library). */
    suspend fun libraryPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val ext = extension()
        val feed = ext.getAs<LibraryFeedClient, dev.brahmkshatriya.echo.common.models.Feed<Shelf>> { loadLibraryFeed() }.getOrThrow()
        val tabs = feed.notSortTabs
        val shelves = if (tabs.isEmpty()) feed.getPagedData(null).pagedData.loadPage(null).data
        else tabs.flatMap { tab -> runCatching { feed.getPagedData(tab).pagedData.loadPage(null).data }.getOrDefault(emptyList()) }
        shelves.flatMap { s ->
            when (s) {
                is Shelf.Item -> listOf(s.media)
                is Shelf.Lists.Items -> s.list
                else -> emptyList()
            }
        }.filterIsInstance<Playlist>().distinctBy { it.id }
    }

    /** Every track of a Deezer playlist (for importing it into Vibes). */
    suspend fun allTracks(playlist: Playlist): List<Track> = withContext(Dispatchers.IO) {
        val ext = extension()
        val full = ext.getAs<PlaylistClient, Playlist> { loadPlaylist(playlist) }.getOrDefault(playlist)
        ext.getAs<PlaylistClient, List<Track>> { loadTracks(full).pagedDataOfFirst().loadAll() }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ playback

    suspend fun extensionId() = extension().id

    fun play(extId: String, tracks: List<Track>, index: Int = 0, context: EchoMediaItem? = null) {
        if (tracks.isEmpty()) return
        if (extId == DEEZER && !DeezerAuth.canPlay()) return
        runCatching { if (dev.brahmkshatriya.echo.vibes.VibesGraph.studio.isActive) dev.brahmkshatriya.echo.vibes.VibesGraph.studio.stop() }
        val start = tracks[index.coerceIn(0, tracks.size - 1)]
        player.setQueue(extId, tracks, index, context ?: if (tracks.size == 1) trackRadioPlaceholder(start) else null)
        player.setPlaying(true)
    }

    fun play(song: SongRef) = play(song.ext, listOf(song.toTrack()))

    fun playAll(songs: List<SongRef>, index: Int = 0, shuffle: Boolean = false, ctx: VibesPlaylist? = null) {
        if (songs.isEmpty()) return
        if (songs.any { it.ext == DEEZER } && !DeezerAuth.canPlay()) return
        runCatching { if (dev.brahmkshatriya.echo.vibes.VibesGraph.studio.isActive) dev.brahmkshatriya.echo.vibes.VibesGraph.studio.stop() }
        dev.brahmkshatriya.echo.vibes.VibesGraph.playContext = ctx?.let {
            PlayContext(it.id, it.name, it.cover ?: it.songs.firstOrNull()?.cover, it.songs.map { s -> s.key }.toSet())
        }
        val ext = songs[index.coerceIn(0, songs.size - 1)].ext
        val list = if (shuffle) songs.shuffled() else songs
        player.setQueue(ext, list.map { it.toTrack() }, if (shuffle) 0 else index, null)
        player.setPlaying(true)
    }

    fun togglePlay() {
        val cur = player.playerState.current.value ?: return
        if (!cur.isPlaying && DeezerAuth.explainIfFailing()) return
        player.setPlaying(!cur.isPlaying)
    }

    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun pause() = player.setPlaying(false)

    fun position(): Long = player.browser.value?.currentPosition ?: 0L
    fun duration(): Long = player.browser.value?.duration?.takeIf { it > 0 } ?: 0L
}
