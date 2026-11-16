package dev.brahmkshatriya.echo.vibes.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.edit
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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Karaoke HD: the lead vocal is removed by an AI model (UVR MDX-Net "Inst_HQ_3", 66 MB, downloaded
 * once) running on the phone.
 *
 * One worker separates ~6 s pieces with this priority:
 *  1. the song on screen, from the play position forward (then the start);
 *  2. the next song of the queue, from its start, so it begins in HD;
 *  3. playlists the user asked to "prepare for karaoke".
 * HD takes over from the real-time filter as soon as the separated part stays ahead of playback
 * at the measured speed. Finished songs are stored compressed (AAC, ~6 MB) and start instantly.
 * The first runs try the engine settings (threads / XNNPACK) and keep the fastest for this phone.
 */
object KaraokeHD {
    private const val MODEL_NAME = "UVR-MDX-NET-Inst_HQ_3.onnx"
    private const val MODEL_URL =
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx"
    private const val MODEL_MIN_BYTES = 60_000_000L
    const val RATE = 44100
    private const val CACHE_MAX_BYTES = 450L * 1024 * 1024

    sealed interface Phase {
        data object Idle : Phase
        data object NeedsModel : Phase
        data class DownloadingModel(val progress: Float) : Phase
        data class Fetching(val progress: Float) : Phase
        data class Separating(val progress: Float) : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    /** Background preparation (next song / playlist). */
    data class Background(val title: String?, val progress: Float, val queued: Int, val doneInBatch: Int, val batch: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var ctx: Context
    private val http by lazy { OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build() }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    private val _background = MutableStateFlow<Background?>(null)
    val background: StateFlow<Background?> = _background
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    /** Separating or downloading: keeps [KaraokeService] (and the notification) alive. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private fun markBusy() { if (!_busy.value) { _busy.value = true; if (::ctx.isInitialized) KaraokeService.start(ctx) } }

    /** Title, text and progress for the notification. */
    fun statusLine(): Triple<String, String, Float> {
        when (val p = _phase.value) {
            is Phase.DownloadingModel -> return Triple("Scarico il karaoke HD", "${(p.progress * 100).toInt()}%", p.progress)
            is Phase.Fetching -> return Triple("Karaoke HD", "Preparo la canzone…", 0f)
            is Phase.Separating -> return Triple("Karaoke HD: tolgo la voce", "${curSep?.song?.title ?: ""} · ${(p.progress * 100).toInt()}%", p.progress)
            else -> {}
        }
        val b = _background.value
        return if (b != null) Triple(
            "Karaoke HD: preparo in anticipo",
            "${b.title ?: ""} · ${(b.progress * 100).toInt()}%" + if (b.batch > 0) " · ${b.doneInBatch}/${b.batch}" else "",
            b.progress
        ) else Triple("Karaoke HD", "Quasi fatto…", 0f)
    }

    /** Keys of songs whose HD backing track is stored. */
    private val _cached = MutableStateFlow<Set<String>>(emptySet())
    val cached: StateFlow<Set<String>> = _cached

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        refreshCached()
    }

    private val modelFile get() = File(ctx.filesDir, "models/$MODEL_NAME")
    fun hasModel() = modelFile.exists() && modelFile.length() > MODEL_MIN_BYTES
    private val cacheDir get() = File(ctx.filesDir, "karaoke").apply { mkdirs() }
    private fun cacheFile(k: String) = File(cacheDir, "${k.replace(':', '_')}.m4a")
    private fun refreshCached() {
        _cached.value = cacheDir.listFiles { f -> f.name.endsWith(".m4a") }
            ?.map { it.name.removeSuffix(".m4a").replaceFirst('_', ':') }?.toSet().orEmpty()
    }

    // ------------------------------------------------------------------ per-song separation state

    private class Sep(val song: SongRef, val sep: MdxSeparator, val inL: ShortBuffer, val inR: ShortBuffer, val total: Int) {
        val outL: ShortBuffer = direct(total); val outR: ShortBuffer = direct(total)
        val chunks = (total + sep.gen - 1) / sep.gen
        val done = BooleanArray(chunks)
        @Volatile var doneCount = 0
        val complete get() = doneCount == chunks
        fun chunkOf(sample: Int) = (sample / sep.gen).coerceIn(0, chunks - 1)
        /** Samples separated contiguously from [at]. */
        fun readyAhead(at: Int): Int {
            var c = chunkOf(at)
            while (c < chunks && done[c]) c++
            return (minOf(c * sep.gen, total) - at).coerceAtLeast(0)
        }
        fun undoneFrom(at: Int) = (chunkOf(at) until chunks).count { !done[it] }
    }

