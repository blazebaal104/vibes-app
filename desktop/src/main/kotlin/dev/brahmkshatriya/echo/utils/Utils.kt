package dev.brahmkshatriya.echo.utils

import android.content.Context
import kotlinx.serialization.json.Json

object ContextUtils {
    const val SETTINGS_NAME = "settings"
    fun Context.getSettings() = getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
}

object Serializer {
    val json = Json { ignoreUnknownKeys = true }
    inline fun <reified T> String.toData() = runCatching { json.decodeFromString<T>(this) }
    inline fun <reified T> T.toJson() = json.encodeToString(this)
    val Throwable.rootCause: Throwable get() = generateSequence(this) { it.cause }.last()
}
