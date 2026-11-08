package android.media

import android.content.Context
import android.net.Uri
import dev.brahmkshatriya.echo.vibes.desktop.audio.AudioDecoders
import dev.brahmkshatriya.echo.vibes.desktop.audio.FileSource
import java.io.File
import java.io.InputStream

/** Reads the EXIF orientation of a JPEG (the only tag Vibes asks for). */
class ExifInterface(input: InputStream) {
    constructor(path: String) : this(File(path).inputStream())
    private val orientation: Int = runCatching { parse(input.use { it.readNBytes(256 * 1024) }) }.getOrDefault(ORIENTATION_NORMAL)

    fun getAttributeInt(tag: String, def: Int): Int = if (tag == TAG_ORIENTATION) orientation else def
    fun getAttribute(tag: String): String? = if (tag == TAG_ORIENTATION) orientation.toString() else null

    private fun parse(b: ByteArray): Int {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return ORIENTATION_NORMAL
        var i = 2
        while (i + 4 < b.size) {
            if (b[i] != 0xFF.toByte()) return ORIENTATION_NORMAL
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && String(b, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                val t = i + 10
                val le = b[t] == 'I'.code.toByte()
                fun u16(o: Int) = if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
                fun u32(o: Int) = if (le) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
                val ifd = t + u32(t + 4)
                val n = u16(ifd)
                for (k in 0 until n) {
                    val e = ifd + 2 + k * 12
                    if (u16(e) == 0x0112) return u16(e + 8)
                }
                return ORIENTATION_NORMAL
            }
            i += 2 + len
        }
        return ORIENTATION_NORMAL
    }

    companion object {
        const val TAG_ORIENTATION = "Orientation"
        const val ORIENTATION_NORMAL = 1
        const val ORIENTATION_ROTATE_180 = 3
        const val ORIENTATION_ROTATE_90 = 6
        const val ORIENTATION_ROTATE_270 = 8
        const val ORIENTATION_UNDEFINED = 0
    }
}

/** Title / duration of a picked audio (or video) file. */
class MediaMetadataRetriever {
    private var file: File? = null
    fun setDataSource(path: String) { file = File(path) }
    fun setDataSource(context: Context, uri: Uri) { file = File(uri.path ?: uri.toString()) }
    fun extractMetadata(key: Int): String? {
        val f = file ?: return null
        return when (key) {
            METADATA_KEY_DURATION -> AudioDecoders.probeDurationMs(FileSource(f), f.parentFile ?: File(".")).takeIf { it > 0 }?.toString()
            METADATA_KEY_TITLE -> null
            else -> null
        }
    }
    fun getFrameAtTime(us: Long): android.graphics.Bitmap? = null
    fun release() {}
    fun close() {}
    companion object {
        const val METADATA_KEY_TITLE = 7
        const val METADATA_KEY_ARTIST = 2
        const val METADATA_KEY_DURATION = 9
        const val METADATA_KEY_VIDEO_WIDTH = 18
        const val METADATA_KEY_VIDEO_HEIGHT = 19
        const val OPTION_CLOSEST_SYNC = 2
    }
}

/**
 * Voice notes from the PC microphone. Saved as WAV (16 kHz mono): phones play it like the m4a
 * the Android app records.
 */
class MediaRecorder(context: Context? = null) {
    private var out: String? = null
    private var line: javax.sound.sampled.TargetDataLine? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val fmt = javax.sound.sampled.AudioFormat(16000f, 16, 1, true, false)

    fun setAudioSource(s: Int) {}
    fun setOutputFormat(f: Int) {}
    fun setAudioEncoder(e: Int) {}
    fun setAudioEncodingBitRate(b: Int) {}
    fun setAudioSamplingRate(r: Int) {}
    fun setAudioChannels(c: Int) {}
    fun setMaxDuration(ms: Int) {}
    fun setOutputFile(path: String) { out = path }

    fun prepare() {
        val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine::class.java, fmt)
        line = (javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.TargetDataLine).apply { open(fmt) }
    }

    fun start() {
        val l = line ?: error("non preparato")
        val path = out ?: error("nessun file")
        running = true
        l.start()
        thread = Thread {
            val pcm = java.io.ByteArrayOutputStream()
            val buf = ByteArray(3200)
            while (running) { val n = l.read(buf, 0, buf.size); if (n > 0) pcm.write(buf, 0, n) }
            val bytes = pcm.toByteArray()
            val ais = javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(bytes), fmt, (bytes.size / 2).toLong())
            javax.sound.sampled.AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, File(path))
        }.apply { start() }
    }

    fun stop() { running = false; line?.stop(); thread?.join(3000) }
    fun release() { running = false; line?.close() }
    val maxAmplitude: Int get() = 0

    object AudioSource { const val MIC = 1 }
    object OutputFormat { const val MPEG_4 = 2; const val AAC_ADTS = 6 }
    object AudioEncoder { const val AAC = 3 }
}

/** Simple player (chat voice notes), on top of the desktop ExoPlayer. */
class MediaPlayer {
    private val p = androidx.media3.exoplayer.ExoPlayer.Builder(android.content.DesktopEnv.app).build()
    private var onDone: ((MediaPlayer) -> Unit)? = null
    init {
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) onDone?.invoke(this@MediaPlayer)
            }
        })
    }
    fun setDataSource(path: String) = p.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(File(path))))
    fun setDataSource(context: Context, uri: Uri) = p.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
    fun prepare() = p.prepare()
    fun prepareAsync() = p.prepare()
    fun start() { if (p.playbackState == androidx.media3.common.Player.STATE_ENDED) p.seekTo(0); p.play() }
    fun pause() = p.pause()
    fun stop() = p.stop()
    fun release() = p.release()
    fun reset() = p.clearMediaItems()
    fun seekTo(ms: Int) = p.seekTo(ms.toLong())
    val isPlaying get() = p.isPlaying
    val currentPosition get() = p.currentPosition.toInt()
    val duration get() = p.duration.coerceAtLeast(0).toInt()
    var isLooping: Boolean
        get() = p.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE
        set(v) { p.repeatMode = if (v) androidx.media3.common.Player.REPEAT_MODE_ONE else androidx.media3.common.Player.REPEAT_MODE_OFF }
    fun setVolume(l: Float, r: Float) { p.volume = l }
    fun setOnCompletionListener(l: (MediaPlayer) -> Unit) { onDone = l }
    fun setOnPreparedListener(l: (MediaPlayer) -> Unit) { l(this) }
    fun setOnErrorListener(l: (MediaPlayer, Int, Int) -> Boolean) {}
}

class AudioManager { companion object { const val AUDIOFOCUS_GAIN = 1; const val STREAM_MUSIC = 3 } }
