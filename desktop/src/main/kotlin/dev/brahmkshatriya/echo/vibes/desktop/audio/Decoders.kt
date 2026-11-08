package dev.brahmkshatriya.echo.vibes.desktop.audio

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded audio: interleaved signed 16-bit samples. */
interface PcmSource : Closeable {
    val sampleRate: Int
    val channels: Int
    /** total length in ms, -1 if unknown */
    val durationMs: Long
    /** where this decoder actually starts (after a seek), in ms */
    val startMs: Long
    /** fills [buf] with interleaved samples, returns how many (always whole frames), -1 at the end */
    fun read(buf: ShortArray): Int
}

/** Opens any supported format (MP3, FLAC, AAC/M4A, WAV) at [positionMs]. */
object AudioDecoders {
    fun open(src: ByteSource, positionMs: Long, tmpDir: File): PcmSource {
        val head = src.open(0).use { it.readNBytes(12) }
        val dec = when {
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC" -> FlacPcm(src, 0)
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> AacMp4Pcm(src.toLocalFile(tmpDir))
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" -> WavPcm(src)
            head.size >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xF6) == 0xF0 -> AdtsPcm(src)
            else -> return Mp3Pcm.open(src, positionMs)
        }
        if (positionMs > 0) dec.skipTo(positionMs)
        return dec
    }

    /** Length in ms without playing (for the Studio / stories). */
    fun probeDurationMs(src: ByteSource, tmpDir: File): Long = runCatching { open(src, 0, tmpDir).use { it.durationMs } }.getOrDefault(-1)
}

/** Base for decoders that can only seek by decoding forward. */
abstract class ForwardPcm : PcmSource {
    final override var startMs: Long = 0; protected set
    private var skipSamples = 0L
    fun skipTo(ms: Long) {
        startMs = ms
        skipSamples = ms * sampleRate / 1000 * channels
        val tmp = ShortArray(8192)
        while (skipSamples > 0) {
            val n = decode(tmp)
            if (n < 0) break
            skipSamples -= n
        }
        skipSamples = 0
    }
    protected abstract fun decode(buf: ShortArray): Int
    override fun read(buf: ShortArray) = decode(buf)
}

// ------------------------------------------------------------------ MP3 (JLayer)

class Mp3Pcm private constructor(
    private val input: InputStream,
    override val durationMs: Long,
    override val startMs: Long,
    firstSkipMs: Long,
) : PcmSource {
    private val bitstream = javazoom.jl.decoder.Bitstream(BufferedInputStream(input, 64 * 1024))
    private val decoder = javazoom.jl.decoder.Decoder()
    override var sampleRate = 44100; private set
    override var channels = 2; private set
    private var pending: ShortArray = ShortArray(0)
    private var pendingPos = 0
    private var pendingLen = 0
    private var ended = false

    init {
        // decode-free frame skipping for the part before the target (cheap)
        var skipped = 0.0
        while (skipped < firstSkipMs) {
            val h = bitstream.readFrame() ?: break
            skipped += h.ms_per_frame()
            bitstream.closeFrame()
        }
        // first real frame fixes the format
        fill()
    }

    private fun fill(): Boolean {
        while (true) {
            val h = runCatching { bitstream.readFrame() }.getOrNull() ?: run { ended = true; return false }
            val out = runCatching { decoder.decodeFrame(h, bitstream) as javazoom.jl.decoder.SampleBuffer }.getOrNull()
            bitstream.closeFrame()
            if (out == null) continue
            sampleRate = out.sampleFrequency
            channels = out.channelCount
            pending = out.buffer; pendingLen = out.bufferLength; pendingPos = 0
            if (pendingLen > 0) return true
        }
    }

    override fun read(buf: ShortArray): Int {
        if (pendingPos >= pendingLen && (ended || !fill())) return -1
        val n = minOf(buf.size - buf.size % channels, pendingLen - pendingPos)
        System.arraycopy(pending, pendingPos, buf, 0, n)
        pendingPos += n
        return n
    }

    override fun close() { runCatching { bitstream.close() }; runCatching { input.close() } }

    companion object {
        /**
         * Opens at [positionMs]. Constant-bitrate files (all Deezer streams) jump straight to the right
         * byte; others skip frames from the start (still fast: no decoding).
         */
        fun open(src: ByteSource, positionMs: Long): Mp3Pcm {
            val len = src.length
            // probe: ID3 size, first header (bitrate), total length
            var dataStart = 0L
            var duration = -1L
            var bytesPerMs = 0.0
            src.open(0).use { raw ->
                val i = BufferedInputStream(raw, 64 * 1024)
                i.mark(10)
                val id3 = i.readNBytes(10)
                if (id3.size == 10 && String(id3, 0, 3, Charsets.ISO_8859_1) == "ID3") {
                    dataStart = 10L + ((id3[6].toLong() and 0x7F) shl 21 or ((id3[7].toLong() and 0x7F) shl 14) or
                        ((id3[8].toLong() and 0x7F) shl 7) or (id3[9].toLong() and 0x7F))
                }
                i.reset()
                val bs = javazoom.jl.decoder.Bitstream(i)
                val h = runCatching { bs.readFrame() }.getOrNull()
                if (h != null && len > 0) {
                    duration = h.total_ms((len - dataStart).toInt().coerceAtLeast(1)).toLong()
                    if (!h.vbr() && h.bitrate() > 0) bytesPerMs = h.bitrate() / 8000.0
                }
                runCatching { bs.close() }
            }
            if (positionMs > 0 && bytesPerMs > 0 && len > 0) {
                val off = (dataStart + positionMs * bytesPerMs).toLong().coerceAtMost(len - 1)
                return Mp3Pcm(src.open(off), duration, positionMs, 0)
            }
            return Mp3Pcm(src.open(0), duration, positionMs, positionMs)
        }
    }
}

