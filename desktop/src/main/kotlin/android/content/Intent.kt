package android.content

import android.net.Uri

class Intent(var action: String? = null, var data: Uri? = null) {
    constructor(context: Context, cls: Class<*>) : this(null, null)
    constructor(action: String, uri: Uri?, context: Context, cls: Class<*>) : this(action, uri)
    val extras = HashMap<String, Any?>()
    var type: String? = null
    var flags = 0
    fun setAction(a: String?) = apply { action = a }
    fun setData(u: Uri?) = apply { data = u }
    fun setType(t: String?) = apply { type = t }
    fun setDataAndType(u: Uri?, t: String?) = apply { data = u; type = t }
    fun setPackage(p: String?) = this
    fun setClassName(p: String, c: String) = this
    fun addFlags(f: Int) = apply { flags = flags or f }
    fun putExtra(k: String, v: Any?) = apply { extras[k] = v }
    fun getStringExtra(k: String) = extras[k] as? String
    fun getBooleanExtra(k: String, def: Boolean) = extras[k] as? Boolean ?: def
    fun hasExtra(k: String) = extras.containsKey(k)
    fun resolveActivity(pm: android.content.pm.PackageManager): ComponentName? = ComponentName()

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SENDTO = "android.intent.action.SENDTO"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_TITLE = "android.intent.extra.TITLE"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
        fun createChooser(target: Intent, title: CharSequence?): Intent = target
    }
}

class ClipData private constructor(val text: CharSequence) {
    companion object {
        fun newPlainText(label: CharSequence?, text: CharSequence): ClipData = ClipData(text)
    }
    fun getItemAt(i: Int) = Item(text)
    val itemCount get() = 1
    class Item(val text: CharSequence?) { fun coerceToText(c: Context?): CharSequence = text ?: "" }
}

/** The Windows clipboard. */
object ClipboardManager {
    private val cb get() = java.awt.Toolkit.getDefaultToolkit().systemClipboard
    fun setText(text: String) = runCatching { cb.setContents(java.awt.datatransfer.StringSelection(text), null) }
    fun setPrimaryClip(clip: ClipData) { setText(clip.text.toString()) }
    val primaryClip: ClipData?
        get() = runCatching { cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String }.getOrNull()
            ?.let { ClipData.newPlainText("", it) }
    fun hasPrimaryClip() = primaryClip != null
    val text: CharSequence? get() = primaryClip?.text
}
