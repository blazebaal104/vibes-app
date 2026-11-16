package dev.brahmkshatriya.echo.vibes

import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.SongMatcher
import dev.brahmkshatriya.echo.vibes.music.SongMatcher.Want
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scoring of the playlist transfer (offline): same songs score high, other versions / songs low. */
class SongMatcherTest {
    private val m = SongMatcher(DeezerPublic(OkHttpClient()), null)
    private fun song(t: String, a: String, d: Long? = null) = SongRef("deezer", "1", t, a, duration = d)

    @Test
    fun `same song in other spellings scores high`() {
        assertTrue(m.score(Want("Thriller", "Michael Jackson", 358_000), song("Thriller", "Michael Jackson", 357_000)) > 0.95)
        assertTrue(m.score(Want("Blinding Lights", "The Weeknd"), song("Blinding Lights", "The Weeknd")) > 0.95)
        assertTrue(m.score(Want("Under Pressure - Remastered 2011", "Queen, David Bowie"), song("Under Pressure", "Queen")) > 0.85)
        assertTrue(m.score(Want("Despacito (feat. Daddy Yankee)", "Luis Fonsi"), song("Despacito", "Luis Fonsi, Daddy Yankee")) > 0.85)
        assertTrue(m.score(Want("Perdonami", "Sfera Ebbasta"), song("Perdonami", "Sfera Ebbasta")) > 0.95)
        assertTrue(m.score(Want("Let's Groove", "Earth, Wind & Fire"), song("Let's Groove", "Earth, Wind & Fire")) > 0.95)
    }

    @Test
    fun `other versions and other songs score low`() {
        assertTrue(m.score(Want("With Or Without You", "U2"), song("With Or Without You (Daniel Lanois Remix)", "U2")) <
            m.score(Want("With Or Without You", "U2"), song("With Or Without You", "U2")))
        assertTrue(m.score(Want("Hello", "Adele"), song("Hello", "Lionel Richie")) < 0.75)
        assertTrue(m.score(Want("Yesterday", "The Beatles"), song("Yellow Submarine", "The Beatles")) < 0.72)
    }
}