// ------------------------------------------------------------------ FLAC (jFLAC)

class FlacPcm(src: ByteSource, startByte: Long) : ForwardPcm() {
    private val input = BufferedInputStream(src.open(startByte), 64 * 1024)
    private val dec = org.jflac.FLACDecoder(input)
    private val info = dec.readStreamInfo()
    override val sampleRate = info.sampleRate
    override val channels = info.channels
    override val durationMs = if (info.totalSamples > 0) info.totalSamples * 1000 / sampleRate else -1
    private val bps = info.bitsPerSample
    private var pcm = org.jflac.util.ByteData(0)
    private var pos = 0
    private var len = 0

    private fun next(): Boolean {
        while (true) {
            val f = runCatching { dec.readNextFrame() }.getOrNull() ?: return false
            pcm = dec.decodeFrame(f, pcm)
            pos = 0; len = pcm.len
            if (len > 0) return true
        }
    }

    override fun decode(buf: ShortArray): Int {
        val bytes = bps / 8
        if (pos >= len && !next()) return -1
        var n = 0
        val max = buf.size - buf.size % channels
        while (n < max && pos + bytes <= len) {
            val d = pcm.data
            buf[n++] = when (bytes) {
                1 -> ((d[pos].toInt() - 128) shl 8).toShort()
                2 -> ((d[pos].toInt() and 0xFF) or (d[pos + 1].toInt() shl 8)).toShort()
                else -> ((d[pos + 1].toInt() and 0xFF) or (d[pos + 2].toInt() shl 8)).toShort() // 24-bit: top 16
            }
            pos += bytes
        }
        return n
    }

    override fun close() { runCatching { input.close() } }
}

// ------------------------------------------------------------------ AAC (JAAD)

class AacMp4Pcm(file: File) : ForwardPcm() {
    private val raf = java.io.RandomAccessFile(file, "r")
    private val track: net.sourceforge.jaad.mp4.api.AudioTrack
    private val dec: net.sourceforge.jaad.aac.Decoder
    private val sb = net.sourceforge.jaad.aac.SampleBuffer().apply { setBigEndian(false) }
    override val sampleRate: Int
    override val channels: Int
    override val durationMs: Long
    private var buf = ShortArray(0); private var pos = 0; private var len = 0

    init {
        val movie = net.sourceforge.jaad.mp4.MP4Container(raf).movie
        track = movie.getTracks(net.sourceforge.jaad.mp4.api.Type.AUDIO).first() as net.sourceforge.jaad.mp4.api.AudioTrack
        dec = net.sourceforge.jaad.aac.Decoder(track.decoderSpecificInfo)
        sampleRate = track.sampleRate
        channels = track.channelCount.coerceIn(1, 2)
        durationMs = (movie.duration * 1000).toLong()
    }

