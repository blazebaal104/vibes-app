package androidx.media3.exoplayer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.brahmkshatriya.echo.vibes.desktop.audio.AudioOut
import dev.brahmkshatriya.echo.vibes.desktop.audio.AudioDecoders
import dev.brahmkshatriya.echo.vibes.desktop.audio.ByteSource
import dev.brahmkshatriya.echo.vibes.desktop.audio.FileSource
import dev.brahmkshatriya.echo.vibes.desktop.audio.HttpSource
import dev.brahmkshatriya.echo.vibes.desktop.audio.PcmSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.SwingUtilities

open class DefaultRenderersFactory(val context: Context) {
    open fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? = null
    fun setEnableDecoderFallback(b: Boolean) = this
    fun setExtensionRendererMode(m: Int) = this
}

/**
 * Desktop ExoPlayer: same API the Vibes code uses (queue, play/pause, seek, speed, volume, repeat,
 * listeners, audio processors), playing through Java Sound. Formats: see AudioDecoders.
 * One decoding thread per player; listener callbacks arrive on the UI thread like on Android.
 */
class ExoPlayer private constructor(private val processors: List<AudioProcessor>) : Player {

    class Builder(context: Context, private val factory: DefaultRenderersFactory? = null) {
        fun setAudioAttributes(a: Any?, handleFocus: Boolean) = this
        fun setHandleAudioBecomingNoisy(b: Boolean) = this
        fun setWakeMode(m: Int) = this
        fun setLoadControl(l: Any?) = this
        fun setSeekBackIncrementMs(ms: Long) = this
        fun setSeekForwardIncrementMs(ms: Long) = this
        fun build(): ExoPlayer {
            val sink = factory?.buildAudioSink(factory.context, false, false)
            return ExoPlayer((sink as? DefaultAudioSink)?.processors.orEmpty())
        }
    }

    // ------------------------------------------------------------------ state (guarded by lock)

    private val lock = Object()
    private val items = ArrayList<MediaItem>()
    private var index = 0
    @Volatile private var pwr = false
    @Volatile private var state = Player.STATE_IDLE
    @Volatile private var playingNow = false
    @Volatile private var error: PlaybackException? = null
    @Volatile private var rep = Player.REPEAT_MODE_OFF
    @Volatile private var shuffle = false
    @Volatile private var vol = 1f
    @Volatile private var speed = 1f
    @Volatile private var released = false
    private var shuffleOrder: List<Int> = emptyList()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Player.Listener>()

    /** crossfade length in ms (0 = gapless). The main player sets it from Settings. */
    @Volatile var crossfadeMs: Long = 0

    // commands for the audio thread
    private var seekRequest: Pair<Int, Long>? = null
    private var reloadRequested = false

    // what the audio thread is playing
    private class Playing(val index: Int, val item: MediaItem, val pcm: PcmSource) {
        var contentMs = pcm.startMs.toDouble()   // position of the next frame to be written
        val durationMs get() = pcm.durationMs
        var ended = false
        val resampler = Resampler()
    }

    @Volatile private var cur: Playing? = null
    private val out = AudioOut()

    // ------------------------------------------------------------------ audio thread

