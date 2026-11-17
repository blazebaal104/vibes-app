package dev.brahmkshatriya.echo.vibes.ui

import kotlinx.coroutines.flow.first

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.material.icons.rounded.Tune

import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.focusable

import androidx.compose.ui.focus.focusRequester

import androidx.compose.ui.input.key.isCtrlPressed

import androidx.compose.ui.input.key.key

import androidx.compose.ui.input.key.type

import androidx.compose.ui.input.key.onKeyEvent

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.theme.VibesThemes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Global UI state shared by every screen. */
class AppState(
    val engine: MusicEngine,
    val nav: Navigator,
    theme: dev.brahmkshatriya.echo.vibes.theme.VibesTheme,
) {
    var theme by mutableStateOf(theme)
    val social get() = VibesGraph.social
    val library get() = VibesGraph.library
    val supa get() = VibesGraph.supa
    val deezer get() = VibesGraph.deezer
    val along = dev.brahmkshatriya.echo.vibes.music.ListenAlong(engine)
}

val LocalApp = androidx.compose.runtime.staticCompositionLocalOf<AppState> { error("no app state") }

/** What the platform changes in the shared UI. The PC app sets it up before the first frame. */
object PlatformUI {
    /** PC: its own window layout (library panel, now playing panel, player bar) in place of the phone's. */
    var desktopScaffold: (@Composable (MutableStateFlow<Uri?>) -> Unit)? = null
    val isDesktop get() = desktopScaffold != null
    /** PC: the screens draw on the window's own (animated) background instead of their own. */
    var transparentScreens = false
    /** PC: the search text typed in the top bar (null on the phone: the search screen has its own box). */
    var searchQuery: androidx.compose.runtime.MutableState<String>? = null
    /** PC: wraps every song row (hover highlight, right-click menu); the row gets the menu opener. */
    /** PC: menus / panels open on top (Esc closes them instead of going back). */
    @Volatile var popups = 0
    var songRow: (@Composable (song: dev.brahmkshatriya.echo.vibes.backend.SongRef, content: @Composable (openMenu: () -> Unit) -> Unit) -> Unit)? = null
}

