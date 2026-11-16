package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The scrolling content of the app is the haze "source": glass bars blur what passes under them. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Liquid glass: frosted backdrop blur (bars over content, Android 12+), a soft tint, a specular
 * gradient, a bright rim and an optional light reflection gliding across. Falls back to a plain card
 * when turned off in Settings → Aspetto.
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = Color.White,
    blur: Boolean = false,
    fallback: Color? = null,
): Modifier {
    val s = LocalSettings.current
    if (!s.liquidGlass) return this.clip(shape).background(fallback ?: LocalVibes.current.card)
    val haze = LocalHaze.current
    val bg = MaterialTheme.colorScheme.background
    // the reflection loop exists only when it is shown AND the window is in the foreground:
    // before, every glass element ran its own infinite animation even with reflections turned off
    val shineOn = s.glassShine && !s.reduceMotion && Perf.animate
    val shine = liveFloat(-0.6f, 1.6f, infiniteRepeatable(tween(4200, easing = LinearEasing)), "shine", enabled = shineOn)
    var m = this.clip(shape)
    // PC: no live blur (see deskGlass): the translucent fallback below looks the same over the soft background
    m = if (blur && haze != null && !PlatformUI.isDesktop) m.hazeEffect(haze) {
        blurRadius = (22 * s.glassBlur).dp
        backgroundColor = bg
        tints = listOf(HazeTint(bg.copy(alpha = 0.35f)), HazeTint(tint.copy(alpha = 0.08f)))
        noiseFactor = 0.07f
    } else m.background(bg.copy(alpha = if (blur) 0.72f else 0.25f)).background(tint.copy(alpha = 0.09f))
    return m
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.07f))))
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.28f))), shape)
        .drawWithContent {
            drawContent()
            if (shineOn) {
                val x = size.width * shine
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                        start = Offset(x - size.height, 0f), end = Offset(x + size.height * 0.4f, size.height)
                    )
                )
            }
        }
}
