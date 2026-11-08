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
    System.setProperty("sun.awt.exception.handler", "")
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

    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition(Alignment.Center))
        val icon = painterResource("icon.png")
        Window(
            onCloseRequest = ::exitApplication,
            title = "Vibes",
            icon = icon,
            state = state,
            onPreviewKeyEvent = { ev ->
                // Esc = Android's back button (closes dialogs/panels first, then goes back)
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) androidx.activity.compose.BackDispatcher.back() else false
            },
        ) {
            LaunchedOnce {
                window.minimumSize = java.awt.Dimension(900, 620)
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
