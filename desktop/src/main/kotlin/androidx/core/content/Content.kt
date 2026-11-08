package androidx.core.content

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File

inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val e = edit(); e.action(); if (commit) e.commit() else e.apply()
}

object ContextCompat {
    /** Desktop has no runtime permissions: microphone/notifications are always "granted". */
    fun checkSelfPermission(context: Context, permission: String): Int = android.content.pm.PackageManager.PERMISSION_GRANTED
    fun startForegroundService(context: Context, intent: android.content.Intent) {}
    fun <T> getSystemService(context: Context, cls: Class<T>): T? = context.getSystemService(cls)
    fun getColor(context: Context, id: Int): Int = 0
}

object FileProvider {
    fun getUriForFile(context: Context, authority: String, file: File): Uri = Uri.fromFile(file)
}