@Composable
fun VibesApp(engine: MusicEngine, pendingLink: MutableStateFlow<Uri?>) {
    val context = LocalContext.current
    val nav = remember { Navigator() }
    val state = remember {
        AppState(engine, nav, VibesThemes.current(context) ?: VibesThemes.presets.first())
    }
    LocalAppHolder.app = state
    VibesMaterial(state.theme) {
        androidx.compose.runtime.CompositionLocalProvider(LocalApp provides state) {
            val session by state.supa.session.collectAsState()
            val hasProfile by state.social.hasProfile.collectAsState()
            val meNow by state.social.me.collectAsState()
            LaunchedEffect(session?.userId) {
                if (session == null) { state.social.clear(); return@LaunchedEffect }
                // retry until the profile loads (offline at start-up, slow network...)
                while (runCatching { state.social.bootstrap() }.isFailure) delay(3000)
                // friends see this week's stats + records
                dev.brahmkshatriya.echo.vibes.VibesGraph.onListened(force = true)
                // shared playlists: catch up with what friends changed while the app was closed
                runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.collab.refresh() }
                // playlist transfer: match with the Deezer account's search too (no request limit, wider catalogue)
                dev.brahmkshatriya.echo.vibes.VibesGraph.matcher.engineSearch = { q ->
                    if (state.engine.deezerLoggedIn.first()) {
                        val ext = state.engine.extensionId()
                        state.engine.search(q).tracks.take(10).map { dev.brahmkshatriya.echo.vibes.backend.SongRef.from(ext, it) }
                    } else emptyList()
                }
                // albums saved the old way (copied into a playlist) become saved albums again
                runCatching { state.library.migrateAlbums(state.deezer) }
                // the library of the account: what was made on the other devices arrives here
                runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.librarySync.sync() }
                // listening time of the account: what the other devices heard counts here too
                runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.listenSync.sync(); dev.brahmkshatriya.echo.vibes.VibesGraph.onListened(true) }
                // keep friends' live "now playing" fresh in lists
                var tick = 0
                while (true) {
                    delay(30_000); runCatching { state.social.refreshFriends() }
                    runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.librarySync.sync() }
                    if (tick % 2 == 0) runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.listenSync.sync() }
                    if (++tick % 4 == 0) runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.collab.refresh() }
                }
            }
            // Surface sets LocalContentColor, so every Text without an explicit colour is readable
            // on the theme background (white on dark themes, dark on light ones).
            androidx.compose.material3.Surface(
                Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                when {
                    session == null -> AuthScreen()
                    hasProfile == null -> LoadingScreen()
                    hasProfile == false -> ProfileSetupScreen()
                    meNow?.banned == true -> BannedScreen(meNow?.banReason)
                    LocalSettings.current.needsOnboarding && System.getenv("VIBES_UITEST") == null -> OnboardingScreen()
                    else -> {
                        val desk = PlatformUI.desktopScaffold
                        if (desk != null) desk(pendingLink) else MainScaffold(pendingLink)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    GradientBackground {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            VibesLogo(Modifier.size(84.dp))
            Text("Vibes", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
fun MainScaffold(pendingLink: MutableStateFlow<Uri?>) {
    val app = LocalApp.current
    val nav = app.nav
    val link by pendingLink.collectAsState()

    LaunchedEffect(link) {
        val uri = link ?: return@LaunchedEffect
        pendingLink.value = null
        handleLink(app, uri)
    }

    val update by dev.brahmkshatriya.echo.vibes.VibesGraph.updater.pending.collectAsState()
    BackHandler(enabled = update != null || nav.canGoBack || nav.playerOpen || nav.share != null || nav.addToPlaylist != null) {
        if (update != null) dev.brahmkshatriya.echo.vibes.VibesGraph.updater.close() else nav.back()
    }

    val hazeState = remember { dev.chrisbanes.haze.HazeState() }
    // PC (emulator) / tablet in landscape: side menu like Spotify desktop, content not stretched
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    val sideW = if (wide) 230.dp else 0.dp
    val keyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }
    androidx.compose.runtime.CompositionLocalProvider(LocalHaze provides hazeState) {
    Box(Modifier.fillMaxSize()
        // keyboard (PC): Space play/pause, Ctrl+arrows previous/next, Ctrl+F search, Esc back.
        // onKeyEvent (not preview): text fields keep their spaces and arrows.
        .onKeyEvent { ev -> if (ev.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false else pcShortcut(app, ev) }
        .focusRequester(keyFocus).focusable()) {
        // "Sfondo reattivo": one living background behind every screen (the screens turn transparent)
        val reactive = LocalSettings.current.reactiveBackground
        Box(Modifier.fillMaxSize().padding(start = sideW).hazeSource(hazeState)) {
        if (reactive) ReactiveBackground(Modifier.fillMaxSize())
        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            targetState = ScreenKey(tabs.indexOf(nav.tab), nav.depth, nav.current),
            contentKey = { it.route },
            transitionSpec = {
                val a = initialState; val b = targetState
                val ease = androidx.compose.animation.core.FastOutSlowInEasing
                when {
                    // tab switch: slide towards the tab you picked
                    a.tab != b.tab -> {
                        val dir = if (b.tab > a.tab) 1 else -1
                        (slideInHorizontally(tween(340, easing = ease)) { dir * it / 4 } + fadeIn(tween(260)) +
                                androidx.compose.animation.scaleIn(tween(340, easing = ease), initialScale = 0.96f))
                            .togetherWith(slideOutHorizontally(tween(300, easing = ease)) { -dir * it / 4 } + fadeOut(tween(200)))
                    }
                    // opening a page: it comes in from the right over the old one
                    b.depth > a.depth -> (slideInHorizontally(tween(320, easing = ease)) { it } + fadeIn(tween(200)))
                        .togetherWith(slideOutHorizontally(tween(320, easing = ease)) { -it / 5 } + fadeOut(tween(260)))
                    // going back
                    b.depth < a.depth -> (slideInHorizontally(tween(320, easing = ease)) { -it / 5 } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(300, easing = ease)) { it } + fadeOut(tween(260)))
                    else -> fadeIn(tween(220)).togetherWith(fadeOut(tween(160)))
                }.apply { targetContentZIndex = b.depth.toFloat() }
            },
            label = "screen"
        ) { key ->
            if (wide) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 980.dp).fillMaxHeight()) { Screen(key.route) }
            } else Screen(key.route)
        }
        }
        if (wide) SideMenu(Modifier.align(Alignment.TopStart).width(sideW).fillMaxHeight())

        // games are full screen: no mini player / tab bar covering their controls
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        val imeOpen = WindowInsets.isImeVisible
        // screens with something to type / controls at the bottom are full screen, like WhatsApp:
        // the mini player and the tab bar never cover their input bar
        val cur = nav.current
        val inGame = imeOpen || nav.playerOpen || cur is Route.Guess || cur is Route.BlindTest || cur is Route.Chat ||
                cur is Route.EditProfile || cur is Route.AddFriend || cur is Route.DeezerLogin || cur is Route.Import ||
                cur is Route.Stories || cur is Route.NewStory || cur is Route.StoryRecap || cur is Route.Layers || cur is Route.Publish
        AnimatedVisibility(
            !inGame, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(280)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(160)),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = sideW)) {
                AlongBanner()
                StudioBar()
                MiniPlayer()
                if (!wide) BottomBar() else Spacer(Modifier.navigationBarsPadding().height(8.dp))
            }
        }

        AnimatedVisibility(
            nav.playerOpen,
            enter = slideInVertically(tween(320)) { it } + fadeIn(),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(),
        ) {
            if (wide) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 560.dp).fillMaxHeight()) { FullPlayer() }
            } else FullPlayer()
        }

        nav.share?.let { ShareSheet(it) { nav.share = null } }
        nav.addToPlaylist?.let { AddToPlaylistSheet(it) { nav.addToPlaylist = null } }
        UpdateHost()
        DeezerAuthHost()
        Toast(nav.toast) { nav.toast = null }
    }
    }
}

