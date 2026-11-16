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

/**
 * Real network test against Apple Music: the release is published only if this passes in CI, so
 * a broken Apple import never reaches users (lesson from the YouTube Music format change).
 */
class AppleImportTest {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val importer = Importer(DeezerPublic(http), { error("library not needed") }, http)
    private fun online() = runCatching { InetAddress.getByName("music.apple.com"); true }.getOrDefault(false)

    @Test
    fun `detects Apple Music and iTunes links`() {
        listOf(
            "https://music.apple.com/it/playlist/successi-del-momento/pl.f4d106fed2bd41149aaacabb233eb5eb",
            "https://music.apple.com/us/playlist/my-mix/pl.u-AkAmPlyUxXxXxX",
            "https://music.apple.com/it/album/1440841363",
            "https://itunes.apple.com/it/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb",
            "guarda https://music.apple.com/it/album/views/1440841363?l=en",
        ).forEach { assertEquals(it, "Apple Music", importer.detect(it)) }
    }

    @Test
    fun `reads an Apple Music playlist with name, cover and artists`() = runBlocking {
        assumeTrue(online())
        val src = importer.fromApple("https://music.apple.com/it/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb")
        val items = src.items!!
        println("Apple test: '${src.name}' ${items.size} songs, cover=${src.cover}")
        assertTrue("songs found (${items.size})", items.size >= 20)
        assertTrue(items.all { it.title.isNotBlank() && it.artist.isNotBlank() })
        assertTrue(items.map { it.artist }.toSet().size > 5) // real artists, not one placeholder
        assertTrue(src.name.isNotBlank() && !src.name.contains("Apple Music"))
        assertNotNull(src.cover)
    }

    @Test
    fun `reads an Apple Music album and an itunes link`() = runBlocking {
        assumeTrue(online())
        val album = importer.fromApple("https://music.apple.com/it/album/1440841363")
        assertTrue("album tracks (${album.items!!.size})", album.items!!.size >= 10)
        val itunes = importer.fromApple("https://itunes.apple.com/it/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb")
        assertTrue(itunes.items!!.size >= 20)
    }
}
