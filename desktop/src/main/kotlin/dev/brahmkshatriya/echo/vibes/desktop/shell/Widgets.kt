package dev.brahmkshatriya.echo.vibes.desktop.shell

import dev.brahmkshatriya.echo.vibes.ui.ambientBounds
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.ui.Cover
import dev.brahmkshatriya.echo.vibes.ui.EqualizerBars
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.fmt
import kotlinx.coroutines.launch

val HandCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.HAND_CURSOR))
val ResizeCursor = PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR))

/** Colours of the PC window: deep night base, glass panels, the Vibes logo gradient. */
object Desk {
    val window = Color(0xFF07040C)
    val panel = Color(0xFF120C18)
    val raised = Color(0x1FFFFFFF)
    val hover = Color(0xFF2A2A2A)
    val text = Color.White
    val sub = Color(0xFFB3B3B3)
}

/** The Vibes logo gradient (violet, magenta, orange): play buttons, progress, highlights. */
val VibesGradient = listOf(Color(0xFF7B2FF7), Color(0xFFC2187A), Color(0xFFFF6A3D))

/**
 * Vibes liquid glass for the big PC panels: the living background blurred behind, a soft dark tint,
 * a specular sheen and a bright rim. Plain dark panels when liquid glass is off in Settings.
 */
@Composable
fun Modifier.deskGlass(shape: Shape = RoundedCornerShape(22.dp), dark: Float = 0.42f): Modifier {
    val s = dev.brahmkshatriya.echo.vibes.ui.LocalSettings.current
    if (!s.liquidGlass) return this.clip(shape).background(Desk.panel)
    val haze = dev.brahmkshatriya.echo.vibes.ui.LocalHaze.current
    var m = this.clip(shape)
    m = if (haze != null) m.hazeEffect(haze) {
        blurRadius = (44 * s.glassBlur.coerceAtLeast(0.3f)).dp
        noiseFactor = 0.05f
        tints = listOf(HazeTint(Color.Black.copy(alpha = dark)))
    } else m.background(Color.Black.copy(alpha = 0.6f))
    return m
        .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f), Color.White.copy(alpha = 0.05f))))
        .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.16f))), shape)
}

/** The round play button, in the Vibes gradient (pause while [playing]). */
@Composable
fun PlayCircle(size: Dp, playing: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var hover by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (hover) 1.06f else 1f, tween(120), label = "pc")
    Box(
        modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(12.dp, CircleShape, ambientColor = VibesGradient[1], spotColor = VibesGradient[1]).clip(CircleShape).background(Brush.linearGradient(VibesGradient))
            .hovered { hover = it }.pointerHoverIcon(HandCursor).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausa" else "Riproduci",
            tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}

