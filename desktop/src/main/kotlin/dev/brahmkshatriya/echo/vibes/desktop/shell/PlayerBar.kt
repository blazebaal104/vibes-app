package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.ui.Cover
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.currentSong
import dev.brahmkshatriya.echo.vibes.ui.fmt
import dev.brahmkshatriya.echo.vibes.ui.openArtistOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sleep timer: pauses after N minutes or at the end of the song. */
object SleepTimer {
    var endsAt by mutableStateOf(0L)
    var endOfSong by mutableStateOf(false)
    private var job: Job? = null
    fun set(scope: kotlinx.coroutines.CoroutineScope, app: dev.brahmkshatriya.echo.vibes.ui.AppState, minutes: Int) {
        job?.cancel(); endOfSong = false
        if (minutes <= 0) { endsAt = 0; return }
        endsAt = System.currentTimeMillis() + minutes * 60_000L
        job = scope.launch { delay(minutes * 60_000L); app.engine.pause(); endsAt = 0; app.nav.toast("Timer: musica in pausa 😴") }
    }
    fun atEndOfSong(scope: kotlinx.coroutines.CoroutineScope, app: dev.brahmkshatriya.echo.vibes.ui.AppState) {
        job?.cancel(); endsAt = 0; endOfSong = true
        val key = app.engine.player.playerState.current.value?.mediaItem?.mediaId
        job = scope.launch {
            while (app.engine.player.playerState.current.value?.mediaItem?.mediaId == key) delay(300)
            app.engine.pause(); endOfSong = false; app.nav.toast("Timer: musica in pausa 😴")
        }
    }
}

/** Position / duration, polled. */
@Composable
fun rememberPlayback(): Pair<Long, Long> {
    val app = LocalApp.current
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            dev.brahmkshatriya.echo.vibes.ui.Perf.awaitVisible()
            pos = app.engine.position(); dur = app.engine.duration()
            delay(dev.brahmkshatriya.echo.vibes.ui.Perf.tick(200))
        }
    }
    return pos to dur
}

