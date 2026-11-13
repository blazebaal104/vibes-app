package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.vibes.backend.SongRef

fun parseColor(hex: String, fallback: Color = Color(0xFFB84DFF)): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/** Clickable with a soft spring "press" scale instead of a ripple. */
@Composable
fun Modifier.bouncy(onClick: () -> Unit, onLong: (() -> Unit)? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val settings = LocalSettings.current
    val s by animateFloatAsState(if (pressed && !settings.reduceMotion) 0.92f else 1f, spring(dampingRatio = 0.38f, stiffness = 700f), label = "press")
    val dim by animateFloatAsState(if (pressed) 0.82f else 1f, androidx.compose.animation.core.tween(120), label = "dim")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val click = {
        if (settings.haptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        onClick()
    }
    val long = onLong?.let { l -> {
        if (settings.haptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); l()
    } }
    return this
        .androidx_graphics(s, dim)
        .then(
            if (long != null) Modifier.longPressable(source, click, long)
            else Modifier.clickable(interactionSource = source, indication = null, onClick = click)
        )
}

private fun Modifier.androidx_graphics(scale: Float, alpha: Float) =
    this.then(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha })

private fun Modifier.longPressable(source: MutableInteractionSource, onClick: () -> Unit, onLong: () -> Unit) =
    this.then(
        Modifier.combinedClickable(
            interactionSource = source, indication = null, onClick = onClick, onLongClick = onLong
        )
    )

@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape((LocalSettings.current.corners / 2).dp), placeholder: Color? = null) {
    Box(
        modifier
            .clip(shape)
            .background(placeholder ?: LocalVibes.current.cardHigh),
        contentAlignment = Alignment.Center
    ) {
        if (url == null) Icon(
            Icons.Rounded.MusicNote, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp)
        )
        else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // ask Deezer's CDN for a cover the size it is drawn at (a list row needs a 250 px image,
            // not the 1000 px one: ~15 KB instead of ~200 KB)
            val px = constraints.maxWidth.takeIf { it in 1 until 100_000 } ?: 1000
            AsyncImage(model = sizedCover(url, px), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private val dzSize = Regex("/(\\d{2,4})x(\\d{2,4})-")

/** Deezer CDN covers come in any square size: pick the smallest standard one that is sharp at [px]. */
fun sizedCover(url: String, px: Int): String {
    if (!url.contains("dzcdn.net/images/")) return url
    val target = listOf(120, 250, 500, 1000).firstOrNull { it >= px } ?: 1000
    return url.replace(dzSize, "/${target}x${target}-")
}

@Composable
fun Avatar(profile: Profile?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val color = parseColor(profile?.avatarColor ?: "#B84DFF")
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center
    ) {
        val photo = profile?.avatarUrl
        if (photo != null) AsyncImage(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(profile?.avatarEmoji ?: "🎧", fontSize = (size.value * 0.48f).sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) Text(
            action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onAction).padding(4.dp)
        )
    }
}

@Composable
fun SongRow(
    song: SongRef,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    index: Int? = null,
    onMore: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .bouncy(onClick, onMore)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (index != null) Text(
            "$index", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(26.dp)
        )
        Cover(song.cover, Modifier.size(50.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OfflineMark(song)
                Text(
                    song.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (playing) EqualizerBars(Modifier.padding(horizontal = 8.dp).size(18.dp))
        trailing?.invoke()
        if (onMore != null) IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, "Altro", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Three little bars bouncing, shown next to the song that is playing. */
@Composable
fun EqualizerBars(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "eq")
    val heights = (0..2).map { i ->
        t.animateFloat(
            0.25f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(380 + i * 120),
                androidx.compose.animation.core.RepeatMode.Reverse
            ), label = "b$i"
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            Box(
                Modifier.weight(1f).fillMaxHeight(h.value)
                    .clip(RoundedCornerShape(2.dp)).background(color)
            )
        }
    }
}


@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by androidx.compose.animation.animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "pill")
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .glass(CircleShape, fallback = LocalVibes.current.cardHigh)
            .background(bg)
            .bouncy(onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun BigButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary, loading: Boolean = false,
) {
    Box(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.35f))
            .then(if (enabled && !loading) Modifier.bouncy(onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (loading) androidx.compose.material3.CircularProgressIndicator(
            Modifier.size(22.dp), color = content, strokeWidth = 2.5.dp
        )
        else Text(text, color = content, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun GradientBackground(modifier: Modifier = Modifier, tint: Color? = null, image: String? = null, content: @Composable BoxScope.() -> Unit) {
    val v = LocalVibes.current
    val prefs = LocalSettings.current
    val bg = if (prefs.background == "amoled") Color.Black else MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize().background(bg)) {
        if (image != null) {
            // a profile's own background photo: blurred and dimmed, with the colour wash on top (glass look)
            val blurs = android.os.Build.VERSION.SDK_INT >= 31
            AsyncImage(
                image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().then(if (blurs) Modifier.blur(28.dp) else Modifier),
            )
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (blurs) 0.38f else 0.55f)))
        } else if (prefs.background == "image") BackgroundImage(prefs.backgroundDim, prefs.backgroundBlur)
        if (prefs.background != "solid" && prefs.background != "amoled" || tint != null) Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to (tint ?: v.bgTop).copy(alpha = if (image != null) 0.45f else if (prefs.background == "image") 0.55f else 0.95f),
                    0.45f to (tint ?: v.bgTop).copy(alpha = if (image != null || prefs.background == "image") 0.12f else 0.25f),
                    1f to Color.Transparent,
                )
            )
        )
        content()
    }
}

/** The user's own photo as app background, darkened (and blurred on Android 12+). */
@Composable
private fun BoxScope.BackgroundImage(dim: Float, blur: Int) {
    val file = dev.brahmkshatriya.echo.vibes.VibesGraph.prefs.backgroundFile
    val stamp = file.lastModified()
    val bmp = androidx.compose.runtime.remember(stamp) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
    } ?: return
    androidx.compose.foundation.Image(
        bmp, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.matchParentSize()
            .then(if (android.os.Build.VERSION.SDK_INT >= 31 && blur > 0) Modifier.blur(blur.dp) else Modifier)
    )
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = dim)))
}

@Composable
fun EmptyState(emoji: String, title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 52.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (action != null) { Spacer(Modifier.height(18.dp)); action() }
    }
}

val ScreenPadding = PaddingValues(bottom = 160.dp)
