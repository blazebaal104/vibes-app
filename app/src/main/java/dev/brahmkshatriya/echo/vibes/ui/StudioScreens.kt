package dev.brahmkshatriya.echo.vibes.ui

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
import dev.brahmkshatriya.echo.vibes.music.StudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun Upload.item() = StudioPlayer.Item(id, title, artist, cover, url)
private fun Sample.item() = StudioPlayer.Item(id, name, listOfNotNull(bpm?.let { "$it BPM" }, key, genre.ifBlank { null }).joinToString(" · "), null, url, sample = true)

fun playUploads(app: AppState, list: List<Upload>, index: Int) {
    app.engine.pause()
    VibesGraph.studio.play(list.map { it.item() }, index)
    list.getOrNull(index)?.let { VibesGraph.social.count("play", it.id) }
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
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A2B), Color(0xFFB84DFF)))).padding(horizontal = 10.dp, vertical = 6.dp),
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

@Composable
fun StudioScreen() {
    val app = LocalApp.current
    val nav = app.nav
    var latest by remember { mutableStateOf<List<Upload>?>(null) }
    var people by remember { mutableStateOf<Map<String, Profile>>(emptyMap()) }
    LaunchedEffect(Unit) {
        latest = runCatching { app.social.latestUploads(50) }.getOrDefault(emptyList())
        val ids = latest.orEmpty().map { it.userId }.distinct()
        people = ids.mapNotNull { id -> runCatching { app.social.profile(id) }.getOrNull() }.associateBy { it.id }
    }
    GradientBackground(tint = Color(0xFFFF8A2B)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Studio", style = MaterialTheme.typography.headlineMedium)
                }
                Text("La musica fatta da chi usa Vibes, e una libreria di sample per produrre.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioTile("🎙️", "Pubblica la tua musica", Color(0xFFFF8A2B), Modifier.weight(1f)) { nav.open(Route.Publish) }
                    StudioTile("🎛️", "Libreria sample", Color(0xFFB84DFF), Modifier.weight(1f)) { nav.open(Route.Samples) }
                }
            }
            item { SectionTitle("Nuovi brani dai Viber") }
            val list = latest
            if (list == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            else if (list.isEmpty()) item { EmptyState("🎙️", "Ancora nessun brano", "Sii il primo a pubblicare la tua musica su Vibes.") }
            else itemsIndexed(list, key = { _, u -> u.id }) { i, u -> UploadRow(u, people[u.userId], Modifier.enter(i)) { playUploads(app, list, i) } }
        }
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
fun UploadRow(u: Upload, owner: Profile?, modifier: Modifier = Modifier, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val nav = LocalApp.current.nav
    val st by VibesGraph.studio.state.collectAsState()
    val playing = st?.item?.id == u.id
    Row(modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Cover(u.cover, Modifier.size(54.dp))
            if (playing && st?.playing == true) EqualizerBars(Modifier.align(Alignment.Center).size(20.dp), color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(listOfNotNull(u.artist.ifBlank { owner?.name }, u.album, "${u.plays} ascolti").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        owner?.let { Avatar(it, 28.dp, Modifier.bouncy({ nav.open(Route.Profile(it.id)) })) }
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

private data class DraftTrack(val uri: Uri, val title: String, val durationMs: Long)

@Composable
fun PublishScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by app.social.me.collectAsState()
    val tracks = remember { mutableStateListOf<DraftTrack>() }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var artist by remember { mutableStateOf(me?.name ?: "") }
    var album by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<String?>(null) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { u ->
            val (t, d) = audioMeta(context, u)
            tracks += DraftTrack(u, t ?: fileName(context, u), d)
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) cover = u }
    GradientBackground(tint = Color(0xFFFF8A2B)) {
        LazyColumn(Modifier.imePadding(), contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Pubblica la tua musica", style = MaterialTheme.typography.headlineSmall)
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
                        VibesField(artist, { artist = it }, "Nome artista")
                        VibesField(album, { album = it }, if (tracks.size > 1) "Titolo album/EP" else "Album (facoltativo)")
                        VibesField(genre, { genre = it }, "Genere (es. trap, pop)")
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Brani (${tracks.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Pill("+ Aggiungi audio", true, { audioPicker.launch(arrayOf("audio/*")) })
                }
                Text("MP3, M4A, WAV, FLAC… Più file = un album. Pubblica solo musica tua.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            itemsIndexed(tracks) { i, t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(26.dp))
                    VibesField(t.title, { v -> tracks[i] = t.copy(title = v) }, "Titolo", Modifier.weight(1f))
                    Text(fmtTime(t.durationMs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { tracks.removeAt(i) }) { Icon(Icons.Rounded.Delete, "Togli") }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    progress?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                    BigButton("Pubblica", {
                        progress = "Preparo…"
                        scope.launch {
                            runCatching {
                                val coverUrl = cover?.let { c ->
                                    progress = "Carico la copertina…"
                                    val jpeg = withContext(Dispatchers.IO) { squareJpeg(context, c, 1000) }
                                    app.social.uploadPublicImage(jpeg, "release")
                                }
                                val albumId = if (tracks.size > 1 || album.isNotBlank()) java.util.UUID.randomUUID().toString() else null
                                val ups = tracks.mapIndexed { i, t ->
                                    progress = "Carico ${i + 1}/${tracks.size}: ${t.title}"
                                    val mime = context.contentResolver.getType(t.uri) ?: "audio/mpeg"
                                    val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp3"
                                    val f = withContext(Dispatchers.IO) { copyToCache(context, t.uri, ext) }
                                    if (f.length() > 100L * 1024 * 1024) { f.delete(); error("\"${t.title}\" è più di 100 MB") }
                                    val url = app.social.uploadAsset(f, ext, mime)
                                    f.delete()
                                    Upload(title = t.title.ifBlank { "Senza titolo" }, artist = artist, album = album.ifBlank { null }, albumId = albumId,
                                        trackNo = i + 1, cover = coverUrl, url = url, durationMs = t.durationMs, genre = genre)
                                }
                                progress = "Pubblico…"
                                app.social.publishUploads(ups)
                            }.onSuccess { nav.toast("Pubblicato! 🎉"); nav.back() }
                                .onFailure { nav.toast(it.message ?: "Pubblicazione non riuscita") }
                            progress = null
                        }
                    }, Modifier.fillMaxWidth(), enabled = tracks.isNotEmpty() && artist.isNotBlank(), loading = progress != null)
                }
            }
        }
    }
}

// =====================================================================================  samples

private val sampleGenres = listOf("Trap", "Drill", "Boom bap", "Lo-fi", "R&B", "Pop", "House", "Afro", "Reggaeton", "Rock", "Jazz", "Soul")

@Composable
fun SamplesScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<String?>(null) }
    var genre by remember { mutableStateOf<String?>(null) }
    var bpm by remember { mutableStateOf(60f..180f) }
    var results by remember { mutableStateOf<List<Sample>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val st by VibesGraph.studio.state.collectAsState()
    LaunchedEffect(q, kind, genre, bpm, refresh) {
        delay(300)
        val full = bpm.start <= 60f && bpm.endInclusive >= 180f
        results = runCatching {
            app.social.searchSamples(q, kind, genre, if (full) null else bpm.start.toInt(), if (full) null else bpm.endInclusive.toInt())
        }.getOrDefault(emptyList())
    }
    GradientBackground(tint = Color(0xFFB84DFF)) {
        LazyColumn(Modifier.imePadding(), contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Libreria sample", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Pill("+ Carica", true, { adding = true }, Modifier.padding(end = 12.dp))
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    VibesField(q, { q = it }, "Cerca: 808, piano, chitarra, Fm…")
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Tutti", kind == null, { kind = null })
                        Pill("Loop", kind == "loop", { kind = "loop" })
                        Pill("One-shot", kind == "oneshot", { kind = "oneshot" })
                    }
                }
                LazyRow(contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sampleGenres) { g -> Pill(g, genre == g, { genre = if (genre == g) null else g }) }
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("BPM: ${bpm.start.toInt()} – ${bpm.endInclusive.toInt()}${if (bpm.endInclusive >= 180f) "+" else ""}", style = MaterialTheme.typography.labelLarge)
                    RangeSlider(bpm, { bpm = it }, valueRange = 60f..180f, steps = 23)
                }
            }
            val list = results
            if (list == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            else if (list.isEmpty()) item { EmptyState("🎛️", "Nessun sample trovato", "Carica i tuoi loop e one-shot: la libreria cresce con la community.") }
            else items(list, key = { it.id }) { s ->
                val playing = st?.item?.id == s.id && st?.playing == true
                val bg by animateColorAsState(if (playing) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, label = "sp")
                Row(Modifier.fillMaxWidth().background(bg).clickable {
                    if (playing) VibesGraph.studio.toggle() else { app.engine.pause(); VibesGraph.studio.play(listOf(s.item()), 0, loop = s.kind == "loop") }
                }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(LocalVibes.current.cardHigh), contentAlignment = Alignment.Center) {
                        if (playing) EqualizerBars(Modifier.size(20.dp)) else Icon(Icons.Rounded.PlayArrow, null)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(if (s.kind == "loop") "Loop" else "One-shot", s.bpm?.let { "$it BPM" }, s.key, s.genre.ifBlank { null },
                            "${s.downloads} download").joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { downloadSample(context, s); app.social.count("download", s.id); nav.toast("Scarico \"${s.name}\" in Download/Vibes") }) {
                        Icon(Icons.Rounded.Download, "Scarica")
                    }
                }
            }
        }
    }
    if (adding) AddSampleDialog(onDismiss = { adding = false }) { adding = false; refresh++ }
}

private fun downloadSample(context: Context, s: Sample) {
    val ext = s.url.substringAfterLast('.', "wav").take(4)
    val req = DownloadManager.Request(Uri.parse(s.url))
        .setTitle(s.name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Vibes/${s.name.replace(Regex("[^A-Za-z0-9 _-]"), "_")}.$ext")
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
