package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Deezer refuses to stream when its session is missing or expired (AppException.LoginRequired).
 * The engine only reports it on a flow, so playback just stopped silently: this turns it into a
 * visible "ricollega Deezer" sheet (see DeezerAuthHost).
 */
object DeezerAuth {
    val needed = MutableStateFlow(false)

    /** Last known login state from the user DB (null = not known yet). */
    @Volatile var loggedIn: Boolean? = null
    @Volatile private var dismissedAt = 0L
    /** Deezer refused the last song and nothing was relinked since (makes the link sheet mandatory). */
    val failingState = MutableStateFlow(false)
    val failing: Boolean get() = failingState.value

    /** Which deezer.com page the login screen opens straight away: true sign up, false log in. */
    @Volatile var startWeb: Boolean? = null
    fun takeStartWeb(): Boolean? = startWeb.also { startWeb = null }

    fun isLoginError(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth < 12) {
            if (e is AppException.LoginRequired || e is ClientException.LoginRequired) return true
            val m = e.message.orEmpty()
            if ("LoginRequired" in m || "re-login" in m || "Invalid CSRF" in m) return true
            e = e.cause.takeIf { it !== e }
            depth++
        }
        return false
    }

    /** A song failed: ask to (re)link Deezer, at most once a minute after "Più tardi". */
    fun onPlaybackError(t: Throwable?) {
        if (!isLoginError(t)) return
        failingState.value = true
        if (System.currentTimeMillis() - dismissedAt < 60_000) return
        needed.value = true
    }

    /** Called before starting playback: false = not linked, sheet shown instead. */
    fun canPlay(): Boolean {
        if (loggedIn == false) { needed.value = true; return false }
        return true
    }

    /** The user explicitly pressed play/next while Deezer keeps refusing: always explain why. */
    fun explainIfFailing(): Boolean {
        if (loggedIn == false || failing) { needed.value = true; return true }
        return false
    }

    /** The start-up check found the stored session dead: the panel becomes mandatory. */
    fun sessionDead() { failingState.value = true }

    /** A song actually started: Deezer works again. */
    fun ok() { failingState.value = false }

    fun dismiss() {
        dismissedAt = System.currentTimeMillis()
        needed.value = false
    }

    fun linked() {
        failingState.value = false
        dismissedAt = 0L
        needed.value = false
    }
}
