package androidx.compose.ui.platform

import androidx.compose.runtime.compositionLocalOf

/** Android's Configuration, as far as the Vibes UI reads it (window size in dp). */
class Configuration(val screenWidthDp: Int = 1280, val screenHeightDp: Int = 800) {
    val orientation get() = if (screenWidthDp >= screenHeightDp) ORIENTATION_LANDSCAPE else ORIENTATION_PORTRAIT
    val smallestScreenWidthDp get() = minOf(screenWidthDp, screenHeightDp)
    companion object { const val ORIENTATION_PORTRAIT = 1; const val ORIENTATION_LANDSCAPE = 2 }
}

val LocalConfiguration = compositionLocalOf { Configuration() }
