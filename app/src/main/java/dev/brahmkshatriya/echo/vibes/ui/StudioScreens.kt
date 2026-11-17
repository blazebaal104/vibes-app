package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.BookmarkBorder

import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.vibes.backend.Sample
import dev.brahmkshatriya.echo.vibes.backend.Upload
import dev.brahmkshatriya.echo.vibes.backend.VibesAlbum
import dev.brahmkshatriya.echo.vibes.music.StudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun Sample.item() = StudioPlayer.Item(id, name, listOfNotNull(bpm?.let { "$it BPM" }, key, genre.ifBlank { null }).joinToString(" · "), null, url, sample = true)

/**
 * Songs published on Vibes play in the real player (queue, notification, lock screen, stats), not in
 * the little studio player. The play is counted for the artist only after a THIRD of the song has
 * really been heard (ListenTracker -> Social.countUploadPlay).
 */
fun playUploads(app: AppState, list: List<Upload>, index: Int) {
    if (list.isEmpty()) return
    app.engine.playAll(list.map { dev.brahmkshatriya.echo.vibes.music.VibesUploads.songOf(it) }, index)
}

private fun copyToCache(context: Context, uri: Uri, ext: String): File {
    val f = File(context.cacheDir, "up_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
    context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    return f
}

private fun audioMeta(context: Context, uri: Uri): Pair<String?, Long> = runCatching {
    android.media.MediaMetadataRetriever().run {
        setDataSource(context, uri)
        val t = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
        val d = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        release(); t to d
    }
}.getOrDefault(null to 0L)

private fun fileName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull()?.substringBeforeLast('.') ?: "Brano"

/** Bar shown above the mini player while user music / samples play. */
@Composable
fun StudioBar() {
    val st by VibesGraph.studio.state.collectAsState()
    AnimatedVisibility(st != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val s = st ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .glass(RoundedCornerShape(14.dp), tint = Color(0xFFFF8A2B), blur = true)
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A2B).copy(alpha = 0.55f), Color(0xFFB84DFF).copy(alpha = 0.45f)))).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (s.item.cover != null) Cover(s.item.cover, Modifier.size(36.dp)) else Text(if (s.item.sample) "🎛️" else "🎙️", fontSize = 24.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.item.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.item.artist, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
            }
            IconButton(onClick = { VibesGraph.studio.toggle() }) {
                Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White)
            }
            IconButton(onClick = { VibesGraph.studio.stop() }) { Icon(Icons.Rounded.Close, "Chiudi", tint = Color.White) }
        }
    }
}

// =====================================================================================  hub

/** Aurora palettes the user can pick for the Studio ("vibe dello studio"). */
val studioVibes = listOf(
    "neon" to listOf(Color(0xFFFF2E97), Color(0xFF35D0F5), Color(0xFFB84DFF)),
    "tramonto" to listOf(Color(0xFFFF8A2B), Color(0xFFFF2E97), Color(0xFFFFD43B)),
    "oceano" to listOf(Color(0xFF1E6BFF), Color(0xFF00C2A8), Color(0xFF35D0F5)),
    "vaporwave" to listOf(Color(0xFFFF71CE), Color(0xFF01CDFE), Color(0xFFB967FF)),
    "foresta" to listOf(Color(0xFF1ED760), Color(0xFF0B6E4F), Color(0xFFC6F432)),
    "mono" to listOf(Color(0xFFDDDDDD), Color(0xFF777777), Color(0xFF333333)),
)
private val vibeNames = mapOf("neon" to "Neon", "tramonto" to "Tramonto", "oceano" to "Oceano", "vaporwave" to "Vaporwave", "foresta" to "Foresta", "mono" to "Mono")

/** Slowly drifting aurora blobs behind the Studio. */
@Composable
fun Aurora(colors: List<Color>, modifier: Modifier = Modifier) {
    val p1 = liveFloat(0.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(9000), androidx.compose.animation.core.RepeatMode.Reverse), "a1")
    val p2 = liveFloat(0.5f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(13000), androidx.compose.animation.core.RepeatMode.Reverse), "a2")
    val p3 = liveFloat(0.2f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(11000), androidx.compose.animation.core.RepeatMode.Reverse), "a3")
    // PC: transparent, so the app's own background shows through (no black slab beside the content)
    val bg = if (PlatformUI.transparentScreens) Color.Transparent else MaterialTheme.colorScheme.background
    androidx.compose.foundation.Canvas(modifier.fillMaxSize().background(bg)) {
        fun blob(c: Color, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.55f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(cx, cy), radius = r),
                radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy))
        val w = size.width; val h = size.height
        blob(colors[0], w * (0.15f + 0.5f * p1), h * (0.10f + 0.15f * p2), w * 0.75f)
        blob(colors[1], w * (0.85f - 0.4f * p2), h * (0.30f + 0.2f * p3), w * 0.7f)
        blob(colors[2], w * (0.3f + 0.4f * p3), h * (0.55f + 0.2f * p1), w * 0.8f)
        if (!PlatformUI.transparentScreens) drawRect(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.7f), bg), startY = h * 0.35f))
    }
}