/** Icon button with a hover highlight and a tooltip-like label. */
@Composable
fun DeskIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, active: Boolean = false, size: Dp = 32.dp, iconSize: Dp = 20.dp, enabled: Boolean = true, onClick: () -> Unit) {
    var hover by remember { mutableStateOf(false) }
    val tint by animateColorAsState(
        when { !enabled -> Desk.sub.copy(alpha = 0.4f); active -> MaterialTheme.colorScheme.primary; hover -> Color.White; else -> Desk.sub }, tween(120), label = "di")
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Box(Modifier.shadow(6.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)).background(Color(0xFF282828)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        },
        delayMillis = 500,
    ) {
        Box(
            modifier.size(size).clip(CircleShape).hovered { hover = it }
                .then(if (enabled) Modifier.pointerHoverIcon(HandCursor).clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(iconSize))
            if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** Vertical bar between panels: drag to resize. */
@Composable
fun ResizeHandle(onDrag: (Float) -> Unit, onEnd: () -> Unit = {}) {
    var hover by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box(
        Modifier.width(8.dp).fillMaxHeight().hovered { hover = it }.pointerHoverIcon(ResizeCursor)
            .pointerInput(Unit) { detectDragGestures(onDragEnd = onEnd) { c, d -> c.consume(); onDrag(with(density) { d.x.toDp().value }) } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight(0.96f).background(if (hover) Color.White.copy(alpha = 0.35f) else Color.Transparent))
    }
}

/** Cover + title + subtitle card for the centre's shelves: lights up on hover, play button pops up. */
@Composable
fun MediaCard(
    cover: String?, title: String, subtitle: String, width: Dp,
    round: Boolean = false, playing: Boolean = false,
    art: (@Composable (Modifier) -> Unit)? = null,
    menu: (() -> List<MenuItem?>)? = null,
    onPlay: (() -> Unit)? = null, onOpen: () -> Unit,
) {
    var hover by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (hover) Color.White.copy(alpha = 0.07f) else Color.Transparent, tween(160), label = "mc")
    val lift by animateFloatAsState(if (hover) 1f else 0f, tween(220), label = "lift")
    val glow = rememberCoverColor(cover) ?: VibesGradient[0]
    val tap = dev.brahmkshatriya.echo.vibes.ui.rememberAmbientTap(cover)
    ContextMenuBox(items = { menu?.invoke() ?: emptyList() }) { _ ->
        Column(
            Modifier.width(width).ambientBounds(tap).clip(RoundedCornerShape(16.dp)).background(bg).hovered { hover = it }.hoverTint(cover)
                .pointerHoverIcon(HandCursor).clickable(onClick = { tap.fire(); onOpen() }).padding(10.dp),
        ) {
            // the cover lifts and glows in its own colour (Vibes' "pop" instead of a flat highlight)
            Box(Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { translationY = -6.dp.toPx() * lift; val sc = 1f + 0.02f * lift; scaleX = sc; scaleY = sc }) {
                val shape: Shape = if (round) CircleShape else RoundedCornerShape(14.dp)
                val m = Modifier.fillMaxSize().shadow((6 + 18 * lift).dp, shape, ambientColor = glow, spotColor = glow)
                if (art != null) art(m) else Cover(cover, m, shape)
                if (onPlay != null) {
                    androidx.compose.animation.AnimatedVisibility(
                        hover || playing, Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { it / 3 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { it / 3 },
                    ) { PlayCircle(48.dp, playing, onClick = onPlay) }
                }
            }
            Text(title, color = Desk.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            Text(subtitle, color = Desk.sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A row of cards that fits the width (as many as fit, one line); "Mostra tutto" opens them all as a grid. */
@Composable
fun <T> Shelf(title: String, items: List<T>, subtitle: String? = null, minCard: Dp = 168.dp, expanded: Boolean = false, card: @Composable (T, Dp) -> Unit) {
    if (items.isEmpty()) return
    var all by remember(title) { mutableStateOf(expanded) }
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Desk.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                subtitle?.let { Text(it, color = Desk.sub, style = MaterialTheme.typography.bodySmall) }
            }
            var h by remember { mutableStateOf(false) }
            Text(if (all) "Mostra meno" else "Mostra tutto", color = Desk.sub, style = MaterialTheme.typography.labelLarge,
                textDecoration = if (h) TextDecoration.Underline else null,
                modifier = Modifier.hovered { h = it }.pointerHoverIcon(HandCursor).clickable { all = !all }.padding(4.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val n = ((maxWidth.value + 0) / minCard.value).toInt().coerceAtLeast(2)
            val w = maxWidth / n
            val rows = if (all) items.chunked(n) else listOf(items.take(n))
            Column {
                rows.forEach { row ->
                    Row { row.forEach { card(it, w) } }
                }
            }
        }
    }
}

/** Heart-on-gradient art of "Brani che ti piacciono". */
@Composable
fun LikedArt(modifier: Modifier, shape: Shape = RoundedCornerShape(12.dp), iconSize: Dp = 22.dp) {
    Box(modifier.clip(shape).background(Brush.linearGradient(VibesGradient)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** Column titles of the song lists. */
@Composable
fun TrackHeader(showAlbum: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", color = Desk.sub, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
            Text("Titolo", color = Desk.sub, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1.4f))
            if (showAlbum) Text("Album", color = Desk.sub, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(36.dp))
            Box(Modifier.width(52.dp)) { Icon(Icons.Rounded.AccessTime, "Durata", tint = Desk.sub, modifier = Modifier.size(16.dp)) }
            Spacer(Modifier.width(36.dp))
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** Which row of a list is selected (single click), shared by the rows of one page. */
class Selection { var key by mutableStateOf<String?>(null) }

/**
 * One song of the playlist / album / liked pages: # (play on hover, bars while playing), title +
 * artist, album, duration; hover shows ♥ and ⋯; double click plays; right click opens the menu.
 */
@Composable
fun TrackRow(
    index: Int, s: SongRef, onPlay: () -> Unit, selection: Selection,
    showAlbum: Boolean = true, showCover: Boolean = true,
    removeFrom: (() -> Unit)? = null, removeLabel: String = "Rimuovi da questa playlist",
) {
    val app = LocalApp.current
    val desk = LocalDesk.current
    val cur by app.engine.player.playerState.current.collectAsState()
    val now = cur?.mediaItem?.mediaId == s.key
    val isPlaying = now && cur?.isPlaying == true
    val liked by app.library.liked.collectAsState()
    val scope = rememberCoroutineScope()
    var hover by remember { mutableStateOf(false) }
    val bg = when { selection.key == s.key -> Color.White.copy(alpha = 0.16f); hover -> Color.White.copy(alpha = 0.08f); else -> Color.Transparent }
    ContextMenuBox(items = { songMenu(app, desk, s, scope, removeFrom, removeLabel) }, modifier = Modifier.padding(horizontal = 12.dp)) { open ->
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(bg).hovered { hover = it }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onDoubleClick = onPlay, onClick = { selection.key = s.key })
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    hover -> Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Riproduci", tint = Color.White,
                        modifier = Modifier.size(20.dp).pointerHoverIcon(HandCursor).clickable { if (now) app.engine.togglePlay() else onPlay() })
                    isPlaying -> EqualizerBars(Modifier.size(14.dp))
                    else -> Text("${index + 1}", color = if (now) MaterialTheme.colorScheme.primary else Desk.sub, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically) {
                if (showCover) { Cover(s.cover, Modifier.size(40.dp), RoundedCornerShape(4.dp)); Spacer(Modifier.width(12.dp)) }
                Column {
                    Text(s.title, color = if (now) MaterialTheme.colorScheme.primary else Desk.text, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinkText(s.artist, hoverParent = hover) { scope.launch { dev.brahmkshatriya.echo.vibes.ui.openArtistOf(app, s) } }
                }
            }
            if (showAlbum) Box(Modifier.weight(1f).padding(end = 8.dp)) {
                LinkText(s.album ?: "", hoverParent = hover) { scope.launch { Actions.openAlbum(app, s) } }
            }
            val isLiked = liked.any { it.key == s.key }
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                if (hover || isLiked) Icon(
                    if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    if (isLiked) "Rimuovi da Brani che ti piacciono" else "Salva in Brani che ti piacciono",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else Desk.sub,
                    modifier = Modifier.size(18.dp).pointerHoverIcon(HandCursor).clickable { app.library.toggleLike(s) })
            }
            Text(s.duration?.let { fmt(it) } ?: "", color = Desk.sub, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp))
            Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                if (hover) Icon(Icons.Rounded.MoreHoriz, "Altre opzioni", tint = Color.White,
                    modifier = Modifier.size(20.dp).pointerHoverIcon(HandCursor).clickable { open() })
            }
        }
    }
}

/** Grey text that underlines and turns white under the mouse (artist / album names). */
@Composable
fun LinkText(text: String, hoverParent: Boolean = false, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Desk.sub, onClick: () -> Unit) {
    var h by remember { mutableStateOf(false) }
    Text(text, color = if (h || hoverParent) Color.White else color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textDecoration = if (h) TextDecoration.Underline else null,
        modifier = Modifier.hovered { h = it }.pointerHoverIcon(HandCursor).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick))
}
