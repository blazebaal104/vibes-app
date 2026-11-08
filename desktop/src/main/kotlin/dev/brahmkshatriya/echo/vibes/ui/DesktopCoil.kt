package dev.brahmkshatriya.echo.vibes.ui

/** Coil on PC has one "platform context": the Android Context the screens pass maps to it. */
fun coil3.SingletonImageLoader.get(context: android.content.Context): coil3.ImageLoader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
