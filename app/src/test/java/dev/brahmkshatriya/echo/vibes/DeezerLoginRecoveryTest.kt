package dev.brahmkshatriya.echo.vibes

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.DeezerExtension
import dev.brahmkshatriya.echo.extension.DeezerSession
import dev.brahmkshatriya.echo.vibes.music.DeezerAuth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The bug users hit: Deezer refused a session once (arlExpired = true) and after re-linking the
 * account every song still failed with LoginRequired until the app was killed.
 */
class DeezerLoginRecoveryTest {

    private val user = User(
        "1", "test",
        extras = mapOf("arl" to "a".repeat(192), "sid" to "fr123", "token" to "tok", "user_id" to "1", "license_token" to "lic")
    )

    @Test
    fun `expired session blocks playback without network`() = runBlocking {
        val ext = DeezerExtension()
        ext.setLoginUser(null)
        DeezerSession.getInstance().isArlExpired(true)
        try {
            ext.handleArlExpiration(); fail("expected LoginRequired")
        } catch (_: ClientException.LoginRequired) { }
    }

    @Test
    fun `re-linking clears the expired flag so songs can play again`() = runBlocking {
        val ext = DeezerExtension()
        DeezerSession.getInstance().isArlExpired(true)
        ext.setLoginUser(user)
        assertFalse(DeezerSession.getInstance().arlExpired)
        // full credentials + not expired: must pass without throwing and without touching the network
        ext.handleArlExpiration()
    }

    @Test
    fun `login errors are recognised through the player's wrapping`() {
        val wrapped = IOException(RuntimeException(ClientException.LoginRequired()))
        assertTrue(DeezerAuth.isLoginError(wrapped))
        assertFalse(DeezerAuth.isLoginError(IOException("timeout")))
        // expired ARL from the gateway (no email/password stored)
        assertTrue(DeezerAuth.isLoginError(Exception("Please re-login (Best use User + Pass method)")))
    }

    @Test
    fun `play is blocked and the sheet requested when Deezer is not linked`() {
        DeezerAuth.linked()
        DeezerAuth.loggedIn = false
        assertFalse(DeezerAuth.canPlay())
        assertTrue(DeezerAuth.needed.value)
        DeezerAuth.loggedIn = true
        DeezerAuth.linked()
        assertTrue(DeezerAuth.canPlay())
        assertFalse(DeezerAuth.needed.value)
    }

    @Test
    fun `refused song shows the sheet, a later working song clears it`() {
        DeezerAuth.loggedIn = true
        DeezerAuth.linked()
        DeezerAuth.onPlaybackError(IOException(ClientException.LoginRequired()))
        assertTrue(DeezerAuth.needed.value)
        assertTrue(DeezerAuth.failing)
        DeezerAuth.dismiss()
        DeezerAuth.ok()
        assertFalse(DeezerAuth.explainIfFailing())
    }

    @Test
    fun `dead session found at start-up makes the panel mandatory until relinked`() {
        DeezerAuth.loggedIn = true
        DeezerAuth.linked()
        DeezerAuth.sessionDead()
        assertTrue(DeezerAuth.failingState.value)
        DeezerAuth.dismiss()
        assertTrue(DeezerAuth.failingState.value) // "dismiss" cannot get rid of it
        DeezerAuth.linked()
        assertFalse(DeezerAuth.failingState.value)
    }
}
