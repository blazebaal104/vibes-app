package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.MaterialTheme
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef.Companion.url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Sfondo reattivo" (Settings > Aspetto, off by default).
 *
 * One background behind every screen that reacts to what you do:
 *  - opening an album/playlist/artist: a wave of the cover's colour opens out of the cover you tapped,
 *    and that page keeps the colour until you go back;
 *  - otherwise it follows the song playing;
 *  - switching tab the light drifts sideways (towards the tab), opening a page it zooms in a little.
 */
object Ambient {
    /** Colour of the page opened at "tab:depth". */
    val pages = mutableStateMapOf<String, Color>()
    var burst by mutableStateOf<Burst?>(null)

    data class Burst(val color: Color, val center: Offset?, val id: Long = System.nanoTime())

    val enabled: Boolean get() = VibesGraph.prefs.value.reactiveBackground

    fun key(nav: Navigator, depth: Int) = "${tabs.indexOf(nav.tab)}:$depth"

    fun open(key: String, color: Color, center: Offset?) {
        pages[key] = color
        burst = Burst(color, center)
    }
}

class AmbientTap internal constructor(private val scope: CoroutineScope, private val nav: Navigator) {
    internal var center: Offset? = null
    internal var url: String? = null

    /** Call when the card is tapped, BEFORE navigating (the new page is depth + 1). */
    fun fire() {
        if (!Ambient.enabled) return
        val key = Ambient.key(nav, nav.depth + 1)
        val c = center
        val u = url ?: return
        scope.launch { CoverTint.of(u)?.let { Ambient.open(key, it, c) } }
    }
}

@Composable
fun rememberAmbientTap(url: String?): AmbientTap {
    val scope = rememberCoroutineScope()
    val nav = LocalApp.current.nav
    val t = remember(nav) { AmbientTap(scope, nav) }
    t.url = url
    return t
}

fun Modifier.ambientBounds(t: AmbientTap): Modifier =
    onGloballyPositioned { t.center = it.boundsInRoot().center }

@Composable
fun ReactiveBackground(modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val nav = app.nav
    val v = LocalVibes.current
    val prefs = LocalSettings.current
    val bg = if (prefs.background == "amoled") Color.Black else MaterialTheme.colorScheme.background

    val tabIdx = tabs.indexOf(nav.tab).coerceAtLeast(0)
    val depth = nav.depth
    // pages you have left are forgotten, so re-opening them starts a fresh wave
    LaunchedEffect(tabIdx, depth) {
        Ambient.pages.keys.filter { k -> k.startsWith("$tabIdx:") && (k.substringAfter(':').toIntOrNull() ?: 0) > depth }
            .forEach { Ambient.pages.remove(it) }
    }

    val cur by app.engine.player.playerState.current.collectAsState()
    val npUrl = cur?.track?.cover.url()
    var npColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(npUrl) { npColor = CoverTint.of(npUrl) }

    val page = (depth downTo 1).firstNotNullOfOrNull { Ambient.pages["$tabIdx:$it"] }
    val target = page ?: npColor ?: v.bgTop
    val base by animateColorAsState(target, tween(1100), label = "ambient")
    val second by animateColorAsState(lerp(target, v.accent, 0.45f), tween(1400), label = "ambient2")

    val still = prefs.reduceMotion
    val drift by animateFloatAsState(tabIdx.toFloat(), spring(dampingRatio = 0.9f, stiffness = 40f), label = "drift")
    val zoom by animateFloatAsState(1f + 0.12f * min(depth, 3), tween(700, easing = FastOutSlowInEasing), label = "zoom")
    val phase = if (still) 0f else {
        val t = rememberInfiniteTransition(label = "amb")
        t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart), label = "ph").value
    }

    // the colour wave opening out of the tapped cover
    val wave = remember { Animatable(1f) }
    var waveColor by remember { mutableStateOf(Color.Transparent) }
    var waveCenter by remember { mutableStateOf<Offset?>(null) }
    val b = Ambient.burst
    LaunchedEffect(b?.id) {
        b ?: return@LaunchedEffect
        waveColor = b.color; waveCenter = b.center
        wave.snapTo(0f)
        wave.animateTo(1f, tween(if (still) 1 else 750, easing = FastOutSlowInEasing))
    }

    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(bg)
        fun blob(cx: Float, cy: Float, r: Float, c: Color, a: Float) {
            val center = Offset(cx, cy)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), center = center, radius = r), radius = r, center = center)
        }
        // positions move on a slow loop and slide with the tab you are on
        val d = drift * 0.9f
        val r = w * 0.75f * zoom
        blob(w * (0.5f + 0.42f * sin(d + phase * 0.6f)), h * (0.16f + 0.06f * cos(phase)), r, base, 0.85f)
        blob(w * (0.5f + 0.45f * sin(d * 1.3f + 2.4f + phase * 0.4f)), h * (0.55f + 0.1f * sin(phase * 0.7f)), r * 0.85f, second, 0.55f)
        blob(w * (0.5f + 0.4f * cos(d * 0.7f + 1f - phase * 0.5f)), h * (0.92f), r * 0.7f, base, 0.35f)

        val p = wave.value
        if (p < 1f) {
            val c = waveCenter ?: Offset(w / 2, h / 3)
            val far = max(hypot(c.x, c.y), max(hypot(w - c.x, c.y), max(hypot(c.x, h - c.y), hypot(w - c.x, h - c.y))))
            val rad = far * p
            if (rad > 1f) drawCircle(
                Brush.radialGradient(listOf(waveColor.copy(alpha = 0.55f * (1 - p)), waveColor.copy(alpha = 0.85f * (1 - p)), Color.Transparent),
                    center = c, radius = rad),
                radius = rad, center = c,
            )
        }
        // keep text readable lower down
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.55f to bg.copy(alpha = 0.35f), 1f to bg.copy(alpha = 0.75f)))
    }
}
