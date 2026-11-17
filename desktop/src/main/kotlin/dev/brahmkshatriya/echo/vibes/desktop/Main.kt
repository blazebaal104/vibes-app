package dev.brahmkshatriya.echo.vibes.desktop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import android.content.DesktopEnv
import android.net.Uri
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.notify.VibesNotifier
import dev.brahmkshatriya.echo.vibes.ui.LocalAppHolder
import dev.brahmkshatriya.echo.vibes.ui.VibesApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Vibes for Windows. Same app as the phone (same Compose screens, same Supabase account, same Deezer
 * engine), in a desktop window: the wide layout (side menu like Spotify desktop) kicks in on its own.
 */
fun main(args: Array<String>) {
    // a log next to the user data (%APPDATA%\\Vibes\\vibes.log): the installed app has no console
    if (System.console() == null && System.getenv("VIBES_UITEST") == null) runCatching {
        val log = java.io.PrintStream(java.io.FileOutputStream(DesktopEnv.dataDir.resolve("vibes.log")), true, "UTF-8")
        System.setOut(log); System.setErr(log)
    }
    println("Vibes ${dev.brahmkshatriya.echo.BuildConfig.VERSION_NAME} (${dev.brahmkshatriya.echo.BuildConfig.VERSION_CODE}) · ${System.getProperty("os.name")} · Java ${System.getProperty("java.version")}")
    try { start(args) } catch (e: Throwable) {
        e.printStackTrace()
        javax.swing.JOptionPane.showMessageDialog(null, "Vibes non è riuscito ad aprirsi:\n${e.javaClass.simpleName}: ${e.message}\n\nDettagli in ${DesktopEnv.dataDir.resolve("vibes.log")}", "Vibes", javax.swing.JOptionPane.ERROR_MESSAGE)
        kotlin.system.exitProcess(1)
    }
}

