package dev.brahmkshatriya.echo.vibes.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.StoryMedia
import dev.brahmkshatriya.echo.vibes.backend.StoryStyle
import dev.brahmkshatriya.echo.vibes.backend.StoryText
import dev.brahmkshatriya.echo.vibes.games.ClipPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

const val MAX_VIDEO_MS = 60_000L
const val PHOTO_DEFAULT_MS = 10_000L

val textAnims = listOf("none" to "Nessuna", "pop" to "Pop", "typewriter" to "Macchina", "fade" to "Dissolvenza",
    "bounce" to "Rimbalzo", "wave" to "Onda", "glow" to "Brillio")
val textColors = listOf("#FFFFFF", "#000000", "#FF2E97", "#FFD43B", "#1ED760", "#35D0F5", "#B84DFF", "#FF8A2B")

// =====================================================================================  shared pieces

/** Story text with its animation (also used live in the editor). */
@Composable
fun AnimatedStoryText(t: StoryText, key: Any, modifier: Modifier = Modifier) {
    val color = parseColor(t.color)
    val base = TextStyle(
        color = color, fontSize = t.size.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
        shadow = if (t.bg) null else Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f)
    )
    val appear = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        appear.snapTo(0f)
        appear.animateTo(1f, when (t.anim) {
            "pop" -> spring(dampingRatio = 0.45f, stiffness = 300f)
            "typewriter" -> tween((t.text.length * 55).coerceIn(300, 2500), easing = LinearEasing)
            "none" -> tween(0)
            else -> tween(700, easing = FastOutSlowInEasing)
        })
    }
    val loop = rememberInfiniteTransition(label = "txt")
    val phase by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "ph")
    val glow by loop.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "gl")
    val bgMod = if (t.bg) Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp) else Modifier
    when (t.anim) {
        "wave" -> Row(modifier.then(bgMod)) {
            t.text.forEachIndexed { i, c ->
                Text(c.toString(), style = base, modifier = Modifier.graphicsLayer {
                    translationY = sin((phase * 2 * PI + i * 0.5)).toFloat() * 10f
                    alpha = appear.value
                })
            }
        }
        else -> Text(
            if (t.anim == "typewriter") t.text.take((t.text.length * appear.value).roundToInt()) else t.text,
            style = if (t.anim == "glow") base.copy(shadow = Shadow(color.copy(alpha = glow), blurRadius = 30f * glow)) else base,
            modifier = modifier.then(bgMod).graphicsLayer {
                when (t.anim) {
                    "pop" -> { scaleX = appear.value; scaleY = appear.value }
                    "fade" -> { alpha = appear.value; translationY = (1 - appear.value) * 30f }
                    "bounce" -> { translationY = -kotlin.math.abs(sin(phase * 2 * PI)).toFloat() * 18f; alpha = appear.value }
                }
            }
        )
    }
}

/** Places story texts on the screen (x/y are fractions of it). */
@Composable
fun StoryTextsLayer(texts: List<StoryText>, key: Any, onMove: ((Int, Float, Float) -> Unit)? = null, onTap: ((Int) -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        texts.forEachIndexed { i, t ->
            var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            Box(
                Modifier
                    .offset { IntOffset((t.x * w - size.width / 2).roundToInt(), (t.y * h - size.height / 2).roundToInt()) }
                    .onSizeChanged { size = it }
                    .then(if (onMove != null) Modifier.pointerInput(i) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onMove(i, ((texts[i].x * w + drag.x) / w).coerceIn(0.05f, 0.95f), ((texts[i].y * h + drag.y) / h).coerceIn(0.05f, 0.95f))
                        }
                    } else Modifier)
                    .then(if (onTap != null) Modifier.clickable { onTap(i) } else Modifier)
            ) { AnimatedStoryText(t, "$key-$i-${t.anim}-${t.text.length}") }
        }
    }
}

