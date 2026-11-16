package dev.brahmkshatriya.echo.vibes

import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.Importer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** The ways to bring a long playlist (offline): copied Spotify songs, written lists, CSV and Spotify's JSON export. */
class ImportFormatsTest {
    private val http = OkHttpClient()
    private val imp = Importer(DeezerPublic(http), { error("no library") }, http)

    @Test
    fun `recognises copied songs and lists`() {
        val copied = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT\nhttps://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"
        assertEquals("Brani Spotify", imp.detect(copied))
        assertEquals("Lista", imp.detect("1. Queen - Bohemian Rhapsody\n2. Sfera Ebbasta – Tran Tran\nThe Weeknd - Blinding Lights"))
        assertEquals("Spotify", imp.detect("https://open.spotify.com/playlist/37i9dQZF1DX4UtSsGT1Sbe"))
        val list = imp.fromList("Queen - Bohemian Rhapsody\nThe Weeknd - Blinding Lights").items!!
        assertEquals("Bohemian Rhapsody", list[0].title); assertEquals("Queen", list[0].artist)
    }

    @Test
    fun `reads Exportify CSV with quotes and commas`() {
        val csv = "\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Track Duration (ms)\",\"ISRC\"\n" +
            "\"spotify:track:1\",\"Under Pressure - Remastered 2011\",\"Queen, David Bowie\",\"Hot Space\",\"248440\",\"GBUM71029605\"\n" +
            "\"spotify:track:2\",\"Hello, Goodbye\",\"The Beatles\",\"Magical\",\"210000\",\"GBAYE0601642\"\n"
        val src = imp.fromFile("La mia playlist.csv", csv)
        assertEquals(1, src.size); assertEquals("La mia playlist", src[0].name)
        val w = src[0].items!!
        assertEquals(2, w.size)
        assertEquals("Hello, Goodbye", w[1].title); assertEquals("Queen, David Bowie", w[0].artist)
        assertEquals(248440L, w[0].durationMs); assertEquals("GBUM71029605", w[0].isrc)
    }

    @Test
    fun `reads Spotify data download JSON with several playlists`() {
        val json = """{"playlists":[{"name":"Estate","items":[{"track":{"trackName":"Tran Tran","artistName":"Sfera Ebbasta","albumName":"Rockstar"}}]},
            {"name":"Palestra","items":[{"track":{"trackName":"Lose Yourself","artistName":"Eminem"}},{"track":{"trackName":"Till I Collapse","artistName":"Eminem"}}]}]}"""
        val src = imp.fromFile("Playlist1.json", json)
        assertEquals(listOf("Estate", "Palestra"), src.map { it.name })
        assertEquals(2, src[1].items!!.size)
    }
}
