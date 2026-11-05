package dev.brahmkshatriya.echo.vibes.ui

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
            LaunchedEffect(session?.userId) {
                if (session == null) { state.social.clear(); return@LaunchedEffect }
                // retry until the profile loads (offline at start-up, slow network...)
                while (runCatching { state.social.bootstrap() }.isFailure) delay(3000)
                // friends see this week's stats + records
                dev.brahmkshatriya.echo.vibes.VibesGraph.onListened(force = true)
                // keep friends' live "now playing" fresh in lists
                while (true) { delay(30_000); runCatching { state.social.refreshFriends() } }
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
                    LocalSettings.current.needsOnboarding -> OnboardingScreen()
                    else -> MainScaffold(pendingLink)
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
    androidx.compose.runtime.CompositionLocalProvider(LocalHaze provides hazeState) {
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
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
        ) { key -> Screen(key.route) }

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
            Column(Modifier.fillMaxWidth()) {
                AlongBanner()
                StudioBar()
                MiniPlayer()
                BottomBar()
            }
        }

        AnimatedVisibility(
            nav.playerOpen,
            enter = slideInVertically(tween(320)) { it } + fadeIn(),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(),
        ) { FullPlayer() }

        nav.share?.let { ShareSheet(it) { nav.share = null } }
        nav.addToPlaylist?.let { AddToPlaylistSheet(it) { nav.addToPlaylist = null } }
        UpdateHost()
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
        Route.Games -> GamesScreen()
        is Route.Playlist -> PlaylistScreen(route.id)
        Route.Liked -> LikedScreen()
        is Route.Media -> (route.item as? dev.brahmkshatriya.echo.common.models.Artist)?.let { ArtistFromEngine(it) } ?: MediaScreen(route.item)
        is Route.DzArtist -> ArtistScreen(route.id, route.name)
        is Route.DzAlbum -> DzAlbumScreen(route.id)
        is Route.Chat -> ChatScreen(route.friendId)
        is Route.Profile -> ProfileScreen(route.userId)
        Route.EditProfile -> EditProfileScreen()
        Route.AddFriend -> AddFriendScreen()
        Route.Settings -> SettingsScreen()
        Route.DeezerLogin -> DeezerLoginScreen()
        is Route.SharedPlaylist -> SharedPlaylistScreen(route.id)
        is Route.Guess -> GuessGameScreen(route.lobbyId, route.source)
        Route.Stats -> StatsScreen()
        Route.Studio -> StudioScreen()
        Route.Publish -> PublishScreen()
        Route.Samples -> SamplesScreen()
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
    // floating liquid-glass pill; Studio is the special orb in the middle
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp).glass(RoundedCornerShape(30.dp), blur = true).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NavItem("Home", Icons.Rounded.Home, Icons.Outlined.Home, nav.tab == Route.Home) { nav.selectTab(Route.Home) } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NavItem("Cerca", Icons.Rounded.Search, Icons.Outlined.Search, nav.tab == Route.Search) { nav.selectTab(Route.Search) } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NavItem("Libreria", Icons.Rounded.LibraryMusic, Icons.Outlined.LibraryMusic, nav.tab == Route.Library) { nav.selectTab(Route.Library) } }
            Spacer(Modifier.width(70.dp))
            Box(Modifier.weight(1.5f), contentAlignment = Alignment.Center) { NavItem("Amici", Icons.Rounded.Forum, Icons.Outlined.Forum, nav.tab == Route.Friends, badge) { nav.selectTab(Route.Friends) } }
            Box(Modifier.weight(1.5f), contentAlignment = Alignment.Center) { NavItem("Giochi", Icons.Rounded.SportsEsports, Icons.Outlined.SportsEsports, nav.tab == Route.Games) { nav.selectTab(Route.Games) } }
        }
        StudioOrb(nav.tab == Route.Studio, Modifier.align(Alignment.TopCenter).offset(x = 6.dp)) { nav.selectTab(Route.Studio) }
    }
}

/** The Studio button: a glowing glass orb with a slowly turning aurora inside. */
@Composable
private fun StudioOrb(selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(6000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    val pulse by t.animateFloat(1f, 1.07f, androidx.compose.animation.core.infiniteRepeatable(tween(1400), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    val scale by androidx.compose.animation.core.animateFloatAsState(if (selected) 1.1f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f), label = "orbs")
    Column(modifier.bouncy(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(62.dp).graphicsLayer { scaleX = scale * pulse; scaleY = scale * pulse }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Brush.sweepGradient(listOf(Color(0xFFFF8A2B), Color(0xFFFF2E97), Color(0xFFB84DFF), Color(0xFF35D0F5), Color(0xFFFF8A2B))))
                .graphicsLayer { rotationZ = spin },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(54.dp).graphicsLayer { rotationZ = -spin }.glass(androidx.compose.foundation.shape.CircleShape, blur = true), contentAlignment = Alignment.Center) {
                Text("🎛️", fontSize = 24.sp)
            }
        }
        Text("Studio", style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp))
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun Toast(message: String?, onDone: () -> Unit) {
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
