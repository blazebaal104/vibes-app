package android.graphics.drawable

open class Drawable
class GradientDrawable(val orientation: Orientation = Orientation.TOP_BOTTOM, val colors: IntArray? = null) : Drawable() {
    enum class Orientation { TOP_BOTTOM, TR_BL, RIGHT_LEFT, BR_TL, BOTTOM_TOP, BL_TR, LEFT_RIGHT, TL_BR }
    var cornerRadius = 0f
    fun setSize(w: Int, h: Int) {}
    fun setColor(c: Int) {}
    fun setStroke(w: Int, c: Int) {}
}
class ColorDrawable(val color: Int = 0) : Drawable()
