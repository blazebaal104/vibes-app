package dev.brahmkshatriya.echo.playback.renderer

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Global switch for the karaoke (vocal removal) filter, read on the audio thread. */
object Karaoke {
    /** true = remove the lead vocal. */
    @Volatile var enabled = false
    /** 0..1, how much of the centred vocal is taken out (UI slider "Voce"). */
    @Volatile var strength = 0.9f
    /**
     * 0..1 level of the normal player, applied here (not the player's volume, which the user sees).
     * PC karaoke keeps the song playing silently under the HD backing track and fades between the two.
     */
    @Volatile var duck = 1f
}

/**
 * Real-time vocal remover ("karaoke"), stereo 16-bit PCM.
 *
 * Not the classic L-R trick (which also kills bass, kick and snare and turns the song mono). It works
 * in the frequency domain (STFT, 2048 points, 75 % overlap, sqrt-Hann analysis/synthesis):
 *  - for every frequency bin it measures how "centred" the sound is: the left and right spectra must
 *    have similar magnitude AND the same phase. A lead vocal is mixed dead centre, so it scores ~1;
 *    stereo guitars, pads, reverb and wide backing vocals score low and are left alone;
 *  - only the voice band is processed (about 120 Hz - 9 kHz, with soft edges): bass and kick under
 *    it and hi-hats/air above it pass untouched, so the beat keeps its punch;
 *  - the centred part of each bin is estimated as mask * (L+R)/2 and subtracted from BOTH channels,
 *    so the stereo image of everything else survives;
 *  - the mask is sharpened (power curve) and smoothed in time per bin (fast attack, slower release),
 *    which avoids the "underwater"/musical-noise artefacts of a raw per-frame mask.
 * Latency when on: one frame (~46 ms at 44.1 kHz). When off it is a plain passthrough.
 */
@OptIn(UnstableApi::class)
class KaraokeProcessor : BaseAudioProcessor() {
    private companion object {
        const val N = 2048
        const val HOP = N / 4
        const val BINS = N / 2 + 1
    }

    private var channels = 0
    private var sampleRate = 44100
    private var running = false // STFT pipeline active (karaoke on at the last frame boundary)

    private val window = FloatArray(N) { sqrt(0.5f - 0.5f * cos(2.0 * PI * it / N).toFloat()) }
    private val inL = FloatArray(N); private val inR = FloatArray(N)
    private val olaL = FloatArray(N); private val olaR = FloatArray(N)
    private var fill = 0
    private val reL = FloatArray(N); private val imL = FloatArray(N)
    private val reR = FloatArray(N); private val imR = FloatArray(N)
    private val maskSmooth = FloatArray(BINS)
    private val bandWeight = FloatArray(BINS)
    private var fadeIn = 0f // wet amount ramp when switching on, avoids a click

    // FFT tables
    private val cosT = FloatArray(N / 2) { cos(-2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(-2.0 * PI * it / N).toFloat() }
    private val rev = IntArray(N).also { r ->
        val bits = Integer.numberOfTrailingZeros(N)
        for (i in 0 until N) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2)
            return AudioProcessor.AudioFormat.NOT_SET // mono / other formats: inactive
        channels = 2
        sampleRate = inputAudioFormat.sampleRate
        val binHz = sampleRate.toFloat() / N
        for (k in 0 until BINS) {
            val f = k * binHz
            // soft band edges: 90->160 Hz fade in, 8k->11k Hz fade out
            val low = ((f - 90f) / 70f).coerceIn(0f, 1f)
            val high = ((11000f - f) / 3000f).coerceIn(0f, 1f)
            bandWeight[k] = smooth(low) * smooth(high)
        }
        return inputAudioFormat
    }