    private class Ready(val song: SongRef, val outL: ShortBuffer, val outR: ShortBuffer, val total: Int)

    private fun direct(n: Int): ShortBuffer = ByteBuffer.allocateDirect(maxOf(n, 1) * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    /** The song on screen: either still separating ([curSep]) or ready ([curReady]). */
    @Volatile private var curKey: String? = null
    @Volatile private var curSep: Sep? = null
    @Volatile private var curReady: Ready? = null
    /** Background slot: next song of the queue or a playlist song. */
    @Volatile private var bgSep: Sep? = null
    @Volatile var nextInQueue: SongRef? = null
    private val playlistQueue = ArrayDeque<SongRef>()
    private var batchSize = 0; private var batchDone = 0
    @Volatile private var samplesPerSecond = 0.0
    @Volatile var externalPositionMs: Long = 0
    private var worker: Job? = null
    private var loadJob: Job? = null

    /** Opens the karaoke for [song] at [positionMs]. */
    fun open(song: SongRef, positionMs: Long) {
        if (curKey == song.key && _phase.value !is Phase.Failed) return
        stopPlayback()
        curKey = song.key; curReady = null
        externalPositionMs = positionMs
        // the background slot may already hold this song (it was "next"): promote it
        val bg = bgSep
        curSep = if (bg?.song?.key == song.key) bg.also { bgSep = null } else null
        if (!hasModel()) { _phase.value = Phase.NeedsModel; return }
        loadJob?.cancel()
        markBusy()
        loadJob = scope.launch {
            runCatching {
                val cached = cacheFile(song.key)
                when {
                    curSep != null -> _phase.value = if (curSep!!.complete) Phase.Ready else Phase.Separating(curSep!!.doneCount.toFloat() / curSep!!.chunks)
                    cached.exists() -> {
                        _phase.value = Phase.Fetching(1f)
                        val (l, r, n) = decode(cached)
                        cached.setLastModified(System.currentTimeMillis())
                        if (curKey == song.key) { curReady = Ready(song, l, r, n); _phase.value = Phase.Ready }
                    }
                    else -> {
                        _phase.value = Phase.Fetching(0f)
                        val s = kotlinx.coroutines.withTimeoutOrNull(120_000) {
                            load(song) { if (curKey == song.key) _phase.value = Phase.Fetching(it) }
                        } ?: throw IllegalStateException("la canzone non si scarica (connessione lenta?)")
                        if (curKey == song.key) { curSep = s; _phase.value = Phase.Separating(0f) }
                    }
                }
                startWorker()
            }.onFailure { e ->
                if (e !is kotlinx.coroutines.CancellationException && curKey == song.key) {
                    _phase.value = Phase.Failed(friendly(e))
                    report("apertura ${song.title}", e)
                }
                if (worker?.isActive != true) _busy.value = false
            }
        }
    }

    /** "Riprova" after a failure. */
    fun retry(song: SongRef, positionMs: Long) {
        curKey = null; curSep = null; curReady = null
        open(song, positionMs)
    }

    private fun friendly(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "memoria del telefono piena"
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "niente connessione"
        else -> e.message?.take(80) ?: "errore"
    }

    private fun report(what: String, e: Throwable) {
        runCatching { dev.brahmkshatriya.echo.vibes.diag.ErrorReporter.report("karaoke", "HD: $what", e) }
    }

    /** Leaving the karaoke: returns where HD was (null if it was not playing). */
    fun close(): Long? {
        val pos = if (_active.value) positionMs() else null
        stopPlayback()
        loadJob?.cancel()
        // an unfinished current song keeps being separated in the background slot if it is free
        val s = curSep
        if (s != null && !s.complete && bgSep == null) bgSep = s
        curSep = null; curReady = null; curKey = null; nextInQueue = null
        _phase.value = Phase.Idle
        return pos
    }

    /** "Prepare for karaoke": separates these songs in the background and stores them. */
    fun preparePlaylist(songs: List<SongRef>) {
        val todo = songs.filter { it.key !in _cached.value && playlistQueue.none { q -> q.key == it.key } }
        playlistQueue.addAll(todo)
        batchSize += todo.size
        startWorker()
    }

    private suspend fun load(song: SongRef, onFetch: (Float) -> Unit): Sep = withContext(Dispatchers.IO) {
        val src = File(ctx.cacheDir, "karaoke-src-${song.id}.audio")
        try {
            VibesOffline.fetchAudio(song, src, onFetch)
            val (l, r, n) = decode(src)
            Sep(song, MdxSeparator { runModel(it) }, l, r, n)
        } finally { src.delete() }
    }

    // ------------------------------------------------------------------ worker

    private fun startWorker() {
        if (worker?.isActive == true) return
        markBusy()
        worker = scope.launch {
            while (isActive) {
                val did = runCatching { step() }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    report("preparazione in anticipo", e)
                    _background.value = null
                    bgSep = null
                    false
                }
                if (!did) delay(300)
                if (!did && (curSep == null || curSep!!.complete) && bgSep == null && playlistQueue.isEmpty() &&
                    (nextInQueue == null || nextInQueue!!.key in _cached.value)) {
                    _background.value = null
                    break
                }
            }
        }.also { j -> j.invokeOnCompletion { if (loadJob?.isActive != true) _busy.value = false } }
    }