data class ScreenKey(val tab: Int, val depth: Int, val route: Route)

suspend fun handleLink(app: AppState, uri: Uri) {
    val nav = app.nav
    val parts = uri.pathSegments
    when (uri.host) {
        "chat" -> parts.firstOrNull()?.let { nav.openOn(Route.Friends, Route.Chat(it)) }
        "album" -> parts.firstOrNull()?.toLongOrNull()?.let { nav.openOn(Route.Home, Route.DzAlbum(it)) }
        "invite" -> parts.firstOrNull()?.let { code ->
            runCatching { app.social.followByCode(code) }
                .onSuccess { nav.toast("Ora segui ${it.name} ✨"); nav.openOn(Route.Friends, Route.Profile(it.id)) }
                .onFailure { nav.toast(it.message ?: "Invito non valido") }
        }
        "playlist" -> parts.firstOrNull()?.let { nav.openOn(Route.Library, Route.SharedPlaylist(it)) }
        "user" -> parts.firstOrNull()?.let { nav.openOn(Route.Friends, Route.Profile(it)) }
        "lobby" -> parts.firstOrNull()?.let { nav.openOn(Route.Friends, Route.ListenLobby(it)) }
        "challenge" -> if (parts.size >= 2) {
            val (game, lobby) = parts[0] to parts[1]
            nav.openOn(Route.Games, if (game == "blindtest") Route.BlindTest(lobby) else Route.Guess(lobby))
        }
    }
}

@Composable
fun Screen(route: Route) {
    when (route) {
        Route.Home -> HomeScreen()
        Route.Search -> SearchScreen()
        Route.Library -> LibraryScreen()
        Route.Friends -> FriendsScreen()
        is Route.ListenLobby -> ListenLobbyScreen(route.id)
        Route.Games -> GamesScreen()
        is Route.Playlist -> PlaylistScreen(route.id)
        Route.Liked -> LikedScreen()
        Route.Downloads -> DownloadsScreen()
        is Route.Media -> {
            val it = route.item
            val albumId = (it as? dev.brahmkshatriya.echo.common.models.Album)?.id?.toLongOrNull()
            when {
                it is dev.brahmkshatriya.echo.common.models.Artist -> ArtistFromEngine(it)
                // a Deezer album: the album page (saved as an album, never as a playlist)
                albumId != null -> DzAlbumScreen(albumId)
                else -> MediaScreen(it)
            }
        }
        is Route.DzArtist -> ArtistScreen(route.id, route.name)
        is Route.DzAlbum -> DzAlbumScreen(route.id)
        is Route.DzPlaylist -> DzPlaylistScreen(route.id)
        is Route.Chat -> ChatScreen(route.friendId)
        is Route.Profile -> ProfileScreen(route.userId)
        Route.EditProfile -> EditProfileScreen()
        Route.AddFriend -> AddFriendScreen()
        Route.Settings -> SettingsScreen()
        Route.Feedback -> FeedbackScreen()
        Route.FeedbackAdmin -> FeedbackAdminScreen()
        Route.DeezerLogin -> DeezerLoginScreen()
        is Route.SharedPlaylist -> SharedPlaylistScreen(route.id, route.collab)
        is Route.Guess -> GuessGameScreen(route.lobbyId, route.source)
        Route.Stats -> StatsScreen()
        Route.Studio -> StudioScreen()
        Route.Publish -> PublishScreen()
        Route.Samples -> SamplesScreen()
        Route.StudioStats -> StudioStatsScreen()
        Route.Podcasts -> PodcastsScreen()
        is Route.Podcast -> PodcastShowScreen(route.id, route.title, route.picture)
        is Route.VibesRelease -> VibesReleaseScreen(route.id)
        Route.Layers -> LayersGameScreen()
        is Route.Stories -> StoryViewerScreen(route.userId)
        is Route.NewStory -> NewStoryScreen(route.song)
        is Route.StoryRecap -> StoryRecapScreen(route.month)
        is Route.Follows -> FollowsScreen(route.userId, route.followers)
        Route.History -> HistoryScreen()
        is Route.BlindTest -> BlindTestScreen(route.lobbyId)
        is Route.Genre -> GenreScreen(route.name)
        Route.Import -> ImportScreen()
    }
}

