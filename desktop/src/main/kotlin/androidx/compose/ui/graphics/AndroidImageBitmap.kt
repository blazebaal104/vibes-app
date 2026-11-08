package androidx.compose.ui.graphics

/** Same name as Android's Bitmap.asImageBitmap(): converts the desktop Bitmap for Compose Image(). */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = image.toComposeImageBitmap()

fun ImageBitmap.asAndroidBitmap(): android.graphics.Bitmap = android.graphics.Bitmap(toAwtImage())
