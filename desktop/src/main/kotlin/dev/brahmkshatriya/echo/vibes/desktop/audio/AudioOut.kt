package dev.brahmkshatriya.echo.vibes.desktop.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/** Where PCM goes: the sound card, or a clock when the PC has no audio device. */
interface PcmSink {
    val framePosition: Long
    val bufferFrames: Int
    val queuedFrames: Int
    val isRunning: Boolean
    fun write(b: ByteArray, off: Int, len: Int): Int
    fun start(); fun stop(); fun flush(); fun drain(); fun close()
}

private class JavaSoundSink(private val l: SourceDataLine) : PcmSink {
    override val framePosition get() = l.longFramePosition
    override val bufferFrames get() = l.bufferSize / 4
    override val queuedFrames get() = (l.bufferSize - l.available()) / 4
    override val isRunning get() = l.isRunning
    override fun write(b: ByteArray, off: Int, len: Int) = l.write(b, off, len)
    override fun start() = l.start()
    override fun stop() = l.stop()
    override fun flush() = l.flush()
    override fun drain() = l.drain()
    override fun close() = l.close()
}

/** No sound device: frames are "played" at real-time speed so the app behaves the same. */
private class ClockSink(private val rate: Int, override val bufferFrames: Int) : PcmSink {
    private var played = 0L
    private var queued = 0L
    private var since = System.nanoTime()
    @Volatile override var isRunning = false; private set
    @Synchronized private fun advance() {
        val now = System.nanoTime()
        if (isRunning) {
            val n = minOf(queued, (now - since) * rate / 1_000_000_000L)
            played += n; queued -= n
            since += n * 1_000_000_000L / rate
        } else since = now
    }
    override val framePosition: Long get() { advance(); return played }
    override val queuedFrames: Int get() { advance(); return queued.toInt() }
    override fun write(b: ByteArray, off: Int, len: Int): Int {
        if (!isRunning) start()
        while (true) { advance(); if (queued < bufferFrames) break; Thread.sleep(5) }
        synchronized(this) { queued += len / 4 }
        return len
    }
    override fun start() { advance(); isRunning = true; synchronized(this) { since = System.nanoTime() } }
    override fun stop() { advance(); isRunning = false }
    @Synchronized override fun flush() { queued = 0 }
    override fun drain() { while (queuedFrames > 0 && isRunning) Thread.sleep(5) }
    override fun close() {}
}

/**
 * The sound card (Java Sound line, 44.1 kHz stereo 16-bit). Also answers "which moment of the song
 * is coming out of the speakers right now", accounting for the line's buffer: each written block
 * is tagged with its content position, and the played frame counter is mapped back through them.
 * VIBES_AUDIO_DUMP=<file>: also writes everything played to a WAV file (tests).
 */
class AudioOut {
    companion object {
        const val RATE = 44100
        private val FORMAT = AudioFormat(RATE.toFloat(), 16, 2, true, false)
        /** ~120 ms device buffer: responsive pause/seek, no crackles */
        private const val BUFFER_BYTES = RATE * 4 / 8
        private val dump: RandomAccessFile? by lazy {
            System.getenv("VIBES_AUDIO_DUMP")?.let { p ->
                RandomAccessFile(File(p), "rw").apply { setLength(0); write(ByteArray(44)) }.also { f ->
                    Runtime.getRuntime().addShutdownHook(Thread { synchronized(f) { runCatching { finishWav(f) } } })
                }
            }
        }

        private fun finishWav(f: RandomAccessFile) {
            val data = f.length() - 44
            val h = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + data).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray())
                .putInt(16).putShort(1).putShort(2).putInt(RATE).putInt(RATE * 4).putShort(4).putShort(16)
                .put("data".toByteArray()).putInt(data.toInt())
            f.seek(0); f.write(h.array()); f.close()
        }
    }

    private var line: PcmSink? = null
    private val bytes = ByteArray(16384)

    // ring of (lineFrameEnd, contentMsEnd, speed) for blocks written
    private val markFrame = LongArray(512)
    private val markMs = DoubleArray(512)
    private val markSpeed = FloatArray(512)
    private var markHead = 0
    private var markCount = 0
    private var written = 0L          // frames written so far (line position + queued)
    @Volatile private var lastContentMs = -1.0
    private val lock = Any()

    private fun ensure(): PcmSink {
        line?.let { return it }
        val l: PcmSink = runCatching {
            val sl = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, FORMAT)) as SourceDataLine
            sl.open(FORMAT, BUFFER_BYTES)
            sl.start()
            println("vibes-audio: output ${AudioSystem.getMixerInfo().joinToString { it.name }}")
            JavaSoundSink(sl)
        }.getOrElse {
            println("vibes-audio: no sound device (${it.message}), playing on a clock")
            ClockSink(RATE, BUFFER_BYTES / 4).apply { start() }
        }
        line = l
        synchronized(lock) { written = l.framePosition; markCount = 0 }
        return l
    }

    private var boundaryFrame = -1L
    private var boundaryMs = 0.0

    /**
     * A new item starts at [contentMs]. keepLatency: the previous song is still in the device buffer
     * (gapless / crossfade), so until those frames are played the position reads as the new start.
     */
    fun markItemStart(contentMs: Double, keepLatency: Boolean = false) {
        synchronized(lock) {
            lastContentMs = contentMs
            if (!keepLatency) { markCount = 0; boundaryFrame = -1 }
            else { boundaryFrame = written; boundaryMs = contentMs }
        }
    }

    fun write(data: ByteBuffer, contentMsAfter: Double, speed: Float) {
        val l = ensure()
        if (!l.isRunning) l.start()
        var len = data.remaining()
        while (len > 0) {
            val n = minOf(len, bytes.size)
            data.get(bytes, 0, n)
            dump?.let { f -> synchronized(f) { f.write(bytes, 0, n) } }
            var off = 0
            while (off < n) off += l.write(bytes, off, n - off)
            len -= n
        }
        synchronized(lock) {
            written = l.framePosition + l.queuedFrames
            val i = (markHead + markCount) % markFrame.size
            markFrame[i] = written; markMs[i] = contentMsAfter; markSpeed[i] = speed
            if (markCount < markFrame.size) markCount++ else markHead = (markHead + 1) % markFrame.size
        }
    }

    /** content position (ms) of what is audible now; -1 if nothing written yet */
    fun playedContentMs(): Double {
        val l = line ?: return lastContentMs
        synchronized(lock) {
            if (markCount == 0) return lastContentMs
            val played = l.framePosition
            if (played <= boundaryFrame) return boundaryMs
            for (k in 0 until markCount) {
                val i = (markHead + k) % markFrame.size
                if (markFrame[i] >= played) {
                    return markMs[i] - (markFrame[i] - played) * 1000.0 / RATE * markSpeed[i]
                }
            }
            val last = (markHead + markCount - 1) % markFrame.size
            return markMs[last]
        }
    }

    fun pause() { line?.let { if (it.isRunning) it.stop() } }

    fun flush() {
        line?.let { it.stop(); it.flush() }
        synchronized(lock) { markCount = 0; lastContentMs = -1.0; boundaryFrame = -1 }
    }

    fun drain() { runCatching { line?.drain() } }

    fun close() { line?.close(); line = null }
}