/** Photo or video of a slide, with zoom/pan ("crop"). Video loops its trimmed part. */
@Composable
fun SlideMedia(
    type: String, source: String, trimStartMs: Long, durationMs: Long, volume: Float,
    zoom: Float, offX: Float, offY: Float, playing: Boolean = true, modifier: Modifier = Modifier,
) {
    val tf = Modifier.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offX * size.width; translationY = offY * size.height }
    if (type == "image") {
        AsyncImage(source, null, contentScale = ContentScale.Crop, modifier = modifier.fillMaxSize().then(tf))
        return
    }
    val context = LocalContext.current
    val player = remember(source) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
        }
    }
    LaunchedEffect(source, trimStartMs, durationMs) {
        player.setMediaItem(
            MediaItem.Builder().setUri(source).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(trimStartMs).setEndPositionMs(trimStartMs + durationMs).build()
            ).build()
        )
        player.prepare()
    }
    LaunchedEffect(volume) { player.volume = volume }
    LaunchedEffect(playing) { if (playing) player.play() else player.pause() }
    DisposableEffect(source) { onDispose { player.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; this.player = player } },
        update = { it.player = player },
        modifier = modifier.fillMaxSize().then(tf)
    )
}

/**
 * Song sticker: appears big in the middle (like the reveal in the guessing game), then shrinks into
 * the bottom-right corner where the cover "breathes" on the beat next to 3 white sound bars.
 */
