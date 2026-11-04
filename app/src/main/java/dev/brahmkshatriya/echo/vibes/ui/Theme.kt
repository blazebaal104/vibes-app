package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.vibes.theme.VibesTheme

val Outfit = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
    Font(R.font.outfit_extrabold, FontWeight.ExtraBold),
)

private fun t(size: Int, weight: FontWeight, line: Int = (size * 1.25).toInt(), spacing: Float = 0f) =
    TextStyle(fontFamily = Outfit, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

val VibesTypography = Typography(
    displayLarge = t(48, FontWeight.ExtraBold, 52, -1f),
    displayMedium = t(38, FontWeight.ExtraBold, 42, -0.8f),
    displaySmall = t(32, FontWeight.Bold, 36, -0.5f),
    headlineLarge = t(28, FontWeight.Bold, 32, -0.4f),
    headlineMedium = t(24, FontWeight.Bold, 28, -0.3f),
    headlineSmall = t(20, FontWeight.Bold, 24),
    titleLarge = t(18, FontWeight.Bold, 22),
    titleMedium = t(16, FontWeight.SemiBold, 20),
    titleSmall = t(14, FontWeight.SemiBold, 18),
    bodyLarge = t(16, FontWeight.Normal, 22),
    bodyMedium = t(14, FontWeight.Normal, 19),
    bodySmall = t(12, FontWeight.Normal, 16),
    labelLarge = t(14, FontWeight.SemiBold, 18),
    labelMedium = t(12, FontWeight.Medium, 16),
    labelSmall = t(11, FontWeight.Medium, 14),
)

/** Extra theme colours Material doesn't have: the background gradient of the active preset. */
@Immutable
data class VibesColors(
    val bgTop: Color,
    val bgBottom: Color,
    val accent: Color,
    val onAccent: Color,
    val card: Color,
    val cardHigh: Color,
    val muted: Color,
    val isDark: Boolean,
)

val LocalVibes = staticCompositionLocalOf {
    VibesColors(Color(0xFF4A1670), Color(0xFF12051C), Color(0xFFB84DFF), Color.White,
        Color(0x14FFFFFF), Color(0x24FFFFFF), Color(0x99FFFFFF), true)
}

fun VibesTheme.colors(): Pair<ColorScheme, VibesColors> {
    val accent = Color(accent)
    val top = Color(bgTop)
    val bottom = Color(bgBottom)
    val dark = this.dark
    val base = if (dark) (if (amoled) Color.Black else bottom) else bottom
    val onBase = if (dark) Color.White else Color(0xFF15121A)
    val onAccent = if (accent.luminance() > 0.5f) Color(0xFF111111) else Color.White
    val card = onBase.copy(alpha = if (dark) 0.08f else 0.06f).compositeOver(base)
    val cardHigh = onBase.copy(alpha = if (dark) 0.14f else 0.11f).compositeOver(base)
    val muted = onBase.copy(alpha = 0.62f)
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent,
        tertiary = accent, background = base, onBackground = onBase, surface = base, onSurface = onBase,
        surfaceVariant = cardHigh, onSurfaceVariant = muted, surfaceContainer = card,
        surfaceContainerHigh = cardHigh, surfaceContainerHighest = cardHigh, surfaceContainerLow = card,
        outline = onBase.copy(alpha = 0.25f), outlineVariant = onBase.copy(alpha = 0.12f),
        primaryContainer = accent.copy(alpha = 0.22f).compositeOver(base), onPrimaryContainer = onBase,
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, secondary = accent, onSecondary = onAccent,
        tertiary = accent, background = base, onBackground = onBase, surface = base, onSurface = onBase,
        surfaceVariant = cardHigh, onSurfaceVariant = muted, surfaceContainer = card,
        surfaceContainerHigh = cardHigh, surfaceContainerHighest = cardHigh, surfaceContainerLow = card,
        outline = onBase.copy(alpha = 0.25f), outlineVariant = onBase.copy(alpha = 0.12f),
        primaryContainer = accent.copy(alpha = 0.18f).compositeOver(base), onPrimaryContainer = onBase,
    )
    return scheme to VibesColors(top, if (amoled && dark) Color.Black else bottom, accent, onAccent, card, cardHigh, muted, dark)
}

@Composable
fun VibesMaterial(theme: VibesTheme, content: @Composable () -> Unit) {
    val (scheme, extra) = theme.colors()
    androidx.compose.runtime.CompositionLocalProvider(LocalVibes provides extra) {
        MaterialTheme(colorScheme = scheme, typography = VibesTypography, content = content)
    }
}

/** Difficulty colours used by the games (green, yellow, orange, red). */
object GameColors {
    val Easy = Color(0xFF2EE66B)
    val Medium = Color(0xFFFFD43B)
    val Hard = Color(0xFFFF8A3D)
    val Impossible = Color(0xFFFF3B5C)
}
