package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.ui.graphics.Color
import dev.brahmkshatriya.echo.vibes.desktop.shell.CoverColors

/** PC version of the phone's CoverTint: same colours the PC shell already uses. */
object CoverTint {
    suspend fun of(url: String?): Color? = CoverColors.of(url)
}
