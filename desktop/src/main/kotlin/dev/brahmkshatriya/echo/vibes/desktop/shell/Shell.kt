package dev.brahmkshatriya.echo.vibes.desktop.shell

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.ui.Cover
import dev.brahmkshatriya.echo.vibes.ui.DeezerAuthHost
import dev.brahmkshatriya.echo.vibes.ui.KaraokeScreen
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.LocalHaze
import dev.brahmkshatriya.echo.vibes.ui.LocalVibes
import dev.brahmkshatriya.echo.vibes.ui.Route
import dev.brahmkshatriya.echo.vibes.ui.Screen
import dev.brahmkshatriya.echo.vibes.ui.ShareSheet
import dev.brahmkshatriya.echo.vibes.ui.AddToPlaylistSheet
import dev.brahmkshatriya.echo.vibes.ui.Toast
import dev.brahmkshatriya.echo.vibes.ui.UpdateHost
import dev.brahmkshatriya.echo.vibes.ui.currentSong
import dev.brahmkshatriya.echo.vibes.ui.handleLink
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.MutableStateFlow

/** The PC window's state lives here so Main (taskbar, full screen) can reach it. */
object DeskHolder { val state = DeskState() }

/**
 * Vibes on PC: Spotify-like window. Top bar (back/forward, home, search, Studio/Giochi/Amici,
 * profile), resizable library on the left, the page in the middle on a background that takes the
 * colour of what you point at, "now playing" / queue / friends on the right (resizable, closable),
 * the player bar at the bottom. Full screen view, lyrics view, karaoke and mini player on top.
 */
@Composable
fun DesktopShell(pendingLink: MutableStateFlow<Uri?>) {
    val app = LocalApp.current
    val nav = app.nav
    val desk = DeskHolder.state
    val link by pendingLink.collectAsState()
    LaunchedEffect(link) {
        val uri = link ?: return@LaunchedEffect
        pendingLink.value = null
        handleLink(app, uri)
    }
    val update by VibesGraph.updater.pending.collectAsState()
    val searchFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // forward history: anything opened normally clears it (like a browser)
    val expected = remember { arrayOfNulls<Route>(1) }
    // (read in composition, never in a snapshotFlow: the navigator creates a tab's stack on first read)
    val route = nav.current
    val last = remember { arrayOfNulls<Route>(1) }
    LaunchedEffect(route) {
        val prev = last[0]; last[0] = route
        if (prev != null && prev != route) { if (route == expected[0]) expected[0] = null else desk.forward.clear() }
        // a page opened from anywhere closes the lyrics view
        if (prev != null) desk.overlay = CenterOverlay.None
    }
    fun back() {
        when {
            desk.karaoke -> desk.karaoke = false
            desk.fullPlayer -> { desk.fullPlayer = false; if (desk.isWindowFullscreen()) desk.toggleWindowFullscreen() }
            desk.overlay != CenterOverlay.None -> desk.overlay = CenterOverlay.None
            else -> { val cur = nav.current; if (nav.back()) { desk.forward.add(cur); expected[0] = nav.current }  }
        }
    }
    fun forward() {
        val r = desk.forward.removeLastOrNull() ?: return
        expected[0] = r; desk.overlay = CenterOverlay.None; nav.open(r)
    }
    BackHandler(enabled = true) {
        if (update != null) VibesGraph.updater.close() else back()
    }

    val haze = remember { dev.chrisbanes.haze.HazeState() }
    CompositionLocalProvider(LocalDesk provides desk, LocalHaze provides haze) {
        Box(
            Modifier.fillMaxSize().background(Desk.window)
                .onKeyEvent { ev -> if (ev.type != KeyEventType.KeyDown) false else shortcut(app, ev, searchFocus, ::back, ::forward) }
                .focusRequester(rootFocus).focusable()
        ) {
            // the living background: glows in the colours of the song playing, seen through the glass panels
            if (dev.brahmkshatriya.echo.vibes.ui.LocalSettings.current.reactiveBackground) dev.brahmkshatriya.echo.vibes.ui.ReactiveBackground(Modifier.fillMaxSize().hazeSource(haze))
            else LivingBackground(Modifier.fillMaxSize().hazeSource(haze))
            Column(Modifier.fillMaxSize()) {
                TopBar(searchFocus, ::back, ::forward)
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(0.dp)) {
                    LibraryPanel(desk.libraryWidth.dp)
                    ResizeHandle({ d -> desk.libraryWidth = (desk.libraryWidth + d).coerceIn(72f, 560f) },
                        onEnd = { if (desk.libraryWidth < 160f) desk.libraryWidth = 72f else if (desk.libraryWidth < 240f) desk.libraryWidth = 240f; desk.save() })
                    CenterPane(Modifier.weight(1f).fillMaxHeight())
                    // always there: dragged narrow (or "riduci") it becomes a strip of buttons, never disappears
                    if (desk.rightCollapsed) {
                        ResizeHandle({ d -> if (d < -12f) desk.expandRight() })
                        RightStrip()
                    } else {
                        ResizeHandle({ d -> desk.rightWidth = (desk.rightWidth - d).coerceIn(200f, 560f) },
                            onEnd = { if (desk.rightWidth < 260f) { desk.rightWidth = 340f; desk.collapseRight() } else desk.save() })
                        RightPanelHost(desk.rightWidth.dp)
                    }
                }
                PlayerBar()
            }

            AnimatedVisibility(desk.fullPlayer, enter = fadeIn(tween(250)) + slideInVertically(tween(300)) { it / 8 }, exit = fadeOut(tween(200))) {
                FullScreenPlayer()
            }
            if (desk.karaoke) {
                val now = currentSong()
                if (now == null) desk.karaoke = false
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    KaraokeScreen(now.first, now.second) { desk.karaoke = false }
                }
            }
            nav.share?.let { ShareSheet(it) { nav.share = null } }
            nav.addToPlaylist?.let { AddToPlaylistSheet(it) { nav.addToPlaylist = null } }
            UpdateHost()
            DeezerAuthHost()
            Toast(nav.toast) { nav.toast = null }
            if (desk.miniPlayer) MiniPlayerWindow { desk.miniPlayer = false }
        }
    }
}

