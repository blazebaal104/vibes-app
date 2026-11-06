package dev.brahmkshatriya.echo.vibes

import dev.brahmkshatriya.echo.vibes.karaoke.MdxSeparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * With an identity "model" the MDX pipeline (STFT -> chunks -> trim -> ISTFT) must give back the
 * input: this proves the port of the CI-validated reference has no off-by-one in padding, framing,
 * trimming or chunk stitching. Only content above dimF bins (here: nothing) could be lost.
 */
class MdxSeparatorTest {
    @Test
    fun `identity model reconstructs the song across chunk boundaries`() {
        val sep = MdxSeparator(compensate = 1f) { it.copyOf() }
        val n = sep.gen * 2 + 12_345 // three runs, last one partial
        val l = FloatArray(n) { (0.3 * sin(2 * PI * 440 * it / 44100) + 0.1 * sin(2 * PI * 3000 * it / 44100)).toFloat() }
        val r = FloatArray(n) { (0.2 * sin(2 * PI * 220 * it / 44100 + 1.0)).toFloat() }
        val outL = FloatArray(n); val outR = FloatArray(n)
        var pos = 0
        assertEquals(3, sep.runsFor(n))
        while (pos < n) {
            val (a, b) = sep.process(l, r, pos)
            a.copyInto(outL, pos); b.copyInto(outR, pos)
            pos += sep.gen
        }
        var maxErr = 0f
        for (i in 0 until n) maxErr = maxOf(maxErr, abs(outL[i] - l[i]), abs(outR[i] - r[i]))
        println("MDX identity max error: $maxErr")
        assertTrue("reconstruction error $maxErr", maxErr < 1e-3f)
    }

    @Test
    fun `model output is used and compensated`() {
        // a "model" that zeroes the right channel's planes: right must come out silent
        val sep = MdxSeparator(compensate = 1.022f) { x ->
            x.copyOf().also { y -> java.util.Arrays.fill(y, 2 * 3072 * 256, 4 * 3072 * 256, 0f) }
        }
        val n = sep.gen
        val s = FloatArray(n) { (0.3 * sin(2 * PI * 440 * it / 44100)).toFloat() }
        val (a, b) = sep.process(s, s, 0)
        assertTrue(b.all { abs(it) < 1e-4f })
        assertEquals(s[5000] * 1.022f, a[5000], 2e-3f)
    }
}
