package androidx.activity.compose

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Same as Android's: launch() opens the matching Windows dialog, the result comes back to [onResult]. */
@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ActivityResultLauncher<I> {
    val cb by rememberUpdatedState(onResult)
    return remember(contract) {
        object : ActivityResultLauncher<I>() {
            override fun launch(input: I) {
                Thread {
                    val r = contract.pickPublic(input)
                    javax.swing.SwingUtilities.invokeLater { cb(r) }
                }.start()
            }
        }
    }
}

private fun <I, O> ActivityResultContract<I, O>.pickPublic(input: I): O = androidx.activity.result.contract.pickOf(this, input)

/**
 * Back: Esc key / mouse "back" button. The innermost enabled handler wins, like on Android.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val cb by rememberUpdatedState(onBack)
    val en by rememberUpdatedState(enabled)
    DisposableEffect(Unit) {
        val h = BackDispatcher.Handler({ en }, { cb() })
        BackDispatcher.add(h)
        onDispose { BackDispatcher.remove(h) }
    }
}

object BackDispatcher {
    class Handler(val enabled: () -> Boolean, val onBack: () -> Unit)
    private val stack = mutableListOf<Handler>()
    fun add(h: Handler) { synchronized(stack) { stack += h } }
    fun remove(h: Handler) { synchronized(stack) { stack -= h } }
    /** true if a screen handled it */
    fun back(): Boolean {
        val h = synchronized(stack) { stack.lastOrNull { it.enabled() } } ?: return false
        h.onBack(); return true
    }
}
