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

/**
 * AAC in an MP4/M4A container (phone voice notes, uploads). A small demuxer of our own (JAAD's MP4
 * parser trips on the metadata boxes many encoders write) + JAAD's AAC decoder.
 */
class AacMp4Pcm(file: File) : ForwardPcm() {
    private val raf = java.io.RandomAccessFile(file, "r")
    private val mp4 = Mp4Audio.parse(raf)
    private val dec = net.sourceforge.jaad.aac.Decoder(mp4.asc)
    private val sb = net.sourceforge.jaad.aac.SampleBuffer().apply { setBigEndian(false) }
    override val sampleRate: Int
    override val channels: Int
    override val durationMs: Long = mp4.durationMs
    private var buf = ShortArray(0); private var pos = 0; private var len = 0
    private var next = 0

    init {
        // decode one frame to learn the real output format (HE-AAC doubles the rate)
        var rate = mp4.sampleRate; var ch = mp4.channels.coerceIn(1, 2)
        if (decodeInto()) { rate = sb.sampleRate; ch = sb.channels.coerceIn(1, 2) }
        sampleRate = rate; channels = ch
        next = 0; len = 0; pos = 0
    }

    private fun decodeInto(): Boolean {
        while (next < mp4.offsets.size) {
            val i = next++
            val frame = ByteArray(mp4.sizes[i])
            raf.seek(mp4.offsets[i]); raf.readFully(frame)
            if (runCatching { dec.decodeFrame(frame, sb) }.isSuccess) return true
        }
        return false
    }

    private fun fillNext(): Boolean {
        if (!decodeInto()) return false
        val bb = ByteBuffer.wrap(sb.data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val ch = sb.channels.coerceAtLeast(1)
        val frames = bb.remaining() / ch
        if (buf.size < frames * channels) buf = ShortArray(frames * channels)
        for (i in 0 until frames) {
            if (channels == 1) buf[i] = bb.get(i * ch)
            else { buf[2 * i] = bb.get(i * ch); buf[2 * i + 1] = bb.get(i * ch + if (ch > 1) 1 else 0) }
        }
        pos = 0; len = frames * channels
        return true
    }

    override fun decode(out: ShortArray): Int {
        while (pos >= len) if (!fillNext()) return -1
        val n = minOf(out.size - out.size % channels, len - pos)
        System.arraycopy(buf, pos, out, 0, n); pos += n
        return n
    }

    override fun close() { runCatching { raf.close() } }
}

/** Minimal MP4 reader: the first audio track's AAC config and where each frame is in the file. */
class Mp4Audio(val asc: ByteArray, val sampleRate: Int, val channels: Int, val durationMs: Long, val offsets: LongArray, val sizes: IntArray) {
    companion object {
        private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")

        fun parse(f: java.io.RandomAccessFile): Mp4Audio {
            var result: Mp4Audio? = null
            fun boxes(start: Long, end: Long, visit: (type: String, body: Long, size: Long) -> Unit) {
                var p = start
                while (p + 8 <= end) {
                    f.seek(p)
                    var size = f.readInt().toLong() and 0xFFFFFFFFL
                    val type = String(f.readFully(4), Charsets.ISO_8859_1)
                    var header = 8L
                    if (size == 1L) { size = f.readLong(); header = 16 }
                    if (size == 0L) size = end - p
                    if (size < header) break
                    visit(type, p + header, size - header)
                    p += size
                }
            }
            fun trak(start: Long, end: Long): Mp4Audio? {
                var handler = ""; var timescale = 0L; var duration = 0L
                var asc: ByteArray? = null; var rate = 44100; var ch = 2
                var sizes = IntArray(0); var fixedSize = 0; var count = 0
                var chunkOffsets = LongArray(0)
                val stsc = ArrayList<IntArray>()
                fun walk(s: Long, e: Long) {
                    boxes(s, e) { type, body, size ->
                        when (type) {
                            in containers -> walk(body, body + size)
                            "hdlr" -> { f.seek(body + 8); handler = String(f.readFully(4), Charsets.ISO_8859_1) }
                            "mdhd" -> {
                                f.seek(body); val v = f.readUnsignedByte(); f.skipBytes(3)
                                if (v == 1) { f.skipBytes(16); timescale = f.readInt().toLong() and 0xFFFFFFFFL; duration = f.readLong() }
                                else { f.skipBytes(8); timescale = f.readInt().toLong() and 0xFFFFFFFFL; duration = f.readInt().toLong() and 0xFFFFFFFFL }
                            }
                            "stsd" -> boxes(body + 8, body + size) { et, eb, es ->
                                if (et == "mp4a") {
                                    f.seek(eb + 16); ch = f.readUnsignedShort(); f.skipBytes(6); rate = f.readInt() ushr 16
                                    boxes(eb + 28, eb + es) { ct, cb, cs -> if (ct == "esds") asc = esds(f, cb, cs) }
                                }
                            }
                            "stsz" -> {
                                f.seek(body + 4); fixedSize = f.readInt(); count = f.readInt()
                                sizes = if (fixedSize == 0) IntArray(count) { f.readInt() } else IntArray(count) { fixedSize }
                            }
                            "stco" -> { f.seek(body + 4); val n = f.readInt(); chunkOffsets = LongArray(n) { f.readInt().toLong() and 0xFFFFFFFFL } }
                            "co64" -> { f.seek(body + 4); val n = f.readInt(); chunkOffsets = LongArray(n) { f.readLong() } }
                            "stsc" -> { f.seek(body + 4); val n = f.readInt(); repeat(n) { stsc += intArrayOf(f.readInt(), f.readInt(), f.readInt()) } }
                        }
                    }
                }
                walk(start, end)
                if (handler != "soun" || asc == null || sizes.isEmpty() || chunkOffsets.isEmpty()) return null
                // sample -> file offset
                val offsets = LongArray(sizes.size)
                var sample = 0
                for (c in chunkOffsets.indices) {
                    val entry = stsc.lastOrNull { it[0] <= c + 1 } ?: break
                    var off = chunkOffsets[c]
                    repeat(entry[1]) {
                        if (sample < sizes.size) { offsets[sample] = off; off += sizes[sample]; sample++ }
                    }
                }
                val n = sample
                return Mp4Audio(asc!!, rate, ch, if (timescale > 0) duration * 1000 / timescale else -1, offsets.copyOf(n), sizes.copyOf(n))
            }
            boxes(0, f.length()) { type, body, size ->
                if (type == "moov") boxes(body, body + size) { t, b, s -> if (t == "trak" && result == null) result = trak(b, b + s) }
            }
            return result ?: throw java.io.IOException("Nessuna traccia audio AAC nel file")
        }

        /** AudioSpecificConfig from an esds box. */
        private fun esds(f: java.io.RandomAccessFile, body: Long, size: Long): ByteArray? {
            f.seek(body + 4)
            val end = body + size
            fun len(): Int { var l = 0; repeat(4) { val b = f.readUnsignedByte(); l = (l shl 7) or (b and 0x7F); if (b and 0x80 == 0) return l }; return l }
            while (f.filePointer < end) {
                val tag = f.readUnsignedByte(); val l = len()
                when (tag) {
                    0x03 -> { f.skipBytes(2); val flags = f.readUnsignedByte()
                        if (flags and 0x80 != 0) f.skipBytes(2)
                        if (flags and 0x40 != 0) f.skipBytes(f.readUnsignedByte())
                        if (flags and 0x20 != 0) f.skipBytes(2) }
                    0x04 -> f.skipBytes(13)
                    0x05 -> return f.readFully(l)
                    else -> f.skipBytes(l)
                }
            }
            return null
        }
    }
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
