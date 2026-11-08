package dev.brahmkshatriya.echo.playback.listener

import android.content.Context

class EffectsListener {
    companion object {
        const val GLOBAL_FX = "global_fx"
        const val BASS_BOOST = "bass_boost"
        const val PLAYBACK_SPEED = "playback_speed"
        val speedRange = listOf(
            0.1f, 0.175f, 0.25f, 0.33f, 0.5f, 0.66f, 0.75f, 0.85f, 0.9f, 0.95f,
            1f, 1.05f, 1.1f, 1.15f, 1.25f, 1.33f, 1.5f, 1.66f, 1.75f, 1.88f, 2f,
            2.33f, 2.5f, 3f, 4f, 8f, 16f, 32f, 64f
        )
        const val CHANGE_PITCH = "change_pitch"
        const val CUSTOM_EFFECTS = "custom_effects"
        fun Context.globalFx() = getSharedPreferences(GLOBAL_FX, Context.MODE_PRIVATE)
    }
}