@Composable
private fun BottomBar() {
    val app = LocalApp.current
    val nav = app.nav
    val unread by app.social.unread.collectAsState()
    val requests by app.social.requests.collectAsState()
    val badge = unread.values.sum() + requests.size
    // liquid-glass bar attached to the bottom edge; the glass is its own layer so the Studio orb
    // can grow out of the bar without being clipped
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().glass(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), blur = true))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NavItem("Home", Icons.Rounded.Home, Icons.Outlined.Home, nav.tab == Route.Home) { nav.selectTab(Route.Home) } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NavItem("Cerca", Icons.Rounded.Search, Icons.Outlined.Search, nav.tab == Route.Search) { nav.selectTab(Route.Search) } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NavItem("Libreria", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic, nav.tab == Route.Library) { nav.selectTab(Route.Library) } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { StudioNavItem(nav.tab == Route.Studio) { nav.selectTab(Route.Studio) } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NavItem("Amici", Icons.Rounded.Forum, Icons.Outlined.Forum, nav.tab == Route.Friends, badge) { nav.selectTab(Route.Friends) } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NavItem("Giochi", Icons.Rounded.SportsEsports, Icons.Outlined.SportsEsports, nav.tab == Route.Games) { nav.selectTab(Route.Games) } }
        }
    }
}