    /** Separates one piece of the most important song. Returns false if there was nothing to do. */
    private suspend fun step(): Boolean {
        if (!hasModel()) return false
        // 1. the song on screen
        curSep?.let { s ->
            val at = if (_active.value) positionSamples() else (externalPositionMs * RATE / 1000).toInt()
            val forward = (s.chunkOf(at) until s.chunks).firstOrNull { !s.done[it] }
            val c = forward ?: (0 until s.chunks).firstOrNull { !s.done[it] }
            if (c != null) {
                try { runChunk(s, c) } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    report("separazione ${s.song.title} (pezzo $c, motore ${sessionConfig})", e)
                    if (curSep === s) { curSep = null; _phase.value = Phase.Failed(friendly(e)) }
                    return true
                }
                if (curSep === s) _phase.value = if (s.complete) Phase.Ready else Phase.Separating(s.doneCount.toFloat() / s.chunks)
                if (s.complete) finish(s)
                return true
            }
        }
        // 2. background slot: the next song of the queue has priority over a playlist song
        val next = nextInQueue?.takeIf { it.key !in _cached.value && it.key != curKey }
        var bg = bgSep
        if (next != null && bg != null && bg.song.key != next.key) {
            // a playlist song was in progress: put it back in line (its progress is redone later)
            if (bg.song.key != curKey) playlistQueue.addFirst(bg.song)
            bgSep = null; bg = null
        }
        if (bg == null) {
            val target = next ?: run {
                var t: SongRef? = null
                while (t == null && playlistQueue.isNotEmpty()) {
                    t = playlistQueue.removeFirst().takeIf { it.key !in _cached.value && it.key != curKey }
                    if (t == null) batchDone++
                }
                t
            } ?: return false
            _background.value = Background(target.title, 0f, playlistQueue.size, batchDone, batchSize)
            bg = runCatching { load(target) {} }.getOrNull()
            if (bg == null) { if (target.key != next?.key) batchDone++; return true }
            bgSep = bg
        }
        val s = bg
        val c = (0 until s.chunks).firstOrNull { !s.done[it] }
        if (c != null) runChunk(s, c)
        _background.value = Background(s.song.title, s.doneCount.toFloat() / s.chunks, playlistQueue.size, batchDone, batchSize)
        if (s.complete) {
            finish(s)
            if (bgSep === s) bgSep = null
            if (s.song.key != nextInQueue?.key) batchDone++
            if (playlistQueue.isEmpty() && batchDone >= batchSize) { batchDone = 0; batchSize = 0 }
        }
        return true
    }

