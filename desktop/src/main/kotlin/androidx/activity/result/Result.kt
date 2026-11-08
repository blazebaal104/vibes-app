package androidx.activity.result

import androidx.activity.result.contract.ActivityResultContracts

class PickVisualMediaRequest(val mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo) {
    val type get() = mediaType
    class Builder {
        private var t: ActivityResultContracts.PickVisualMedia.VisualMediaType = ActivityResultContracts.PickVisualMedia.ImageAndVideo
        fun setMediaType(v: ActivityResultContracts.PickVisualMedia.VisualMediaType) = apply { t = v }
        fun build() = PickVisualMediaRequest(t)
    }
}

class ActivityResult(val resultCode: Int, val data: android.content.Intent?)

abstract class ActivityResultLauncher<I> {
    abstract fun launch(input: I)
    fun unregister() {}
}