    private val thread = Thread({ loop() }, "vibes-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }

    private val tmpDir by lazy { File(System.getProperty("java.io.tmpdir"), "vibes-audio").apply { mkdirs() } }

    private fun sourceOf(item: MediaItem): ByteSource {
        item.desktopSource?.let { return runBlocking { it() } }
        val uri = item.localConfiguration?.uri?.toString() ?: throw PlaybackException("Nessuna sorgente", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        return if (uri.startsWith("http")) HttpSource(uri) else FileSource(File(android.net.Uri.parse(uri).path ?: uri))
    }

    private fun openItem(i: Int, posMs: Long): Playing {
        val item = synchronized(lock) { items[i] }
        val src = sourceOf(item)
        val pcm = AudioDecoders.open(src, posMs, tmpDir)
        println("vibes-audio: open ${item.mediaId} @${posMs}ms -> ${pcm.javaClass.simpleName} ${pcm.sampleRate}Hz ${pcm.channels}ch ${pcm.durationMs}ms")
        return Playing(i, item, pcm)
    }

    /** next item prepared during the last seconds of the current one (gapless / crossfade) */
    @Volatile private var preloaded: Playing? = null
    @Volatile private var preloading: Int = -1

    private fun nextIndexOf(i: Int): Int? = synchronized(lock) {
        if (rep == Player.REPEAT_MODE_ONE) return i
        val order = order()
        val p = order.indexOf(i)
        when {
            p < 0 -> null
            p + 1 < order.size -> order[p + 1]
            rep == Player.REPEAT_MODE_ALL && order.isNotEmpty() -> order.first()
            else -> null
        }
    }

    private fun order(): List<Int> = if (shuffle && shuffleOrder.size == items.size) shuffleOrder else items.indices.toList()

    private fun loop() {
        val frames = 1024
        val decodeBuf = ShortArray(8192)
        val mix = FloatArray(frames * 2)
        val mixNext = FloatArray(frames * 2)
        val pcmOut = ShortArray(frames * 2)
        val bb = ByteBuffer.allocateDirect(frames * 4 * 2).order(ByteOrder.nativeOrder())
        while (!released) {
            try {
                // ---- commands
                val seek = synchronized(lock) { val s = seekRequest; seekRequest = null; s }
                val reload = synchronized(lock) { val r = reloadRequested; reloadRequested = false; r }
                if (seek != null || reload) {
                    val (si, sp) = seek ?: (index to 0L)
                    out.flush()
                    closePlaying()
                    if (synchronized(lock) { items.isEmpty() }) { setState(Player.STATE_IDLE); continue }
                    setState(Player.STATE_BUFFERING)
                    val p = try { openItem(si, sp) } catch (e: Throwable) { fail(e); continue }
                    if (seekRequestPending()) { p.pcm.close(); continue }
                    cur = p
                    out.markItemStart(p.contentMs)
                    processors.forEach { it.flush() }
                    setState(Player.STATE_READY)
                }
                val p = cur
                if (p == null || !pwr || state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                    out.pause(); setPlayingNow(false)
                    synchronized(lock) { if (seekRequest == null && !reloadRequested) lock.wait(50) }
                    continue
                }

                // ---- preload the next item ~15 s before the end
                val remaining = if (p.durationMs > 0) p.durationMs - p.contentMs else Double.MAX_VALUE
                val nxt = nextIndexOf(p.index)
                if (nxt != null && remaining < 15_000 + crossfadeMs && preloaded == null && preloading != nxt) startPreload(nxt)

                // ---- crossfade window
                val fade = crossfadeMs.takeIf { it > 0 && rep != Player.REPEAT_MODE_ONE } ?: 0
                val inFade = fade > 0 && nxt != null && remaining <= fade && preloaded?.index == nxt

                // ---- decode one block of the current item at 44.1 kHz stereo
                val got = p.resampler.read(p.pcm, decodeBuf, mix, frames, speed)
                if (got <= 0) { onItemEnded(p); continue }
                p.contentMs += got * 1000.0 / AudioOut.RATE * speed

                if (inFade) {
                    val n = preloaded!!
                    val g2 = n.resampler.read(n.pcm, decodeBuf, mixNext, got, speed)
                    if (g2 > 0) n.contentMs += g2 * 1000.0 / AudioOut.RATE * speed
                    for (f in 0 until got) {
                        val t = ((fade - (remaining - f * 1000.0 / AudioOut.RATE)) / fade).coerceIn(0.0, 1.0).toFloat()
                        val a = kotlin.math.cos(t * Math.PI / 2).toFloat(); val b = kotlin.math.sin(t * Math.PI / 2).toFloat()
                        val nx = f < g2
                        mix[2 * f] = mix[2 * f] * a + (if (nx) mixNext[2 * f] * b else 0f)
                        mix[2 * f + 1] = mix[2 * f + 1] * a + (if (nx) mixNext[2 * f + 1] * b else 0f)
                    }
                    // the fade has reached its middle: the next song becomes the current one
                    if (remaining - got * 1000.0 / AudioOut.RATE <= fade / 2.0) switchToPreloaded(p, crossfading = true)
                }

                // ---- effects, volume, output
                val v = vol
                for (i in 0 until got * 2) pcmOut[i] = (mix[i] * v).toInt().coerceIn(-32768, 32767).toShort()
                var data: ByteBuffer = bb.apply { clear(); asShortBuffer().put(pcmOut, 0, got * 2); limit(got * 4) }
                for (proc in processors) {
                    if (!proc.isActive()) {
                        proc.configure(AudioProcessor.AudioFormat(AudioOut.RATE, 2, androidx.media3.common.C.ENCODING_PCM_16BIT)); proc.flush()
                        if (!proc.isActive()) continue
                    }
                    proc.queueInput(data)
                    data = proc.getOutput()
                }
                setPlayingNow(true)
                out.write(data, cur?.contentMs ?: p.contentMs, speed)
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                fail(e)
            }
        }
        closePlaying(); out.close()
    }

    private fun seekRequestPending() = synchronized(lock) { seekRequest != null }

    private fun startPreload(i: Int) {
        preloading = i
        Thread({
            val p = runCatching { openItem(i, 0) }.getOrNull()
            if (preloading == i && !released) preloaded = p else p?.pcm?.close()
        }, "vibes-preload").apply { isDaemon = true; start() }
    }

    private fun switchToPreloaded(old: Playing, crossfading: Boolean) {
        val n = preloaded ?: return
        // the old item keeps fading out inside the crossfade loop only while it is `cur`; once we
        // switch, the remainder of the fade is the new item at full volume (equal-power midpoint)
        preloaded = null; preloading = -1
        old.pcm.close()
        cur = n
        out.markItemStart(n.contentMs, keepLatency = true)
        synchronized(lock) { index = n.index }
        dispatch(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_POSITION_DISCONTINUITY) {
            onMediaItemTransition(n.item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            onPositionDiscontinuity(Player.PositionInfo(old.index, old.contentMs.toLong()), Player.PositionInfo(n.index, n.contentMs.toLong()), Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        }
    }

    private fun onItemEnded(p: Playing) {
        val nxt = nextIndexOf(p.index)
        if (nxt == null) {
            out.drain()
            setPlayingNow(false)
            setState(Player.STATE_ENDED)
            return
        }
        if (preloaded?.index == nxt) { switchToPreloaded(p, false); return }
        // not preloaded yet (short song / slow network): open now
        preloading = -1; preloaded?.pcm?.close(); preloaded = null
        p.pcm.close()
        setState(Player.STATE_BUFFERING)
        val n = try { openItem(nxt, 0) } catch (e: Throwable) { synchronized(lock) { index = nxt }; fail(e); return }
        cur = n
        out.markItemStart(n.contentMs, keepLatency = true)
        synchronized(lock) { index = nxt }
        setState(Player.STATE_READY)
        dispatch(Player.EVENT_MEDIA_ITEM_TRANSITION) { onMediaItemTransition(n.item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) }
    }

    private fun closePlaying() {
        cur?.pcm?.close(); cur = null
        preloading = -1; preloaded?.pcm?.close(); preloaded = null
    }

    private fun fail(e: Throwable) {
        val pe = e as? PlaybackException ?: PlaybackException(e.message ?: e.javaClass.simpleName, e,
            if (e is java.io.IOException) PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED else PlaybackException.ERROR_CODE_DECODING_FAILED)
        System.err.println("vibes-audio: error ${pe.message}"); e.printStackTrace()
        closePlaying(); out.pause()
        error = pe
        setPlayingNow(false)
        state = Player.STATE_IDLE
        dispatch(Player.EVENT_PLAYER_ERROR, Player.EVENT_PLAYBACK_STATE_CHANGED) {
            onPlayerErrorChanged(pe); onPlayerError(pe); onPlaybackStateChanged(Player.STATE_IDLE)
        }
    }

    // ------------------------------------------------------------------ events

    private fun dispatch(vararg events: Int, call: Player.Listener.() -> Unit) {
        val ev = Player.Events(events.toSet())
        val run = Runnable { listeners.forEach { l -> runCatching { l.call() }.onFailure { it.printStackTrace() }; runCatching { l.onEvents(this, ev) } } }
        if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeLater(run)
    }

    private fun setState(s: Int) {
        if (state == s) return
        state = s
        dispatch(Player.EVENT_PLAYBACK_STATE_CHANGED) { onPlaybackStateChanged(s) }
        updatePlaying()
    }

    private fun setPlayingNow(b: Boolean) { playingNow = b; updatePlaying() }

    @Volatile private var reportedPlaying = false
    private fun updatePlaying() {
        val now = pwr && state == Player.STATE_READY && playingNow
        if (now != reportedPlaying) {
            reportedPlaying = now
            dispatch(Player.EVENT_IS_PLAYING_CHANGED) { onIsPlayingChanged(now) }
        }
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    // ------------------------------------------------------------------ Player API

    override val isPlaying get() = reportedPlaying
    override var playWhenReady: Boolean
        get() = pwr
        set(v) {
            if (pwr == v) return
            pwr = v
            if (!v) out.pause()
            dispatch(Player.EVENT_PLAY_WHEN_READY_CHANGED) { onPlayWhenReadyChanged(v, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
            updatePlaying(); wake()
        }
    override val playbackState get() = state
    override val currentPosition: Long
        get() {
            val p = cur ?: return synchronized(lock) { seekRequest?.second } ?: 0L
            return out.playedContentMs().takeIf { it >= 0 }?.toLong() ?: p.pcm.startMs
        }
    override val bufferedPosition get() = cur?.contentMs?.toLong() ?: 0L
    override val duration: Long get() = cur?.durationMs?.takeIf { it > 0 } ?: androidx.media3.common.C.TIME_UNSET
    override val currentMediaItem get() = synchronized(lock) { items.getOrNull(index) }
    override val currentMediaItemIndex get() = synchronized(lock) { index }
    override val mediaItemCount get() = synchronized(lock) { items.size }
    override val nextMediaItemIndex get() = synchronized(lock) { nextIndexOf(index) } ?: androidx.media3.common.C.INDEX_UNSET
    override val previousMediaItemIndex: Int
        get() = synchronized(lock) {
            val o = order(); val p = o.indexOf(index)
            if (p > 0) o[p - 1] else if (rep == Player.REPEAT_MODE_ALL && o.isNotEmpty()) o.last() else androidx.media3.common.C.INDEX_UNSET
        }
    override var repeatMode: Int
        get() = rep
        set(v) { rep = v; dropPreload(); dispatch(Player.EVENT_REPEAT_MODE_CHANGED) { onRepeatModeChanged(v) } }
    override var shuffleModeEnabled: Boolean
        get() = shuffle
        set(v) {
            synchronized(lock) {
                shuffle = v
                shuffleOrder = if (v) listOf(index) + items.indices.filter { it != index }.shuffled() else emptyList()
            }
            dropPreload()
            dispatch(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) { onShuffleModeEnabledChanged(v) }
        }
    override var volume: Float
        get() = vol
        set(v) { vol = v.coerceIn(0f, 1f); dispatch() { onVolumeChanged(vol) } }
    override val playerError get() = error
    override val currentTimeline get() = Timeline(mediaItemCount)
    override var playbackParameters: PlaybackParameters
        get() = PlaybackParameters(speed)
        set(v) = setPlaybackSpeed(v.speed)

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.25f, 4f)
        dispatch(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) { onPlaybackParametersChanged(PlaybackParameters(this@ExoPlayer.speed)) }
    }

    private fun dropPreload() { preloading = -1; preloaded?.pcm?.close(); preloaded = null }

    override fun getMediaItemAt(index: Int) = synchronized(lock) { items[index] }
    override fun hasNextMediaItem() = nextMediaItemIndex != androidx.media3.common.C.INDEX_UNSET
    override fun hasPreviousMediaItem() = previousMediaItemIndex != androidx.media3.common.C.INDEX_UNSET

    override fun play() { error = null; if (state == Player.STATE_ENDED) seekTo(index, 0); if (state == Player.STATE_IDLE && cur == null && mediaItemCount > 0) prepare(); playWhenReady = true }
    override fun pause() { playWhenReady = false }

    override fun prepare() {
        error = null
        synchronized(lock) { if (items.isNotEmpty() && cur == null && seekRequest == null) seekRequest = index to 0L }
        wake()
    }

    override fun stop() {
        playWhenReady = false
        synchronized(lock) { seekRequest = null }
        out.flush(); closePlaying(); setState(Player.STATE_IDLE)
    }

    override fun release() { released = true; stop(); thread.interrupt() }

    override fun seekTo(positionMs: Long) = seekTo(currentMediaItemIndex, positionMs)

    override fun seekTo(index: Int, positionMs: Long) {
        val changed: Boolean
        val old = currentPosition
        synchronized(lock) {
            if (items.isEmpty()) return
            val i = index.coerceIn(0, items.size - 1)
            changed = i != this.index
            this.index = i
            seekRequest = i to positionMs.coerceAtLeast(0)
            lock.notifyAll()
        }
        dropPreload()
        if (state == Player.STATE_ENDED) state = Player.STATE_BUFFERING
        val item = currentMediaItem
        dispatch(Player.EVENT_POSITION_DISCONTINUITY) {
            if (changed) onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            onPositionDiscontinuity(Player.PositionInfo(index, old), Player.PositionInfo(index, positionMs), Player.DISCONTINUITY_REASON_SEEK)
        }
    }

    override fun seekToNextMediaItem() { val n = nextMediaItemIndex; if (n >= 0) seekTo(n, 0) }
    override fun seekToPreviousMediaItem() { val p = previousMediaItemIndex; if (p >= 0) seekTo(p, 0) else seekTo(0) }
    override fun seekToPrevious() { if (currentPosition > 3000 || !hasPreviousMediaItem()) seekTo(0) else seekToPreviousMediaItem() }

    override fun setMediaItem(item: MediaItem) = setMediaItems(listOf(item), 0, 0)
    override fun setMediaItems(items: List<MediaItem>) = setMediaItems(items, 0, 0)

    override fun setMediaItems(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val first: MediaItem?
        synchronized(lock) {
            this.items.clear(); this.items.addAll(items)
            index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            shuffleOrder = if (shuffle) listOf(index) + items.indices.filter { it != index }.shuffled() else emptyList()
            first = items.getOrNull(index)
            seekRequest = if (items.isEmpty()) null else index to startPositionMs.coerceAtLeast(0)
            if (items.isEmpty()) reloadRequested = true
            lock.notifyAll()
        }
        error = null
        dropPreload()
        dispatch(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION) {
            onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            onMediaItemTransition(first, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        }
    }

    override fun addMediaItems(items: List<MediaItem>) = addMediaItems(mediaItemCount, items)

    override fun addMediaItems(index: Int, items: List<MediaItem>) {
        synchronized(lock) {
            val at = index.coerceIn(0, this.items.size)
            this.items.addAll(at, items)
            if (at <= this.index && this.items.size > items.size) this.index += items.size
            if (shuffle) shuffleOrder = shuffleOrder.map { if (it >= at) it + items.size else it } + items.indices.map { at + it }.shuffled()
        }
        dropPreload()
        dispatch(Player.EVENT_TIMELINE_CHANGED) { onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
    }

    override fun removeMediaItem(index: Int) = removeMediaItems(index, index + 1)

    override fun removeMediaItems(from: Int, to: Int) {
        var currentRemoved = false
        synchronized(lock) {
            val f = from.coerceIn(0, items.size); val t = to.coerceIn(f, items.size)
            if (index in f until t) currentRemoved = true
            repeat(t - f) { items.removeAt(f) }
            if (index >= t) index -= (t - f) else if (currentRemoved) index = f.coerceAtMost(items.size - 1).coerceAtLeast(0)
            if (shuffle) shuffleOrder = shuffleOrder.filter { it !in f until t }.map { if (it >= t) it - (t - f) else it }
        }
        dropPreload()
        if (currentRemoved) { if (mediaItemCount == 0) stop() else seekTo(index, 0) }
        dispatch(Player.EVENT_TIMELINE_CHANGED) { onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
    }

    override fun moveMediaItem(from: Int, to: Int) {
        synchronized(lock) {
            if (from !in items.indices || to !in items.indices) return
            val it = items.removeAt(from); items.add(to, it)
            index = when {
                index == from -> to
                from < index && to >= index -> index - 1
                from > index && to <= index -> index + 1
                else -> index
            }
            if (shuffle) shuffleOrder = items.indices.toList()
        }
        dropPreload()
        dispatch(Player.EVENT_TIMELINE_CHANGED) { onTimelineChanged(currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
    }

    override fun replaceMediaItem(index: Int, item: MediaItem) { synchronized(lock) { if (index in items.indices) items[index] = item } }

    override fun clearMediaItems() = setMediaItems(emptyList())

    override fun addListener(l: Player.Listener) { listeners += l }
    override fun removeListener(l: Player.Listener) { listeners -= l }
}

/** Converts any rate / mono-stereo to 44.1 kHz stereo floats, with playback speed (changes pitch). */
internal class Resampler {
    private var buf = FloatArray(0)
    private var have = 0      // frames in buf
    private var pos = 0.0     // fractional read position in frames
    private var eof = false

    /** Writes up to [frames] frames into [out] (stereo interleaved floats), returns frames written or -1. */
    fun read(src: PcmSource, tmp: ShortArray, out: FloatArray, frames: Int, speed: Float): Int {
        val step = src.sampleRate.toDouble() / AudioOut.RATE * speed
        val ch = src.channels.coerceAtLeast(1)
        var n = 0
        while (n < frames) {
            val i = pos.toInt()
            if (i + 1 >= have) {
                if (eof) break
                val got = src.read(tmp)
                if (got < 0) { eof = true; break }
                val keep = if (i < have) have - i else 0
                val f = got / ch
                val nb = FloatArray((keep + f) * 2)
                for (k in 0 until keep) { nb[2 * k] = buf[2 * (i + k)]; nb[2 * k + 1] = buf[2 * (i + k) + 1] }
                for (j in 0 until f) {
                    val l = tmp[j * ch] / 32768f
                    val r = if (ch > 1) tmp[j * ch + 1] / 32768f else l
                    nb[2 * (keep + j)] = l; nb[2 * (keep + j) + 1] = r
                }
                pos -= if (keep > 0) i.toDouble() else have.toDouble()
                if (pos < 0) pos = 0.0
                buf = nb; have = keep + f
                continue
            }
            val fr = (pos - i).toFloat()
            out[2 * n] = buf[2 * i] + (buf[2 * i + 2] - buf[2 * i]) * fr
            out[2 * n + 1] = buf[2 * i + 1] + (buf[2 * i + 3] - buf[2 * i + 1]) * fr
            n++
            pos += step
        }
        return if (n == 0 && eof) -1 else n
    }
}
