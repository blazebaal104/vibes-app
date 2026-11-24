package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.VibesPlaylist

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Library : Route
    data object Friends : Route
    /** Group listening lobby (null id = create a new one). */
    data class ListenLobby(val id: String) : Route
    data object Games : Route

    data class Playlist(val id: String) : Route
    data object Liked : Route
    data object Downloads : Route
    data class Media(val item: EchoMediaItem) : Route
    data class Chat(val friendId: String) : Route
    data class Profile(val userId: String?) : Route
    data object EditProfile : Route
    data object AddFriend : Route
    data object Settings : Route
    data object Feedback : Route
    data object FeedbackAdmin : Route
    data object DeezerLogin : Route
    data class SharedPlaylist(val id: String, val collab: String? = null) : Route
    data class Guess(val lobbyId: String? = null, val source: dev.brahmkshatriya.echo.vibes.games.GameSource? = null) : Route
    data class BlindTest(val lobbyId: String? = null) : Route
    data class Genre(val name: String) : Route
    data object Import : Route
    data object Stats : Route
    data object Studio : Route
    data object Publish : Route
    data object Samples : Route
    data object StudioStats : Route
    data object Podcasts : Route
    /** One podcast: its page with the episodes. */
    data class Podcast(val id: Long, val title: String, val picture: String?) : Route
    /** An album published on Vibes. */
    data class VibesRelease(val id: String) : Route
    data object Layers : Route
    data class DzArtist(val id: Long, val name: String = "") : Route
    data class DzAlbum(val id: Long) : Route
    data class DzPlaylist(val id: Long) : Route
    data class Stories(val userId: String) : Route
    data class NewStory(val song: SongRef? = null) : Route
    data class StoryRecap(val month: String) : Route
    data class Follows(val userId: String, val followers: Boolean) : Route
    data object History : Route
    /** Who liked / saved your published songs. */
    data object Activity : Route
}

val tabs = listOf(Route.Home, Route.Search, Route.Library, Route.Studio, Route.Friends, Route.Games)

/** What the share sheet is sharing. */
sealed interface ShareItem {
    data class Song(val song: SongRef) : ShareItem
    data class Playlist(val playlist: VibesPlaylist) : ShareItem
}

/** Simple back-stack navigation: one stack per tab, like Spotify. */
@Stable
class Navigator {
    var tab by mutableStateOf<Route>(when (dev.brahmkshatriya.echo.vibes.VibesGraph.prefs.value.startTab) {
        "search" -> Route.Search; "library" -> Route.Library; "friends" -> Route.Friends; "games" -> Route.Games; "studio" -> Route.Studio
        else -> Route.Home
    })
        private set
    private val stacks = mutableMapOf<Route, androidx.compose.runtime.snapshots.SnapshotStateList<Route>>()

    private fun stack(t: Route) = stacks.getOrPut(t) { mutableStateListOf(t) }

    val current: Route get() = stack(tab).last()
    val depth: Int get() = stack(tab).size
    val canGoBack get() = stack(tab).size > 1 || tab != Route.Home

    var playerOpen by mutableStateOf(false)
    var share by mutableStateOf<ShareItem?>(null)
    var addToPlaylist by mutableStateOf<SongRef?>(null)
    var toast by mutableStateOf<String?>(null)

    fun selectTab(t: Route) {
        if (t == tab) { val s = stack(t); while (s.size > 1) s.removeAt(s.lastIndex) }
        tab = t
    }

    fun open(r: Route) {
        playerOpen = false
        if (r in tabs) { selectTab(r); return }
        stack(tab).add(r)
    }

    /** Opens a route on a given tab (used by notifications / links). */
    fun openOn(t: Route, r: Route) {
        tab = t
        val s = stack(t)
        while (s.size > 1) s.removeAt(s.lastIndex)
        if (r != t) s.add(r)
    }

    fun back(): Boolean {
        if (share != null) { share = null; return true }
        if (addToPlaylist != null) { addToPlaylist = null; return true }
        if (playerOpen) { playerOpen = false; return true }
        val s = stack(tab)
        if (s.size > 1) { s.removeAt(s.lastIndex); return true }
        if (tab != Route.Home) { tab = Route.Home; return true }
        return false
    }

    fun toast(msg: String) { toast = msg }
}
