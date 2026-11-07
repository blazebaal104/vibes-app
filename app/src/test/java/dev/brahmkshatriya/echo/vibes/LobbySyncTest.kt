package dev.brahmkshatriya.echo.vibes

import dev.brahmkshatriya.echo.vibes.backend.Supa
import dev.brahmkshatriya.echo.vibes.listen.LobbySync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

class LobbySyncTest {

    @Test
    fun `offset picks the tightest round trip`() {
        // true offset: server is 5000 ms ahead; network delay asymmetric and noisy
        val rnd = Random(7)
        val samples = (1..8).map {
            val t0 = 1_000_000L + it * 500
            val up = 20L + rnd.nextLong(0, 120); val down = 20L + rnd.nextLong(0, 120)
            LobbySync.Sample(t0, t0 + up + 5000, t0 + up + down)
        }
        val off = LobbySync.offset(samples)!!
        val bound = LobbySync.offsetError(samples)!!
        println("offset $off (true 5000), bound $bound")
        assertTrue(abs(off - 5000) <= bound)
    }

    @Test
    fun `everyone computes the same position, across song boundaries`() {
        val d = listOf(180_000L, 200_000L, 150_000L)
        // song 0 started at server time 10_000
        assertEquals(LobbySync.Position(0, 5_000, false), LobbySync.expected(0, 0, 10_000, true, 15_000, d))
        assertEquals(LobbySync.Position(1, 20_000, false), LobbySync.expected(0, 0, 10_000, true, 10_000 + 200_000, d))
        assertEquals(2, LobbySync.expected(0, 0, 10_000, true, 10_000 + 400_000, d).index)
        assertTrue(LobbySync.expected(0, 0, 10_000, true, 10_000 + 600_000, d).ended)
        // start scheduled 1.5 s in the future: negative position = wait
        assertEquals(-1500, LobbySync.expected(0, 0, 11_500, true, 10_000, d).posMs)
        // paused: frozen
        assertEquals(42_000, LobbySync.expected(1, 42_000, 0, false, 999_999, d).posMs)
    }

    @Test
    fun `corrections are gentle for small errors and a seek for big ones`() {
        assertEquals(LobbySync.Fix.None, LobbySync.fix(15, 10_000))
        val slow = LobbySync.fix(80, 10_000) as LobbySync.Fix.Speed
        assertTrue("ahead -> slower", slow.speed < 1f && slow.speed >= 0.96f)
        val fast = LobbySync.fix(-200, 10_000) as LobbySync.Fix.Speed
        assertTrue("behind -> faster", fast.speed > 1f && fast.speed <= 1.04f)
        assertEquals(LobbySync.Fix.Seek(10_150), LobbySync.fix(900, 10_000, seekLatency = 150))
    }

    @Test
    fun `simulated phones converge under 100 ms`() {
        // a phone starting 230 ms late with jittery position readings gets within 30 ms in < 12 s
        var err = -230.0
        val rnd = Random(3)
        var t = 0
        val window = ArrayDeque<Long>()
        while (t < 12_000) {
            window.addLast((err + rnd.nextInt(-15, 16)).toLong()); if (window.size > 5) window.removeFirst()
            val f = if (window.size >= 3) LobbySync.fix(LobbySync.median(window.toList()), 0) else LobbySync.Fix.None
            val speed = when (f) { is LobbySync.Fix.Speed -> f.speed.toDouble(); is LobbySync.Fix.Seek -> { err = 0.0; 1.0 }; else -> 1.0 }
            err += (speed - 1.0) * 100 // 100 ms tick
            t += 100
        }
        println("simulated final error $err ms")
        assertTrue("final error $err", abs(err) < 30)
    }

    @Test
    fun `real server clock can be measured precisely`() {
        assumeTrue(runCatching { InetAddress.getByName(Supa.URL.removePrefix("https://")); true }.getOrDefault(false))
        val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        val samples = (1..8).mapNotNull {
            val t0 = System.currentTimeMillis()
            val body = runCatching {
                http.newCall(Request.Builder().url("${Supa.URL}/rest/v1/rpc/vibes_now")
                    .header("apikey", Supa.KEY).header("Authorization", "Bearer ${Supa.KEY}")
                    .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { it.body.string() }
            }.getOrNull() ?: return@mapNotNull null
            val t1 = System.currentTimeMillis()
            body.trim().toLongOrNull()?.let { LobbySync.Sample(t0, it, t1) }
        }
        assumeTrue("server reachable", samples.isNotEmpty())
        val bound = LobbySync.offsetError(samples)!!
        println("vibes_now from CI: ${samples.size} samples, best rtt ${bound * 2} ms, offset error <= $bound ms")
        // CI runners are in the US, the server in Europe: only check that it is measurable at all
        assertTrue("clock error bound $bound ms", bound < 500)
    }
}
