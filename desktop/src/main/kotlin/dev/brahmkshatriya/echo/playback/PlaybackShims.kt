package dev.brahmkshatriya.echo.playback

import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track

/** What the desktop player keeps with each queue entry. */
data class QueueTag(val extensionId: String, val track: Track, val context: EchoMediaItem?)

object MediaItemUtils {
    val MediaItem.tag: QueueTag get() = desktopTag as QueueTag
    val MediaItem.extensionId: String get() = (desktopTag as? QueueTag)?.extensionId ?: ""
    val MediaItem.track: Track get() = (desktopTag as? QueueTag)?.track ?: error("no track")
    val MediaItem.context: EchoMediaItem? get() = (desktopTag as? QueueTag)?.context

    /** "Radio from this song": when the queue runs out, similar songs are added. */
    fun trackRadioPlaceholder(track: Track): EchoMediaItem =
        Radio(id = "vibes-radio:" + track.id, title = "Radio · " + track.title, cover = track.cover, extras = mapOf("vibes_radio_seed" to track.id))

    fun EchoMediaItem.isRadioPlaceholder() = this is Radio && id.startsWith("vibes-radio:")
}

class PlayerService {
    companion object {
        const val TRUE_CROSSFADE_SECONDS = "true_crossfade_seconds"
        const val VIBES_DEFAULT_FADE = 3
        const val SKIP_SILENCE = "skip_silence"
    }
}
