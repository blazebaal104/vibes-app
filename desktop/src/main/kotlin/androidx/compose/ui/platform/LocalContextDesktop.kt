package androidx.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Android's LocalContext: on PC every screen gets the one desktop Context. */
val LocalContext = staticCompositionLocalOf<android.content.Context> { android.content.DesktopEnv.app }
