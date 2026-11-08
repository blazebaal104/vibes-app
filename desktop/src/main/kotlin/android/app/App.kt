package android.app

import android.net.Uri

/** Downloads straight into the user's Downloads folder (no system download manager on Windows). */
class DownloadManager {
    class Request(val uri: Uri) {
        var title: CharSequence? = null
        var fileName: String? = null
        fun setTitle(t: CharSequence?) = apply { title = t }
        fun setDescription(t: CharSequence?) = this
        fun setMimeType(t: String?) = this
        fun addRequestHeader(k: String, v: String) = this
        fun setNotificationVisibility(v: Int) = this
        fun setAllowedOverMetered(b: Boolean) = this
        fun setDestinationInExternalPublicDir(dir: String, name: String) = apply { fileName = name }
        fun setDestinationInExternalFilesDir(c: android.content.Context, dir: String?, name: String) = apply { fileName = name }
        companion object { const val VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1; const val VISIBILITY_VISIBLE = 0 }
    }
    fun enqueue(r: Request): Long {
        val name = r.fileName ?: r.uri.lastPathSegment ?: "download"
        val dst = java.io.File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }.resolve(name)
        Thread {
            runCatching {
                okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(r.uri.toString()).build()).execute().use { res ->
                    dst.outputStream().use { o -> res.body.byteStream().copyTo(o) }
                }
                android.widget.Toast.show("Scaricato in Download: $name")
            }
        }.start()
        return System.currentTimeMillis()
    }
}

open class Activity : android.content.Context()
open class Service : android.content.Context()
class Notification
class PendingIntent { companion object { const val FLAG_IMMUTABLE = 1; const val FLAG_UPDATE_CURRENT = 2 } }
