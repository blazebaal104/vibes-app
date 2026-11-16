package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.ui.Cover
import dev.brahmkshatriya.echo.vibes.ui.KaraokeLine
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.currentSong
import dev.brahmkshatriya.echo.vibes.ui.fmt
import dev.brahmkshatriya.echo.vibes.ui.openArtistOf
import dev.brahmkshatriya.echo.vibes.ui.sizedCover
import kotlinx.coroutines.launch

/** Synced lyrics lines: current one white, sung ones dim, next ones dark; click a line to jump there. */
@Composable
fun LyricsLines(song: SongRef, pos: Long, big: Boolean, modifier: Modifier = Modifier, empty: @Composable () -> Unit) {
    val app = LocalApp.current
    var lines by remember(song.key) { mutableStateOf(LyricsCache.get(song.key)) }
    LaunchedEffect(song.key) { if (lines == null) lines = LyricsCache.load(app, song) }
    val list: List<KaraokeLine> = lines.orEmpty()
    if (lines == null) { Box(modifier, contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = Color.White) }; return }
    if (list.isEmpty()) { Box(modifier, contentAlignment = Alignment.Center) { empty() }; return }
    val current = list.indexOfLast { it.start <= pos + 150 }
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(if (big) 18.dp else 12.dp)) {
        itemsIndexed(list) { i, line ->
            var hover by remember { mutableStateOf(false) }
            val color by animateColorAsState(
                when { i == current -> Color.White; hover -> Color.White.copy(alpha = 0.9f); i < current -> Color.White.copy(alpha = 0.55f); else -> Color.Black.copy(alpha = 0.6f) },
                tween(250), label = "ly")
            Text(line.text.ifBlank { "♪" }, color = color, fontSize = if (big) 34.sp else 26.sp, lineHeight = if (big) 42.sp else 32.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth().hovered { hover = it }.pointerHoverIcon(HandCursor).clickable { app.engine.seekTo(line.start) })
        }
    }
}

/** The centre's lyrics view (button "Testo" in the player bar): big synced lyrics on the cover's colour. */
@Composable
fun LyricsView() {
    val now = currentSong()
    val (pos, _) = rememberPlayback()
    val desk = LocalDesk.current
    if (now == null) { desk.overlay = CenterOverlay.None; return }
    val color = rememberCoverColor(now.first.cover) ?: Color(0xFF503070)
    val bg by animateColorAsState(color, tween(600), label = "lyb")
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(bg, bg.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.55f))))) {
        // wide: lyrics on the left, the cover breathing on the beat on the right (no more half-empty view)
        val wide = maxWidth > 1000.dp
        Row(Modifier.fillMaxSize()) {
            LyricsLines(now.first, pos, big = true, Modifier.weight(if (wide) 1.3f else 1f).fillMaxHeight().padding(horizontal = 48.dp).widthIn(max = 900.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nessun testo per questo brano", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Il testo arriva dal servizio di Deezer quando c'è.", color = Color.White.copy(alpha = 0.7f))
                }
            }
            if (wide) dev.brahmkshatriya.echo.vibes.ui.BeatStage(now.first, pos, now.second, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * Full-screen view (Spotify's "full screen"): blurred cover behind, big cover + title, controls,
 * lyrics on the side when there are some. Esc or the arrow closes it.
 */
@Composable
fun FullScreenPlayer() {
    val app = LocalApp.current
    val desk = LocalDesk.current
    val scope = rememberCoroutineScope()
    val now = currentSong() ?: run { desk.fullPlayer = false; return }
    val (song, playing) = now
    val (pos, dur) = rememberPlayback()
    var lyrics by remember { mutableStateOf(true) }
    val color = rememberCoverColor(song.cover) ?: Color(0xFF301848)
    fun close() { desk.fullPlayer = false; if (desk.isWindowFullscreen()) desk.toggleWindowFullscreen() }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}) {
        if (song.cover != null) AsyncImage(sizedCover(song.cover, 250), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(80.dp).scale(1.2f), alpha = 0.7f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f)))))
        Column(Modifier.fillMaxSize().padding(40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Text("V", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                }
                Text("In riproduzione" + (dev.brahmkshatriya.echo.vibes.VibesGraph.playContext?.let { " da ${it.name}" } ?: ""), color = Color.White,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp).weight(1f))
                DeskIcon(Icons.Rounded.Lyrics, if (lyrics) "Nascondi testo" else "Mostra testo", active = lyrics, size = 40.dp, iconSize = 24.dp) { lyrics = !lyrics }
                DeskIcon(Icons.Rounded.CloseFullscreen, "Esci da schermo intero", size = 40.dp, iconSize = 24.dp) { close() }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val side = lyrics && maxWidth > 900.dp
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                        AnimatedContent(song.key, transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(200))) }, label = "fs") { _ ->
                            Cover(song.cover, Modifier.size(if (side) 300.dp else 360.dp).shadow(30.dp, RoundedCornerShape(10.dp)), RoundedCornerShape(10.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(song.title, color = Color.White, fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LinkText(song.artist, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.headlineSmall) {
                            scope.launch { close(); openArtistOf(app, song) }
                        }
                    }
                    if (side) LyricsLines(song, pos, big = false, Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp)) {
                        Text("Nessun testo per questo brano", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(pos), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                SeekBar(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.weight(1f).padding(horizontal = 12.dp), onSeek = { app.engine.seekTo((it * dur).toLong()) })
                Text(fmt(dur), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                DeskIcon(Icons.Rounded.SkipPrevious, "Precedente", size = 56.dp, iconSize = 36.dp) { app.engine.previous() }
                Spacer(Modifier.width(18.dp))
                Box(Modifier.size(68.dp).clip(CircleShape).background(Color.White).pointerHoverIcon(HandCursor).clickable { app.engine.togglePlay() },
                    contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausa" else "Play", tint = Color.Black, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.width(18.dp))
                DeskIcon(Icons.Rounded.SkipNext, "Successivo", size = 56.dp, iconSize = 36.dp) { app.engine.next() }
            }
        }
    }
}
