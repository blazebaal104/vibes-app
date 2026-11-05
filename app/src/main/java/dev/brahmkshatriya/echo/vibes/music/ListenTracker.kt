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

    private fun now() = System.currentTimeMillis()

    private fun begin(mi: MediaItem?) {
        item = mi; acc = 0; startedAt = now()
        playingSince = if (player.isPlaying) now() else null
    }

    fun flush() {
        val mi = item ?: return
        val ms = acc + (playingSince?.let { now() - it } ?: 0L)
        item = null; acc = 0; playingSince = null
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
            VibesGraph.listens.add(Listen(song, startedAt, ms, name, id, cover))
            VibesGraph.onListened()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        flush(); begin(mediaItem)
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
        if (isPlaying) DeezerAuth.ok()
        if (item == null) { if (isPlaying) begin(player.currentMediaItem); return }
        if (isPlaying) { if (playingSince == null) playingSince = now() }
        else { playingSince?.let { acc += now() - it }; playingSince = null }
    }
}