@Composable
fun SongSticker(song: SongRef, bpm: Float, key: Any, paused: Boolean) {
    val t = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        t.snapTo(0f)
        delay(1300)
        t.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    val intro = remember(key) { Animatable(0.5f) }
    LaunchedEffect(key) { intro.snapTo(0.5f); intro.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
    val beatMs = (60_000f / (if (bpm > 40) bpm else 110f)).toInt()
    val beat by rememberInfiniteTransition(label = "beat").animateFloat(1f, if (paused) 1f else 1.09f,
        infiniteRepeatable(tween(beatMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bt")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val big = 230.dp; val small = 54.dp
        val p = t.value
        val sizeDp = big + (small - big) * p
        val bigX = (maxWidth - big) / 2; val bigY = (maxHeight - big) / 2 - 40.dp
        val smallX = maxWidth - small - 70.dp; val smallY = maxHeight - small - 96.dp
        val x = bigX + (smallX - bigX) * p; val y = bigY + (smallY - bigY) * p
        Box(Modifier.offset(x, y)) {
            Cover(song.cover, Modifier.size(sizeDp).graphicsLayer {
                val s = (if (p >= 1f) beat else 1f) * intro.value
                scaleX = s; scaleY = s
            }.shadow(20.dp, RoundedCornerShape(10.dp)), RoundedCornerShape(10.dp))
        }
        // title under the big cover, fades while it shrinks
        if (p < 1f) Column(
            Modifier.offset(0.dp, bigY + big + 14.dp).fillMaxWidth().graphicsLayer { alpha = (1f - p * 2).coerceIn(0f, 1f) * intro.value },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(song.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center, maxLines = 2)
            Text(song.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
        }
        if (p >= 1f) Row(Modifier.offset(smallX + small + 10.dp, smallY + small / 2 - 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!paused) EqualizerBars(Modifier.size(22.dp), color = Color.White)
        }
        if (p >= 1f) Column(
            Modifier.offset(16.dp, smallY + 6.dp).width(maxWidth - small - 120.dp), horizontalAlignment = Alignment.End
        ) {
            Text(song.title, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        }
        @Suppress("UNUSED_VARIABLE") val unused = d
    }
}

// =====================================================================================  editor

private data class EditSlide(
    val uri: Uri, val type: String, val lengthMs: Long,
    val durationMs: Long, val trimStartMs: Long = 0, val volume: Float = 1f,
    val zoom: Float = 1f, val offX: Float = 0f, val offY: Float = 0f,
    val texts: List<StoryText> = emptyList(),
)

private fun videoLength(context: Context, uri: Uri): Long = runCatching {
    android.media.MediaMetadataRetriever().run {
        setDataSource(context, uri)
        val d = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        release(); d
    }
}.getOrDefault(0L)

private fun slideOf(context: Context, uri: Uri): EditSlide {
    val mime = context.contentResolver.getType(uri) ?: ""
    val video = mime.startsWith("video") || uri.toString().endsWith(".mp4")
    return if (video) {
        val len = videoLength(context, uri).coerceAtLeast(1000)
        EditSlide(uri, "video", len, minOf(len, MAX_VIDEO_MS))
    } else EditSlide(uri, "image", 0, PHOTO_DEFAULT_MS)
}

private enum class Tool { NONE, MUSIC, TEXT, TIME, VOLUME, CROP }

@Composable
fun NewStoryScreen(initial: SongRef?) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slides = remember { mutableStateListOf<EditSlide>() }
    var current by remember { mutableIntStateOf(0) }
    var song by remember { mutableStateOf(initial) }
    var dzId by remember { mutableStateOf<Long?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var bpm by remember { mutableFloatStateOf(0f) }
    var songStart by remember { mutableFloatStateOf(0f) }
    var songVolume by remember { mutableFloatStateOf(1f) }
    var gradient by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(Tool.NONE) }
    var picking by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf(StoryText("")) }
    var posting by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(initial != null) }
    val clip = remember { ClipPlayer(context) }
    DisposableEffect(Unit) { app.engine.pause(); onDispose { clip.release() } }

    // capture / pick
    val captureFile = remember { File(context.cacheDir, "story_capture").apply { mkdirs() } }
    var pendingCapture by remember { mutableStateOf<Uri?>(null) }
    fun newCaptureUri(ext: String): Uri {
        val f = File(captureFile, "cap_${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(context, context.packageName + ".provider", f)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { u -> runCatching { slides += slideOf(context, u) } }
        if (uris.isNotEmpty()) { started = true; current = slides.lastIndex.coerceAtLeast(0) }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingCapture?.let { if (ok) { slides += EditSlide(it, "image", 0, PHOTO_DEFAULT_MS); started = true; current = slides.lastIndex } }
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        pendingCapture?.let { if (ok) { slides += slideOf(context, it).copy(type = "video"); started = true; current = slides.lastIndex } }
    }
    val pickGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    val takePhoto = { newCaptureUri("jpg").also { pendingCapture = it; photo.launch(it) }; Unit }
    val takeVideo = { newCaptureUri("mp4").also { pendingCapture = it; video.launch(it) }; Unit }

    // song preview
    LaunchedEffect(song?.key) {
        val s = song ?: return@LaunchedEffect
        dzId = runCatching { VibesGraph.deezer.deezerId(s) }.getOrNull()
        preview = runCatching { VibesGraph.deezer.freshPreview(dzId, s) }.getOrNull()
        bpm = dzId?.let { runCatching { VibesGraph.deezer.bpm(it) }.getOrNull() } ?: 0f
        preview?.let { clip.load(it); clip.playFrom(songStart.toLong(), songVolume) }
    }
    LaunchedEffect(songVolume) { clip.setVolume(songVolume) }

    // ---------------------------------------------------------------- start: choose what to post
    if (!started) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A0E45), Color.Black))).statusBarsPadding().navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.Rounded.Close, "Chiudi", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Text("Crea una storia", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text("Una foto o un video, con la canzone che vuoi in sottofondo.", color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
            StudioChoice("📸", "Scatta una foto") { takePhoto() }
            StudioChoice("🎥", "Registra un video") { takeVideo() }
            StudioChoice("🖼️", "Dalla galleria (anche più foto/video)") { pickGallery() }
            StudioChoice("🎵", "Solo musica") { started = true; if (song == null) picking = true }
            Spacer(Modifier.weight(1.4f))
        }
        if (picking) GlobalSongPicker(onDismiss = { picking = false }) { song = it; picking = false }
        return
    }

    val s = slides.getOrNull(current)
    val (top, bottom) = storyGradients[gradient]
    fun update(block: (EditSlide) -> EditSlide) { if (current in slides.indices) slides[current] = block(slides[current]) }
    var tip by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(6000); tip = false }
    var removing by remember { mutableStateOf<Int?>(null) }
    fun newText() { if (s == null) nav.toast("Aggiungi prima una foto o un video") else { draft = StoryText(""); editingText = -1; tool = Tool.TEXT } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------------------------------------------------------------- canvas (rounded like a phone story)
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 92.dp).navigationBarsPadding().clip(RoundedCornerShape(18.dp))
            .then(if (tool == Tool.CROP && s != null) Modifier.pointerInput(current) {
                detectTransformGestures { _, pan, zoomBy, _ ->
                    update { e -> e.copy(zoom = (e.zoom * zoomBy).coerceIn(1f, 4f),
                        offX = (e.offX + pan.x / size.width).coerceIn(-1f, 1f), offY = (e.offY + pan.y / size.height).coerceIn(-1f, 1f)) }
                }
            } else Modifier.pointerInput(current, tool) {
                // tap on an empty spot = write text there
                androidx.compose.foundation.gestures.detectTapGestures { o ->
                    if (tool == Tool.NONE && s != null) { draft = StoryText("", x = o.x / size.width, y = o.y / size.height); editingText = -1; tool = Tool.TEXT }
                    else if (tool != Tool.TEXT) tool = Tool.NONE
                }
            })
        ) {
            if (s == null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(parseColor(top), parseColor(bottom)))))
            else AnimatedContent(current, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "slide") { idx ->
                slides.getOrNull(idx)?.let { e ->
                    SlideMedia(e.type, e.uri.toString(), e.trimStartMs, e.durationMs, e.volume, e.zoom, e.offX, e.offY)
                }
            }
            s?.let { e ->
                StoryTextsLayer(e.texts, "edit$current",
                    onMove = { i, x, y -> update { it.copy(texts = it.texts.toMutableList().also { l -> l[i] = l[i].copy(x = x, y = y) }) } },
                    onTap = { i -> draft = e.texts[i]; editingText = i; tool = Tool.TEXT })
            }
            song?.let { SongSticker(it, bpm, "edit${it.key}", false) }
            // first-time hint
            androidx.compose.animation.AnimatedVisibility(tip && s != null, Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                enter = fadeIn(), exit = fadeOut()) {
                Text("Tocca lo schermo per scrivere · trascina il testo per spostarlo", color = Color.White,
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            // close
            IconButton(onClick = { nav.back() }, modifier = Modifier.padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))) {
                Icon(Icons.Rounded.Close, "Annulla", tint = Color.White)
            }
            // ---- tool rail on the right, Instagram style
            Column(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioTool("Aa", "Testo", tool == Tool.TEXT) { newText() }
                StudioTool("🎵", "Musica", tool == Tool.MUSIC) { tool = if (tool == Tool.MUSIC) Tool.NONE else Tool.MUSIC }
                if (s != null) {
                    StudioTool("⏱️", if (s.type == "video") "Taglia" else "Durata", tool == Tool.TIME) { tool = if (tool == Tool.TIME) Tool.NONE else Tool.TIME }
                    StudioTool("✂️", "Ritaglia", tool == Tool.CROP) { tool = if (tool == Tool.CROP) Tool.NONE else Tool.CROP }
                }
                StudioTool("🔊", "Volume", tool == Tool.VOLUME) { tool = if (tool == Tool.VOLUME) Tool.NONE else Tool.VOLUME }
                StudioTool("➕", "Aggiungi", false) { pickGallery() }
            }
        }

        // ---------------------------------------------------------------- bottom: panel + slides + share
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()) {
            AnimatedContent(tool, transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut()) }, label = "panel") { t ->
                if (t != Tool.NONE) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(Color(0xF0121016)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // panel header: what this tool does + done
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(when (t) {
                            Tool.MUSIC -> "🎵 Musica della storia"; Tool.TEXT -> "Aa Testo"; Tool.TIME -> if (s?.type == "video") "⏱️ Taglia il video" else "⏱️ Quanto dura la foto"
                            Tool.VOLUME -> "🔊 Volumi"; Tool.CROP -> "✂️ Ritaglia"; Tool.NONE -> ""
                        }, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (t == Tool.TEXT && editingText != null && editingText!! >= 0) Pill("Elimina", false, {
                            val i = editingText!!; update { it.copy(texts = it.texts.filterIndexed { j, _ -> j != i }) }; editingText = null; tool = Tool.NONE
                        })
                        Spacer(Modifier.width(8.dp))
                        Pill("Fatto ✓", true, {
                            if (t == Tool.TEXT) {
                                val i = editingText
                                if (draft.text.isNotBlank()) update { e ->
                                    if (i == null || i < 0) e.copy(texts = e.texts + draft)
                                    else e.copy(texts = e.texts.toMutableList().also { it[i] = draft })
                                }
                                editingText = null
                            }
                            tool = Tool.NONE
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    when (t) {
                        Tool.MUSIC -> {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).clickable { picking = true }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Cover(song?.cover, Modifier.size(46.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song?.title ?: "Scegli un brano", color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(song?.artist ?: "Tocca per cercare tra tutte le canzoni", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                Text("Cambia", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                            StudioSlider("Da che punto parte", fmtTime(songStart.toLong()), songStart, 0f, 25_000f,
                                onChange = { songStart = it }, onDone = { clip.playFrom(songStart.toLong(), songVolume) })
                            if (s == null) {
                                Text("Sfondo", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                                    itemsIndexed(storyGradients) { i, (a, b) ->
                                        Box(Modifier.size(34.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(parseColor(a), parseColor(b))))
                                            .border(if (i == gradient) 3.dp else 1.dp, Color.White, CircleShape).clickable { gradient = i })
                                    }
                                }
                            }
                        }
                        Tool.TEXT -> {
                            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)).padding(12.dp)) {
                                BasicTextField(draft.text, { draft = draft.copy(text = it.take(120)) },
                                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp), cursorBrush = SolidColor(Color.White),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { inner -> if (draft.text.isEmpty()) Text("Scrivi qualcosa…", color = Color.White.copy(alpha = 0.6f)); inner() })
                            }
                            Text("Colore", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                                itemsIndexed(textColors) { _, c ->
                                    Box(Modifier.size(30.dp).clip(CircleShape).background(parseColor(c))
                                        .border(if (draft.color == c) 3.dp else 1.dp, Color.White, CircleShape).clickable { draft = draft.copy(color = c) })
                                }
                                item { Pill(if (draft.bg) "Riquadro ✓" else "Riquadro", draft.bg, { draft = draft.copy(bg = !draft.bg) }) }
                            }
                            Text("Animazione", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                                itemsIndexed(textAnims) { _, (k, l) -> Pill(l, draft.anim == k, { draft = draft.copy(anim = k) }) }
                            }
                            StudioSlider("Grandezza", "${draft.size.toInt()}", draft.size, 16f, 64f, onChange = { draft = draft.copy(size = it) })
                        }
                        Tool.TIME -> s?.let { e ->
                            if (e.type == "image") StudioSlider("La foto resta per", "${e.durationMs / 1000} secondi", e.durationMs.toFloat(), 3000f, 30_000f,
                                onChange = { v -> update { it.copy(durationMs = (v / 1000).roundToInt() * 1000L) } })
                            else {
                                val maxEnd = e.lengthMs.toFloat()
                                val range = e.trimStartMs.toFloat()..(e.trimStartMs + e.durationMs).toFloat().coerceAtMost(maxEnd)
                                Row {
                                    Text("Da ${fmtTime(e.trimStartMs)} a ${fmtTime(e.trimStartMs + e.durationMs)}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    Text("${e.durationMs / 1000} s", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                                }
                                androidx.compose.material3.RangeSlider(range, { r ->
                                    var start = r.start.toLong(); var end = r.endInclusive.toLong()
                                    if (end - start < 1000) end = (start + 1000).coerceAtMost(e.lengthMs)
                                    if (end - start > MAX_VIDEO_MS) { if (start != e.trimStartMs) end = start + MAX_VIDEO_MS else start = end - MAX_VIDEO_MS }
                                    update { it.copy(trimStartMs = start, durationMs = end - start) }
                                }, valueRange = 0f..maxEnd,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
                                Text("Il video dura ${fmtTime(e.lengthMs)}: sposta i due cerchi per scegliere il pezzo${if (e.lengthMs > MAX_VIDEO_MS) " (max 60 s)" else ""}.",
                                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Tool.VOLUME -> {
                            StudioSlider("Canzone", if (songVolume == 0f) "muta" else "${(songVolume * 100).toInt()}%", songVolume, 0f, 1f, onChange = { songVolume = it })
                            if (s?.type == "video") StudioSlider("Audio del video", if (s.volume == 0f) "muto" else "${(s.volume * 100).toInt()}%", s.volume, 0f, 1f,
                                onChange = { v -> update { it.copy(volume = v) } })
                        }
                        Tool.CROP -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Allarga con due dita per zoomare, trascina per spostare", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Pill("Reimposta", false, { update { it.copy(zoom = 1f, offX = 0f, offY = 0f) } })
                        }
                        Tool.NONE -> {}
                    }
                }
            }
            // slides + share
            Row(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(slides) { i, e ->
                        Box(Modifier.size(width = 46.dp, height = 66.dp).clip(RoundedCornerShape(8.dp))
                            .border(if (i == current) 2.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))
                            .pointerInput(i) { androidx.compose.foundation.gestures.detectTapGestures(onTap = { current = i }, onLongPress = { removing = i }) }) {
                            AsyncImage(e.uri.toString(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            Text("${e.durationMs / 1000}s", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd)
                                .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 3.dp))
                            if (e.type == "video") Text("▶", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(3.dp))
                        }
                    }
                    item {
                        Box(Modifier.size(width = 46.dp, height = 66.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.15f))
                            .clickable { pickGallery() }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Aggiungi", tint = Color.White) }
                    }
                }
                Spacer(Modifier.width(10.dp))
                BigButton(posting ?: "Condividi ›", {
                    val sng = song ?: run { tool = Tool.MUSIC; picking = true; return@BigButton }
                    scope.launch {
                        runCatching {
                            val media = slides.mapIndexed { i, e ->
                                posting = "Carico ${i + 1}/${slides.size}…"
                                val file = withContext(Dispatchers.IO) { prepareUpload(context, e.uri, e.type == "video") }
                                val url = app.social.uploadStoryFile(file, e.type == "video")
                                file.delete()
                                StoryMedia(e.type, url, e.durationMs, e.trimStartMs, e.volume, e.zoom, e.offX, e.offY, e.texts)
                            }
                            posting = "Pubblico…"
                            app.social.postStory(sng, "", StoryStyle(top, bottom, preview, dzId, songStart.toLong(), songVolume, bpm), media)
                        }.onSuccess { nav.toast("Storia pubblicata ✨"); nav.back() }
                            .onFailure { nav.toast(it.message ?: "Non sono riuscito a pubblicarla") }
                        posting = null
                    }
                }, Modifier.width(150.dp), loading = posting != null, container = Color.White, content = Color.Black)
            }
        }
    }
    removing?.let { i ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Togliere questa ${if (slides.getOrNull(i)?.type == "video") "clip" else "foto"}?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                if (i in slides.indices) slides.removeAt(i); current = current.coerceAtMost(slides.lastIndex).coerceAtLeast(0); removing = null
            }) { Text("Togli") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removing = null }) { Text("Annulla") } }
        )
    }
    if (picking) GlobalSongPicker(onDismiss = { picking = false }) { song = it; picking = false }
}

/** Photos: resized JPEG. Videos: copied as they are (max ~60 MB); trimming is applied on playback. */
private fun prepareUpload(context: Context, uri: Uri, video: Boolean): File {
    val out = File(context.cacheDir, "story_up_${System.currentTimeMillis()}.${if (video) "mp4" else "jpg"}")
    if (video) {
        context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        if (out.length() > 60L * 1024 * 1024) { out.delete(); error("Video troppo pesante (max 60 MB): accorcialo o registralo a qualità più bassa") }
        return out
    }
    val bmp = context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
    val rotation = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { android.media.ExifInterface(it) }
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    val deg = when (rotation) { android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f; android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
    val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
    val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(deg) }
    val fixed = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    out.outputStream().use { fixed.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, it) }
    return out
}

@Composable
private fun StudioChoice(emoji: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.1f))
            .bouncy(onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 28.sp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

@Composable
private fun StudioTool(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.bouncy(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(if (selected) Color.White else Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 20.sp, color = if (selected) Color.Black else Color.White, fontWeight = FontWeight.Bold)
        }
        Text(label, fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun StudioSlider(title: String, label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row {
            Text(title, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value.coerceIn(min, max), onChange, valueRange = min..max, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
    }
}