/** Search box without the floating label (no jumping text): glass, search icon, clear button. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), fallback = LocalVibes.current.card).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Icons.Rounded.Close, "Cancella", tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { onChange("") })
    }
}

@Composable
fun StudioScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val prefs = LocalSettings.current
    val following by app.social.following.collectAsState()
    var latest by remember { mutableStateOf<List<Upload>?>(null) }
    var trending by remember { mutableStateOf<List<Upload>>(emptyList()) }
    var friends by remember { mutableStateOf<List<Upload>>(emptyList()) }
    var mine by remember { mutableStateOf<List<Upload>>(emptyList()) }
    var likes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var people by remember { mutableStateOf<Map<String, Profile>>(emptyMap()) }
    var vibePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(following.size) {
        latest = runCatching { app.social.latestUploads(30) }.getOrDefault(emptyList())
        trending = runCatching { app.social.trendingUploads(15) }.getOrDefault(emptyList()).filter { it.plays > 0 || it.likes > 0 }
        friends = runCatching { app.social.uploadsFrom(following, 20) }.getOrDefault(emptyList())
        mine = app.social.uid?.let { runCatching { app.social.uploadsOf(it) }.getOrNull() }.orEmpty()
        likes = runCatching { app.social.myLikes() }.getOrDefault(emptySet())
        val ids = (latest.orEmpty() + trending + friends).map { it.userId }.distinct()
        people = ids.mapNotNull { id -> runCatching { app.social.profile(id) }.getOrNull() }.associateBy { it.id }
    }
    val colors = studioVibes.firstOrNull { it.first == prefs.studioVibe }?.second ?: studioVibes[0].second
    fun toggleLike(u: Upload) {
        val on = u.id !in likes
        likes = if (on) likes + u.id else likes - u.id
        scope.launch { runCatching { app.social.like(u.id, on) } }
    }
    Box(Modifier.fillMaxSize()) {
        Aurora(colors)
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(start = 18.dp, end = 10.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Studio", style = MaterialTheme.typography.displaySmall)
                        Text("Crea, pubblica, campiona.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(46.dp).glass(CircleShape).bouncy({ vibePicker = !vibePicker }), contentAlignment = Alignment.Center) { Text("🎨", fontSize = 22.sp) }
                }
                AnimatedVisibility(vibePicker) {
                    LazyRow(contentPadding = PaddingValues(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(studioVibes, key = { it.first }) { (k, c) ->
                            Row(Modifier.glass(CircleShape).background(if (prefs.studioVibe == k) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                                .bouncy({ VibesGraph.prefs.update { it.copy(studioVibe = k) } }).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(18.dp).clip(CircleShape).background(Brush.linearGradient(c)))
                                Spacer(Modifier.width(6.dp))
                                Text(vibeNames[k] ?: k, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            // the two halves
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudioHalf("🎙️", "Pubblica", "I tuoi brani e album", colors[0], Modifier.weight(1f)) { nav.open(Route.Publish) }
                    StudioHalf("🎛️", "Sample", "Loop e one-shot", colors[2], Modifier.weight(1f)) { nav.open(Route.Samples) }
                }
            }
            // quick actions
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { QuickChip("📊", "Statistiche") { nav.open(Route.StudioStats) } }
                    item { QuickChip("💿", "Pubblica album") { nav.open(Route.Publish) } }
                    item { QuickChip("🎲", "Sample a caso") { nav.open(Route.Samples); VibesGraph.prefs.update { it } ; nav.toast("Premi 🎲 nella libreria per un sample a sorpresa") } }
                    item { QuickChip("❤️", "Sample preferiti") { nav.open(Route.Samples) } }
                    item { QuickChip("➕", "Carica sample") { nav.open(Route.Samples) } }
                    item { QuickChip("👤", "Il mio profilo") { nav.open(Route.Profile(null)) } }
                }
            }
            if (mine.isNotEmpty()) item {
                Row(Modifier.padding(16.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp)).bouncy({ nav.open(Route.StudioStats) }).padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    StatBubble("${mine.size}", "brani")
                    StatBubble(fmtInt(mine.sumOf { it.plays }), "ascolti")
                    StatBubble(fmtInt(mine.sumOf { it.likes }), "❤️")
                    StatBubble(fmtInt(mine.sumOf { it.saves }), "🔖")
                }
            }
            if (trending.isNotEmpty()) {
                item { SectionTitle("🔥 Di tendenza") }
                itemsIndexed(trending, key = { _, u -> "t" + u.id }) { i, u ->
                    UploadRow(u, people[u.userId], Modifier.enter(i), liked = u.id in likes, onLike = { toggleLike(u) }) { playUploads(app, trending, i) }
                }
            }
            if (friends.isNotEmpty()) {
                item { SectionTitle("👥 Da chi segui") }
                itemsIndexed(friends, key = { _, u -> "f" + u.id }) { i, u ->
                    UploadRow(u, people[u.userId], Modifier.enter(i), liked = u.id in likes, onLike = { toggleLike(u) }) { playUploads(app, friends, i) }
                }
            }
            item { SectionTitle("🆕 Nuovi dai Viber") }
            val list = latest
            if (list == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            else if (list.isEmpty()) item { EmptyState("🎙️", "Ancora nessun brano", "Sii il primo a pubblicare la tua musica su Vibes.") }
            else itemsIndexed(list, key = { _, u -> "n" + u.id }) { i, u ->
                UploadRow(u, people[u.userId], Modifier.enter(i), liked = u.id in likes, onLike = { toggleLike(u) }) { playUploads(app, list, i) }
            }
        }
    }
}

@Composable
private fun StudioHalf(emoji: String, title: String, sub: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val float = liveFloat(-6f, 6f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800), androidx.compose.animation.core.RepeatMode.Reverse), "fl")
    Column(modifier.height(190.dp).glass(RoundedCornerShape(26.dp), tint = color)
        .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent))).bouncy(onClick).padding(16.dp)) {
        Text(emoji, fontSize = 46.sp, modifier = Modifier.graphicsLayer { translationY = float })
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickChip(emoji: String, label: String, onClick: () -> Unit) {
    Row(Modifier.glass(CircleShape).bouncy(onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 16.sp); Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatBubble(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StudioTile(emoji: String, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.4f)))).bouncy(onClick).padding(16.dp)) {
        Text(emoji, fontSize = 30.sp)
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun UploadRow(u: Upload, owner: Profile?, modifier: Modifier = Modifier, onDelete: (() -> Unit)? = null,
              liked: Boolean? = null, onLike: (() -> Unit)? = null, onClick: () -> Unit) {
    val nav = LocalApp.current.nav
    val cur by LocalApp.current.engine.player.playerState.current.collectAsState()
    val playing = cur?.track?.id == dev.brahmkshatriya.echo.vibes.music.VibesUploads.PREFIX + u.id
    val saved by VibesUploadSaves.ids.collectAsState()
    LaunchedEffect(Unit) { VibesUploadSaves.ensure() }
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Cover(u.cover, Modifier.size(54.dp))
            if (playing && cur?.isPlaying == true) EqualizerBars(Modifier.align(Alignment.Center).size(20.dp), color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(listOfNotNull(if (u.explicit) "🅴" else null, u.artist.ifBlank { owner?.name }, u.album, "${fmtInt(u.plays)} ascolti",
                if (u.visibility != "public") "🔒 follower" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        owner?.let { Avatar(it, 28.dp, Modifier.bouncy({ nav.open(Route.Profile(it.id)) })) }
        if (onLike != null) {
            val sc by androidx.compose.animation.core.animateFloatAsState(if (liked == true) 1.2f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f), label = "lk")
            Text(if (liked == true) "❤️" else "🤍", fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape).clickable { onLike() }.padding(4.dp))
        }
        val isSaved = u.id in saved
        IconButton(onClick = { VibesUploadSaves.toggle(u, !isSaved); nav.toast(if (isSaved) "Tolto dai salvati" else "Salvato nella tua libreria") }) {
            Icon(if (isSaved) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkBorder, if (isSaved) "Togli dai salvati" else "Salva")
        }
        IconButton(onClick = { nav.addToPlaylist = dev.brahmkshatriya.echo.vibes.music.VibesUploads.songOf(u) }) {
            Icon(Icons.Rounded.PlaylistAdd, "Aggiungi a playlist")
        }
        onDelete?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Delete, "Elimina") } }
    }
}

/** Profile section: songs the user published. */
@Composable
fun ProfileUploads(p: Profile, isMe: Boolean) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var list by remember(p.id) { mutableStateOf<List<Upload>>(emptyList()) }
    LaunchedEffect(p.id) { list = runCatching { app.social.uploadsOf(p.id) }.getOrDefault(emptyList()) }
    if (list.isEmpty() && !isMe) return
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(if (isMe) "La tua musica" else "La musica di ${p.name}", Modifier.weight(1f))
            if (isMe) Pill("Pubblica", true, { app.nav.open(Route.Publish) }, Modifier.padding(end = 16.dp))
        }
        if (list.isEmpty()) Text("Pubblica i tuoi brani: li sentiranno tutti su Vibes.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        list.forEachIndexed { i, u ->
            UploadRow(u, null, onDelete = if (isMe) ({ scope.launch { runCatching { app.social.deleteUpload(u.id) }; list = list - u } }) else null) {
                playUploads(app, list, i)
            }
        }
    }
}

