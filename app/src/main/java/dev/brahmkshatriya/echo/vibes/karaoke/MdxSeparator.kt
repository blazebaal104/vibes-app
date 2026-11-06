package dev.brahmkshatriya.echo.vibes.karaoke

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos

/**
 * MDX-Net chunking + STFT, ported 1:1 from the reference that was validated in CI against MUSDB
 * stems (.github/probe/mdx2.py: Inst_HQ_3 with n_fft 6144 / dim_f 3072 gave 14.2 dB SDR on the
 * instrumental, vs 2.3 dB for the untouched mix; the measured gain 1.023 matches UVR's
 * compensate 1.022 for this model).
 *
 * - STFT like torch.stft(center = true, reflect padding, periodic Hann), hop 1024.
 * - Model input [1, 4, dimF, dimT] = L.re, L.im, R.re, R.im of the first dimF bins.
 * - Chunks of hop*(dimT-1) samples; n_fft/2 is trimmed on both sides of every chunk, so chunks
 *   advance by gen = chunk - n_fft and the output is seamless.
 * [model] maps the input tensor (flattened, channel-major) to the output tensor of the same shape.
 */
class MdxSeparator(
    val nFft: Int = 6144,
    val dimF: Int = 3072,
    val dimT: Int = 256,
    val hop: Int = 1024,
    private val compensate: Float = 1.022f,
    private val model: (FloatArray) -> FloatArray,
) {
    val chunk = hop * (dimT - 1)
    val trim = nFft / 2
    /** Output samples produced by each model run. */
    val gen = chunk - 2 * trim
    private val window = FloatArray(nFft) { (0.5 - 0.5 * cos(2.0 * PI * it / nFft)).toFloat() }
    private val fft = FloatFFT_1D(nFft.toLong())
    private val bins = nFft / 2 + 1

    /** First original sample covered by the window of the run that outputs from [outStart]. */
    fun windowStart(outStart: Int) = outStart - trim

    /** How many model runs a song of [samples] needs. */
    fun runsFor(samples: Int) = (samples + gen - 1) / gen

    /**
     * Separates output samples [outStart, outStart + gen) of the song (clipped to its length).
     * [left]/[right] are the whole song; returns the instrumental for that span.
     */
    fun process(left: FloatArray, right: FloatArray, outStart: Int): Pair<FloatArray, FloatArray> {
        val total = left.size
        // the chunk covers original samples [outStart - trim, outStart - trim + chunk); outside = 0
        val wl = FloatArray(chunk); val wr = FloatArray(chunk)
        val from = outStart - trim
        for (i in 0 until chunk) {
            val j = from + i
            if (j in 0 until total) { wl[i] = left[j]; wr[i] = right[j] }
        }
        return processWindow(wl, wr, minOf(gen, total - outStart).coerceAtLeast(0))
    }

    /** Same as [process] with the chunk window already cut ([chunk] samples, see [windowStart]). */
    fun processWindow(wl: FloatArray, wr: FloatArray, n: Int): Pair<FloatArray, FloatArray> {
        val input = FloatArray(4 * dimF * dimT)
        stft(wl, input, 0); stft(wr, input, 2)
        val out = model(input)
        val yl = istft(out, 0); val yr = istft(out, 2)
        val rl = FloatArray(n) { yl[trim + it] * compensate }
        val rr = FloatArray(n) { yr[trim + it] * compensate }
        return rl to rr
    }

    /** STFT of one channel into input channels [ch] (re) and [ch+1] (im). */
    private fun stft(x: FloatArray, dst: FloatArray, ch: Int) {
        val pad = nFft / 2
        val n = x.size
        val frame = FloatArray(nFft)
        val planeRe = ch * dimF * dimT
        val planeIm = (ch + 1) * dimF * dimT
        for (t in 0 until dimT) {
            val start = t * hop - pad
            for (i in 0 until nFft) {
                var j = start + i
                // numpy/torch "reflect": mirror without repeating the edge sample
                if (j < 0) j = -j
                if (j >= n) j = 2 * n - 2 - j
                frame[i] = x[j.coerceIn(0, n - 1)] * window[i]
            }
            fft.realForward(frame)
            // packed layout: [0]=Re0, [1]=Re(n/2), [2k]=Re k, [2k+1]=Im k
            for (f in 0 until dimF) {
                val re: Float; val im: Float
                if (f == 0) { re = frame[0]; im = 0f } else { re = frame[2 * f]; im = frame[2 * f + 1] }
                dst[planeRe + f * dimT + t] = re
                dst[planeIm + f * dimT + t] = im
            }
        }
    }

    /** Inverse STFT of output channels [ch]/[ch+1] (bins >= dimF are zero), chunk samples long. */
    private fun istft(y: FloatArray, ch: Int): FloatArray {
        val pad = nFft / 2
        val outLen = chunk + nFft
        val acc = FloatArray(outLen)
        val norm = FloatArray(outLen)
        val frame = FloatArray(nFft)
        val planeRe = ch * dimF * dimT
        val planeIm = (ch + 1) * dimF * dimT
        for (t in 0 until dimT) {
            java.util.Arrays.fill(frame, 0f)
            for (f in 0 until minOf(dimF, bins - 1)) {
                val re = y[planeRe + f * dimT + t]; val im = y[planeIm + f * dimT + t]
                if (f == 0) frame[0] = re else { frame[2 * f] = re; frame[2 * f + 1] = im }
            }
            // frame[1] (Nyquist) stays 0: dimF < bins
            fft.realInverse(frame, true)
            val off = t * hop
            for (i in 0 until nFft) {
                val w = window[i]
                acc[off + i] += frame[i] * w
                norm[off + i] += w * w
            }
        }
        val res = FloatArray(chunk)
        for (i in 0 until chunk) {
            val k = i + pad
            res[i] = if (norm[k] > 1e-8f) acc[k] / norm[k] else 0f
        }
        return res
    }
}
