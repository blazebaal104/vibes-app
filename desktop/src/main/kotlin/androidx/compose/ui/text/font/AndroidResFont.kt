package androidx.compose.ui.text.font

/** Android's Font(R.font.x, weight): loads the same .ttf from the app resources. */
fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    androidx.compose.ui.text.platform.Font("fonts/${dev.brahmkshatriya.echo.R.font.files[resId] ?: "outfit_regular"}.ttf", weight, style)
