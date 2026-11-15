package dev.brahmkshatriya.echo.ui.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isRadioPlaceholder
import dev.brahmkshatriya.echo.playback.MediaItemUtils.tag
import dev.brahmkshatriya.echo.playback.PlayerService
import dev.brahmkshatriya.echo.playback.QueueTag
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.vibes.desktop.audio.ByteSource
import dev.brahmkshatriya.echo.vibes.desktop.audio.FileSource
import dev.brahmkshatriya.echo.vibes.desktop.audio.HeadCachedSource
import dev.brahmkshatriya.echo.vibes.desktop.audio.HttpSource
import dev.brahmkshatriya.echo.vibes.desktop.audio.ProviderSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout

/** Same shape as the phone's PlayerState.current, which the Vibes UI reads. */
class PlayerState {
    data class Current(val index: Int, val mediaItem: MediaItem, val isLoaded: Boolean, val isPlaying: Boolean, val isPlaceholder: Boolean = false) {
        val context get() = (mediaItem.desktopTag as? QueueTag)?.context
        val track: Track get() = mediaItem.tag.track
    }
    val current = MutableStateFlow<Current?>(null)
}

/**
 * Desktop counterpart of the phone's PlayerViewModel + PlayerService: one ExoPlayer (desktop audio
 * engine) with the karaoke filter in its chain, the listening tracker and "live" publisher attached,
 * crossfade from Settings, Deezer streams resolved through the extension.
 */
