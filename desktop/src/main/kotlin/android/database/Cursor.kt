package android.database

import java.io.File

/** One-row cursor describing a picked file (name + size), like Android's OpenableColumns query. */
class Cursor(private val file: File) : java.io.Closeable {
    private var pos = -1
    fun moveToFirst(): Boolean { pos = 0; return true }
    fun moveToNext(): Boolean = false
    fun getColumnIndex(name: String): Int = when (name) {
        android.provider.OpenableColumns.DISPLAY_NAME -> 0
        android.provider.OpenableColumns.SIZE -> 1
        else -> -1
    }
    fun getColumnIndexOrThrow(name: String): Int = getColumnIndex(name)
    fun getString(i: Int): String? = when (i) { 0 -> file.name; 1 -> file.length().toString(); else -> null }
    fun getLong(i: Int): Long = if (i == 1) file.length() else 0
    fun isNull(i: Int) = i < 0
    val count: Int get() = 1
    override fun close() {}
}
