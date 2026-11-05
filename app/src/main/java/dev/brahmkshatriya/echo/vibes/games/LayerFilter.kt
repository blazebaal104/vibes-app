package dev.brahmkshatriya.echo.vibes.games

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-pass filter (two cascaded biquads, 24 dB/oct) on 16-bit PCM. With a low cutoff you only hear
 * kick and bass; raising it "adds" the other instruments one layer at a time. cutoff = 0 → off.
 */
class LayerFilter : BaseAudioProcessor() {
    @Volatile var cutoffHz: Float = 0f
    private var rate = 44100
    private var channels = 2
    private var appliedCutoff = -1f
    private val b = DoubleArray(3); private val a = DoubleArray(3)
    // state[channel][stage][x1,x2,y1,y2]
    private var state = Array(2) { Array(2) { DoubleArray(4) } }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        rate = inputAudioFormat.sampleRate; channels = inputAudioFormat.channelCount
        state = Array(channels) { Array(2) { DoubleArray(4) } }
        appliedCutoff = -1f
        return inputAudioFormat
    }

    private fun coefficients(fc: Float) {
        val w0 = 2 * PI * fc / rate
        val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
        val c = cos(w0)
        val a0 = 1 + alpha
        b[0] = (1 - c) / 2 / a0; b[1] = (1 - c) / a0; b[2] = (1 - c) / 2 / a0
        a[1] = -2 * c / a0; a[2] = (1 - alpha) / a0
        appliedCutoff = fc
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val fc = cutoffHz
        if (fc <= 0f || fc >= rate / 2f) { out.put(inputBuffer); out.flip(); return }
        if (fc != appliedCutoff) coefficients(fc)
        val inShorts = inputBuffer.order(ByteOrder.nativeOrder())
        var ch = 0
        while (inShorts.remaining() >= 2) {
            var x = inShorts.short.toDouble()
            for (st in 0 until 2) {
                val s = state[ch][st]
                val y = b[0] * x + b[1] * s[0] + b[2] * s[1] - a[1] * s[2] - a[2] * s[3]
                s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y
                x = y
            }
            out.putShort(x.coerceIn(-32768.0, 32767.0).toInt().toShort())
            ch = (ch + 1) % channels
        }
        out.flip()
    }

    override fun onReset() { state = Array(channels) { Array(2) { DoubleArray(4) } } }
}

/** ExoPlayer with [LayerFilter] in its audio chain. */
fun layeredPlayer(context: Context, filter: LayerFilter): ExoPlayer {
    val factory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(filter)).build()
    }
    return ExoPlayer.Builder(context, factory).build()
}