private fun start(args: Array<String>) {
    val ctx = DesktopEnv.app
    VibesGraph.init(ctx)
    val loader = ExtensionLoader(ctx)
    val player = PlayerViewModel(ctx, loader)
    val engine = MusicEngine(loader, player)
    dev.brahmkshatriya.echo.vibes.music.VibesOffline.engine = engine
    VibesNotifier.start(ctx)

    val pendingLink = MutableStateFlow<Uri?>(args.firstOrNull { it.startsWith("vibes://") }?.let(Uri::parse))
    DesktopTray.onOpen = { link -> pendingLink.value = Uri.parse(link) }
    android.widget.Toast.sink = { msg -> javax.swing.SwingUtilities.invokeLater { runCatching { LocalAppHolder.app?.nav?.toast(msg) } } }
    android.content.DesktopEnv.onShare = { text ->
        android.content.ClipboardManager.setText(text)
        android.widget.Toast.show("Link copiato: incollalo dove vuoi")
    }

    // UI tests (no Deezer account): put the test library's songs in the player, paused, so the
    // "now playing" parts of the window have something to show
    if (System.getenv("VIBES_UITEST") != null) CoroutineScope(SupervisorJob() + Dispatchers.Swing).launch {
        kotlinx.coroutines.delay(75_000)
        val songs = VibesGraph.library.recent.value.take(6)
        if (songs.isNotEmpty()) runCatching { player.setQueue(songs.first().ext, songs.map { it.toTrack() }, 0, null); player.setPlaying(false) }
    }

    // like the phone's activity: each song that starts → recents, play counts, "now playing" for friends
    CoroutineScope(SupervisorJob() + Dispatchers.Swing).launch {
        player.playerState.current.filterNotNull().distinctUntilChangedBy { it.mediaItem.mediaId }.collect { cur ->
            runCatching {
                val song = SongRef.from(cur.mediaItem.extensionId, cur.track)
                VibesGraph.library.recordPlay(song)
                VibesGraph.social.setNowPlaying(song)
            }
        }
    }

    // the PC layout (library / page / now playing / player bar) instead of the phone's
    dev.brahmkshatriya.echo.vibes.ui.PlatformUI.desktopScaffold = { link -> dev.brahmkshatriya.echo.vibes.desktop.shell.DesktopShell(link) }
    dev.brahmkshatriya.echo.vibes.ui.PlatformUI.transparentScreens = true
    dev.brahmkshatriya.echo.vibes.ui.PlatformUI.searchQuery = androidx.compose.runtime.mutableStateOf("")
    dev.brahmkshatriya.echo.vibes.ui.PlatformUI.songRow = { song, content -> dev.brahmkshatriya.echo.vibes.desktop.shell.DeskSongRow(song, content) }

    application {
        // big screens: start large (most of the screen), like the other desktop apps
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        val scale = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX }.getOrDefault(1.0)
        val w = (screen.width / scale * 0.86).coerceIn(1100.0, 1920.0); val h = (screen.height / scale * 0.86).coerceIn(700.0, 1200.0)
        val uitest = System.getenv("VIBES_UITEST") != null
        val state = if (uitest) rememberWindowState(size = DpSize(screen.width.dp, screen.height.dp), position = WindowPosition(0.dp, 0.dp))
        else rememberWindowState(size = DpSize(w.dp, h.dp), position = WindowPosition(Alignment.Center),
            placement = if (screen.width / scale <= 1600) androidx.compose.ui.window.WindowPlacement.Maximized else androidx.compose.ui.window.WindowPlacement.Floating)
        val desk = dev.brahmkshatriya.echo.vibes.desktop.shell.DeskHolder.state
        // where to go back to when leaving full screen (kept across recompositions: it used to be reset,
        // and going straight from Fullscreen to Maximized is ignored on Windows -> "stuck" full screen)
        val before = androidx.compose.runtime.remember { arrayOf(androidx.compose.ui.window.WindowPlacement.Maximized) }
        val fsScope = androidx.compose.runtime.rememberCoroutineScope()
        desk.isWindowFullscreen = { state.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen }
        desk.toggleWindowFullscreen = {
            if (state.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen) {
                val back = before[0]
                state.placement = androidx.compose.ui.window.WindowPlacement.Floating
                if (back == androidx.compose.ui.window.WindowPlacement.Maximized) fsScope.launch {
                    kotlinx.coroutines.delay(120); state.placement = androidx.compose.ui.window.WindowPlacement.Maximized
                }
            } else {
                before[0] = state.placement.takeIf { it != androidx.compose.ui.window.WindowPlacement.Fullscreen }
                    ?: androidx.compose.ui.window.WindowPlacement.Maximized
                state.placement = androidx.compose.ui.window.WindowPlacement.Fullscreen
            }
        }
        val icon = painterResource("icon.png")
        Window(
            onCloseRequest = {
                // closing: what changed on this PC reaches the account before the app goes away
                VibesGraph.flushBlocking()
                exitApplication()
            },
            title = "Vibes",
            icon = icon,
            state = state,
            onPreviewKeyEvent = { ev ->
                // Esc = Android's back button (closes dialogs/panels first, then goes back)
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape && dev.brahmkshatriya.echo.vibes.ui.PlatformUI.popups == 0) androidx.activity.compose.BackDispatcher.back() else false
            },
        ) {
            LaunchedOnce {
                window.minimumSize = java.awt.Dimension(1000, 640)
                // render only in the foreground: minimized / in the tray = no decorative animation,
                // no progress ticking; another app in front = animations paused
                val perf = dev.brahmkshatriya.echo.vibes.ui.Perf
                fun updatePerf() {
                    val wasVisible = perf.visible
                    perf.visible = window.isVisible && (window.extendedState and java.awt.Frame.ICONIFIED) == 0
                    perf.focused = perf.visible && window.isActive
                    // another app in front (a game): Vibes steps back, the audio thread keeps its MMCSS slot
                    dev.brahmkshatriya.echo.vibes.desktop.WinPerf.background(!perf.focused)
                    // account sync: hiding the window sends what changed, showing it takes the phone's changes
                    if (wasVisible != perf.visible) runCatching { if (perf.visible) VibesGraph.syncNow() else VibesGraph.flushSync() }
                }
                window.addWindowStateListener { updatePerf() }
                window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = updatePerf()
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) = updatePerf()
                })
                window.addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentShown(e: java.awt.event.ComponentEvent?) = updatePerf()
                    override fun componentHidden(e: java.awt.event.ComponentEvent?) = updatePerf()
                })
                updatePerf()
                // taskbar preview buttons: previous / play-pause / next / like (Windows only)
                runCatching { dev.brahmkshatriya.echo.vibes.desktop.shell.TaskbarButtons.install(window, engine) }
                    .onFailure { println("taskbar buttons: ${it.message}") }
                runCatching { DesktopTray.install(javax.imageio.ImageIO.read(javaClass.getResourceAsStream("/icon.png"))) }
                DesktopTray.onShow = { javax.swing.SwingUtilities.invokeLater { window.isVisible = true; window.toFront(); window.requestFocus() } }
                // mouse "back" side button
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
                    if (e is java.awt.event.MouseEvent && e.id == java.awt.event.MouseEvent.MOUSE_PRESSED && e.button == 4)
                        androidx.activity.compose.BackDispatcher.back()
                }, java.awt.AWTEvent.MOUSE_EVENT_MASK)
            }
            BoxWithConstraints {
                CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                    VibesApp(engine, pendingLink)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun LaunchedOnce(block: () -> Unit) = androidx.compose.runtime.LaunchedEffect(Unit) { block() }
