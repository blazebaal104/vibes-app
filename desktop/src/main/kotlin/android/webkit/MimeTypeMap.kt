package android.webkit

class MimeTypeMap private constructor() {
    private val types = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "opus" to "audio/opus", "mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
        "pdf" to "application/pdf", "txt" to "text/plain", "json" to "application/json", "zip" to "application/zip",
    )
    fun getMimeTypeFromExtension(ext: String?): String? = ext?.lowercase()?.let { types[it] }
    fun getExtensionFromMimeType(mime: String?): String? = types.entries.firstOrNull { it.value == mime }?.key
    fun hasExtension(ext: String?) = ext?.lowercase() in types
    companion object {
        private val single = MimeTypeMap()
        @JvmStatic fun getSingleton() = single
        @JvmStatic fun getFileExtensionFromUrl(url: String?): String = url?.substringBefore('?')?.substringAfterLast('.', "") ?: ""
    }
}
