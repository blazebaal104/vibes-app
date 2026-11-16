package dev.brahmkshatriya.echo.vibes.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.desktop.audio.AudioDecoders
import dev.brahmkshatriya.echo.vibes.desktop.audio.FileSource
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
import java.nio.FloatBuffer
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.coroutines.coroutineContext

/**
 * Karaoke HD on PC: same engine as the phone (UVR MDX-Net "Inst_HQ_3", downloaded once, run with ONNX
 * Runtime), same priorities (the song on screen from the play position, then the next one of the
 * queue, then playlists to prepare). A PC is several times faster than a phone, so most songs are
 * ready within seconds. Separated songs are kept on disk (raw 16-bit, up to ~1.5 GB) and start at once.
 * Same API as the phone's KaraokeHD, which the karaoke screen uses.
 */
object KaraokeHD {
    private const val MODEL_NAME = "UVR-MDX-NET-Inst_HQ_3.onnx"
    private const val MODEL_URL =
        "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx"
    private const val MODEL_MIN_BYTES = 60_000_000L
    const val RATE = 44100
    private const val CACHE_MAX_BYTES = 1536L * 1024 * 1024

    sealed interface Phase {
        data object Idle : Phase
        data object NeedsModel : Phase
        data class DownloadingModel(val progress: Float) : Phase
        data class Fetching(val progress: Float) : Phase
        data class Separating(val progress: Float) : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

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
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _cached = MutableStateFlow<Set<String>>(emptySet())
    val cached: StateFlow<Set<String>> = _cached

    fun statusLine(): Triple<String, String, Float> {
        when (val p = _phase.value) {
            is Phase.DownloadingModel -> return Triple("Scarico il karaoke HD", "${(p.progress * 100).toInt()}%", p.progress)
            is Phase.Fetching -> return Triple("Karaoke HD", "Preparo la canzone…", 0f)
            is Phase.Separating -> return Triple("Karaoke HD: tolgo la voce", "${curSep?.song?.title ?: ""} · ${(p.progress * 100).toInt()}%", p.progress)
            else -> {}
        }
        val b = _background.value
        return if (b != null) Triple("Karaoke HD: preparo in anticipo", "${b.title ?: ""} · ${(b.progress * 100).toInt()}%", b.progress)
        else Triple("Karaoke HD", "Pronto", 0f)
    }

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context
        refreshCached()
    }
    private fun ensureInit() { if (!::ctx.isInitialized) init(android.content.DesktopEnv.app) }

    private val modelFile get() = File(ctx.filesDir, "models/$MODEL_NAME")
    fun hasModel(): Boolean { ensureInit(); return modelFile.exists() && modelFile.length() > MODEL_MIN_BYTES }
    private val cacheDir get() = File(ctx.filesDir, "karaoke").apply { mkdirs() }
    private fun cacheFile(k: String) = File(cacheDir, "${k.replace(':', '_')}.pcm")
    private fun refreshCached() {
        _cached.value = cacheDir.listFiles { f -> f.name.endsWith(".pcm") }
            ?.map { it.name.removeSuffix(".pcm").replaceFirst('_', ':') }?.toSet().orEmpty()
    }

    // ------------------------------------------------------------------ per-song state

    private class Sep(val song: SongRef, val sep: MdxSeparator, val inL: ShortArray, val inR: ShortArray, val total: Int) {
        val outL = ShortArray(total); val outR = ShortArray(total)
        val chunks = (total + sep.gen - 1) / sep.gen
        val done = BooleanArray(chunks)
        @Volatile var doneCount = 0
        val complete get() = doneCount == chunks
        fun chunkOf(sample: Int) = (sample / sep.gen).coerceIn(0, chunks - 1)
        fun readyAhead(at: Int): Int {
            var c = chunkOf(at)
            while (c < chunks && done[c]) c++
            return (minOf(c * sep.gen, total) - at).coerceAtLeast(0)
        }
        fun undoneFrom(at: Int) = (chunkOf(at) until chunks).count { !done[it] }
    }

