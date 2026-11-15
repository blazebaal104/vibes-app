package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.ui.sizedCover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** The main colour of a cover (vibrant, darkened enough for white text on top), cached per URL. */
object CoverColors {
    private val cache = ConcurrentHashMap<String, Color>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun cached(url: String?): Color? = url?.let { cache[it] }

    suspend fun of(url: String?): Color? {
        if (url.isNullOrBlank()) return null
        cache[url]?.let { return it }
        if (url in missing) return null
        val c = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (url.startsWith("http")) VibesGraph.supa.http.newCall(Request.Builder().url(sizedCover(url, 120)).build()).execute().use { it.body.bytes() }
                else java.io.File(url.removePrefix("file://")).readBytes()
                val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return@runCatching null
                dominant(img)
            }.getOrNull()
        }
        if (c != null) cache[url] = c else missing += url
        return c
    }

    private fun dominant(img: java.awt.image.BufferedImage): Color {
        // 24x24 sample; hue buckets weighted by saturation x brightness: the colour you "see" in the cover
        val n = 24
        val buckets = DoubleArray(36); val sums = Array(36) { DoubleArray(3) }
        var r0 = 0.0; var g0 = 0.0; var b0 = 0.0
        for (y in 0 until n) for (x in 0 until n) {
            val rgb = img.getRGB(x * img.width / n, y * img.height / n)
            val r = (rgb shr 16 and 255) / 255f; val g = (rgb shr 8 and 255) / 255f; val b = (rgb and 255) / 255f
            r0 += r; g0 += g; b0 += b
            val hsb = java.awt.Color.RGBtoHSB((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt(), null)
            val w = (hsb[1] * hsb[1]) * (0.25 + hsb[2]) * (if (hsb[2] < 0.12) 0.1 else 1.0)
            val k = ((hsb[0] * 36).toInt()).coerceIn(0, 35)
            buckets[k] += w; sums[k][0] += r * w; sums[k][1] += g * w; sums[k][2] += b * w
        }
        val best = buckets.indices.maxBy { buckets[it] }
        val (r, g, b) = if (buckets[best] > 2.0) Triple(sums[best][0] / buckets[best], sums[best][1] / buckets[best], sums[best][2] / buckets[best])
        else Triple(r0 / (n * n), g0 / (n * n), b0 / (n * n))
        // keep the hue, make it deep enough to sit behind white text
        val hsb = java.awt.Color.RGBtoHSB((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt(), null)
        val c = java.awt.Color.getHSBColor(hsb[0], (hsb[1] * 1.1f).coerceIn(0.25f, 0.85f), hsb[2].coerceIn(0.38f, 0.62f))
        return Color(c.red, c.green, c.blue)
    }
}

@Composable
fun rememberCoverColor(url: String?): Color? {
    var c by remember(url) { mutableStateOf(CoverColors.cached(url)) }
    LaunchedEffect(url) { if (c == null) c = CoverColors.of(url) }
    return c
}

/**
 * Pointing at something with a cover tints the centre background with the cover's colour (like
 * Spotify's home). Leaving it fades back.
 */
@Composable
fun Modifier.hoverTint(url: String?): Modifier {
    val desk = LocalDesk.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    return this
        .onPointerEvent(PointerEventType.Enter) {
            job?.cancel()
            job = scope.launch { delay(60); CoverColors.of(url)?.let { desk.hoverColor = it } }
        }
        .onPointerEvent(PointerEventType.Exit) {
            job?.cancel()
            job = scope.launch { delay(140); desk.hoverColor = null }
        }
}

/** True while the mouse is over the element. */
@Composable
fun Modifier.hovered(onChange: (Boolean) -> Unit): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { onChange(true) }
    .onPointerEvent(PointerEventType.Exit) { onChange(false) }
