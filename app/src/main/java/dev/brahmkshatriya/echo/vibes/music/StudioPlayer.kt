package dev.brahmkshatriya.echo.vibes.music

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays music published by Vibes users and samples (plain files on Supabase Storage),
 * separately from the Deezer engine.
 */
class StudioPlayer(context: Context) {
    data class Item(val id: String, val title: String, val artist: String, val cover: String?, val url: String, val sample: Boolean = false)
    data class State(val item: Item, val playing: Boolean, val index: Int, val size: Int)

    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private var items: List<Item> = emptyList()
    private val _state = MutableStateFlow<State?>(null)
    val state = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) { publish() }
        })
    }

    private fun publish() {
        val i = player.currentMediaItemIndex
        val it = items.getOrNull(i) ?: run { _state.value = null; return }
        _state.value = State(it, player.isPlaying, i, items.size)
    }

    fun play(list: List<Item>, index: Int = 0, loop: Boolean = false) {
        items = list
        player.setMediaItems(list.map {
            MediaItem.Builder().setUri(it.url).setMediaId(it.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()).build()
        }, index, 0)
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.prepare(); player.play()
        publish()
    }

    fun toggle() { if (player.isPlaying) player.pause() else player.play() }
    fun next() { if (player.hasNextMediaItem()) player.seekToNextMediaItem() }
    fun previous() { player.seekToPreviousMediaItem() }
    fun position() = player.currentPosition
    fun duration() = player.duration.coerceAtLeast(0)
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun stop() { player.stop(); player.clearMediaItems(); items = emptyList(); _state.value = null }
    val isActive get() = _state.value != null
}
