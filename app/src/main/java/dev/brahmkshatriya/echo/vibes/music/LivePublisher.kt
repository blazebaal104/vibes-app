package dev.brahmkshatriya.echo.vibes.music

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.LiveListen
import dev.brahmkshatriya.echo.vibes.backend.SongRef

/**
 * Publishes what's playing in real time (song, paused/playing, exact position, playlist and the
 * next songs) every time something changes, plus a heartbeat every 25 s while playing. Friends
 * use it for the live card on the profile and for "Ascolta insieme".
 */
class LivePublisher(private val player: Player) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val publish = Runnable { send() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (player.isPlaying) send()
            handler.postDelayed(this, 25_000)
        }
    }

    init { handler.postDelayed(heartbeat, 25_000) }

    private fun songOf(mi: MediaItem?): SongRef? = mi?.let { runCatching { SongRef.from(it.extensionId, it.track) }.getOrNull() }

    private fun schedule() { handler.removeCallbacks(publish); handler.postDelayed(publish, 400) }

    private fun send() {
        val mi = player.currentMediaItem
        val song = songOf(mi)
        if (song == null) { VibesGraph.social.publishLive(null); return }
        val vctx = VibesGraph.playContext?.takeIf { song.key in it.keys }
        val ectx = mi?.context
        val (ctx, ctxId) = when {
            vctx != null -> vctx.name to "vibes:${vctx.id}"
            ectx is Playlist -> ectx.title to "pl:${ectx.id}"
            ectx is Album -> ectx.title to "al:${ectx.id}"
            else -> null to null
        }
        val idx = player.currentMediaItemIndex
        val queue = (idx + 1 until minOf(player.mediaItemCount, idx + 11)).mapNotNull { songOf(player.getMediaItemAt(it)) }
        VibesGraph.social.publishLive(
            LiveListen(
                song = song.copy(data = null),
                playing = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING),
                position = player.currentPosition.coerceAtLeast(0),
                duration = player.duration.takeIf { it > 0 } ?: (song.duration ?: 0),
                at = System.currentTimeMillis(),
                ctx = ctx, ctxId = ctxId,
                queue = queue.map { it.copy(data = null) },
            )
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) = schedule()
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = schedule()
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = schedule()
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = schedule()

    fun release() {
        handler.removeCallbacksAndMessages(null)
        VibesGraph.social.publishLive(null)
    }
}