    private fun smooth(x: Float) = x * x * (3 - 2 * x)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / 4
        if (frames == 0) return
        val want = Karaoke.enabled
        if (!running && !want) { // passthrough
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer); out.flip(); applyDuck(out); return
        }
        if (!running && want) { resetState(); running = true; fadeIn = 0f }
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        // every completed hop produces HOP output frames
        val maxOut = ((fill + frames) / HOP) * HOP
        val out = replaceOutputBuffer(maxOut * 4).order(ByteOrder.nativeOrder())
        repeat(frames) {
            val l = src.short / 32768f
            val r = src.short / 32768f
            inL[N - HOP + fill] = l; inR[N - HOP + fill] = r
            fill++
            if (fill == HOP) {
                processFrame()
                for (i in 0 until HOP) {
                    out.putShort(toPcm(olaL[i] * 0.5f)); out.putShort(toPcm(olaR[i] * 0.5f))
                }
                System.arraycopy(olaL, HOP, olaL, 0, N - HOP); java.util.Arrays.fill(olaL, N - HOP, N, 0f)
                System.arraycopy(olaR, HOP, olaR, 0, N - HOP); java.util.Arrays.fill(olaR, N - HOP, N, 0f)
                System.arraycopy(inL, HOP, inL, 0, N - HOP)
                System.arraycopy(inR, HOP, inR, 0, N - HOP)
                fill = 0
                // switched off: drop back to passthrough at a frame boundary once faded out
                if (!Karaoke.enabled && fadeIn <= 0f) running = false
            }
        }
        out.flip()
        applyDuck(out)
        // anything left in the input after `running` turned false is emitted untouched next call
    }

    private var lastDuck = 1f

    /** Scales the block by [Karaoke.duck], ramping from the previous level (no zipper clicks). */
    private fun applyDuck(buf: ByteBuffer) {
        val target = Karaoke.duck.coerceIn(0f, 1f)
        val from = lastDuck
        lastDuck = target
        if (from >= 0.999f && target >= 0.999f) return
        val b = buf.duplicate().order(ByteOrder.nativeOrder())
        val n = b.remaining() / 2
        if (n == 0) return
        val start = b.position()
        for (i in 0 until n) {
            val g = from + (target - from) * (i.toFloat() / n)
            val at = start + i * 2
            b.putShort(at, (b.getShort(at) * g).toInt().toShort())
        }
    }

    private fun toPcm(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private fun processFrame() {
        for (i in 0 until N) {
            val w = window[i]
            reL[i] = inL[i] * w; imL[i] = 0f
            reR[i] = inR[i] * w; imR[i] = 0f
        }
        fft(reL, imL, false); fft(reR, imR, false)

        val target = if (Karaoke.enabled) 1f else 0f
        fadeIn += (target - fadeIn) * 0.15f
        if (target == 0f && fadeIn < 0.01f) fadeIn = 0f
        val amount = Karaoke.strength.coerceIn(0f, 1f) * fadeIn

        for (k in 0 until BINS) {
            val bw = bandWeight[k]
            var m = 0f
            if (bw > 0f && amount > 0f) {
                val lr = reL[k]; val li = imL[k]; val rr = reR[k]; val ri = imR[k]
                val eL = lr * lr + li * li
                val eR = rr * rr + ri * ri
                val crossRe = lr * rr + li * ri
                val crossIm = li * rr - lr * ri
                val crossMag = sqrt(crossRe * crossRe + crossIm * crossIm)
                val sum = eL + eR + 1e-12f
                val similarity = 2f * crossMag / sum // 1 = equal magnitude
                val phase = if (crossMag > 1e-12f) max(0f, crossRe / crossMag) else 0f // 1 = in phase
                var c = similarity * phase
                c = c * c * c * c // sharpen: only really centred bins
                m = c
            }
            // per-bin temporal smoothing: fast attack, slower release
            val prev = maskSmooth[k]
            val sm = if (m > prev) prev + (m - prev) * 0.6f else prev + (m - prev) * 0.25f
            maskSmooth[k] = sm
            val g = sm * bw * amount
            if (g > 0f) {
                val cr = 0.5f * (reL[k] + reR[k]) * g
                val ci = 0.5f * (imL[k] + imR[k]) * g
                reL[k] -= cr; imL[k] -= ci
                reR[k] -= cr; imR[k] -= ci
            }
            // mirror for the real inverse transform
            if (k in 1 until N / 2) {
                reL[N - k] = reL[k]; imL[N - k] = -imL[k]
                reR[N - k] = reR[k]; imR[N - k] = -imR[k]
            }
        }
        fft(reL, imL, true); fft(reR, imR, true)
        // small make-up gain for the removed energy
        val makeUp = 1f + 0.15f * amount
        for (i in 0 until N) {
            val w = window[i] * makeUp
            olaL[i] += reL[i] * w
            olaR[i] += reR[i] * w
        }
    }

    /** In-place radix-2 complex FFT; inverse is scaled by 1/N. */
    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until N) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= N) {
            val half = size / 2
            val step = N / size
            var start = 0
            while (start < N) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) -sinT[k] else sinT[k]
                    val l = j + half
                    val tr = wr * re[l] - wi * im[l]
                    val ti = wr * im[l] + wi * re[l]
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        if (inverse) {
            val s = 1f / N
            for (i in 0 until N) { re[i] *= s; im[i] *= s }
        }
    }

    private fun resetState() {
        java.util.Arrays.fill(inL, 0f); java.util.Arrays.fill(inR, 0f)
        java.util.Arrays.fill(olaL, 0f); java.util.Arrays.fill(olaR, 0f)
        java.util.Arrays.fill(maskSmooth, 0f)
        fill = 0
    }

    override fun onFlush() { resetState(); running = false }
    override fun onReset() { resetState(); running = false; channels = 0 }
}