class PlayerViewModel(private val context: Context, private val loader: ExtensionLoader) {
    val karaoke = dev.brahmkshatriya.echo.playback.renderer.KaraokeProcessor()
    private val exo: ExoPlayer = ExoPlayer.Builder(context, object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean) =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(karaoke)).build()
    }).build()

    val browser: StateFlow<Player?> = MutableStateFlow(exo)
    val playerState = PlayerState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { maybeExtendRadio() }
            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
        val tracker = dev.brahmkshatriya.echo.vibes.music.ListenTracker(exo, context)
        exo.addListener(tracker)
        exo.addListener(dev.brahmkshatriya.echo.vibes.music.LivePublisher(exo))
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { tracker.flush() } })
        applySettings()
        context.getSettings().registerOnSharedPreferenceChangeListener { _, _ -> applySettings() }
        context.getSharedPreferences(dev.brahmkshatriya.echo.playback.listener.EffectsListener.GLOBAL_FX, 0)
            .registerOnSharedPreferenceChangeListener { _, _ -> applySettings() }
    }

    private fun applySettings() {
        val fade = context.getSettings().getInt(PlayerService.TRUE_CROSSFADE_SECONDS, PlayerService.VIBES_DEFAULT_FADE)
        exo.crossfadeMs = fade.coerceIn(0, 12) * 1000L
        val fx = context.getSharedPreferences(dev.brahmkshatriya.echo.playback.listener.EffectsListener.GLOBAL_FX, 0)
        val range = dev.brahmkshatriya.echo.playback.listener.EffectsListener.speedRange
        val speed = range.getOrNull(fx.getInt(dev.brahmkshatriya.echo.playback.listener.EffectsListener.PLAYBACK_SPEED, range.indexOf(1f))) ?: 1f
        if (speed != exo.playbackParameters.speed) exo.setPlaybackSpeed(speed)
    }

    /** Bumped on every player change: the queue panel and the taskbar buttons follow it. */
    val changes = MutableStateFlow(0L)

    private fun publish() {
        changes.value = changes.value + 1
        val i = exo.currentMediaItemIndex
        val item = exo.currentMediaItem
        playerState.current.value = item?.takeIf { it.desktopTag is QueueTag }?.let {
            PlayerState.Current(i, it, exo.playbackState == Player.STATE_READY, exo.isPlaying || (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING))
        }
    }

    // ------------------------------------------------------------------ resolving a track's audio

    private fun itemFor(extId: String, t: Track, ctx: EchoMediaItem?): MediaItem = MediaItem.Builder()
        .setMediaId("$extId:${t.id}")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artists.joinToString { it.name }).build())
        .setDesktopTag(QueueTag(extId, t, ctx))
        .setDesktopSource { resolve(extId, t) }
        .build()

    private suspend fun resolve(extId: String, track: Track): ByteSource {
        // songs published on Vibes play from their own URL
        if (dev.brahmkshatriya.echo.vibes.music.VibesUploads.isUpload(track.id)) {
            val url = dev.brahmkshatriya.echo.vibes.music.VibesUploads.urlFor(track.id) ?: error("Brano pubblicato non più disponibile")
            return HttpSource(url)
        }
        // downloaded: plays offline
        dev.brahmkshatriya.echo.vibes.music.VibesOffline.fileFor(context, extId, track.id)?.let { return FileSource(it) }
        val ext = loader.music.value.first { it.id == extId }
        val full = withTimeout(30_000) { ext.getAs<TrackClient, Track> { loadTrack(track, false) }.getOrThrow() }
        // 320 kbps MP3 first (seeks instantly), then 128, FLAC last
        val order = listOf(6, 3, 9)
        val servers = full.servers.sortedBy { s -> order.indexOf(s.quality).let { if (it < 0) 99 else it } }
        var last: Throwable? = null
        for (s in servers) {
            val media = runCatching {
                withTimeout(30_000) { ext.getAs<TrackClient, Streamable.Media> { loadStreamableMedia(s, false) }.getOrThrow() }
            }.onFailure { last = it }.getOrNull() as? Streamable.Media.Server ?: continue
            for (src in media.sources) {
                when (src) {
                    is Streamable.Source.Raw -> {
                        val p = src.streamProvider ?: continue
                        val (first, len) = p.provide(0, -1)
                        return HeadCachedSource(ProviderSource(len) { off -> runBlocking { p.provide(off, -1).first } }, first)
                    }
                    is Streamable.Source.Http -> if (src.decryption == null) return HttpSource(src.request.url, src.request.headers)
                }
            }
        }
        throw last ?: IllegalStateException("Brano non disponibile")
    }

    // ------------------------------------------------------------------ errors / radio

    private var consecutiveErrors = 0
    private fun onError(e: PlaybackException) {
        publish()
        if (dev.brahmkshatriya.echo.vibes.music.DeezerAuth.isLoginError(e)) return
        // like the phone: a song that can't play is skipped (a few times at most)
        if (++consecutiveErrors <= 3 && exo.hasNextMediaItem()) {
            exo.seekToNextMediaItem(); exo.play()
        }
    }

    private var radioLoading = false
    private fun maybeExtendRadio() {
        consecutiveErrors = 0
        val item = exo.currentMediaItem ?: return
        val tag = item.desktopTag as? QueueTag ?: return
        if (tag.context?.isRadioPlaceholder() != true) return
        if (exo.currentMediaItemIndex < exo.mediaItemCount - 2 || radioLoading) return
        radioLoading = true
        io.launch {
            runCatching {
                val ext = loader.music.value.first { it.id == tag.extensionId }
                val radio = ext.getAs<RadioClient, dev.brahmkshatriya.echo.common.models.Radio> { radio(tag.track, null) }.getOrThrow()
                val tracks = ext.getAs<RadioClient, List<Track>> {
                    loadTracks(radio).pagedDataOfFirst().loadPage(null).data
                }.getOrThrow()
                val known = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }.toSet()
                val add = tracks.filter { "${tag.extensionId}:${it.id}" !in known }.map { itemFor(tag.extensionId, it, tag.context) }
                scope.launch { exo.addMediaItems(add) }
            }
            radioLoading = false
        }
    }

    // ------------------------------------------------------------------ API used by MusicEngine / UI

    fun setQueue(id: String, list: List<Track>, index: Int, context: EchoMediaItem?, respectShuffle: Boolean = false, shuffleList: Boolean = false, contextStart: Boolean = false) {
        if (list.isEmpty()) return
        val start = if (shuffleList) 0 else index.coerceIn(0, list.size - 1)
        consecutiveErrors = 0
        exo.setMediaItems(list.map { itemFor(id, it, context) }, start, 0)
        exo.prepare()
        publish()
        if (list.size == 1) maybeExtendRadio()
    }

    fun setPlaying(isPlaying: Boolean) { if (isPlaying) exo.play() else exo.pause(); publish() }
    fun next() { if (exo.hasNextMediaItem()) exo.seekToNextMediaItem(); exo.play() }
    fun previous() { exo.seekToPrevious(); exo.play() }
    fun previousTrack() = exo.seekToPreviousMediaItem()
    fun seekTo(pos: Long) = exo.seekTo(pos)
    fun seekToAdd(position: Int) = exo.seekTo(position, 0)
    fun setShuffle(isShuffled: Boolean, changeCurrent: Boolean = false) { exo.shuffleModeEnabled = isShuffled }
    fun setRepeat(repeatMode: Int) { exo.repeatMode = repeatMode }

    /** Queue screen helpers */
    fun queue(): List<MediaItem> = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it) }
    fun addToQueue(extId: String, tracks: List<Track>, next: Boolean) {
        val items = tracks.map { itemFor(extId, it, null) }
        if (exo.mediaItemCount == 0) { exo.setMediaItems(items, 0, 0); exo.prepare(); exo.play(); return }
        if (next) exo.addMediaItems(exo.currentMediaItemIndex + 1, items) else exo.addMediaItems(items)
    }
    /** Vibes: songs that continue a single shared/recommended song (instead of looping it). */
    fun appendTracks(extId: String, tracks: List<Track>) { if (tracks.isNotEmpty()) addToQueue(extId, tracks, next = false) }
    fun removeQueueItem(i: Int) { exo.removeMediaItem(i); publish() }
    fun moveQueueItem(from: Int, to: Int) { exo.moveMediaItem(from, to); publish() }
    /** Jumps to queue entry [i] and plays it. */
    fun playAt(i: Int) { if (i in 0 until exo.mediaItemCount) { exo.seekTo(i, 0); exo.play(); publish() } }
    /** Empties "next up" (keeps what is playing). */
    fun clearUpcoming() { val c = exo.currentMediaItemIndex; if (c + 1 < exo.mediaItemCount) exo.removeMediaItems(c + 1, exo.mediaItemCount); publish() }
    var volume: Float
        get() = exo.volume
        set(v) { exo.volume = v.coerceIn(0f, 1f); publish() }
    val shuffle get() = exo.shuffleModeEnabled
    val repeat get() = exo.repeatMode
}
