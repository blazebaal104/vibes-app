package dev.brahmkshatriya.echo.vibes.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.playback.renderer.Karaoke
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One line of the karaoke view: text shown on screen and its time window. */
data class KaraokeLine(val text: String, val start: Long, val end: Long)

/** Synced lyrics for the song, from the Deezer extension (null = none, empty list = only unsynced). */
suspend fun loadKaraokeLines(app: AppState, song: SongRef): List<KaraokeLine>? = withContext(Dispatchers.IO) {
    runCatching {
        withTimeout(15_000) {
            val ext = app.engine.extension()
            val track = song.toTrack()
            val found = ext.getAs<LyricsClient, List<Lyrics>> { searchTrackLyrics(ext.id, track).loadAll() }.getOrThrow()
            val first = found.firstOrNull() ?: return@withTimeout null
            val full = if (first.lyrics == null) ext.getAs<LyricsClient, Lyrics> { loadLyrics(first) }.getOrThrow() else first
            when (val l = full.lyrics) {
                is Lyrics.Timed -> l.list.filter { it.text.isNotBlank() }.map { KaraokeLine(it.text.trim(), it.startTime, it.endTime) }
                is Lyrics.WordByWord -> l.list.mapNotNull { words ->
                    if (words.isEmpty()) null
                    else KaraokeLine(words.joinToString("") { it.text }.trim(), words.first().startTime, words.last().endTime)
                }.filter { it.text.isNotBlank() }
                else -> emptyList()
            }
        }
    }.getOrNull()
}

/**
 * Karaoke mode: the lead vocal is removed in real time (KaraokeProcessor) and the synced lyrics
 * scroll with the current line lit up. Leaving the screen turns the voice back on.
 */
@Composable
fun KaraokeScreen(song: SongRef, playing: Boolean, onClose: () -> Unit) {
    val app = LocalApp.current
    val (pos, _) = rememberProgress()
    var lines by remember(song.key) { mutableStateOf<List<KaraokeLine>?>(null) }
    var loading by remember(song.key) { mutableStateOf(true) }
    var voice by remember { mutableFloatStateOf(1f - Karaoke.strength) }
    DisposableEffect(Unit) {
        Karaoke.enabled = true
        onDispose { Karaoke.enabled = false }
    }
    BackHandler { onClose() }
    LaunchedEffect(song.key) {
        loading = true
        lines = loadKaraokeLines(app, song)
        loading = false
    }
    val list = lines.orEmpty()
    // a line stays "current" until the next one starts (gaps keep the previous line lit)
    val current = list.indexOfLast { it.start <= pos + 150 }
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0614))) {
        if (song.cover != null && Build.VERSION.SDK_INT >= 31) AsyncImage(
            song.cover, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(80.dp).scale(1.4f), alpha = 0.45f
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0xCC3A1466), Color(0x99000000), Color(0xEE000000))
        )))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Esci dal karaoke", tint = Color.White) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Mic, null, tint = Color(0xFFFF4FD8), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("KARAOKE", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFF4FD8), fontWeight = FontWeight.Bold)
                    }
                    Text(song.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
                IconButton(onClick = { app.engine.togglePlay() }) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pausa", tint = Color.White)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    list.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎤", fontSize = 48.sp)
                        Text(
                            if (lines == null) "Testo non disponibile per questo brano"
                            else "Questo brano non ha il testo sincronizzato",
                            color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text("La base senza voce suona lo stesso: canta a memoria!", color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    }
                    else -> LazyColumn(
                        state = listState, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 60.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        itemsIndexed(list) { i, line ->
                            val isNow = i == current
                            val isPast = i < current
                            val color by animateColorAsState(
                                when {
                                    isNow -> Color.White
                                    isPast -> Color.White.copy(alpha = 0.35f)
                                    else -> Color.White.copy(alpha = 0.55f)
                                }, label = "kc$i"
                            )
                            val scale by animateFloatAsState(if (isNow) 1.06f else 1f, label = "ks$i")
                            Column(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                                .bouncy({ app.engine.seekTo(line.start) })) {
                                Text(line.text, color = color, fontSize = if (isNow) 30.sp else 26.sp, lineHeight = 34.sp,
                                    fontWeight = if (isNow) FontWeight.ExtraBold else FontWeight.Bold)
                                if (isNow && line.end > line.start) {
                                    // how far through the current line we are
                                    val p = ((pos - line.start).toFloat() / (line.end - line.start)).coerceIn(0f, 1f)
                                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                                        Box(Modifier.fillMaxWidth(p).height(4.dp).clip(CircleShape)
                                            .background(Brush.horizontalGradient(listOf(Color(0xFFFF4FD8), Color(0xFF8E7BFF)))))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // voice amount: 0 = no voice (full karaoke), 1 = original song
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voce originale", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(if (voice < 0.05f) "tolta" else "${(voice * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelLarge)
                }
                Slider(voice, { voice = it; Karaoke.strength = 1f - it }, valueRange = 0f..1f)
            }
        }
    }
}
