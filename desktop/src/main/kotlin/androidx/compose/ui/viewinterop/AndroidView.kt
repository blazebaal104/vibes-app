package androidx.compose.ui.viewinterop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Android views can't run on PC: the area stays dark (used only by the video preview). */
@Composable
fun <T : android.view.View> AndroidView(
    factory: (android.content.Context) -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = {},
) {
    val v = remember { factory(android.content.DesktopEnv.app) }
    remember(v) { update(v); 0 }
    Box(modifier.background(Color.Black))
}

@Composable
fun <T : android.view.View> AndroidView(
    factory: (android.content.Context) -> T,
    modifier: Modifier = Modifier,
    onReset: ((T) -> Unit)?,
    onRelease: (T) -> Unit = {},
    update: (T) -> Unit = {},
) = AndroidView(factory, modifier, update)