    private fun runChunk(s: Sep, c: Int) {
        val sep = s.sep
        val outStart = c * sep.gen
        val wl = FloatArray(sep.chunk); val wr = FloatArray(sep.chunk)
        val from = sep.windowStart(outStart)
        for (i in 0 until sep.chunk) {
            val j = from + i
            if (j in 0 until s.total) { wl[i] = s.inL.get(j) / 32768f; wr[i] = s.inR.get(j) / 32768f }
        }
        val n = minOf(sep.gen, s.total - outStart)
        val t0 = System.nanoTime()
        val (a, b) = sep.processWindow(wl, wr, n)
        val secs = (System.nanoTime() - t0) / 1e9
        for (i in 0 until n) {
            s.outL.put(outStart + i, (a[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            s.outR.put(outStart + i, (b[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        s.done[c] = true; s.doneCount++
        val sps = n / secs
        samplesPerSecond = if (samplesPerSecond == 0.0) sps else samplesPerSecond * 0.7 + sps * 0.3
    }

    private fun finish(s: Sep) {
        runCatching { encodeM4a(s.outL, s.outR, s.total, cacheFile(s.song.key)); trimCache(); refreshCached() }
        if (curSep === s) { curReady = Ready(s.song, s.outL, s.outR, s.total); curSep = null }
    }

    /**
     * How close HD is to taking over at [ms], 0..1, reaching 1 exactly when [readyAtMs] turns true.
     * (The bar used to show how much of the WHOLE song was separated: HD starts long before the end,
     * so the bar jumped from ~30% straight to "HD".) Two conditions: 6 s separated ahead of the
     * player, and the rest finishing before the song does.
     */
    fun startProgress(ms: Long): Float {
        val at = (ms * RATE / 1000).toInt()
        if (curReady != null) return 1f
        val s = curSep ?: return 0f
        if (s.complete) return 1f
        val ahead = (s.readyAhead(at).toFloat() / (6 * RATE)).coerceIn(0f, 1f)
        val sps = samplesPerSecond.takeIf { it > 0 } ?: return ahead * 0.5f
        val timeToFinish = s.undoneFrom(at).toDouble() * s.sep.gen / sps
        val timeUntilEnd = (s.total - at).toDouble() / RATE - 4
        val finish = if (timeToFinish <= 0.0) 1f else (timeUntilEnd / timeToFinish).toFloat().coerceIn(0f, 1f)
        return minOf(ahead, finish).let { if (it >= 1f && !readyAtMs(ms)) 0.99f else it }
    }

    /** Whole-song separation (0..1) of the song open in karaoke. */
    fun songProgress(): Float = when {
        curReady != null -> 1f
        else -> curSep?.let { it.doneCount.toFloat() / it.chunks } ?: 0f
    }

    /** Can HD take over at [ms] and stay ahead of playback until the end? */
    fun readyAtMs(ms: Long): Boolean {
        val at = (ms * RATE / 1000).toInt()
        curReady?.let { return at < it.total }
        val s = curSep ?: return false
        if (s.complete) return true
        val ahead = s.readyAhead(at)
        if (ahead < 6 * RATE) return false
        val sps = samplesPerSecond.takeIf { it > 0 } ?: return false
        val timeToFinish = s.undoneFrom(at).toDouble() * s.sep.gen / sps
        val timeUntilEnd = (s.total - at).toDouble() / RATE
        return timeToFinish < timeUntilEnd - 4
    }

    // ------------------------------------------------------------------ model / engine tuning

    fun downloadModel(song: SongRef, positionMs: Long) {
        loadJob?.cancel()
        markBusy()
        loadJob = scope.launch(Dispatchers.IO) {
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
            }.onSuccess { curKey = null; open(song, positionMs) }
                .onFailure { e ->
                    if (e !is kotlinx.coroutines.CancellationException) _phase.value = Phase.Failed(e.message ?: "Errore")
                    if (worker?.isActive != true) _busy.value = false
                }
        }
    }

    /** Engine configurations tried on this phone; the fastest one is remembered. */
    // at most 4 threads and low priority: with 6 the music itself started to stutter
    private val configs = listOf("cpu4", "xnn4", "cpu3", "xnn3")
    private val prefs by lazy { ctx.getSharedPreferences("karaoke_hd2", Context.MODE_PRIVATE) }
    private var session: OrtSession? = null
    private var sessionConfig: String? = null

    private fun sessionFor(config: String): OrtSession {
        if (sessionConfig == config) session?.let { return it }
        session?.close(); session = null
        val env = OrtEnvironment.getEnvironment()
        val threads = config.takeLast(1).toInt().coerceAtMost(Runtime.getRuntime().availableProcessors())
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (config.startsWith("xnn")) {
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                setIntraOpNumThreads(1)
            } else setIntraOpNumThreads(threads)
        }
        return env.createSession(modelFile.absolutePath, opts).also { session = it; sessionConfig = config }
    }

    private fun runModel(input: FloatArray): FloatArray {
        // ONNX Runtime's worker threads inherit this priority when the session is created
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        val best = prefs.getString("best", null)
        val config = best ?: configs.firstOrNull { prefs.getFloat("t_$it", -1f) < 0f }
            ?: configs.minBy { prefs.getFloat("t_$it", Float.MAX_VALUE) }.also { prefs.edit { putString("best", it) } }
        var t0 = System.nanoTime()
        val out = runCatching { val ses = sessionFor(config); t0 = System.nanoTime(); runWith(ses, input) }.getOrElse {
            // configuration not supported on this phone: mark it as very slow and use the plain CPU one
            prefs.edit { putFloat("t_$config", Float.MAX_VALUE / 2) }
            runWith(sessionFor("cpu4"), input)
        }
        if (best == null) prefs.edit { putFloat("t_$config", ((System.nanoTime() - t0) / 1e9).toFloat()) }
        return out
    }

    private fun runWith(s: OrtSession, input: FloatArray): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, 3072, 256)).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                val out = (res[0] as OnnxTensor).floatBuffer
                return FloatArray(out.remaining()).also { out.get(it) }
            }
        }
    }

