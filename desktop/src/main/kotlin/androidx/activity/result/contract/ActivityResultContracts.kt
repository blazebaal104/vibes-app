package androidx.activity.result.contract

import android.net.Uri

/** What the launcher asks for; on PC every "pick" is a Windows file dialog. */
abstract class ActivityResultContract<I, O> {
    abstract fun pick(input: I): O
}

fun <I, O> pickOf(c: ActivityResultContract<I, O>, input: I): O = c.pick(input)

object ActivityResultContracts {
    class PickVisualMedia : ActivityResultContract<androidx.activity.result.PickVisualMediaRequest, Uri?>() {
        sealed interface VisualMediaType
        object ImageOnly : VisualMediaType
        object VideoOnly : VisualMediaType
        object ImageAndVideo : VisualMediaType
        class SingleMimeType(val mimeType: String) : VisualMediaType
        override fun pick(input: androidx.activity.result.PickVisualMediaRequest): Uri? =
            FilePicker.pick(input.type.exts(), multiple = false).firstOrNull()
    }

    class PickMultipleVisualMedia(val max: Int = 10) : ActivityResultContract<androidx.activity.result.PickVisualMediaRequest, List<Uri>>() {
        override fun pick(input: androidx.activity.result.PickVisualMediaRequest): List<Uri> = FilePicker.pick(input.type.exts(), multiple = true).take(max)
    }

    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun pick(input: Array<String>): Uri? = FilePicker.pick(mimeExts(input), multiple = false).firstOrNull()
    }

    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>() {
        override fun pick(input: Array<String>): List<Uri> = FilePicker.pick(mimeExts(input), multiple = true)
    }

    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun pick(input: String): Uri? = FilePicker.pick(mimeExts(arrayOf(input)), multiple = false).firstOrNull()
    }

    /** No camera capture on PC: the user picks an existing photo/video instead. */
    class TakePicture : ActivityResultContract<Uri, Boolean>() {
        override fun pick(input: Uri): Boolean = copyPicked(input, FilePicker.IMAGES)
    }

    class CaptureVideo : ActivityResultContract<Uri, Boolean>() {
        override fun pick(input: Uri): Boolean = copyPicked(input, FilePicker.VIDEOS)
    }

    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun pick(input: String) = true
    }

    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun pick(input: Array<String>) = input.associateWith { true }
    }

    class StartActivityForResult : ActivityResultContract<android.content.Intent, androidx.activity.result.ActivityResult>() {
        override fun pick(input: android.content.Intent) = androidx.activity.result.ActivityResult(0, null)
    }

    private fun copyPicked(dst: Uri, exts: List<String>): Boolean {
        val src = FilePicker.pick(exts, multiple = false).firstOrNull() ?: return false
        return runCatching {
            java.io.File(src.path!!).copyTo(java.io.File(dst.path!!), overwrite = true); true
        }.getOrDefault(false)
    }

    private fun PickVisualMedia.VisualMediaType.exts() = when (this) {
        PickVisualMedia.ImageOnly -> FilePicker.IMAGES
        PickVisualMedia.VideoOnly -> FilePicker.VIDEOS
        PickVisualMedia.ImageAndVideo -> FilePicker.IMAGES + FilePicker.VIDEOS
        is PickVisualMedia.SingleMimeType -> mimeExts(arrayOf(mimeType))
    }

    private fun mimeExts(mimes: Array<String>): List<String> = mimes.flatMap { m ->
        when {
            m.startsWith("image") -> FilePicker.IMAGES
            m.startsWith("video") -> FilePicker.VIDEOS
            m.startsWith("audio") -> FilePicker.AUDIO
            else -> emptyList()
        }
    }.distinct()
}

/** Windows file dialog (native look). Empty filter = all files. */
object FilePicker {
    val IMAGES = listOf("jpg", "jpeg", "png", "gif", "bmp")
    val VIDEOS = listOf("mp4", "mov", "webm", "mkv")
    val AUDIO = listOf("mp3", "m4a", "aac", "wav", "flac")

    fun pick(exts: List<String>, multiple: Boolean): List<Uri> {
        var result: List<Uri> = emptyList()
        val run = Runnable {
            val d = java.awt.FileDialog(null as java.awt.Frame?, "Scegli un file", java.awt.FileDialog.LOAD)
            d.isMultipleMode = multiple
            if (exts.isNotEmpty()) {
                d.file = exts.joinToString(";") { "*.$it" }
                d.setFilenameFilter { _, name -> exts.any { name.lowercase().endsWith(".$it") } }
            }
            d.isVisible = true
            result = d.files.map { Uri.fromFile(it) }
        }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) run.run() else javax.swing.SwingUtilities.invokeAndWait(run)
        return result
    }
}
