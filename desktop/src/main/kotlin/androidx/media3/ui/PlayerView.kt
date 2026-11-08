package androidx.media3.ui

import android.content.Context

/** Video surface placeholder: on PC the story editor shows the video's first frame only. */
open class PlayerView(context: Context) : android.view.View(context) {
    var useController = true
    var resizeMode = 0
    var player: androidx.media3.common.Player? = null
    fun setShutterBackgroundColor(c: Int) {}
    fun setKeepContentOnPlayerReset(b: Boolean) {}
}

object AspectRatioFrameLayout {
    const val RESIZE_MODE_FIT = 0
    const val RESIZE_MODE_FIXED_WIDTH = 1
    const val RESIZE_MODE_FIXED_HEIGHT = 2
    const val RESIZE_MODE_FILL = 3
    const val RESIZE_MODE_ZOOM = 4
}