/** The middle: the page on a background that follows the colour of what you point at. */
@Composable
private fun CenterPane(modifier: Modifier) {
    val app = LocalApp.current
    val nav = app.nav
    val desk = LocalDesk.current
    val v = LocalVibes.current
    val base = lerp(v.bgTop, Color.Black, 0.35f)
    val target = desk.hoverColor ?: desk.pageColor ?: base
    val top by animateColorAsState(target, tween(700), label = "bg")
    Box(modifier.deskGlass(RoundedCornerShape(24.dp))) {
        // the colour of what you point at (or of the page) washes the top of the page
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height; val w = size.width
            drawRect(Brush.verticalGradient(0f to top.copy(alpha = 0.85f), 0.35f to top.copy(alpha = 0.25f), 0.6f to Color.Transparent, startY = 0f, endY = h))
            drawCircle(Brush.radialGradient(listOf(lerp(top, Color.White, 0.2f).copy(alpha = 0.22f), Color.Transparent), center = Offset(w * 0.85f, 0f), radius = w * 0.45f),
                radius = w * 0.45f, center = Offset(w * 0.85f, 0f))
        }
        if (desk.overlay == CenterOverlay.Lyrics) LyricsView()
        else AnimatedContent(
            nav.current, transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(90))) }, label = "page",
            contentKey = { it },
        ) { route -> CenterRoute(route) }
    }
}

@Composable
private fun CenterRoute(route: Route) {
    when (route) {
        Route.Home -> DeskHome()
        Route.Library -> DeskLibraryPage()
        Route.Liked -> DeskLikedPage()
        is Route.Playlist -> DeskPlaylistPage(route.id)
        is Route.DzAlbum -> DeskAlbumPage(route.id)
        is Route.Media -> {
            val albumId = (route.item as? dev.brahmkshatriya.echo.common.models.Album)?.id?.toLongOrNull()
            when {
                albumId != null -> DeskAlbumPage(albumId)
                route.item is dev.brahmkshatriya.echo.common.models.Artist -> Wide(1600) { Screen(route) }
                else -> DeskMediaPage(route.item)
            }
        }
        // the phone's screens, centred at a comfortable width
        Route.Games -> Wide(1300) { Screen(route) }
        is Route.Guess, is Route.BlindTest, Route.Layers -> Wide(900) { Screen(route) }
        // artist pages: as wide as the centre (the banner used to stop with dark strips at the sides)
        is Route.DzArtist -> Wide(1600) { Screen(route) }
        Route.Studio, Route.Publish, Route.Samples, Route.StudioStats, Route.Podcasts, is Route.VibesRelease -> Wide(1300) { Screen(route) }
        is Route.Chat -> Wide(900) { Screen(route) }
        else -> Wide { Screen(route) }
    }
}

