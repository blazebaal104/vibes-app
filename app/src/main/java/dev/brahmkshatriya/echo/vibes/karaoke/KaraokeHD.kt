package dev.brahmkshatriya.echo.vibes.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.VibesOffline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Karaoke HD: the lead vocal is removed by an AI model (UVR MDX-Net "Inst_HQ_3", 66 MB, downloaded
 * once on first use) running on the phone. The song is separated in ~6 s pieces starting from where
 * you are; as soon as enough is ready (ahead of playback, at the measured speed) the backing track
 * plays from its own audio output in sync, while the normal player is paused. Separated songs are
 * cached so they start instantly the next time.
 */
object KaraokeHD {
    private const val MODEL_NAME = "UVR-MDX-NET-Inst_HQ_3.onnx"
    private const val MODEL_URL =
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx"
    private const val MODEL_MIN_BYTES = 60_000_000L
    const val RATE = 44100
    private const val CACHE_KEEP = 8

    sealed interface Phase {
        data object Idle : Phase
        data object NeedsModel : Phase
        data class DownloadingModel(val progress: Float) : Phase
        data class Fetching(val progress: Float) : Phase
        /** [progress] of the whole song; [aheadMs] separated audio available after the play position. */
        data class Separating(val progress: Float, val readyToPlay: Boolean) : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var ctx: Context
    private val http by lazy { OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build() }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    /** HD backing track is the one playing right now. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    // current song
    private var key: String? = null
    private var job: Job? = null
    @Volatile private var outL: ShortArray = ShortArray(0)
    @Volatile private var outR: ShortArray = ShortArray(0)
    @Volatile private var totalSamples = 0
    /** Separated, contiguous range [readyFrom, readyTo) of the song. */
    @Volatile private var readyFrom = 0
    @Volatile private var readyTo = 0
    @Volatile private var samplesPerSecond = 0.0 // measured separation speed (audio samples / wall s)

    private var session: OrtSession? = null

    fun init(context: Context) { if (!::ctx.isInitialized) ctx = context.applicationContext }

    private val modelFile get() = File(ctx.filesDir, "models/$MODEL_NAME")
    fun hasModel() = modelFile.exists() && modelFile.length() > MODEL_MIN_BYTES
    private fun cacheFile(k: String) = File(ctx.cacheDir, "karaoke/${k.replace(':', '_')}.pcm")

    /** Opens the karaoke for [song] at [positionMs] (prepares HD in the background if possible). */
    fun open(song: SongRef, positionMs: Long) {
        if (key == song.key && job?.isActive == true) return
        stopPlayback()
        key = song.key
        job?.cancel()
        readyFrom = 0; readyTo = 0; totalSamples = 0
        if (!hasModel()) { _phase.value = Phase.NeedsModel; return }
        job = scope.launch { runCatching { prepare(song, positionMs) }.onFailure { e ->
            if (e !is kotlinx.coroutines.CancellationException)
                _phase.value = Phase.Failed(e.message ?: "Errore")
        } }
    }

    /** Leaving the karaoke: stops HD, returns where it was so the normal player can continue. */
    fun close(): Long? {
        val pos = if (_active.value) positionMs() else null
        stopPlayback()
        job?.cancel(); job = null; key = null
        outL = ShortArray(0); outR = ShortArray(0)
        _phase.value = Phase.Idle
        return pos
    }

