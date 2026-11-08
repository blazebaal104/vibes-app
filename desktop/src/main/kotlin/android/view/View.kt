package android.view

open class View(val context: android.content.Context) {
    var visibility = 0
    var alpha = 1f
    fun setBackgroundColor(c: Int) {}
    companion object { const val VISIBLE = 0; const val INVISIBLE = 4; const val GONE = 8 }
}

class HapticFeedbackConstants { companion object { const val CLOCK_TICK = 4; const val CONFIRM = 16; const val LONG_PRESS = 0 } }
