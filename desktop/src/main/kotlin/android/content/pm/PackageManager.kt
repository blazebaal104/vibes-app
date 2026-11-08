package android.content.pm

open class PackageManager {
    fun getLaunchIntentForPackage(pkg: String): android.content.Intent? = android.content.Intent()
    companion object {
        const val PERMISSION_GRANTED = 0
        const val PERMISSION_DENIED = -1
    }
}
