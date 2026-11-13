package dev.brahmkshatriya.echo.vibes.music

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.SongRef.Companion.url

/** Where the queue was started from (a Vibes playlist), so stats can rank "most played playlists". */
data class PlayContext(val id: String, val name: String, val cover: String?, val keys: Set<String>)

/**
 * Lives inside the player service (so it also counts background listening): measures how long
 * each song really plays and writes it to [ListenLog] when the song changes.
 */
class ListenTracker(private val player: Player, context: Context) : Player.Listener {
    init { VibesGraph.init(context) }

    private var item: MediaItem? = null
    private var acc = 0L
    private var startedAt = 0L
    private var playingSince: Long? = null
    /** Real length of the current song (from the player), for the "a third of the song" rule. */
    private var dur = 0L
    private fun noteDuration() { player.duration.takeIf { it > 0 }?.let { dur = it } }

    private fun now() = System.currentTimeMillis()

    private fun begin(mi: MediaItem?) {
        item = mi; acc = 0; startedAt = now(); dur = 0
        playingSince = if (player.isPlaying) now() else null
    }

    fun flush() {
        val mi = item ?: return
        val ms = acc + (playingSince?.let { now() - it } ?: 0L)
        val length = dur
        item = null; acc = 0; playingSince = null; dur = 0
        if (VibesGraph.prefs.privateSession) return
        runCatching {
            val track = mi.track
            val song = SongRef.from(mi.extensionId, track)
            val vctx = VibesGraph.playContext?.takeIf { song.key in it.keys }
            val ectx = mi.context
            val (name, id, cover) = when {
                vctx != null -> Triple(vctx.name, "vibes:" + vctx.id, vctx.cover)
                ectx is Playlist -> Triple(ectx.title, "pl:" + ectx.id, ectx.cover.url())
                ectx is Album -> Triple(ectx.title, "al:" + ectx.id, ectx.cover.url())
                else -> Triple(null, null, null)
            }
            val l = Listen(song, startedAt, ms, name, id, cover)
            // a play counts only after a third of the song: tapping a song (or spamming it while
            // offline) is not "listening" to it
            if (VibesGraph.listens.add(l, length)) {
                VibesGraph.library.countPlay(song)
                VibesGraph.onListened()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        flush(); begin(mediaItem)
        // automatic advance (song ended): time until the next one is actually audible
        transitionAt = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && player.playWhenReady) now() else null
    }

    private var transitionAt: Long? = null

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        // paused by the user or by karaoke HD: whatever comes later is not a gap between songs
        if (!playWhenReady) transitionAt = null
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) noteDuration()
        if (playbackState == Player.STATE_READY && player.playWhenReady) checkGap()
    }

    private fun checkGap() {
        val t = transitionAt ?: return
        transitionAt = null
        val gap = now() - t
        if (gap > 800) {
            val mi = player.currentMediaItem
            val what = runCatching { mi?.track?.let { "${it.title} [${mi.extensionId}:${it.id}]" } }.getOrNull() ?: "?"
            dev.brahmkshatriya.echo.vibes.diag.ErrorReporter.report("transition", "silenzio di ${gap / 100 * 100} ms prima di $what")
        }
    }

    /** A song that can't play: report why (with the track) so it can be fixed. */
    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        val mi = player.currentMediaItem
        val what = runCatching { mi?.track?.let { "${it.title} – ${it.artists.joinToString { a -> a.name }} [${mi.extensionId}:${it.id}]" } }.getOrNull() ?: "?"
        var root: Throwable = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        DeezerAuth.onPlaybackError(error)
        dev.brahmkshatriya.echo.vibes.diag.ErrorReporter.report(
            "playback", "${error.errorCodeName}: ${root.javaClass.simpleName}: ${root.message?.take(300)} · $what", error
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) { DeezerAuth.ok(); checkGap() }
        if (item == null) { if (isPlaying) begin(player.currentMediaItem); return }
        if (isPlaying) { noteDuration(); if (playingSince == null) playingSince = now() }
        else { playingSince?.let { acc += now() - it }; playingSince = null }
    }
}