    // ------------------------------------------------------------------ playback (own AudioTrack)

    private var track: AudioTrack? = null
    private var writer: Thread? = null
    @Volatile private var playFrom = 0
    @Volatile private var writePos = 0
    @Volatile private var stopFlag = false
    @Volatile var onEnded: (() -> Unit)? = null

    private fun playTotal() = curReady?.total ?: curSep?.total ?: 0

    fun positionSamples(): Int {
        val t = track ?: return playFrom
        return (playFrom + (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).toInt().coerceAtMost(playTotal())
    }
    fun positionMs(): Long = positionSamples().toLong() * 1000 / RATE

    /** Starts the HD backing track at [ms]; false if that part is not separated yet. */
    fun play(ms: Long): Boolean {
        val total = playTotal()
        val at = (ms * RATE / 1000).toInt().coerceIn(0, maxOf(0, total - 1))
        if (total == 0 || !readyAtMs(ms)) return false
        stopPlayback()
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minBuf * 2, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t; playFrom = at; writePos = at; stopFlag = false
        requestFocus()
        t.play()
        _active.value = true; _playing.value = true
        // keeps the app alive (and shows play/pause in the notifications) with Vibes in the background
        if (::ctx.isInitialized) KaraokeService.start(ctx)
        writer = Thread({
            val block = 2048
            val buf = ShortArray(block * 2)
            // soft start (~0.5 s): the switch from the quick filter to HD no longer cuts in
            val fadeTotal = RATE / 2; var faded = 0
            while (!stopFlag) {
                val ready = curReady; val sep = curSep
                val tot = ready?.total ?: sep?.total ?: 0
                if (tot == 0) { Thread.sleep(30); continue }
                if (writePos >= tot) {
                    Thread.sleep(400)
                    if (!stopFlag && positionSamples() >= tot - RATE / 10) { stopFlag = true; onEnded?.invoke() }
                    continue
                }
                val l: ShortBuffer; val r: ShortBuffer; val avail: Int
                if (ready != null) { l = ready.outL; r = ready.outR; avail = tot - writePos }
                else if (sep != null) { l = sep.outL; r = sep.outR; avail = sep.readyAhead(writePos) }
                else { Thread.sleep(30); continue }
                if (avail <= 0) { Thread.sleep(30); continue } // waiting for the model
                val n = minOf(block, avail)
                for (i in 0 until n) {
                    val g = if (faded < fadeTotal) (faded++).toFloat() / fadeTotal else 1f
                    buf[2 * i] = (l.get(writePos + i) * g).toInt().toShort(); buf[2 * i + 1] = (r.get(writePos + i) * g).toInt().toShort()
                }
                val w = t.write(buf, 0, n * 2)
                if (w > 0) writePos += w / 2 else Thread.sleep(10)
            }
        }, "karaoke-hd").apply { isDaemon = true; start() }
        return true
    }

    private var focusRequest: android.media.AudioFocusRequest? = null
    @Volatile private var pausedByFocus = false

    /** Other apps (YouTube, a call...) take the audio: pause like a normal player would. */
    private fun requestFocus() {
        if (!::ctx.isInitialized) return
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (_playing.value) { pausedByFocus = true; pause() }
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; resume() }
                }
            }.build()
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        runCatching { ctx.getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(req) }
        focusRequest = null
    }

    fun pause() { track?.pause(); _playing.value = false }
    fun resume() { if (track != null) { requestFocus(); track?.play(); _playing.value = true } }
    fun toggle() = if (_playing.value) pause() else resume()
    fun seekTo(ms: Long): Boolean = play(ms)

    fun stopPlayback() {
        stopFlag = true
        writer?.join(500); writer = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        _active.value = false; _playing.value = false
        pausedByFocus = false
        abandonFocus()
    }

    /** Title of the song shown in the karaoke notification. */
    fun currentTitle(): String? = (curReady?.song ?: curSep?.song)?.let { "${it.title} · ${it.artist}" }

    // ------------------------------------------------------------------ audio decode / AAC cache

    private data class Pcm(val l: ShortBuffer, val r: ShortBuffer, val n: Int)

    /** Any format Android can decode -> 16-bit stereo 44.1 kHz in native memory. */
    private fun decode(file: File): Pcm {
        val ex = MediaExtractor(); ex.setDataSource(file.absolutePath)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val durUs = runCatching { fmt.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(300_000_000L)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var cap = (durUs * rate / 1_000_000 + rate).toInt()
        var l = direct(cap); var r = direct(cap); var n = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    val sz = ex.readSampleData(ib, 0)
                    if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
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
                    if (n + frames > cap) {
                        cap = (n + frames) * 2
                        val nl = direct(cap); val nr = direct(cap)
                        for (i in 0 until n) { nl.put(i, l.get(i)); nr.put(i, r.get(i)) }
                        l = nl; r = nr
                    }
                    for (f in 0 until frames) {
                        val a = sb.get(f * channels)
                        l.put(n + f, a); r.put(n + f, if (channels > 1) sb.get(f * channels + 1) else a)
                    }
                    n += frames
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        if (rate == RATE) return Pcm(l, r, n)
        val m = (n.toLong() * RATE / rate).toInt()
        val rl = direct(m); val rr = direct(m)
        for (i in 0 until m) {
            val p = i.toDouble() * rate / RATE
            val j = p.toInt().coerceAtMost(n - 2); val f = p - j
            rl.put(i, (l.get(j) * (1 - f) + l.get(j + 1) * f).toInt().toShort())
            rr.put(i, (r.get(j) * (1 - f) + r.get(j + 1) * f).toInt().toShort())
        }
        return Pcm(rl, rr, m)
    }

    /** Stores a separated song as AAC 256 kbps in an .m4a (~6 MB for 3 minutes). */
    private fun encodeM4a(l: ShortBuffer, r: ShortBuffer, total: Int, out: File) {
        val tmp = File(out.path + ".part")
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
        val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIdx = -1; var muxing = false
        val info = MediaCodec.BufferInfo()
        var pos = 0; var inputDone = false; var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val ii = enc.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = enc.getInputBuffer(ii)!!.order(ByteOrder.LITTLE_ENDIAN)
                    ib.clear()
                    val frames = minOf(ib.capacity() / 4, total - pos)
                    if (frames <= 0) {
                        enc.queueInputBuffer(ii, 0, 0, pos * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                    } else {
                        for (i in 0 until frames) { ib.putShort(l.get(pos + i)); ib.putShort(r.get(pos + i)) }
                        enc.queueInputBuffer(ii, 0, frames * 4, pos * 1_000_000L / RATE, 0)
                        pos += frames
                    }
                }
            }
            val oi = enc.dequeueOutputBuffer(info, 10_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { trackIdx = mux.addTrack(enc.outputFormat); mux.start(); muxing = true }
                oi >= 0 -> {
                    val ob = enc.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) { ob.position(info.offset); ob.limit(info.offset + info.size); mux.writeSampleData(trackIdx, ob, info) }
                    enc.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        enc.stop(); enc.release()
        if (muxing) mux.stop()
        mux.release()
        tmp.renameTo(out)
    }

    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".m4a") }?.sortedByDescending { it.lastModified() } ?: return
        var sum = 0L
        files.forEach { f -> sum += f.length(); if (sum > CACHE_MAX_BYTES) f.delete() }
    }

    fun cacheBytes(): Long = cacheDir.listFiles { f -> f.name.endsWith(".m4a") }?.sumOf { it.length() } ?: 0L
    fun clearCache() { cacheDir.listFiles()?.forEach { it.delete() }; refreshCached() }
}
