package dev.brahmkshatriya.echo.vibes.music

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Upload
import java.util.concurrent.ConcurrentHashMap

/**
 * Songs published on Vibes as normal playlist songs. They get ids "vup:<upload id>" and are played
 * by the same player: StreamableLoader asks this object for the audio URL instead of Deezer.
 * URLs are remembered on the phone; unknown ones are looked up in vibes_uploads.
 */
object VibesUploads {
    const val PREFIX = "vup:"
    private val urls = ConcurrentHashMap<String, String>()
    private var ctx: Context? = null
    private val prefs get() = ctx?.getSharedPreferences("vibes_uploads", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        prefs?.all?.forEach { (k, v) -> (v as? String)?.let { urls[k] = it } }
    }

    fun isUpload(trackId: String) = trackId.startsWith(PREFIX)

    fun songOf(u: Upload): SongRef {
        val id = PREFIX + u.id
        urls[id] = u.url
        prefs?.edit()?.putString(id, u.url)?.apply()
        return SongRef(ext = "deezer", id = id, title = u.title, artist = u.artist, cover = u.cover, album = u.album,
            duration = u.durationMs.takeIf { it > 0 })
    }

    /** Audio URL for a "vup:" track (network lookup if this phone never saw it). */
    suspend fun urlFor(trackId: String): String? {
        urls[trackId]?.let { return it }
        val u = runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.social.uploadById(trackId.removePrefix(PREFIX)) }.getOrNull() ?: return null
        urls[trackId] = u.url
        prefs?.edit()?.putString(trackId, u.url)?.apply()
        return u.url
    }
}
