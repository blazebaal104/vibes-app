package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId

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
import kotlinx.coroutines.launch
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

    /**
     * Asks Deezer who is logged in. Result: true = session works, false = Deezer refused it
     * (expired / must sign in again), null = could not tell (offline, timeout...).
     */
    suspend fun probeDeezer(): Boolean? = withContext(Dispatchers.IO) {
        val r = runCatching {
            kotlinx.coroutines.withTimeout(15_000) {
                extension().getAs<LoginClient, User?> { getCurrentUser() }.getOrThrow()
            }
        }
        when {
            r.isSuccess -> r.getOrNull() != null
            DeezerAuth.isLoginError(r.exceptionOrNull()) -> false
            else -> null
        }
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
        if (tracks.size == 1) { playSingle(extId, tracks.first(), context); return }
        session = null
        player.setQueue(extId, tracks, index, context, contextStart = true)
        player.setPlaying(true)
    }

    fun play(song: SongRef) = play(song.ext, listOf(song.toTrack()))

    // ------------------------------------------------------------------ one song -> "radio"

    private val scope = kotlinx.coroutines.MainScope()
    @Volatile private var singleToken = 0

    /**
     * One song on its own (a song a friend sent, a story, a recent): it plays, and then Vibes goes on
     * with similar songs, like Spotify's autoplay. Before, the queue held that one song only and
     * skipping or reaching its end started it again.
     */
    private fun playSingle(extId: String, track: Track, context: EchoMediaItem?) {
        session = null
        val token = ++singleToken
        player.setQueue(extId, listOf(track), 0, context?.takeIf { it !is Track })
        player.setPlaying(true)
        val song = SongRef.from(extId, track)
        scope.launch {
            val more = runCatching { similarTo(song) }.getOrDefault(emptyList())
            if (token != singleToken || more.isEmpty()) return@launch
            // only if the user is still on that song (they didn't start something else meanwhile)
            if (currentKey() != song.key) return@launch
            player.appendTracks(DEEZER, more.map { it.toTrack() })
        }
    }

    /** Songs like [song]: Deezer's artist radio (similar artists), without the song itself. */
    suspend fun similarTo(song: SongRef, max: Int = 30): List<SongRef> = withContext(Dispatchers.IO) {
        val dz = dev.brahmkshatriya.echo.vibes.VibesGraph.deezer
        val id = dz.deezerId(song) ?: return@withContext emptyList()
        val artistId = dz.track(id.toString())?.artistId?.takeIf { it > 0 } ?: return@withContext emptyList()
        val radio = dz.artistRadio(artistId).ifEmpty {
            dz.related(artistId).take(6).flatMap { (a, _) -> runCatching { dz.artistTop(a, 5) }.getOrDefault(emptyList()) }.shuffled()
        }
        val title = song.title.trim().lowercase()
        radio.filter { it.id != id && it.title.trim().lowercase() != title }
            .distinctBy { it.id }.take(max).map { it.toSongRef() }
    }

    fun playAll(songs: List<SongRef>, index: Int = 0, shuffle: Boolean = false, ctx: VibesPlaylist? = null) {
        if (songs.isEmpty()) return
        if (songs.any { it.ext == DEEZER } && !DeezerAuth.canPlay()) return
        runCatching { if (dev.brahmkshatriya.echo.vibes.VibesGraph.studio.isActive) dev.brahmkshatriya.echo.vibes.VibesGraph.studio.stop() }
        dev.brahmkshatriya.echo.vibes.VibesGraph.playContext = ctx?.let {
            PlayContext(it.id, it.name, it.cover ?: it.songs.firstOrNull()?.cover, it.songs.map { s -> s.key }.toSet())
        }
        val ext = songs[index.coerceIn(0, songs.size - 1)].ext
        val list = if (shuffle) songs.shuffled() else songs
        val start = if (shuffle) 0 else index.coerceIn(0, list.size - 1)
        if (list.size == 1) { playSingle(ext, list.first().toTrack(), null); return }
        session = Session(list, start, ctx, shuffle)
        // the whole playlist goes to the player (it still starts at the tapped song), so that shuffle
        // can pick any song of it, not only the ones after the tapped one
        player.setQueue(ext, list.map { it.toTrack() }, start, null, contextStart = true)
        player.setPlaying(true)
    }

    /**
     * What was last started with [playAll]. The player queue only keeps the tapped song and the
     * ones after it (the earlier ones are dropped), and "previous" only knows songs actually played
     * since: so on the tapped song it restarted it instead of going back. Bug from friends:
     * "quando mandi indietro non riparte la canzone precedente ma quella che stai ascoltando".
     */
    private data class Session(val list: List<SongRef>, val start: Int, val ctx: VibesPlaylist?, val shuffled: Boolean)
    @Volatile private var session: Session? = null

    private fun currentKey(): String? =
        player.playerState.current.value?.let { "${it.mediaItem.extensionId}:${it.track.id}" }

    fun togglePlay() {
        val cur = player.playerState.current.value ?: return
        if (!cur.isPlaying && DeezerAuth.explainIfFailing()) return
        player.setPlaying(!cur.isPlaying)
    }

    fun next() = player.next()
    fun previous() {
        val s = session
        val key = currentKey()
        // Same rule as Spotify/Apple Music: after 3 s "previous" restarts the song. In the first 3 s,
        // on the song that was tapped (nothing played before it in this queue), go to the song that
        // precedes it in the playlist by rebuilding the queue one step earlier.
        if (s != null && key != null && position() <= 3_000 && s.start > 0 && s.list.getOrNull(s.start)?.key == key) {
            val idx = s.start - 1
            session = s.copy(start = idx)
            player.setQueue(s.list[idx].ext, s.list.map { it.toTrack() }, idx, null, contextStart = true)
            player.setPlaying(true)
            return
        }
        player.previous()
    }
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun pause() = player.setPlaying(false)

    fun position(): Long = player.browser.value?.currentPosition ?: 0L
    fun duration(): Long = player.browser.value?.duration?.takeIf { it > 0 } ?: 0L
}
