package dev.brahmkshatriya.echo.vibes

import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.Importer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Real network test against YouTube Music (CI has internet; skipped where YouTube is unreachable). */
class YouTubeImportTest {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val importer = Importer(DeezerPublic(http), { error("library not needed") }, http)

    private fun online() = runCatching { InetAddress.getByName("music.youtube.com"); true }.getOrDefault(false)

    @Test
    fun `detects every YouTube playlist link shape`() {
        listOf(
            "https://music.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI&si=abc",
            "https://youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            "https://www.youtube.com/watch?v=xyz&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            "https://music.youtube.com/browse/VLPLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
        ).forEach { assertNotNull(it, importer.detect(it)) }
        assertEquals("YouTube Music", importer.detect("guarda qui https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"))
    }

    @Test
    fun `reads a long public playlist with name, cover and every page`() = runBlocking {
        assumeTrue(online())
        val src = importer.fromYouTube("https://music.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI")
        val items = src.items!!
        println("YT test: '${src.name}' ${items.size} songs, cover=${src.cover}, first=${items.take(3)}")
        assertTrue("more than one page (100) of songs", items.size > 100)
        assertTrue(src.name.isNotBlank() && src.name != "Da YouTube Music")
        assertNotNull(src.cover)
        assertTrue(items.all { it.title.isNotBlank() && it.artist.isNotBlank() })
        assertTrue("no (Official Video) noise", items.none { it.title.contains("Official", ignoreCase = true) })
    }

    @Test
    fun `reads a YouTube Music editorial playlist`() = runBlocking {
        assumeTrue(online())
        val src = importer.fromYouTube("https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs")
        println("YT test: '${src.name}' ${src.items!!.size} songs, first=${src.items!!.take(3)}")
        assertTrue(src.items!!.size >= 20)
    }
}
