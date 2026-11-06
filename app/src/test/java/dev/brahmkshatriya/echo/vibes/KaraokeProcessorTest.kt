package dev.brahmkshatriya.echo.vibes

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.brahmkshatriya.echo.playback.renderer.Karaoke
import dev.brahmkshatriya.echo.playback.renderer.KaraokeProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** Synthetic signals: a centred "voice" must drop, panned instruments and the bass must survive. */
class KaraokeProcessorTest {
    private val rate = 44100

    @After fun off() { Karaoke.enabled = false }

    /** Runs [seconds] of audio through the processor, returns RMS of L and R over the last half. */
    private fun run(enabled: Boolean, seconds: Double = 1.0, sample: (Int) -> Pair<Double, Double>): Pair<Double, Double> {
        Karaoke.enabled = enabled; Karaoke.strength = 1f
        val p = KaraokeProcessor()
        p.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val total = (rate * seconds).toInt()
        val outL = ArrayList<Double>(); val outR = ArrayList<Double>()
        var i = 0
        while (i < total) {
            val n = minOf(1024, total - i)
            val buf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            repeat(n) { k -> val (l, r) = sample(i + k); buf.putShort((l * 32767).toInt().toShort()); buf.putShort((r * 32767).toInt().toShort()) }
            buf.flip(); p.queueInput(buf); i += n
            val o = p.output.order(ByteOrder.nativeOrder())
            while (o.remaining() >= 4) { outL += o.short / 32768.0; outR += o.short / 32768.0 }
        }
        fun rms(x: List<Double>) = sqrt(x.drop(x.size / 2).sumOf { it * it } / (x.size - x.size / 2))
        return rms(outL) to rms(outR)
    }

    private fun sine(f: Double, i: Int, a: Double = 0.3) = a * sin(2 * PI * f * i / rate)

    @Test fun `passthrough when off is bit exact in level`() {
        val (l, r) = run(false) { val s = sine(440.0, it); s to s }
        assertEquals(0.3 / sqrt(2.0), l, 0.01); assertEquals(0.3 / sqrt(2.0), r, 0.01)
    }

    @Test fun `centred voice-band tone is strongly reduced`() {
        val dry = run(false) { val s = sine(440.0, it) + sine(1200.0, it, 0.15); s to s }.first
        val wet = run(true) { val s = sine(440.0, it) + sine(1200.0, it, 0.15); s to s }.first
        println("karaoke centred: dry=$dry wet=$wet")
        assertTrue("centre must drop by > 12 dB (got ${wet / dry})", wet < dry / 4)
    }

    @Test fun `hard panned instrument is kept`() {
        val (l, r) = run(true) { sine(660.0, it) to 0.0 }
        println("karaoke panned: L=$l R=$r")
        assertTrue("panned guitar must stay (got $l)", l > 0.8 * 0.3 / sqrt(2.0))
    }

    @Test fun `centred bass and kick are kept`() {
        val (l, _) = run(true) { val s = sine(55.0, it); s to s }
        println("karaoke bass: $l")
        assertTrue("bass must stay (got $l)", l > 0.85 * 0.3 / sqrt(2.0))
    }

    @Test fun `mixed song - centre removed, sides kept`() {
        // voice centred at 300 Hz, guitar 70% left at 1000 Hz, synth 70% right at 2500 Hz
        val wet = run(true) { val v = sine(300.0, it); val g = sine(1000.0, it, 0.2); val sy = sine(2500.0, it, 0.2)
            (v + g * 0.85 + sy * 0.15) to (v + g * 0.15 + sy * 0.85) }
        val dry = run(false) { val v = sine(300.0, it); val g = sine(1000.0, it, 0.2); val sy = sine(2500.0, it, 0.2)
            (v + g * 0.85 + sy * 0.15) to (v + g * 0.15 + sy * 0.85) }
        println("karaoke mix: dry=$dry wet=$wet")
        assertTrue(wet.first < dry.first && wet.first > 0.05)
    }
}
