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

/** Network gate: the record library must return real, playable, public-domain records. */
class WebSamplesTest {
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()
    private val samples = WebSamples(http)
    private fun online() = runCatching { InetAddress.getByName("archive.org"); true }.getOrDefault(false)

    @Test
    fun `every category returns public domain records with audio`() = runBlocking {
        assumeTrue(online())
        val weak = mutableListOf<String>()
        for ((cat, _) in samples.categories) {
            val res = samples.search("", cat)
            println("samples $cat: ${res.size} (${res.take(2).map { it.name + " / " + it.pack }})")
            if (res.size < 8) weak += "$cat=${res.size}"
            assertTrue("$cat: years must be <= 1925", res.all { (it.year ?: 1900) <= 1925 })
            assertTrue("$cat: audio urls", res.all { it.url.startsWith("https://archive.org/download/") && it.url.endsWith(".mp3", true) })
            assertTrue("$cat: no drum-kit one-shots", res.none { Regex("(?i)\\b808\\b|kick|snare|hi-?hat").containsMatchIn(it.name) })
        }
        assertTrue("categories with too few records: $weak", weak.size <= 2)
    }

    @Test
    fun `free text search works and the audio actually downloads`() = runBlocking {
        assumeTrue(online())
        val res = samples.search("piano")
        assertTrue("piano results ${res.size}", res.size >= 5)
        val first = res.first()
        val code = http.newCall(Request.Builder().url(first.url).header("Range", "bytes=0-2047").build()).execute().use { it.code }
        println("samples download ${first.url} -> $code")
        assertTrue("download code $code", code == 200 || code == 206)
    }
}
