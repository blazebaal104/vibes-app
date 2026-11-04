package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Piece(
    val x: Float, val vx: Float, val vy: Float, val color: Color,
    val w: Float, val h: Float, val spin: Float, val phase: Float, val delay: Float,
)

/** Confetti burst from the top-center, drawn with a Canvas and frame clock (no extra libraries). */
@Composable
fun Confetti(modifier: Modifier = Modifier, count: Int = 160, colors: List<Color> = listOf(
    Color(0xFFFF2E97), Color(0xFFFFD43B), Color(0xFF2EE66B), Color(0xFF35D0F5), Color(0xFFB84DFF), Color(0xFFFF7A59), Color.White
)) {
    val pieces = remember {
        List(count) {
            val a = Random.nextFloat() * 3.14f
            val speed = 0.55f + Random.nextFloat() * 0.75f
            Piece(
                x = 0.5f + (Random.nextFloat() - 0.5f) * 0.2f,
                vx = cos(a) * speed * 0.6f, vy = -sin(a) * speed * 1.4f,
                color = colors.random(), w = 6f + Random.nextFloat() * 8f, h = 10f + Random.nextFloat() * 12f,
                spin = (Random.nextFloat() - 0.5f) * 900f, phase = Random.nextFloat() * 6.28f,
                delay = Random.nextFloat() * 0.25f,
            )
        }
    }
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (time < 4_500_000_000L) { withFrameNanos { time = it - start } }
    }
    Canvas(modifier.fillMaxSize()) {
        val t = time / 1_000_000_000f
        pieces.forEach { p ->
            val tt = (t - p.delay).coerceAtLeast(0f)
            if (tt <= 0f) return@forEach
            val x = (p.x + p.vx * tt * 0.5f + sin(tt * 3 + p.phase) * 0.02f) * size.width
            val y = size.height * 0.25f + (p.vy * tt + 0.9f * tt * tt) * size.height * 0.45f
            if (y > size.height + 40) return@forEach
            val alpha = (1f - ((tt - 3f) / 1.5f).coerceIn(0f, 1f))
            rotate(p.spin * tt, Offset(x, y)) {
                drawRect(p.color.copy(alpha = alpha), Offset(x - p.w / 2, y - p.h / 2), Size(p.w, p.h * (0.4f + 0.6f * kotlin.math.abs(cos(tt * 6 + p.phase)))))
            }
        }
    }
}