    private fun next(): Boolean {
        while (track.hasMoreFrames()) {
            val f = runCatching { track.readNextFrame() }.getOrNull() ?: return false
            if (runCatching { dec.decodeFrame(f.data, sb) }.isFailure) continue
            val bb = ByteBuffer.wrap(sb.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val ch = sb.channels
            val frames = bb.remaining() / ch
            if (buf.size < frames * channels) buf = ShortArray(frames * channels)
            for (i in 0 until frames) {
                if (channels == 1) buf[i] = bb.get(i * ch)
                else { buf[2 * i] = bb.get(i * ch); buf[2 * i + 1] = bb.get(i * ch + if (ch > 1) 1 else 0) }
            }
            pos = 0; len = frames * channels
            if (len > 0) return true
        }
        return false
    }

    override fun decode(out: ShortArray): Int {
        if (pos >= len && !next()) return -1
        val n = minOf(out.size - out.size % channels, len - pos)
        System.arraycopy(buf, pos, out, 0, n); pos += n
        return n
    }

    override fun close() { runCatching { raf.close() } }
}

class AdtsPcm(src: ByteSource) : ForwardPcm() {
    private val input = BufferedInputStream(src.open(0))
    private val demux = net.sourceforge.jaad.adts.ADTSDemultiplexer(input)
    private val dec = net.sourceforge.jaad.aac.Decoder(demux.decoderSpecificInfo)
    private val sb = net.sourceforge.jaad.aac.SampleBuffer().apply { setBigEndian(false) }
    override val sampleRate = demux.sampleFrequency
    override val channels = demux.channelCount.coerceIn(1, 2)
    override val durationMs = -1L
    private var buf = ShortArray(0); private var pos = 0; private var len = 0

    private fun next(): Boolean {
        while (true) {
            val frame = runCatching { demux.readNextFrame() }.getOrNull() ?: return false
            if (runCatching { dec.decodeFrame(frame, sb) }.isFailure) continue
            val bb = ByteBuffer.wrap(sb.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val ch = sb.channels
            val frames = bb.remaining() / ch
            if (buf.size < frames * channels) buf = ShortArray(frames * channels)
            for (i in 0 until frames) {
                if (channels == 1) buf[i] = bb.get(i * ch)
                else { buf[2 * i] = bb.get(i * ch); buf[2 * i + 1] = bb.get(i * ch + if (ch > 1) 1 else 0) }
            }
            pos = 0; len = frames * channels
            if (len > 0) return true
        }
    }

    override fun decode(out: ShortArray): Int {
        if (pos >= len && !next()) return -1
        val n = minOf(out.size - out.size % channels, len - pos)
        System.arraycopy(buf, pos, out, 0, n); pos += n
        return n
    }

    override fun close() { runCatching { input.close() } }
}

// ------------------------------------------------------------------ WAV / PCM (Java Sound)

class WavPcm(src: ByteSource) : ForwardPcm() {
    private val ais = javax.sound.sampled.AudioSystem.getAudioInputStream(BufferedInputStream(src.open(0))).let { a ->
        val f = a.format
        val target = javax.sound.sampled.AudioFormat(f.sampleRate, 16, f.channels.coerceIn(1, 2), true, false)
        if (f.encoding == javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED && f.sampleSizeInBits == 16 && !f.isBigEndian) a
        else javax.sound.sampled.AudioSystem.getAudioInputStream(target, a)
    }
    override val sampleRate = ais.format.sampleRate.toInt()
    override val channels = ais.format.channels
    override val durationMs = if (ais.frameLength > 0) ais.frameLength * 1000 / sampleRate else -1
    private val bytes = ByteArray(16384)

    override fun decode(buf: ShortArray): Int {
        val want = minOf(bytes.size, (buf.size - buf.size % channels) * 2)
        val got = ais.read(bytes, 0, want)
        if (got <= 0) return -1
        val n = got / 2
        for (i in 0 until n) buf[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        return n - n % channels
    }

    override fun close() { runCatching { ais.close() } }
}
