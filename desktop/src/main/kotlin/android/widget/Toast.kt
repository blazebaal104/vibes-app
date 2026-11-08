package android.widget

/** Short messages; the desktop window shows them like the app's own toasts. */
class Toast private constructor(private val text: String) {
    fun show() = Companion.show(text)
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        var sink: (String) -> Unit = { println("toast: $it") }
        fun show(text: String) = sink(text)
        @JvmStatic fun makeText(c: android.content.Context?, text: CharSequence, d: Int) = Toast(text.toString())
    }
}