/** Four little mixer knobs; [spin] turns each indicator (at different speeds). */
@Composable
private fun KnobsIcon(color: Color, spin: Float, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val r = size.minDimension * 0.19f
        val centers = listOf(0.28f to 0.28f, 0.72f to 0.28f, 0.28f to 0.72f, 0.72f to 0.72f)
        val speeds = listOf(1f, -1.4f, 0.7f, -0.9f)
        val base = listOf(-120f, -45f, -170f, 10f)
        centers.forEachIndexed { i, (fx, fy) ->
            val c = androidx.compose.ui.geometry.Offset(size.width * fx, size.height * fy)
            drawCircle(color.copy(alpha = 0.22f), radius = r * 1.25f, center = c)
            drawCircle(color, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.32f))
            val ang = Math.toRadians((base[i] + spin * speeds[i]).toDouble())
            drawLine(color, c, androidx.compose.ui.geometry.Offset(c.x + (kotlin.math.cos(ang) * r * 0.85f).toFloat(), c.y + (kotlin.math.sin(ang) * r * 0.85f).toFloat()),
                strokeWidth = r * 0.34f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/**
 * Studio tab: a normal small icon in the bar. When selected the knobs start turning, a rainbow
 * ring fades in around it and the whole thing pops up big above the bar.
 */
@Composable
private fun StudioNavItem(selected: Boolean, onClick: () -> Unit) {
    val grow by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 260f), label = "grow")
    val ringSpin = liveFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(3500, easing = androidx.compose.animation.core.LinearEasing)), "ring", enabled = selected)
    val knobSpin = liveFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(2600, easing = androidx.compose.animation.core.LinearEasing)), "knob", enabled = selected)
    val pulse = liveFloat(1f, 1.06f, androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), "pulse", enabled = selected)
    // a quick knob twist every time you tap it
    val twist = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(selected) { if (selected) { twist.snapTo(0f); twist.animateTo(180f, tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing)) } }
    val onColor = MaterialTheme.colorScheme.onSurface
    val offColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.bouncy(onClick).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(28.dp).graphicsLayer {
                val sc = 1f + grow * 1.05f
                scaleX = sc * (if (selected) pulse else 1f); scaleY = scaleX
                translationY = -grow * 20.dp.toPx()
            },
            contentAlignment = Alignment.Center
        ) {
            // rainbow ring
            Box(Modifier.size(28.dp).graphicsLayer { alpha = grow; rotationZ = ringSpin }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Brush.sweepGradient(listOf(Color(0xFFFF2E97), Color(0xFFFF8A2B), Color(0xFFFFD43B), Color(0xFF1ED760), Color(0xFF35D0F5), Color(0xFFB84DFF), Color(0xFFFF2E97)))))
            // dark glass core
            Box(Modifier.size(28.dp - 4.dp * grow).graphicsLayer { alpha = grow }.clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)))
            KnobsIcon(if (selected) onColor else offColor, if (selected) knobSpin + twist.value else 0f, Modifier.size(22.dp - 4.dp * grow))
        }
        Text("Studio", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false,
            color = if (selected) onColor else offColor, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun NavItem(label: String, on: ImageVector, off: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val color by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, tween(220), label = "navc")
    // pop: the icon jumps and settles every time the tab gets selected
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) { pop.snapTo(0.7f); pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f)) }
    }
    val pill by androidx.compose.animation.core.animateFloatAsState(
        if (selected) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f), label = "pill")
    Column(
        Modifier.bouncy(onClick).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(width = 54.dp, height = 32.dp)
                    .graphicsLayer { scaleX = 0.4f + 0.6f * pill; alpha = pill }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
            )
            BadgedBox(badge = {
                if (badge > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$badge") }
            }) {
                Icon(if (selected) on else off, label, tint = color,
                    modifier = Modifier.size(26.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun Toast(message: String?, onDone: () -> Unit) {
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) { shown = message; delay(2600); onDone(); shown = null }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 170.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(shown != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
            Box(
                Modifier.padding(horizontal = 24.dp).glass(RoundedCornerShape(18.dp), blur = true, fallback = MaterialTheme.colorScheme.onSurface)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) { Text(shown ?: "", color = if (LocalSettings.current.liquidGlass) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
fun VibesLogo(modifier: Modifier = Modifier) {
    Box(
        modifier.clip(androidx.compose.foundation.shape.CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF7B2FF7), Color(0xFFC2187A), Color(0xFFFF6A3D)))),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
            listOf(0.62f, 0.46f, 0.3f, 0.46f, 0.62f).forEach { h ->
                Box(
                    Modifier.size(width = 6.dp, height = (40 * h).dp)
                        .clip(RoundedCornerShape(3.dp)).background(Color.White)
                )
            }
        }
    }
}


/** Keyboard shortcuts on PC (BlueStacks / LDPlayer / tablets with a keyboard). */
private fun pcShortcut(app: AppState, ev: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val k = ev.key
    val K = androidx.compose.ui.input.key.Key
    return when {
        k == K.Spacebar || k == K.MediaPlayPause -> { app.engine.togglePlay(); true }
        (ev.isCtrlPressed && k == K.DirectionRight) || k == K.MediaNext -> { app.engine.next(); true }
        (ev.isCtrlPressed && k == K.DirectionLeft) || k == K.MediaPrevious -> { app.engine.previous(); true }
        ev.isCtrlPressed && k == K.F -> { app.nav.selectTab(Route.Search); true }
        ev.isCtrlPressed && k == K.L -> { app.nav.selectTab(Route.Library); true }
        k == K.Escape -> { if (app.nav.playerOpen || app.nav.canGoBack) { app.nav.back(); true } else false }
        else -> false
    }
}

/** Left menu for wide screens (PC): logo, tabs with labels, shortcuts hint. */
@Composable
private fun SideMenu(modifier: Modifier) {
    val app = LocalApp.current
    val nav = app.nav
    val unread by app.social.unread.collectAsState()
    val requests by app.social.requests.collectAsState()
    val badge = unread.values.sum() + requests.size
    Column(modifier.background(Color.Black.copy(alpha = 0.35f)).statusBarsPadding().padding(12.dp)) {
        Text("Vibes", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 18.dp))
        @Composable
        fun Item(label: String, icon: ImageVector, route: Route, count: Int = 0) {
            val sel = nav.tab == route
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (sel) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                .bouncy({ nav.selectTab(route) }).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (count > 0) Text("$count", style = MaterialTheme.typography.labelMedium, color = Color.White,
                    modifier = Modifier.clip(CircleShape).background(Color(0xFFFF4FD8)).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
        Item("Home", Icons.Rounded.Home, Route.Home)
        Item("Cerca", Icons.Rounded.Search, Route.Search)
        Item("Libreria", Icons.Rounded.LibraryMusic, Route.Library)
        Item("Studio", Icons.Rounded.Tune, Route.Studio)
        Item("Amici", Icons.Rounded.Forum, Route.Friends, badge)
        Item("Giochi", Icons.Rounded.SportsEsports, Route.Games)
        Spacer(Modifier.weight(1f))
        Text("Spazio: play/pausa\nCtrl ← →: brano prec./succ.\nCtrl F: cerca · Esc: indietro",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp))
    }
}
