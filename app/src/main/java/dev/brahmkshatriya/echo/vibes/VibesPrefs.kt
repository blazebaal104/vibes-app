package dev.brahmkshatriya.echo.vibes

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** Vibes' own look & behaviour settings (the player's audio settings live in Gladix's prefs). */
@Serializable
data class VibesSettings(
    val font: String = "outfit",          // outfit | poppins | grotesk | nunito | serif | mono | system
    val textScale: Float = 1f,            // 0.85 .. 1.3
    val background: String = "gradient", // gradient | solid | image | amoled
    val backgroundDim: Float = 0.55f,     // darkening over a custom image
    val backgroundBlur: Int = 12,         // dp
    val corners: Int = 12,                // cover / card roundness, dp
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
    val canvasCovers: Boolean = true,     // animated "breathing" cover in the full player
    val privateSession: Boolean = false,  // don't log listens / now playing
    val readReceipts: Boolean = true,     // blue ticks
    val typingAndOnline: Boolean = true,
    val notifyMessages: Boolean = true,
    val notifyChallenges: Boolean = true,
    val notifySongs: Boolean = true,
    val autoUpdate: Boolean = true,
    val confetti: Boolean = true,
    val gameVolumeBoost: Boolean = false,
    val homeRecommendations: Boolean = true,
    val homeFriends: Boolean = true,
    val homeCommunity: Boolean = true, // songs published by Vibes users
    val homePodcasts: Boolean = true,
    val explicitFilter: Boolean = false,
    val startTab: String = "home",
    /** New account: show the favourite-artists questionnaire once. */
    val needsOnboarding: Boolean = false,
    val liquidGlass: Boolean = true,
    val reactiveBackground: Boolean = false,
    /** All Vibes notifications on/off (the per-type switches below apply when on). */
    val notifyEnabled: Boolean = true,
    val notifyFollowers: Boolean = true, // someone starts following you
    /** Karaoke: seconds of the fade between the song with voice and the backing track (in and out). */
    val karaokeFadeSec: Float = 2.5f, // background that follows covers, now playing and page switches
    val glassBlur: Float = 1f,          // 0.4 .. 2
    val glassShine: Boolean = true,     // light reflection gliding on glass
    val studioVibe: String = "neon",    // aurora colours of the Studio
    val favSamples: Set<String> = emptySet(),
    val projectBpm: Int = 140,
    val tempoMatch: Boolean = false,
)

class VibesPrefs(context: Context) {
    private val sp = context.getSharedPreferences("vibes_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        runCatching { Supa.json.decodeFromString(VibesSettings.serializer(), sp.getString("s", null)!!) }.getOrDefault(VibesSettings())
    )
    val state = _state.asStateFlow()
    val value get() = _state.value
    val privateSession get() = value.privateSession

    /** Called after a change made by the user (the account sync uploads it). */
    @Volatile var onUserChange: (() -> Unit)? = null

    fun update(block: (VibesSettings) -> VibesSettings) {
        val before = _state.value
        _state.value = block(before)
        sp.edit().putString("s", Supa.json.encodeToString(VibesSettings.serializer(), _state.value)).apply()
        if (_state.value != before) onUserChange?.invoke()
    }

    /** Settings arriving from the other device: applied without echoing them back. */
    fun setFromSync(s: VibesSettings) {
        if (s == _state.value) return
        _state.value = s
        sp.edit().putString("s", Supa.json.encodeToString(VibesSettings.serializer(), s)).apply()
    }

    /** Custom background image lives in filesDir. */
    val backgroundFile = java.io.File(context.filesDir, "vibes_background.jpg")
}
