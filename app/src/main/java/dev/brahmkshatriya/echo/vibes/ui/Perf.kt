package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * When the app is worth drawing.
 *
 * On the PC the window reports when it is minimized / hidden in the tray ([visible]) and when the
 * user is in another app ([focused]). Decorative loops (glass reflections, breathing covers, moving
 * backgrounds, equalizer bars) run only while [animate]; position tickers sleep while not [visible].
 * So a minimized Vibes playing music behind a game costs (almost) no GPU. The phone stops drawing
 * by itself in the background, so there both stay true.
 */
object Perf {
    var visible by mutableStateOf(true)
    var focused by mutableStateOf(true)
    val animate: Boolean get() = visible && focused

    /** Suspends while the window is minimized/hidden. */
    suspend fun awaitVisible() { if (!visible) snapshotFlow { visible }.first { it } }

    /** How often a ticker should refresh: normal when looked at, slow when in the background. */
    fun tick(normalMs: Long): Long = if (focused) normalMs else maxOf(normalMs, 1000L)
}

/**
 * A looping value that only runs while the window is in the foreground: otherwise it stays at
 * [from] and the infinite transition leaves the composition (no frames are requested at all).
 */
@Composable
fun liveFloat(from: Float, to: Float, spec: InfiniteRepeatableSpec<Float>, label: String = "live", enabled: Boolean = true): Float {
    if (!enabled || !Perf.animate) return from
    val v by rememberInfiniteTransition(label).animateFloat(from, to, spec, label)
    return v
}