// =====================================================================================  publish

/** One song of a release being prepared: a new file from the device, or a single already on Vibes. */
private sealed interface DraftTrack {
    val title: String
    val durationMs: Long
    data class File(val uri: Uri, override val title: String, override val durationMs: Long) : DraftTrack
    data class Existing(val upload: Upload) : DraftTrack {
        override val title get() = upload.title
        override val durationMs get() = upload.durationMs
    }
}

/**
 * Publish: first you choose WHAT you publish.
 *  - Singolo: one song on its own.
 *  - Album / EP: several songs in order, taken from files on the device, from singles you already
 *    published, or both (a single stays a single AND becomes part of the album).
 */
@Composable
fun PublishScreen() {
    val nav = LocalApp.current.nav
    var mode by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(mode != null) { mode = null }
    when (mode) {
        null -> GradientBackground(tint = Color(0xFFFF8A2B)) {
            Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Cosa pubblichi?", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.height(10.dp))
                PublishChoice("🎵", "Singolo", "Un brano da solo. Lo potrai mettere in un album anche più avanti.", Color(0xFFFF8A2B)) { mode = "single" }
                PublishChoice("💿", "Album / EP", "Più brani in ordine: file nuovi dal dispositivo, singoli che hai già pubblicato, o tutti e due.", Color(0xFF8A5CFF)) { mode = "album" }
                PublishChoice("📊", "Statistiche", "Ascolti, ascoltatori, salvati e preferiti dei tuoi brani.", Color(0xFF2BB6FF)) { nav.open(Route.StudioStats) }
            }
        }
        else -> PublishForm(album = mode == "album")
    }
}

