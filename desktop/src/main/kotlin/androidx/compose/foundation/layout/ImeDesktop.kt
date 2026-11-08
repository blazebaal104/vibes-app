package androidx.compose.foundation.layout

import androidx.compose.runtime.Composable

/** PC has a physical keyboard: the on-screen keyboard is never open. */
val WindowInsets.Companion.isImeVisible: Boolean
    @Composable get() = false