/** Thin seek bar: thicker + accent + thumb under the mouse, drag or click to seek. */
@Composable
fun SeekBar(fraction: Float, modifier: Modifier = Modifier, onSeek: (Float) -> Unit, onDrag: ((Float?) -> Unit)? = null) {
    var hover by remember { mutableStateOf(false) }
    var w by remember { mutableStateOf(1) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val f = drag ?: fraction
    val h by animateDpAsState(if (hover || drag != null) 6.dp else 4.dp, label = "sb")
    Box(
        modifier.height(16.dp).onSizeChanged { w = it.width }.hovered { hover = it }.pointerHoverIcon(HandCursor)
            .pointerInput(Unit) { detectTapGestures { o -> onSeek((o.x / w).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> drag = (o.x / w).coerceIn(0f, 1f); onDrag?.invoke(drag) },
                    onDragEnd = { drag?.let(onSeek); drag = null; onDrag?.invoke(null) },
                    onDragCancel = { drag = null; onDrag?.invoke(null) },
                ) { c, d -> c.consume(); drag = ((drag ?: 0f) + d.x / w).coerceIn(0f, 1f); onDrag?.invoke(drag) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(h).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(h).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(if (hover || drag != null) VibesGradient else listOf(Color.White, Color.White))))
        if (hover || drag != null) {
            val density = LocalDensity.current
            Box(Modifier.offset(x = with(density) { (w * f).toDp() } - 6.dp).size(12.dp).clip(CircleShape).background(Color.White))
        }
    }
}

/** The bottom bar: song on the left, controls + progress in the middle, the rest on the right. */
@Composable
fun PlayerBar() {
    val app = LocalApp.current
    val desk = LocalDesk.current
    val scope = rememberCoroutineScope()
    val now = currentSong()
    val (pos, dur) = rememberPlayback()
    val changes by app.engine.player.changes.collectAsState()
    val liked by app.library.liked.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    // a floating glass dock, detached from the window edge
    Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp).fillMaxWidth().height(80.dp)
        .deskGlass(RoundedCornerShape(28.dp), dark = 0.5f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // ---- song
        Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically) {
            if (now != null) {
                val (song, _) = now
                var hoverCover by remember { mutableStateOf(false) }
                Box(Modifier.size(58.dp).hovered { hoverCover = it }) {
                    Cover(song.cover, Modifier.fillMaxSize().clickable { desk.toggleRight(RightPanel.NowPlaying) }, RoundedCornerShape(14.dp))
                    if (hoverCover) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f))
                        .pointerHoverIcon(HandCursor).clickable { desk.fullPlayer = true }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "Apri a schermo intero", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).padding(start = 14.dp)) {
                    LinkText(song.title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) {
                        scope.launch { Actions.openAlbum(app, song) }
                    }
                    ArtistLinkText(song, style = MaterialTheme.typography.bodySmall) { i -> scope.launch { openArtistOf(app, song, i) } }
                }
                val isLiked = liked.any { it.key == song.key }
                Spacer(Modifier.width(10.dp))
                DeskIcon(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    if (isLiked) "Rimuovi da Brani che ti piacciono" else "Salva in Brani che ti piacciono", active = isLiked) { app.library.toggleLike(song) }
            }
        }
        // ---- controls
        Column(Modifier.weight(0.4f).widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val p = app.engine.player
            val shuffle = remember(changes) { p.shuffle }
            val repeat = remember(changes) { p.repeat }
            val playing = now?.second == true
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeskIcon(Icons.Rounded.Shuffle, if (shuffle) "Disattiva casuale" else "Attiva casuale", active = shuffle, enabled = now != null) { p.setShuffle(!shuffle) }
                DeskIcon(Icons.Rounded.SkipPrevious, "Precedente", iconSize = 24.dp, enabled = now != null) { app.engine.previous() }
                PlayCircle(40.dp, playing) { if (now != null) app.engine.togglePlay() }
                DeskIcon(Icons.Rounded.SkipNext, "Successivo", iconSize = 24.dp, enabled = now != null) { app.engine.next() }
                DeskIcon(if (repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (repeat) { 0 -> "Ripeti tutto"; 2 -> "Ripeti brano"; else -> "Non ripetere" }, active = repeat != 0, enabled = now != null) {
                    // off -> all -> one -> off
                    p.setRepeat(when (repeat) { 0 -> 2; 2 -> 1; else -> 0 })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(dragging?.let { (it * dur).toLong() } ?: pos), color = Desk.sub, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
                SeekBar(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.weight(1f).padding(horizontal = 8.dp),
                    onSeek = { app.engine.seekTo((it * dur).toLong()) }, onDrag = { dragging = it })
                Text(fmt(dur), color = Desk.sub, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
            }
        }
        // ---- the rest
        Row(Modifier.weight(0.3f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            DeskIcon(Icons.Rounded.SpeakerGroup, "Visualizza In riproduzione", active = desk.right == RightPanel.NowPlaying) { desk.toggleRight(RightPanel.NowPlaying) }
            DeskIcon(Icons.Rounded.Lyrics, "Testo", active = desk.overlay == CenterOverlay.Lyrics, enabled = now != null) {
                desk.overlay = if (desk.overlay == CenterOverlay.Lyrics) CenterOverlay.None else CenterOverlay.Lyrics
            }
            DeskIcon(Icons.Rounded.Mic, "Karaoke", active = desk.karaoke, enabled = now != null) { desk.karaoke = !desk.karaoke }
            DeskIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Coda", active = desk.right == RightPanel.Queue) { desk.toggleRight(RightPanel.Queue) }
            ContextMenuBox(items = {
                listOf(
                    MenuItem("Disattivato") { SleepTimer.set(scope, app, 0) },
                    MenuItem("5 minuti") { SleepTimer.set(scope, app, 5) },
                    MenuItem("15 minuti") { SleepTimer.set(scope, app, 15) },
                    MenuItem("30 minuti") { SleepTimer.set(scope, app, 30) },
                    MenuItem("45 minuti") { SleepTimer.set(scope, app, 45) },
                    MenuItem("1 ora") { SleepTimer.set(scope, app, 60) },
                    MenuItem("Fine del brano") { SleepTimer.atEndOfSong(scope, app) },
                )
            }) { open ->
                DeskIcon(Icons.Rounded.Bedtime, if (SleepTimer.endsAt > 0) "Timer: ${((SleepTimer.endsAt - System.currentTimeMillis()) / 60000) + 1} min" else "Timer di spegnimento",
                    active = SleepTimer.endsAt > 0 || SleepTimer.endOfSong) { open() }
            }
            VolumeControl()
            DeskIcon(Icons.Rounded.PictureInPictureAlt, "Apri mini player", active = desk.miniPlayer) { desk.miniPlayer = !desk.miniPlayer }
            // only the WINDOW goes full screen (you stay on your page); the big song view opens from the cover
            val fs = desk.isWindowFullscreen()
            DeskIcon(if (fs) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (fs) "Esci da schermo intero" else "Schermo intero") { desk.toggleWindowFullscreen() }
        }
    }
}

@Composable
private fun VolumeControl() {
    val app = LocalApp.current
    val p = app.engine.player
    val changes by p.changes.collectAsState()
    val vol = remember(changes) { p.volume }
    var before by remember { mutableFloatStateOf(1f) }
    DeskIcon(when { vol <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff; vol < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown; else -> Icons.AutoMirrored.Rounded.VolumeUp },
        if (vol <= 0f) "Riattiva audio" else "Disattiva audio") {
        if (vol > 0f) { before = vol; p.volume = 0f } else p.volume = before.coerceAtLeast(0.2f)
    }
    SeekBar(vol, Modifier.width(96.dp), onSeek = { p.volume = it }, onDrag = { it?.let { v -> p.volume = v } })
}