@Composable
private fun PublishChoice(emoji: String, title: String, sub: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 7.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), tint = color)
        .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent))).bouncy(onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 34.sp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PublishForm(album: Boolean) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by app.social.me.collectAsState()
    val tracks = remember { mutableStateListOf<DraftTrack>() }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var artist by remember { mutableStateOf(me?.name ?: "") }
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var followersOnly by remember { mutableStateOf(false) }
    var explicit by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var pickSingles by remember { mutableStateOf(false) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val take = if (album) uris else uris.take(1)
        if (!album) tracks.clear()
        take.forEach { u ->
            val (t, d) = audioMeta(context, u)
            tracks += DraftTrack.File(u, t ?: fileName(context, u), d)
        }
        if (!album && title.isBlank()) tracks.firstOrNull()?.let { title = it.title }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) cover = u }
    val canPublish = artist.isNotBlank() && if (album) tracks.size >= 2 && title.isNotBlank() else tracks.size == 1

    GradientBackground(tint = if (album) Color(0xFF8A5CFF) else Color(0xFFFF8A2B)) {
        LazyColumn(Modifier.imePadding(), contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text(if (album) "Pubblica un album" else "Pubblica un singolo", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(120.dp).clip(RoundedCornerShape(10.dp)).background(LocalVibes.current.cardHigh)
                        .clickable { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center) {
                        if (cover != null) coil3.compose.AsyncImage(cover.toString(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(120.dp))
                        else Text("🖼️\nCopertina", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VibesField(title, { title = it }, if (album) "Titolo dell'album / EP" else "Titolo del brano")
                        VibesField(artist, { artist = it }, "Nome artista")
                        VibesField(genre, { genre = it }, "Genere (es. trap, pop)")
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (album) "Brani (${tracks.size})" else "Audio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (album) "+ File dal dispositivo" else if (tracks.isEmpty()) "+ Scegli il file audio" else "Cambia file", true, { audioPicker.launch(arrayOf("audio/*")) })
                    if (album) Pill("+ Dai tuoi singoli", true, { pickSingles = true })
                }
                Text(if (album) "Almeno 2 brani. Puoi mischiare file nuovi e singoli già pubblicati, e cambiare l'ordine con le frecce. Pubblica solo musica tua."
                    else "MP3, M4A, WAV, FLAC… Pubblica solo musica tua.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            itemsIndexed(tracks) { i, t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (album) Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
                    when (t) {
                        is DraftTrack.File -> if (album) VibesField(t.title, { v -> tracks[i] = t.copy(title = v) }, "Titolo", Modifier.weight(1f))
                            else Text("🎧 " + fileName(context, t.uri), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        is DraftTrack.Existing -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Cover(t.upload.cover, Modifier.size(36.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Singolo già pubblicato", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(fmtTime(t.durationMs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
                    if (album) {
                        IconButton(onClick = { if (i > 0) tracks.add(i - 1, tracks.removeAt(i)) }, enabled = i > 0, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Su") }
                        IconButton(onClick = { if (i < tracks.size - 1) tracks.add(i + 1, tracks.removeAt(i)) }, enabled = i < tracks.size - 1, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Giù") }
                    }
                    IconButton(onClick = { tracks.removeAt(i) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Delete, "Togli") }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dettagli", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
                    VibesField(description, { description = it.take(500) }, "Descrizione (crediti, storia del pezzo…)", singleLine = false, maxLines = 4)
                    VibesField(tags, { tags = it }, "Tag separati da virgola (es. estate, chill)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("🌍 Pubblico", !followersOnly, { followersOnly = false })
                        Pill("🔒 Solo follower", followersOnly, { followersOnly = true })
                        Pill(if (explicit) "🅴 Esplicito ✓" else "🅴 Esplicito", explicit, { explicit = !explicit })
                    }
                    if (cover == null) Text("Niente copertina? Ne creo una io con il titolo e i colori della tua vibe.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    progress?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                    BigButton(if (album) "Pubblica l'album" else "Pubblica il singolo", {
                        progress = "Preparo…"
                        scope.launch {
                            runCatching {
                                progress = "Carico la copertina…"
                                val name = title.ifBlank { tracks.firstOrNull()?.title ?: "Vibes" }
                                val jpeg = cover?.let { c -> withContext(Dispatchers.IO) { squareJpeg(context, c, 1000) } }
                                    ?: withContext(Dispatchers.IO) { generatedCover(name, artist) }
                                val coverUrl = app.social.uploadPublicImage(jpeg, "release")
                                val albumId = if (album) java.util.UUID.randomUUID().toString() else null
                                val tagList = tags.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(10)
                                val vis = if (followersOnly) "followers" else "public"
                                val files = tracks.withIndex().filter { it.value is DraftTrack.File }
                                val fresh = files.mapIndexed { n, (i, t) ->
                                    t as DraftTrack.File
                                    progress = "Carico ${n + 1}/${files.size}: ${t.title}"
                                    val mime = context.contentResolver.getType(t.uri) ?: "audio/mpeg"
                                    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp3"
                                    val f = withContext(Dispatchers.IO) { copyToCache(context, t.uri, ext) }
                                    if (f.length() > 100L * 1024 * 1024) { f.delete(); error("\"${t.title}\" è più di 100 MB") }
                                    val url = app.social.uploadAsset(f, ext, mime)
                                    f.delete()
                                    i to Upload(title = (if (album) t.title else title).ifBlank { "Senza titolo" }, artist = artist,
                                        album = if (album) title else null, albumId = albumId, trackNo = i + 1, cover = coverUrl, url = url,
                                        durationMs = t.durationMs, genre = genre, description = description.trim(), tags = tagList,
                                        visibility = vis, explicit = explicit)
                                }
                                progress = "Pubblico…"
                                val saved = if (fresh.isEmpty()) emptyList() else app.social.publishUploads(fresh.map { it.second })
                                if (album) {
                                    // the album keeps the order chosen here, new files and singles mixed
                                    val byTrackNo = saved.associateBy { it.trackNo }
                                    val ids = tracks.mapIndexedNotNull { i, t ->
                                        when (t) { is DraftTrack.Existing -> t.upload.id; is DraftTrack.File -> byTrackNo[i + 1]?.id }
                                    }
                                    app.social.publishAlbum(VibesAlbum(id = albumId!!, title = title.trim(), artist = artist, cover = coverUrl,
                                        genre = genre, description = description.trim(), tracks = ids, visibility = vis))
                                }
                            }.onSuccess { nav.toast(if (album) "Album pubblicato! 🎉" else "Singolo pubblicato! 🎉"); nav.back() }
                                .onFailure { nav.toast(it.message ?: "Pubblicazione non riuscita") }
                            progress = null
                        }
                    }, Modifier.fillMaxWidth(), enabled = canPublish, loading = progress != null)
                    if (album && tracks.size < 2) Text("Aggiungi almeno 2 brani e il titolo dell'album.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    if (pickSingles) SinglesPicker(already = tracks.filterIsInstance<DraftTrack.Existing>().map { it.upload.id }.toSet(),
        onDismiss = { pickSingles = false }) { picked -> tracks += picked.map { DraftTrack.Existing(it) }; pickSingles = false }
}

/** Your published songs, to put in the album being made. */
@Composable
private fun SinglesPicker(already: Set<String>, onDismiss: () -> Unit, onDone: (List<Upload>) -> Unit) {
    val app = LocalApp.current
    var mine by remember { mutableStateOf<List<Upload>?>(null) }
    val chosen = remember { mutableStateListOf<Upload>() }
    LaunchedEffect(Unit) { mine = app.social.uid?.let { runCatching { app.social.uploadsOf(it) }.getOrNull() }.orEmpty() }
    VibesSheet(onDismiss) {
        Text("I tuoi brani pubblicati", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        val list = mine
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            list.isEmpty() -> EmptyState("🎵", "Nessun brano ancora", "Pubblica prima qualche singolo, oppure aggiungi file dal dispositivo.")
            else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                items(list.filter { it.id !in already }, key = { it.id }) { u ->
                    val on = chosen.any { it.id == u.id }
                    Row(Modifier.fillMaxWidth().clickable { if (on) chosen.removeAll { it.id == u.id } else chosen += u }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Cover(u.cover, Modifier.size(46.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(u.album, fmtTime(u.durationMs)).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Checkbox(on, { if (on) chosen.removeAll { it.id == u.id } else chosen += u })
                    }
                }
            }
        }
        BigButton(if (chosen.isEmpty()) "Scegli i brani" else "Aggiungi ${chosen.size}", { onDone(chosen.toList()) },
            Modifier.fillMaxWidth().padding(16.dp), enabled = chosen.isNotEmpty())
    }
}

// =====================================================================================  samples

private val sampleGenres = listOf("Trap", "Drill", "Boom bap", "Lo-fi", "R&B", "Pop", "House", "Afro", "Reggaeton", "Rock", "Jazz", "Soul")

private val sampleKeys = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** One row of the sample browser (community or web). */
private data class SampleRow(
    val id: String, val name: String, val sub: String, val url: String, val bpm: Int?, val key: String?,
    val loop: Boolean, val community: Sample? = null,
)

/**
 * Hearted samples, stored with everything needed to show and play them. Before, only the ids were
 * saved and the rows came from an in-memory cache, so after reopening the screen the favourites
 * tab was empty ("i sample col cuore non compaiono nei preferiti").
 */
@kotlinx.serialization.Serializable
private data class FavSample(val id: String, val name: String, val sub: String, val url: String, val bpm: Int? = null, val key: String? = null, val loop: Boolean = false)

private object FavSamples {
    private fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "fav_samples.json")
    fun load(ctx: android.content.Context): List<FavSample> =
        runCatching { dev.brahmkshatriya.echo.vibes.backend.Supa.json.decodeFromString<List<FavSample>>(file(ctx).readText()) }.getOrDefault(emptyList())
    fun save(ctx: android.content.Context, list: List<FavSample>) {
        runCatching { file(ctx).writeText(dev.brahmkshatriya.echo.vibes.backend.Supa.json.encodeToString(list)) }
        // the same favourites on the phone and on the PC
        runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.userState.changed(dev.brahmkshatriya.echo.vibes.backend.UserStateSync.FAV_SAMPLES) }
    }
    fun toggle(ctx: android.content.Context, r: SampleRow, on: Boolean) {
        val cur = load(ctx).filter { it.id != r.id }
        save(ctx, if (on) listOf(FavSample(r.id, r.name, r.sub, r.url, r.bpm, r.key, r.loop)) + cur else cur)
    }
}

@Composable
fun SamplesScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val prefs = LocalSettings.current
    var source by remember { mutableIntStateOf(0) } // 0 web, 1 community, 2 favourites
    var q by remember { mutableStateOf("") }
    var recordCat by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var cats by remember { mutableStateOf(VibesGraph.webSamples.categories.map { it.first }) }
    LaunchedEffect(Unit) { runCatching { VibesGraph.webSamples.load(); cats = VibesGraph.webSamples.categories.map { it.first } } }
    var favCount by remember { mutableIntStateOf(FavSamples.load(context).size) }
    var kind by remember { mutableStateOf<String?>(null) }
    var genre by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf<String?>(null) }
    var bpm by remember { mutableStateOf(60f..180f) }
    var sort by remember { mutableIntStateOf(0) } // 0 new, 1 popular, 2 random
    var rows by remember { mutableStateOf<List<SampleRow>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var filters by remember { mutableStateOf(false) }
    val st by VibesGraph.studio.state.collectAsState()
    val favCache = remember { mutableStateMapOf<String, SampleRow>() }
    // favourites arriving from the other device
    val syncedVersion by dev.brahmkshatriya.echo.vibes.VibesGraph.userState.version.collectAsState()
    LaunchedEffect(syncedVersion) { if (syncedVersion > 0) { favCount = FavSamples.load(context).size; refresh++ } }

    LaunchedEffect(q, kind, genre, key, bpm, sort, source, refresh, recordCat) {
        rows = null
        loadError = null
        delay(if (q.isBlank()) 0 else 200)
        val full = bpm.start <= 60f && bpm.endInclusive >= 180f
        val list: List<SampleRow> = when (source) {
            0 -> runCatching {
                VibesGraph.webSamples.search(q, recordCat, kind?.let { it == "loop" }, if (full) null else bpm.start.toInt()..bpm.endInclusive.toInt(), key)
            }.onFailure { loadError = "Non riesco a caricare i sample: controlla la connessione" }.getOrDefault(emptyList()).map { w ->
                SampleRow(w.id, w.name, listOfNotNull(w.pack.ifBlank { null }, w.seconds?.let { "${it}s" }).joinToString(" · "),
                    w.url, w.bpm, w.key, loop = w.loop)
            }
            1 -> runCatching {
                app.social.searchSamples(q, kind, genre, if (full) null else bpm.start.toInt(), if (full) null else bpm.endInclusive.toInt())
            }.getOrDefault(emptyList()).map { c ->
                SampleRow(c.id, c.name, listOfNotNull(if (c.kind == "loop") "Loop" else "One-shot", c.genre.ifBlank { null }, "${c.downloads} download").joinToString(" · "),
                    c.url, c.bpm, c.key, c.kind == "loop", c)
            }
            else -> FavSamples.load(context).map { SampleRow(it.id, it.name, it.sub, it.url, it.bpm, it.key, it.loop) }
        }
        val filtered = if (source == 0) list else list.filter { r ->
            (key == null || r.key?.startsWith(key!!, true) == true) &&
                    (full || source == 1 || r.bpm == null || r.bpm in bpm.start.toInt()..bpm.endInclusive.toInt())
        }
        filtered.forEach { favCache[it.id] = it }
        rows = when (sort) { 1 -> filtered.sortedByDescending { it.community?.downloads ?: 0 }; 2 -> filtered.shuffled(); else -> filtered }
    }
    LaunchedEffect(prefs.tempoMatch, prefs.projectBpm, st?.item?.id) {
        val playingRow = rows?.firstOrNull { it.id == st?.item?.id }
        VibesGraph.studio.setSpeed(if (prefs.tempoMatch && playingRow?.bpm != null) prefs.projectBpm.toFloat() / playingRow.bpm else 1f)
    }
    fun play(r: SampleRow) {
        val playing = st?.item?.id == r.id && st?.playing == true
        if (playing) VibesGraph.studio.toggle()
        else { app.engine.pause(); VibesGraph.studio.play(listOf(StudioPlayer.Item(r.id, r.name, r.sub, null, r.url, sample = true)), 0, loop = r.loop) }
    }

    GradientBackground(tint = Color(0xFFB84DFF)) {
        LazyColumn(Modifier.imePadding(), contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Sample", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Box(Modifier.size(42.dp).glass(CircleShape).bouncy({ rows?.randomOrNull()?.let { play(it); nav.toast("🎲 ${it.name}") } }), contentAlignment = Alignment.Center) { Text("🎲", fontSize = 20.sp) }
                    Spacer(Modifier.width(8.dp))
                    Pill("+ Carica", true, { adding = true }, Modifier.padding(end = 12.dp))
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SearchField(q, { q = it }, if (source == 0) "Piano, vocal chop, sax, pad, lo-fi…" else "Cerca un sample")
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("🎹 Sample", source == 0, { source = 0 })
                        Pill("👥 Community", source == 1, { source = 1 })
                        Pill("❤️ $favCount", source == 2, { source = 2 })
                        Spacer(Modifier.weight(1f))
                        Pill(if (filters) "Filtri ▴" else "Filtri ▾", filters, { filters = !filters })
                    }
                }
                if (source == 0) {
                    Text("Loop e suoni musicali liberi (CC0 / CC-BY: se è CC-BY cita l'autore).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    LazyRow(contentPadding = PaddingValues(16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cats) { c ->
                            Pill(c, recordCat == c, { recordCat = if (recordCat == c) null else c })
                        }
                    }
                }
                AnimatedVisibility(filters) {
                    Column {
                        Row(Modifier.padding(start = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Tutti", kind == null, { kind = null }); Pill("Loop", kind == "loop", { kind = "loop" }); Pill("One-shot", kind == "oneshot", { kind = "oneshot" })
                        }
                        LazyRow(contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(sampleGenres) { g -> Pill(g, genre == g, { genre = if (genre == g) null else g }) }
                        }
                        LazyRow(contentPadding = PaddingValues(16.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(sampleKeys) { k -> Pill(k, key == k, { key = if (key == k) null else k }) }
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("BPM: ${bpm.start.toInt()} – ${bpm.endInclusive.toInt()}${if (bpm.endInclusive >= 180f) "+" else ""}", style = MaterialTheme.typography.labelLarge)
                            RangeSlider(bpm, { bpm = it }, valueRange = 60f..180f, steps = 23)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Nuovi", sort == 0, { sort = 0 }); Pill("Popolari", sort == 1, { sort = 1 }); Pill("Casuale", sort == 2, { sort = 2 })
                            }
                        }
                        // tempo match (Splice-style): preview samples at your project's BPM
                        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Adatta al mio BPM", style = MaterialTheme.typography.titleSmall)
                                Text("Ascolta i sample già alla velocità del tuo beat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("−", fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { VibesGraph.prefs.update { it.copy(projectBpm = (it.projectBpm - 1).coerceAtLeast(60)) } }.padding(8.dp))
                            Text("${prefs.projectBpm}", style = MaterialTheme.typography.titleMedium)
                            Text("+", fontSize = 22.sp, modifier = Modifier.clip(CircleShape).clickable { VibesGraph.prefs.update { it.copy(projectBpm = (it.projectBpm + 1).coerceAtMost(220)) } }.padding(8.dp))
                            androidx.compose.material3.Switch(prefs.tempoMatch, { b -> VibesGraph.prefs.update { it.copy(tempoMatch = b) } })
                        }
                    }
                }
            }
            val list = rows
            if (list == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            else if (list.isEmpty()) item {
                EmptyState("🎛️", when (source) { 2 -> "Nessun preferito"; 1 -> "Nessun sample della community"; else -> loadError ?: "Nessun risultato" },
                    when (source) { 2 -> "Tocca il cuore su un sample per salvarlo qui."; 1 -> "Carica i tuoi loop: la community cresce con te."; else -> "Prova con un'altra parola: piano, guitar, vocal, pad, sax, o togli qualche filtro." })
            }
            else items(list, key = { it.id }) { r ->
                val playing = st?.item?.id == r.id && st?.playing == true
                val fav = r.id in prefs.favSamples || (source == 2)
                val bg by animateColorAsState(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent, label = "sp")
                Column(Modifier.fillMaxWidth().background(bg).clickable { play(r) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                            if (playing) EqualizerBars(Modifier.size(18.dp)) else Icon(Icons.Rounded.PlayArrow, null)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(r.bpm?.let { "$it BPM" }, r.key, r.sub).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (fav) "❤️" else "🤍", fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable {
                            VibesGraph.prefs.update { it.copy(favSamples = if (fav) it.favSamples - r.id else it.favSamples + r.id) }
                            FavSamples.toggle(context, r, !fav)
                            favCount = FavSamples.load(context).size
                            if (source == 2) refresh++
                        }.padding(6.dp))
                        IconButton(onClick = {
                            downloadSample(context, r.name, r.url); r.community?.let { app.social.count("download", it.id) }
                            nav.toast("Scarico \"${r.name}\" in Download/Vibes")
                        }) { Icon(Icons.Rounded.Download, "Scarica") }
                    }
                    Waveform(r.id, if (playing) VibesGraph.studio.position().toFloat() / VibesGraph.studio.duration().coerceAtLeast(1) else 0f, playing)
                }
            }
        }
    }
    if (adding) AddSampleDialog(onDismiss = { adding = false }) { adding = false; source = 1; refresh++ }
}

/** Splice-like waveform strip (shape derived from the sample id) with the playhead. */
@Composable
private fun Waveform(seed: String, progress: Float, playing: Boolean) {
    var p by remember { mutableStateOf(progress) }
    LaunchedEffect(playing) { while (playing) { p = VibesGraph.studio.position().toFloat() / VibesGraph.studio.duration().coerceAtLeast(1); delay(60) } }
    val rnd = remember(seed) { java.util.Random(seed.hashCode().toLong()) }
    val bars = remember(seed) { List(48) { 0.25f + rnd.nextFloat() * 0.75f } }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    androidx.compose.foundation.Canvas(Modifier.padding(start = 54.dp, top = 4.dp).fillMaxWidth().height(22.dp)) {
        val w = size.width / bars.size
        bars.forEachIndexed { i, h ->
            val bh = size.height * h
            drawRoundRect(if (playing && i.toFloat() / bars.size <= p) on else off,
                topLeft = androidx.compose.ui.geometry.Offset(i * w + w * 0.2f, (size.height - bh) / 2),
                size = androidx.compose.ui.geometry.Size(w * 0.6f, bh), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w))
        }
    }
}

