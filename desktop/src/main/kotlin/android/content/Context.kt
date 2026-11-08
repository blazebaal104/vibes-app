package android.content

import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Desktop stand-in for android.content.Context: the bits Vibes uses (app folders, preferences,
 * files picked by the user, clipboard, opening links). Everything lives in %APPDATA%\Vibes.
 */
open class Context {
    open val filesDir: File get() = DesktopEnv.dataDir.resolve("files").apply { mkdirs() }
    open val cacheDir: File get() = DesktopEnv.dataDir.resolve("cache").apply { mkdirs() }
    open val externalCacheDir: File? get() = cacheDir
    open val noBackupFilesDir: File get() = filesDir
    open val packageName: String get() = "app.vibes.music"
    open val applicationContext: Context get() = DesktopEnv.app
    open val contentResolver: ContentResolver get() = ContentResolver
    open val packageManager: PackageManager get() = PackageManager()
    open val mainLooper: android.os.Looper get() = android.os.Looper.getMainLooper()

    fun getExternalFilesDir(type: String?): File? = filesDir.resolve(type ?: "").apply { mkdirs() }
    fun getDir(name: String, mode: Int): File = filesDir.resolve("app_$name").apply { mkdirs() }
    fun getDatabasePath(name: String): File = filesDir.resolve("databases/$name").apply { parentFile.mkdirs() }

    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = DesktopPrefs.get(name)

    @Suppress("UNCHECKED_CAST")
    open fun getSystemService(name: String): Any? = when (name) {
        CLIPBOARD_SERVICE -> ClipboardManager
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    open fun <T> getSystemService(cls: Class<T>): T? = when (cls) {
        ClipboardManager::class.java -> ClipboardManager as T
        else -> null
    }

    open fun startActivity(intent: Intent) = DesktopEnv.handleIntent(intent)
    open fun startService(intent: Intent): ComponentName? = null
    open fun sendBroadcast(intent: Intent) {}
    fun getString(id: Int): String = ""
    fun openFileInput(name: String): InputStream = filesDir.resolve(name).inputStream()
    fun openFileOutput(name: String, mode: Int): OutputStream = filesDir.resolve(name).outputStream()

    companion object {
        const val MODE_PRIVATE = 0
        const val CLIPBOARD_SERVICE = "clipboard"
        const val AUDIO_SERVICE = "audio"
        const val NOTIFICATION_SERVICE = "notification"
        const val DOWNLOAD_SERVICE = "download"
        const val POWER_SERVICE = "power"
    }
}

class ComponentName(val pkg: String = "", val cls: String = "")

/** Where the desktop app keeps its data, and how it opens links/files. Set up in Main.kt. */
object DesktopEnv {
    val dataDir: File by lazy {
        val base = System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home"), ".config")
        base.resolve("Vibes").apply { mkdirs() }
    }
    val app = Context()

    /** Opens web links in the browser; ACTION_SEND copies the text (Windows has no share sheet). */
    var onShare: (String) -> Unit = { ClipboardManager.setText(it) }

    fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> intent.extras[Intent.EXTRA_TEXT]?.toString()?.let(onShare)
            else -> intent.data?.let { open(it.toString()) }
        }
    }

    fun open(target: String) {
        runCatching {
            val d = java.awt.Desktop.getDesktop()
            if (target.startsWith("http") || target.startsWith("mailto:")) d.browse(java.net.URI(target))
            else d.open(File(target.removePrefix("file://")))
        }
    }
}

/** Reads files the user picked (Uri = file path or http URL). */
object ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = runCatching {
        val s = uri.toString()
        if (s.startsWith("http")) java.net.URI(s).toURL().openStream() else File(uri.path ?: s).inputStream()
    }.getOrNull()

    fun openOutputStream(uri: Uri): OutputStream? = runCatching { File(uri.path ?: uri.toString()).outputStream() }.getOrNull()

    fun getType(uri: Uri): String? = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(uri.toString().substringAfterLast('.', "").lowercase())

    fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): android.database.Cursor =
        android.database.Cursor(File(uri.path ?: uri.toString()))

    fun takePersistableUriPermission(uri: Uri, flags: Int) {}
}
