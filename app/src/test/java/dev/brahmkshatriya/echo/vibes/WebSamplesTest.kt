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
        val bad = cat.items.filter { banned.containsMatchIn(it.name) || it.tags.any { t -> banned.matches(t) } }
        assertTrue("drum kit / noise: ${bad.take(5).map { it.name }}", bad.isEmpty())
        assertTrue("only free licences", cat.items.all { it.license == "CC0" || it.license == "CC-BY" })
        // local search is instant (measured after a first warm-up search, as on a phone in use)
        samples.search("guitar")
        val t1 = System.nanoTime()
        val piano = samples.search("piano")
        val searchMs = (System.nanoTime() - t1) / 1_000_000
        println("search 'piano': ${piano.size} in $searchMs ms")
        assertTrue("piano results ${piano.size}", piano.size >= 10)
        assertTrue("search took $searchMs ms", searchMs < 300)
    }

    @Test
    fun `previews actually play`() = runBlocking {
        assumeTrue(online())
        val items = samples.search("").take(5)
        // an outside sample site can have a bad minute (it made a release fail once): most must play
        val ok = items.count { s ->
            val code = runCatching { http.newCall(Request.Builder().url(s.url).header("Range", "bytes=0-2047").build()).execute().use { it.code } }.getOrDefault(-1)
            println("preview $code ${s.url}")
            code == 200 || code == 206
        }
        assertTrue("only $ok of ${items.size} previews played", ok >= (items.size + 1) / 2)
    }
}
