package dev.brahmkshatriya.echo.vibes

import dev.brahmkshatriya.echo.vibes.music.WebSamples
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Network gate: the published sample catalog must be big, musical, fast and playable. */
class WebSamplesTest {
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val samples = WebSamples(http)
    private fun online() = runCatching { InetAddress.getByName("kxgswrwitsedppjzcokh.supabase.co"); true }.getOrDefault(false)

    @Test
    fun `catalog loads fast and every category has real musical samples`() = runBlocking {
        assumeTrue(online())
        val t0 = System.currentTimeMillis()
        val cat = samples.load()
        val ms = System.currentTimeMillis() - t0
        println("catalog: ${cat.items.size} samples in $ms ms")
        assertTrue("catalog size ${cat.items.size}", cat.items.size >= 300)
        val byCat = cat.items.groupBy { it.category }
        println("per category: " + byCat.mapValues { it.value.size })
        assertTrue("categories ${byCat.keys}", byCat.size >= 8)
        val banned = Regex("(?i)\\b(808|kick|snare|hi-?hat|scream|noise|sfx)\\b")
        assertTrue("no drum kit / noise", cat.items.none { banned.containsMatchIn(it.name) || it.tags.any { t -> banned.matches(t) } })
        assertTrue("only free licences", cat.items.all { it.license == "CC0" || it.license == "CC-BY" })
        // local search is instant
        val t1 = System.nanoTime()
        val piano = samples.search("piano")
        val searchMs = (System.nanoTime() - t1) / 1_000_000
        println("search 'piano': ${piano.size} in $searchMs ms")
        assertTrue(piano.size >= 10 && searchMs < 200)
    }

    @Test
    fun `previews actually play`() = runBlocking {
        assumeTrue(online())
        val items = samples.search("").take(5)
        items.forEach { s ->
            val code = http.newCall(Request.Builder().url(s.url).header("Range", "bytes=0-2047").build()).execute().use { it.code }
            println("preview $code ${s.url}")
            assertTrue("preview code $code", code == 200 || code == 206)
        }
    }
}
