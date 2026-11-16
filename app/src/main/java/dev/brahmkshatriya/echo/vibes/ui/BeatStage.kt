package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The right half of karaoke and of the big lyrics view: the cover "breathes" on the beat of the song
 * (Deezer's BPM, kept in step with the playback position), with a glow and rings in its colour.
 *
 * Cheap on purpose: one number per frame, read only while drawing (no recomposition), and only while
 * the song plays and the window is in the foreground. No BPM known: a slow, calm breath.
 */
@Composable
fun BeatStage(song: SongRef, positionMs: Long, playing: Boolean, modifier: Modifier = Modifier, caption: String? = null) {
    var bpm by remember(song.key) { mutableStateOf(0f) }
    LaunchedEffect(song.key) {
        bpm = runCatching {
            val d = VibesGraph.deezer
            val id = song.id.toLongOrNull() ?: d.deezerId(song)
            id?.let { d.bpm(it) } ?: 0f
        }.getOrDefault(0f).takeIf { it in 50f..220f } ?: 0f
    }
    var tint by remember(song.key) { mutableStateOf<Color?>(null) }
    LaunchedEffect(song.key) { tint = CoverTint.of(song.cover) }
    val glow = tint ?: Color(0xFFB04CFF)

    // position extrapolated between the player's updates, so the pulse lands on the beat
    val anchor = remember { longArrayOf(0L, 0L) }
    anchor[0] = positionMs; anchor[1] = System.currentTimeMillis()
    val beat = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, bpm, Perf.animate) {
        if (!playing || !Perf.animate) { beat.floatValue = 0f; return@LaunchedEffect }
        while (true) {
            withFrameMillis { }
            val pos = anchor[0] + (System.currentTimeMillis() - anchor[1])
            beat.floatValue = if (bpm > 0f) {
                val phase = (pos * bpm / 60_000.0) % 1.0
                exp(-phase * 5.5).toFloat()
            } else ((sin(pos / 2400.0 * 2 * PI) + 1) / 2 * 0.5).toFloat()
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // leaves room under the cover for title, artist and caption
        val side = min(maxWidth * 0.62f, maxHeight * 0.5f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(side * 1.35f), contentAlignment = Alignment.Center) {
                // glow + two rings that open on each beat
                Canvas(Modifier.fillMaxSize()) {
                    val b = beat.floatValue
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.minDimension / 2
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.30f + 0.35f * b), Color.Transparent), c, r), r, c)
                    drawCircle(glow.copy(alpha = 0.35f * b), radius = r * (0.78f + 0.18f * (1 - b)), center = c, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.12f * b), radius = r * (0.86f + 0.12f * (1 - b)), center = c, style = Stroke(1.5.dp.toPx()))
                }
                Cover(song.cover, Modifier.size(side).graphicsLayer {
                    val s = 1f + 0.045f * beat.floatValue
                    scaleX = s; scaleY = s
                    shadowElevation = 30f + 40f * beat.floatValue
                    shape = RoundedCornerShape(18.dp); clip = true
                }, RoundedCornerShape(18.dp))
            }
            Text(song.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
            Text(song.artist, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            caption?.let {
                Text(it, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}