@Composable
private fun Wide(max: Int = 1200, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max.dp).fillMaxHeight()) { content() }
    }
}

private fun shortcut(app: dev.brahmkshatriya.echo.vibes.ui.AppState, ev: androidx.compose.ui.input.key.KeyEvent, search: FocusRequester, back: () -> Unit, forward: () -> Unit): Boolean {
    val k = ev.key
    val p = app.engine.player
    return when {
        k == Key.Spacebar || k == Key.MediaPlayPause -> { app.engine.togglePlay(); true }
        ev.isCtrlPressed && k == Key.DirectionRight || k == Key.MediaNext -> { app.engine.next(); true }
        ev.isCtrlPressed && k == Key.DirectionLeft || k == Key.MediaPrevious -> { app.engine.previous(); true }
        ev.isCtrlPressed && k == Key.DirectionUp -> { p.volume = p.volume + 0.1f; true }
        ev.isCtrlPressed && k == Key.DirectionDown -> { p.volume = p.volume - 0.1f; true }
        ev.isCtrlPressed && (k == Key.K || k == Key.L || k == Key.F) -> { runCatching { search.requestFocus() }; true }
        ev.isAltPressed && k == Key.DirectionLeft -> { back(); true }
        ev.isAltPressed && k == Key.DirectionRight -> { forward(); true }
        ev.isCtrlPressed && k == Key.S -> { p.setShuffle(!p.shuffle); true }
        ev.isCtrlPressed && k == Key.R -> { p.setRepeat(when (p.repeat) { 0 -> 2; 2 -> 1; else -> 0 }); true }
        else -> false
    }
}

/** Small always-on-top window with the song and the controls (Spotify's mini player). */
@Composable
private fun MiniPlayerWindow(onClose: () -> Unit) {
    val app = LocalApp.current
    val state = rememberWindowState(size = DpSize(320.dp, 380.dp))
    Window(onCloseRequest = onClose, state = state, title = "Vibes", alwaysOnTop = true, resizable = true) {
        val now = currentSong()
        Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
            if (now == null) Text("Niente in riproduzione", color = Desk.sub, modifier = Modifier.align(Alignment.Center))
            else {
                val (song, playing) = now
                Cover(song.cover, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)) {
                    Text(song.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val (pos, dur) = rememberPlayback()
                    SeekBar(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.fillMaxWidth().padding(vertical = 6.dp), onSeek = { app.engine.seekTo((it * dur).toLong()) })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        DeskIcon(Icons.Rounded.SkipPrevious, "Precedente", size = 40.dp, iconSize = 26.dp) { app.engine.previous() }
                        Box(Modifier.padding(horizontal = 14.dp).size(46.dp).clip(CircleShape).background(Color.White).pointerHoverIcon(HandCursor)
                            .clickable { app.engine.togglePlay() }, contentAlignment = Alignment.Center) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(28.dp))
                        }
                        DeskIcon(Icons.Rounded.SkipNext, "Successivo", size = 40.dp, iconSize = 26.dp) { app.engine.next() }
                    }
                }
            }
        }
    }
}


/** Window background: two slow-moving glows in the colours of the cover playing (theme colours otherwise). */
@Composable
private fun LivingBackground(modifier: Modifier) {
    val v = LocalVibes.current
    val now = currentSong()
    val c = rememberCoverColor(now?.first?.cover) ?: v.bgTop
    val a by animateColorAsState(c, tween(1500), label = "la")
    val b by animateColorAsState(lerp(c, VibesGradient[1], 0.55f), tween(1500), label = "lb")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Desk.window)
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.75f), Color.Transparent), center = Offset(w * 0.18f, h * 0.15f), radius = w * 0.55f),
            radius = w * 0.55f, center = Offset(w * 0.18f, h * 0.15f))
        drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.6f), Color.Transparent), center = Offset(w * 0.88f, h * 0.9f), radius = w * 0.5f),
            radius = w * 0.5f, center = Offset(w * 0.88f, h * 0.9f))
        drawCircle(Brush.radialGradient(listOf(VibesGradient[0].copy(alpha = 0.35f), Color.Transparent), center = Offset(w * 0.6f, h * 0.35f), radius = w * 0.35f),
            radius = w * 0.35f, center = Offset(w * 0.6f, h * 0.35f))
    }
}