private fun downloadSample(context: Context, name: String, url: String) {
    val ext = url.substringAfterLast('.', "wav").take(4)
    val req = DownloadManager.Request(Uri.parse(url))
        .setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Vibes/${name.replace(Regex("[^A-Za-z0-9 _-]"), "_")}.$ext")
    context.getSystemService(DownloadManager::class.java).enqueue(req)
}

@Composable
private fun AddSampleDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("loop") }
    var genre by remember { mutableStateOf("") }
    var bpm by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { file = u; if (name.isBlank()) name = fileName(context, u) }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Carica un sample") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (file == null) "Scegli file audio" else "✓ ${fileName(context, file!!)}", file == null, { picker.launch(arrayOf("audio/*")) })
                VibesField(name, { name = it }, "Nome (es. Dark piano loop)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Loop", kind == "loop", { kind = "loop" }); Pill("One-shot", kind == "oneshot", { kind = "oneshot" })
                }
                VibesField(genre, { genre = it }, "Genere")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VibesField(bpm, { bpm = it.filter(Char::isDigit).take(3) }, "BPM", Modifier.weight(1f))
                    VibesField(key, { key = it.take(4) }, "Tonalità", Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = file != null && name.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        val u = file!!
                        val mime = context.contentResolver.getType(u) ?: "audio/wav"
                        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "wav"
                        val f = withContext(Dispatchers.IO) { copyToCache(context, u, ext) }
                        if (f.length() > 30L * 1024 * 1024) { f.delete(); error("Sample troppo pesante (max 30 MB)") }
                        val url = app.social.uploadAsset(f, ext, mime)
                        val dur = audioMeta(context, u).second
                        f.delete()
                        app.social.publishSample(Sample(name = name.trim(), kind = kind, genre = genre.trim(), bpm = bpm.toIntOrNull(),
                            key = key.trim().ifBlank { null }, url = url, durationMs = dur))
                    }.onSuccess { app.nav.toast("Sample caricato 🎛️"); onDone() }.onFailure { app.nav.toast(it.message ?: "Errore") }
                    busy = false
                }
            }) { Text(if (busy) "Carico…" else "Carica") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}


/** Auto cover: vibe gradient + big title, 1000×1000 JPEG. */
private fun generatedCover(title: String, artist: String): ByteArray {
    val size = 1000
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val palette = studioVibes.random().second.map { android.graphics.Color.argb(255, (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
    c.drawPaint(android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), palette.toIntArray(), null, android.graphics.Shader.TileMode.CLAMP)
    })
    c.drawColor(android.graphics.Color.argb(70, 0, 0, 0))
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = 110f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    val words = title.uppercase().split(" ")
    val lines = mutableListOf<String>(); var line = ""
    words.forEach { w -> if (p.measureText("$line $w") > size - 140 && line.isNotBlank()) { lines += line.trim(); line = w } else line = "$line $w" }
    if (line.isNotBlank()) lines += line.trim()
    var y = size - 120f - (lines.size - 1) * 120f
    lines.take(4).forEach { l -> c.drawText(l, 70f, y, p); y += 120f }
    p.textSize = 48f; p.typeface = android.graphics.Typeface.DEFAULT; p.alpha = 220
    c.drawText(artist, 72f, 110f, p)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
    return out.toByteArray()
}
