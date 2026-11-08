package dev.brahmkshatriya.echo.vibes.desktop.audio

import java.nio.ByteBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * The sound card (Java Sound line, 44.1 kHz stereo 16-bit). Also answers "which moment of the song
 * is coming out of the speakers right now", accounting for the line's buffer: each written block
 * is tagged with its content position, and the played frame counter is mapped back through them.
 */
class AudioOut {
    companion object {
        const val RATE = 44100
        private val FORMAT = AudioFormat(RATE.toFloat(), 16, 2, true, false)
        /** ~120 ms device buffer: responsive pause/seek, no crackles */
        private const val BUFFER_BYTES = RATE * 4 / 8
    }

    private var line: SourceDataLine? = null
    private val bytes = ByteArray(16384)

    // ring of (lineFrameEnd, contentMsEnd, speed) for blocks written
    private val markFrame = LongArray(512)
    private val markMs = DoubleArray(512)
    private val markSpeed = FloatArray(512)
    private var markHead = 0
    private var markCount = 0
    private var written = 0L          // frames written to the current line
    private var lineStartPlayed = 0L  // line frame position when it was (re)opened/flushed
    @Volatile private var lastContentMs = -1.0
    private val lock = Any()

    private fun ensure(): SourceDataLine {
        line?.let { return it }
        val l = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, FORMAT)) as SourceDataLine
        l.open(FORMAT, BUFFER_BYTES)
        l.start()
        line = l
        synchronized(lock) { written = l.longFramePosition; lineStartPlayed = l.longFramePosition; markCount = 0 }
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
            var off = 0
            while (off < n) off += l.write(bytes, off, n - off)
            len -= n
        }
        synchronized(lock) {
            written = l.longFramePosition.coerceAtLeast(0) + l.bufferSize / 4 - l.available() / 4
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
            val played = l.longFramePosition
            if (played <= boundaryFrame) return boundaryMs
            // first block whose end is after the played position
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