    private class Ready(val song: SongRef, val outL: ShortArray, val outR: ShortArray, val total: Int)

    @Volatile private var curKey: String? = null
    @Volatile private var curSep: Sep? = null
    @Volatile private var curReady: Ready? = null
    @Volatile private var bgSep: Sep? = null
    @Volatile var nextInQueue: SongRef? = null
    private val playlistQueue = ArrayDeque<SongRef>()
    private var batchSize = 0; private var batchDone = 0
    @Volatile private var samplesPerSecond = 0.0
    @Volatile var externalPositionMs: Long = 0
    private var worker: Job? = null
    private var loadJob: Job? = null

    fun open(song: SongRef, positionMs: Long) {
        ensureInit()
        if (curKey == song.key && _phase.value !is Phase.Failed) return
        stopPlayback()
        curKey = song.key; curReady = null
        externalPositionMs = positionMs
        val bg = bgSep
        curSep = if (bg?.song?.key == song.key) bg.also { bgSep = null } else null
        if (!hasModel()) { _phase.value = Phase.NeedsModel; return }
        loadJob?.cancel()
        _busy.value = true
        loadJob = scope.launch {
            runCatching {
                val cached = cacheFile(song.key)
                when {
                    curSep != null -> _phase.value = if (curSep!!.complete) Phase.Ready else Phase.Separating(curSep!!.doneCount.toFloat() / curSep!!.chunks)
                    cached.exists() -> {
                        _phase.value = Phase.Fetching(1f)
                        val (l, r, n) = readPcm(cached)
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
                    _phase.value = Phase.Failed(friendly(e)); report("apertura ${song.title}", e)
                }
                if (worker?.isActive != true) _busy.value = false
            }
        }
    }

    fun retry(song: SongRef, positionMs: Long) { curKey = null; curSep = null; curReady = null; open(song, positionMs) }

    private fun friendly(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "memoria piena"
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "niente connessione"
        is UnsatisfiedLinkError -> "motore AI non supportato su questo PC"
        else -> e.message?.take(80) ?: "errore"
    }

    private fun report(what: String, e: Throwable) {
        println("karaoke HD: $what: $e")
        runCatching { dev.brahmkshatriya.echo.vibes.diag.ErrorReporter.report("karaoke", "HD PC: $what", e) }
    }

    fun close(): Long? {
        val pos = if (_active.value) positionMs() else null
        stopPlayback()
        loadJob?.cancel()
        val s = curSep
        if (s != null && !s.complete && bgSep == null) bgSep = s
        curSep = null; curReady = null; curKey = null; nextInQueue = null
        _phase.value = Phase.Idle
        return pos
    }

    fun preparePlaylist(songs: List<SongRef>) {
        ensureInit()
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
        _busy.value = true
        worker = scope.launch {
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            while (isActive) {
                val did = runCatching { step() }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    report("preparazione in anticipo", e)
                    _background.value = null; bgSep = null
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

    private suspend fun step(): Boolean {
        if (!hasModel()) return false
        curSep?.let { s ->
            val at = if (_active.value) positionSamples() else (externalPositionMs * RATE / 1000).toInt()
            val forward = (s.chunkOf(at) until s.chunks).firstOrNull { !s.done[it] }
            val c = forward ?: (0 until s.chunks).firstOrNull { !s.done[it] }
            if (c != null) {
                try { runChunk(s, c) } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    report("separazione ${s.song.title} (pezzo $c)", e)
                    if (curSep === s) { curSep = null; _phase.value = Phase.Failed(friendly(e)) }
                    return true
                }
                if (curSep === s) _phase.value = if (s.complete) Phase.Ready else Phase.Separating(s.doneCount.toFloat() / s.chunks)
                if (s.complete) finish(s)
                return true
            }
        }
        val next = nextInQueue?.takeIf { it.key !in _cached.value && it.key != curKey }
        var bg = bgSep
        if (next != null && bg != null && bg.song.key != next.key) {
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
            if (j in 0 until s.total) { wl[i] = s.inL[j] / 32768f; wr[i] = s.inR[j] / 32768f }
        }
        val n = minOf(sep.gen, s.total - outStart)
        val t0 = System.nanoTime()
        val (a, b) = sep.processWindow(wl, wr, n)
        val secs = (System.nanoTime() - t0) / 1e9
        for (i in 0 until n) {
            s.outL[outStart + i] = (a[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            s.outR[outStart + i] = (b[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        s.done[c] = true; s.doneCount++
        val sps = n / secs
        samplesPerSecond = if (samplesPerSecond == 0.0) sps else samplesPerSecond * 0.7 + sps * 0.3
    }

    private fun finish(s: Sep) {
        runCatching { writePcm(s.outL, s.outR, s.total, cacheFile(s.song.key)); trimCache(); refreshCached() }
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

    // ------------------------------------------------------------------ model

    fun downloadModel(song: SongRef, positionMs: Long) {
        ensureInit()
        loadJob?.cancel()
        _busy.value = true
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
                dst.delete(); tmp.renameTo(dst)
            }.onSuccess { curKey = null; open(song, positionMs) }
                .onFailure { e ->
                    if (e !is kotlinx.coroutines.CancellationException) _phase.value = Phase.Failed(e.message ?: "Errore")
                    if (worker?.isActive != true) _busy.value = false
                }
        }
    }

    private var session: OrtSession? = null

    private fun session(): OrtSession {
        session?.let { return it }
        val env = OrtEnvironment.getEnvironment()
        // half the cores: fast, and the music and the app stay smooth
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
        }
        return env.createSession(modelFile.absolutePath, opts).also { session = it }
    }

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

    // ------------------------------------------------------------------ playback (own audio line)

    private var line: SourceDataLine? = null
    private var writer: Thread? = null
    @Volatile private var playFrom = 0
    @Volatile private var writePos = 0
    @Volatile private var stopFlag = false
    @Volatile var onEnded: (() -> Unit)? = null

    private fun playTotal() = curReady?.total ?: curSep?.total ?: 0

    fun positionSamples(): Int {
        val l = line ?: return playFrom
        return (playFrom + l.longFramePosition).toInt().coerceAtMost(playTotal())
    }
    fun positionMs(): Long = positionSamples().toLong() * 1000 / RATE

    fun play(ms: Long): Boolean {
        val total = playTotal()
        val at = (ms * RATE / 1000).toInt().coerceIn(0, maxOf(0, total - 1))
        if (total == 0 || !readyAtMs(ms)) return false
        stopPlayback()
        val fmt = AudioFormat(RATE.toFloat(), 16, 2, true, false)
        val l = AudioSystem.getSourceDataLine(fmt)
        l.open(fmt, RATE * 4 / 5) // ~200 ms buffer
        line = l; playFrom = at; writePos = at; stopFlag = false
        l.start()
        _active.value = true; _playing.value = true
        writer = Thread({
            val block = 2048
            val bytes = ByteArray(block * 4)
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
                if (!_playing.value) { Thread.sleep(30); continue }
                val ls: ShortArray; val rs: ShortArray; val avail: Int
                if (ready != null) { ls = ready.outL; rs = ready.outR; avail = tot - writePos }
                else if (sep != null) { ls = sep.outL; rs = sep.outR; avail = sep.readyAhead(writePos) }
                else { Thread.sleep(30); continue }
                if (avail <= 0) { Thread.sleep(30); continue }
                val n = minOf(block, avail)
                for (i in 0 until n) {
                    val g = if (faded < fadeTotal) (faded++).toFloat() / fadeTotal else 1f
                    val a = (ls[writePos + i] * g).toInt(); val b = (rs[writePos + i] * g).toInt()
                    bytes[4 * i] = a.toByte(); bytes[4 * i + 1] = (a shr 8).toByte()
                    bytes[4 * i + 2] = b.toByte(); bytes[4 * i + 3] = (b shr 8).toByte()
                }
                val w = l.write(bytes, 0, n * 4)
                if (w > 0) writePos += w / 4 else Thread.sleep(10)
            }
        }, "karaoke-hd").apply { isDaemon = true; start() }
        return true
    }

    fun pause() { line?.stop(); _playing.value = false }
    fun resume() { if (line != null) { line?.start(); _playing.value = true } }
    fun toggle() = if (_playing.value) pause() else resume()
    fun seekTo(ms: Long): Boolean = play(ms)

    fun stopPlayback() {
        stopFlag = true
        writer?.join(500); writer = null
        line?.let { runCatching { it.stop(); it.flush(); it.close() } }
        line = null
        _active.value = false; _playing.value = false
    }

    fun currentTitle(): String? = (curReady?.song ?: curSep?.song)?.let { "${it.title} · ${it.artist}" }

    // ------------------------------------------------------------------ audio in / cache

    private data class Pcm(val l: ShortArray, val r: ShortArray, val n: Int)

    /** Any format the PC player reads -> 16-bit stereo 44.1 kHz. */
    private fun decode(file: File): Pcm {
        AudioDecoders.open(FileSource(file), 0, ctx.cacheDir).use { d ->
            val ch = d.channels.coerceAtLeast(1)
            var cap = ((d.durationMs.takeIf { it > 0 } ?: 300_000L) * d.sampleRate / 1000 + d.sampleRate).toInt()
            var l = ShortArray(cap); var r = ShortArray(cap); var n = 0
            val buf = ShortArray(8192 * ch)
            while (true) {
                val got = d.read(buf); if (got < 0) break
                val frames = got / ch
                if (n + frames > cap) { cap = (n + frames) * 2; l = l.copyOf(cap); r = r.copyOf(cap) }
                for (f in 0 until frames) { val a = buf[f * ch]; l[n + f] = a; r[n + f] = if (ch > 1) buf[f * ch + 1] else a }
                n += frames
            }
            val rate = d.sampleRate
            if (rate == RATE) return Pcm(l, r, n)
            val m = (n.toLong() * RATE / rate).toInt()
            val rl = ShortArray(m); val rr = ShortArray(m)
            for (i in 0 until m) {
                val p = i.toDouble() * rate / RATE
                val j = p.toInt().coerceAtMost(n - 2); val fr = p - j
                rl[i] = (l[j] * (1 - fr) + l[j + 1] * fr).toInt().toShort()
                rr[i] = (r[j] * (1 - fr) + r[j + 1] * fr).toInt().toShort()
            }
            return Pcm(rl, rr, m)
        }
    }

    private fun writePcm(l: ShortArray, r: ShortArray, total: Int, out: File) {
        val tmp = File(out.path + ".part")
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 1 shl 20)).use { o ->
            o.writeInt(total)
            val bb = java.nio.ByteBuffer.allocate(total * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until total) { bb.putShort(l[i]); bb.putShort(r[i]) }
            o.write(bb.array())
        }
        out.delete(); tmp.renameTo(out)
    }

    private fun readPcm(f: File): Pcm {
        java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream(), 1 shl 20)).use { i ->
            val n = i.readInt()
            val bytes = ByteArray(n * 4); i.readFully(bytes)
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val l = ShortArray(n); val r = ShortArray(n)
            for (k in 0 until n) { l[k] = bb.short; r[k] = bb.short }
            return Pcm(l, r, n)
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".pcm") }?.sortedByDescending { it.lastModified() } ?: return
        var sum = 0L
        files.forEach { f -> sum += f.length(); if (sum > CACHE_MAX_BYTES) f.delete() }
    }

    fun cacheBytes(): Long { ensureInit(); return cacheDir.listFiles { f -> f.name.endsWith(".pcm") }?.sumOf { it.length() } ?: 0L }
    fun clearCache() { ensureInit(); cacheDir.listFiles()?.forEach { it.delete() }; refreshCached() }
}
