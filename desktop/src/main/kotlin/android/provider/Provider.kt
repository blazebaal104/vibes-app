package android.provider

object OpenableColumns {
    const val DISPLAY_NAME = "_display_name"
    const val SIZE = "_size"
}

object Settings {
    const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "settings.unknown"
    const val ACTION_APP_NOTIFICATION_SETTINGS = "settings.notifications"
    const val ACTION_APPLICATION_DETAILS_SETTINGS = "settings.details"
    const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"
    const val ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = "settings.battery"
    const val ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS = "settings.battery2"
    object Secure { const val ANDROID_ID = "android_id"; fun getString(r: Any?, k: String): String = "pc" }
}

object MediaStore {
    const val EXTRA_OUTPUT = "output"
    const val ACTION_IMAGE_CAPTURE = "capture"
}
