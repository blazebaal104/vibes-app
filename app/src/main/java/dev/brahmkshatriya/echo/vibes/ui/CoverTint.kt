package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** The main colour of a cover, deep enough to sit behind white text (phone; the PC has its own). */
object CoverTint {
    private val cache = ConcurrentHashMap<String, Color>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    suspend fun of(url: String?): Color? {
        if (url.isNullOrBlank()) return null
        cache[url]?.let { return it }
        if (url in missing) return null
        val c = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = dev.brahmkshatriya.echo.vibes.VibesGraph.supa.http
                    .newCall(Request.Builder().url(sizedCover(url, 120)).build()).execute().use { it.body.bytes() }
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                val p = androidx.palette.graphics.Palette.from(bmp).generate()
                val rgb = p.vibrantSwatch?.rgb ?: p.darkVibrantSwatch?.rgb ?: p.getDominantColor(0).takeIf { it != 0 }
                    ?: return@runCatching null
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(rgb, hsv)
                hsv[1] = (hsv[1] * 1.1f).coerceIn(0.25f, 0.9f)
                hsv[2] = hsv[2].coerceIn(0.38f, 0.65f)
                Color(android.graphics.Color.HSVToColor(hsv))
            }.getOrNull()
        }
        if (c != null) cache[url] = c else missing += url
        return c
    }
}