    fun downloadModel(song: SongRef, positionMs: Long) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                val dst = modelFile.apply { parentFile?.mkdirs() }
                val tmp = File(dst.path + ".part")
                http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("Download del modello fallito (${res.code})")
                    val len = res.body.contentLength().takeIf { it > 0 } ?: 66_000_000L
                    var got = 0L
                    res.body.byteStream().use { i -> tmp.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024)
                        while (coroutineContext.isActive) {
                            val n = i.read(buf); if (n < 0) break
                            o.write(buf, 0, n); got += n
                            _phase.value = Phase.DownloadingModel((got.toFloat() / len).coerceIn(0f, 1f))
                        }
                    } }
                }
                if (tmp.length() < MODEL_MIN_BYTES) { tmp.delete(); throw IllegalStateException("Modello incompleto, riprova") }
                tmp.renameTo(dst)
            }.onSuccess {
                key = null
                open(song, positionMs)
            }.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _phase.value = Phase.Failed(e.message ?: "Errore") }
        }
    }

    private fun session(): OrtSession = session ?: run {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        env.createSession(modelFile.absolutePath, opts).also { session = it }
    }

    private suspend fun prepare(song: SongRef, positionMs: Long) {
        // cached result: instant
        val cached = cacheFile(song.key)
        if (cached.exists() && cached.length() > 0) {
            val (l, r) = readPcm(cached)
            outL = l; outR = r; totalSamples = l.size; readyFrom = 0; readyTo = l.size
            _phase.value = Phase.Ready
            return
        }
        _phase.value = Phase.Fetching(0f)
        val src = File(ctx.cacheDir, "karaoke/src.audio").apply { parentFile?.mkdirs() }
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            VibesOffline.fetchAudio(song, src) { _phase.value = Phase.Fetching(it) }
        }
        val (inL, inR) = decode(src)
        src.delete()
        totalSamples = inL.size
        outL = ShortArray(inL.size); outR = ShortArray(inR.size)
        val sep = MdxSeparator { input -> runModel(input) }
        // start at the piece containing the current position, then go on to the end, then the beginning
        val startPos = ((positionMs * RATE / 1000).toInt() / sep.gen * sep.gen).coerceIn(0, maxOf(0, totalSamples - 1))
        readyFrom = startPos; readyTo = startPos
        val order = (startPos until totalSamples step sep.gen) + (0 until startPos step sep.gen)
        val began = System.nanoTime()
        var done = 0L
        val wl = FloatArray(sep.chunk); val wr = FloatArray(sep.chunk)
        for (outStart in order) {
            coroutineContext.ensureActiveCompat()
            val from = sep.windowStart(outStart)
            for (i in 0 until sep.chunk) {
                val j = from + i
                if (j in 0 until totalSamples) { wl[i] = inL[j] / 32768f; wr[i] = inR[j] / 32768f } else { wl[i] = 0f; wr[i] = 0f }
            }
            val n = minOf(sep.gen, totalSamples - outStart)
            val (a, b) = sep.processWindow(wl, wr, n)
            for (i in 0 until n) {
                outL[outStart + i] = (a[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                outR[outStart + i] = (b[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            done += n
            samplesPerSecond = done / ((System.nanoTime() - began) / 1e9)
            // the front range [startPos, readyTo) grows contiguously; the part before startPos is
            // filled afterwards and only becomes playable when everything is done
            if (outStart == readyTo) readyTo = outStart + n
            _phase.value = Phase.Separating(done.toFloat() / totalSamples, readyToPlayAt(currentPlaySample()))
        }
        readyFrom = 0; readyTo = totalSamples
        _phase.value = Phase.Ready
        scope.launch(Dispatchers.IO) { runCatching { writeCache(song.key) } }
    }

    private fun kotlin.coroutines.CoroutineContext.ensureActiveCompat() {
        if (!isActive) throw kotlinx.coroutines.CancellationException()
    }

    /** Last play position reported by the karaoke screen (normal player), in samples. */
    @Volatile var externalPositionMs: Long = 0
    private fun currentPlaySample() = if (_active.value) positionSamples() else (externalPositionMs * RATE / 1000).toInt()

    /**
     * Can HD take over at sample [at]? Enough separated audio ahead so that, at the measured speed,
     * separation stays in front of playback until the end of the song (plus a margin).
     */
    fun readyToPlayAt(at: Int): Boolean {
        if (totalSamples == 0 || at < readyFrom || at >= readyTo) return false
        if (readyFrom == 0 && readyTo >= totalSamples) return true
        val ahead = readyTo - at
        val remainingToSeparate = totalSamples - readyTo
        val sps = samplesPerSecond.takeIf { it > 0 } ?: return false
        val timeToFinish = remainingToSeparate / sps
        val timeUntilPlaybackGetsThere = (totalSamples - at).toDouble() / RATE
        return ahead > 6 * RATE && timeToFinish < timeUntilPlaybackGetsThere - 4
    }

    fun readyAtMs(ms: Long) = readyToPlayAt((ms * RATE / 1000).toInt())

    private fun runModel(input: FloatArray): FloatArray {
        val s = session()
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, 3072, 256)).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                val out = (res[0] as OnnxTensor).floatBuffer
                return FloatArray(out.remaining()).also { out.get(it) }
            }
        }
    }

    // ---------------- playback (own AudioTrack, so the main player and its effects stay untouched)

    private var track: AudioTrack? = null
    private var writer: Thread? = null
    @Volatile private var playFrom = 0      // sample where the current AudioTrack started
    @Volatile private var writePos = 0      // next sample to write
    @Volatile private var stopFlag = false
    /** Called when the backing track reaches the end of the song. */
    @Volatile var onEnded: (() -> Unit)? = null

    fun positionSamples(): Int {
        val t = track ?: return playFrom
        return (playFrom + (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).toInt().coerceAtMost(totalSamples)
    }
    fun positionMs(): Long = positionSamples().toLong() * 1000 / RATE

    /** Starts the HD backing track at [ms]; false if that part is not separated yet. */
    fun play(ms: Long): Boolean {
        val at = (ms * RATE / 1000).toInt().coerceIn(0, maxOf(0, totalSamples - 1))
        if (at < readyFrom || at >= readyTo) return false
        stopPlayback()
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t; playFrom = at; writePos = at; stopFlag = false
        t.play()
        _active.value = true; _playing.value = true
        writer = Thread({
            val block = 2048
            val buf = ShortArray(block * 2)
            while (!stopFlag) {
                if (writePos >= totalSamples) {
                    // let the buffer drain, then report the end
                    Thread.sleep(400)
                    if (!stopFlag && positionSamples() >= totalSamples - RATE / 10) { stopFlag = true; onEnded?.invoke() }
                    continue
                }
                if (writePos >= readyTo || writePos < readyFrom) { Thread.sleep(30); continue } // waiting for the model
                val n = minOf(block, readyTo - writePos, totalSamples - writePos)
                for (i in 0 until n) { buf[2 * i] = outL[writePos + i]; buf[2 * i + 1] = outR[writePos + i] }
                val w = t.write(buf, 0, n * 2)
                if (w > 0) writePos += w / 2 else Thread.sleep(10)
            }
        }, "karaoke-hd").apply { isDaemon = true; start() }
        return true
    }

    fun pause() { track?.pause(); _playing.value = false }
    fun resume() { track?.play(); _playing.value = true }
    fun toggle() = if (_playing.value) pause() else resume()

    fun seekTo(ms: Long): Boolean = if (readyAtMs(ms) || (readyFrom == 0 && readyTo >= totalSamples)) play(ms) else false

    fun stopPlayback() {
        stopFlag = true
        writer?.join(500); writer = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        _active.value = false; _playing.value = false
    }

    // ---------------- audio decode / cache

    /** Any format Android can decode -> 16-bit stereo 44.1 kHz. */
    private fun decode(file: File): Pair<ShortArray, ShortArray> {
        val ex = MediaExtractor(); ex.setDataSource(file.absolutePath)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val l = ShortGrow(); val r = ShortGrow()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(ib, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                oi >= 0 -> {
                    val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.asShortBuffer()
                    val frames = sb.remaining() / channels
                    for (f in 0 until frames) {
                        val a = sb.get(f * channels)
                        val b = if (channels > 1) sb.get(f * channels + 1) else a
                        l.add(a); r.add(b)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        val ll = l.toArray(); val rr = r.toArray()
        return if (rate == RATE) ll to rr else resample(ll, rate) to resample(rr, rate)
    }

    private fun resample(x: ShortArray, from: Int): ShortArray {
        val n = (x.size.toLong() * RATE / from).toInt()
        return ShortArray(n) { i ->
            val p = i.toDouble() * from / RATE
            val j = p.toInt().coerceAtMost(x.size - 2)
            val f = p - j
            (x[j] * (1 - f) + x[j + 1] * f).toInt().toShort()
        }
    }

    private class ShortGrow {
        private var a = ShortArray(1 shl 20); private var n = 0
        fun add(v: Short) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }

    private fun writeCache(k: String) {
        val l = outL; val r = outR
        if (l.isEmpty()) return
        val f = cacheFile(k).apply { parentFile?.mkdirs() }
        val bb = ByteBuffer.allocate(l.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in l.indices) { bb.putShort(l[i]); bb.putShort(r[i]) }
        f.writeBytes(bb.array())
        // keep only the most recent songs
        f.parentFile?.listFiles { x -> x.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() }
            ?.drop(CACHE_KEEP)?.forEach { it.delete() }
    }

    private fun readPcm(f: File): Pair<ShortArray, ShortArray> {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.remaining() / 4
        val l = ShortArray(n); val r = ShortArray(n)
        for (i in 0 until n) { l[i] = bb.short; r[i] = bb.short }
        f.setLastModified(System.currentTimeMillis())
        return l to r
    }
}
