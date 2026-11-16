package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.vibes.backend.Message
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import dev.brahmkshatriya.echo.vibes.backend.long
import dev.brahmkshatriya.echo.vibes.backend.str
import dev.brahmkshatriya.echo.vibes.notify.VibesNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private fun mediaDir(context: Context) = File(context.cacheDir, "vibes_media").apply { mkdirs() }

private fun extFor(mime: String) = when {
    mime.contains("png") -> "png"
    mime.startsWith("image") -> "jpg"
    mime.contains("mp4") || mime.startsWith("video") -> "mp4"
    mime.startsWith("audio") -> "m4a"
    else -> "bin"
}

/** Local copy of a chat attachment; downloads once, and the receiver then deletes the cloud copy. */
private suspend fun mediaFile(context: Context, m: Message, myId: String?): File? = withContext(Dispatchers.IO) {
    val path = m.payload?.str("path") ?: return@withContext null
    val mime = m.payload.str("mime") ?: "application/octet-stream"
    val f = File(mediaDir(context), "${m.id}.${extFor(mime)}")
    if (f.exists() && f.length() > 0) return@withContext f
    val bytes = runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.social.fetchMedia(path) }.getOrNull() ?: return@withContext null
    f.writeBytes(bytes)
    if (m.receiverId == myId) runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.social.deleteMedia(path) }
    f
}

private fun compressImage(bytes: ByteArray): ByteArray {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val max = 1600
    val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    return ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
}

@Composable
fun ChatScreen(friendId: String) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val friends by app.social.friends.collectAsState()
    val friend = friends.firstOrNull { it.id == friendId }
    val me = app.social.uid
    val messages = remember { mutableStateListOf<Message>() }
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var recording by remember { mutableStateOf<Pair<MediaRecorder, File>?>(null) }
    var recordStart by remember { mutableStateOf(0L) }
    val current = currentSong()

    DisposableEffect(friendId) {
        app.social.openChat = friendId
        VibesNotifier.clear(context, friendId)
        onDispose { if (app.social.openChat == friendId) app.social.openChat = null }
    }
    LaunchedEffect(friendId) {
        runCatching { app.social.history(friendId) }.onSuccess { messages.clear(); messages.addAll(it) }
        if (dev.brahmkshatriya.echo.vibes.VibesGraph.prefs.value.readReceipts) runCatching { app.social.markRead(friendId) }
    }
    LaunchedEffect(friendId) {
        app.social.messageChanges.collect { m ->
            val inChat = (m.senderId == friendId && m.receiverId == me) || (m.senderId == me && m.receiverId == friendId)
            if (!inChat) return@collect
            val i = messages.indexOfFirst { it.id == m.id }
            if (i >= 0) messages[i] = m
            else {
                // replace the optimistic copy of my own message if it's still there
                val p = messages.indexOfFirst { it.pending && it.senderId == m.senderId && it.body == m.body && it.kind == m.kind }
                if (p >= 0) messages[p] = m else messages.add(m)
                if (m.senderId == friendId && dev.brahmkshatriya.echo.vibes.VibesGraph.prefs.value.readReceipts) runCatching { app.social.markRead(friendId) }
            }
        }
    }

    fun sendText() {
        val body = text.trim()
        if (body.isEmpty()) return
        text = ""
        val temp = Message(id = -System.nanoTime(), senderId = me ?: "", receiverId = friendId, body = body).apply { pending = true }
        messages.add(temp)
        scope.launch {
            runCatching { app.social.send(friendId, "text", body) }
                .onSuccess { saved -> val i = messages.indexOf(temp); if (i >= 0) messages[i] = saved }
                .onFailure { messages.remove(temp); nav.toast(it.message ?: "Messaggio non inviato") }
        }
    }

    fun sendMedia(kind: String, bytes: ByteArray, mime: String, duration: Long? = null) {
        scope.launch {
            nav.toast("Invio in corso…")
            runCatching { app.social.sendMedia(friendId, kind, bytes, mime, extFor(mime), duration) }
                .onSuccess { saved ->
                    withContext(Dispatchers.IO) { File(mediaDir(context), "${saved.id}.${extFor(mime)}").writeBytes(bytes) }
                    if (messages.none { it.id == saved.id }) messages.add(saved)
                }
                .onFailure { nav.toast(it.message ?: "Invio non riuscito") }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val raw = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch
            if (raw.size > 50 * 1024 * 1024) { nav.toast("File troppo grande (max 50 MB)"); return@launch }
            if (mime.startsWith("video")) sendMedia("video", raw, mime)
            else sendMedia("image", withContext(Dispatchers.Default) { compressImage(raw) }, "image/jpeg")
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) nav.toast("Tieni premuto il microfono per registrare")
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val f = File(mediaDir(context), "rec_${System.currentTimeMillis()}.m4a")
        val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000); setAudioSamplingRate(44_100)
            setOutputFile(f.absolutePath)
        }
        runCatching { r.prepare(); r.start(); recording = r to f; recordStart = System.currentTimeMillis() }
            .onFailure { r.release(); nav.toast("Microfono non disponibile") }
    }

    fun stopRecording(send: Boolean) {
        val (r, f) = recording ?: return
        recording = null
        val dur = System.currentTimeMillis() - recordStart
        runCatching { r.stop() }; r.release()
        if (send && dur > 700) sendMedia("audio", f.readBytes(), "audio/mp4", dur)
        f.delete()
    }

    // keyboard opens -> keep the latest message right above the input bar
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeOpen = WindowInsets.isImeVisible
    LaunchedEffect(imeOpen, messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    // PC: transparent page over the app background, glass header (no black slab)
    val pageBg = if (PlatformUI.transparentScreens) Color.Transparent else MaterialTheme.colorScheme.background
    Column(Modifier.fillMaxSize().background(pageBg).imePadding()) {
        // header
        Row(
            Modifier.fillMaxWidth().then(if (PlatformUI.transparentScreens) Modifier.padding(top = 8.dp).glass(RoundedCornerShape(18.dp))
                else Modifier.background(LocalVibes.current.card)).statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
            Avatar(friend, 40.dp, Modifier.bouncy({ nav.open(Route.Profile(friendId)) }))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(friend?.name ?: "…", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                friend?.liveNow?.takeIf { it.playing }?.song?.let {
                    Text("🎧 ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { nav.openOn(Route.Games, Route.Games); app.nav.toast("Scegli un gioco e sfida ${friend?.name ?: "il tuo amico"}") }) {
                Icon(Icons.Rounded.SportsEsports, "Sfida")
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (messages.isEmpty()) item {
                EmptyState("💬", "Inizia la conversazione", "Scrivi qualcosa o mandagli il brano che stai ascoltando.")
            }
            items(messages, key = { it.id }) { m -> Bubble(m, mine = m.senderId == me, myId = me) }
        }

        // composer
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(LocalVibes.current.cardHigh).padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (current != null) IconButton(onClick = {
                    scope.launch {
                        runCatching { app.social.sendSong(friendId, current.first) }.onFailure { nav.toast(it.message ?: "Errore") }
                    }
                }) { Icon(Icons.Rounded.MusicNote, "Invia il brano in riproduzione", tint = MaterialTheme.colorScheme.primary) }
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (recording != null) Text("🔴 Registrazione… rilascia per inviare", color = MaterialTheme.colorScheme.primary)
                    else {
                        if (text.isEmpty()) Text("Messaggio", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(
                            text, { text = it.take(4000) }, maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }) {
                    Icon(Icons.Rounded.Image, "Foto o video", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .then(
                        if (text.isNotBlank()) Modifier.bouncy({ sendText() })
                        else Modifier.pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                startRecording()
                                val up = waitForUpOrCancellation()
                                stopRecording(send = up != null)
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (text.isNotBlank()) Icons.Rounded.Send else Icons.Rounded.Mic, "Invia",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (recording != null) 30.dp else 24.dp)
                )
            }
        }
    }
}

@Composable
private fun Bubble(m: Message, mine: Boolean, myId: String?) {
    val app = LocalApp.current
    val nav = app.nav
    val anim = remember(m.id) { Animatable(if (m.pending || (m.createdAt != null && isRecent(m.createdAt))) 0f else 1f) }
    LaunchedEffect(m.id) { anim.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
    val bg = if (mine) MaterialTheme.colorScheme.primary else LocalVibes.current.cardHigh
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = if (PlatformUI.isDesktop) 560.dp else 290.dp)
                .graphicsLayer {
                    scaleX = 0.6f + 0.4f * anim.value; scaleY = 0.6f + 0.4f * anim.value
                    alpha = anim.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (mine) 1f else 0f, 1f)
                }
                // liquid glass bubbles: mine tinted with the accent colour, theirs clear glass
                .then(if (LocalSettings.current.liquidGlass)
                    Modifier.glass(shape, tint = if (mine) bg else Color.White)
                        .background(if (mine) bg.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.04f))
                else Modifier.clip(shape).background(bg)).padding(4.dp)
        ) {
            when (m.kind) {
                "track" -> {
                    val song = m.payload?.let { runCatching { Supa.json.decodeFromJsonElement(SongRef.serializer(), it) }.getOrNull() }
                    if (song != null) Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.18f))
                            .bouncy({ app.engine.play(song) }, { nav.share = ShareItem.Song(song) }).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(song.cover, Modifier.size(56.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f, fill = false).widthIn(max = 170.dp)) {
                            Text(song.title, color = fg, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(36.dp).clip(CircleShape).background(fg), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, "Ascolta", tint = bg)
                        }
                    }
                    if (m.body.isNotBlank()) Text(m.body, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                "playlist" -> {
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.18f))
                            .bouncy({ m.payload?.str("playlist_id")?.let { nav.open(Route.SharedPlaylist(it, m.payload?.str("collab_id"))) } }).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(m.payload?.str("cover"), Modifier.size(56.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("📀 " + (m.payload?.str("name") ?: "Playlist"), color = fg, style = MaterialTheme.typography.titleSmall)
                            Text("${m.payload?.long("count") ?: 0} brani · " + (if (m.payload?.str("collab_id") != null) "condivisa, tocca per salvarla" else "tocca per aprire"), color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (m.body.isNotBlank()) Text(m.body, color = fg, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                "challenge" -> {
                    val game = m.payload?.str("game") ?: "guess"
                    val lobby = m.payload?.str("lobby_id")
                    Column(Modifier.padding(10.dp)) {
                        Text("🎮 Sfida!", color = fg, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (mine) "Hai sfidato a ${VibesNotifier.gameName(game)}" else "Ti sfida a ${VibesNotifier.gameName(game)}",
                            color = fg.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.clip(CircleShape).background(fg).bouncy({
                                lobby?.let { nav.openOn(Route.Games, if (game == "blindtest") Route.BlindTest(it) else Route.Guess(it)) }
                            }).padding(horizontal = 18.dp, vertical = 8.dp)
                        ) { Text(if (mine) "Apri la lobby" else "Gioca ora", color = bg, style = MaterialTheme.typography.labelLarge) }
                    }
                }
                "image", "video" -> MediaContent(m, myId, fg)
                "audio" -> VoiceNote(m, myId, fg)
                else -> Text(m.body, color = fg, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Row(Modifier.align(Alignment.End).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shortTime(m.createdAt), color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                if (mine) { Spacer(Modifier.width(4.dp)); Ticks(m) }
            }
        }
    }
}

private fun isRecent(iso: String) =
    runCatching { System.currentTimeMillis() - java.time.Instant.parse(iso).toEpochMilli() < 10_000 }.getOrDefault(false)

@Composable
private fun MediaContent(m: Message, myId: String?, fg: Color) {
    val context = LocalContext.current
    var file by remember(m.id) { mutableStateOf<File?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(m.id) { file = mediaFile(context, m, myId); failed = file == null }
    val f = file
    Box(Modifier.size(240.dp, 260.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
        when {
            f != null && m.kind == "image" -> AsyncImage(f, "Foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().bouncy({ openFile(context, f, "image/*") }))
            f != null -> Box(Modifier.fillMaxSize().bouncy({ openFile(context, f, "video/*") }), contentAlignment = Alignment.Center) {
                Text("🎬", style = MaterialTheme.typography.displayMedium)
                Icon(Icons.Rounded.PlayArrow, "Guarda", tint = Color.White, modifier = Modifier.size(56.dp))
            }
            failed -> Text("Non più disponibile", color = fg)
            else -> androidx.compose.material3.CircularProgressIndicator(color = fg)
        }
    }
}

private fun openFile(context: Context, f: File, type: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", f)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun VoiceNote(m: Message, myId: String?, fg: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(m.id) { onDispose { player?.release() } }
    val duration = m.payload?.long("duration") ?: 0
    Row(Modifier.padding(6.dp).width(220.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(fg).bouncy({
                if (playing) { player?.pause(); playing = false; return@bouncy }
                scope.launch {
                    val f = mediaFile(context, m, myId) ?: return@launch
                    val p = player ?: MediaPlayer().apply {
                        setDataSource(f.absolutePath); prepare()
                        setOnCompletionListener { playing = false }
                    }.also { player = it }
                    p.start(); playing = true
                }
            }),
            contentAlignment = Alignment.Center
        ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Ascolta", tint = if (fg == Color.White) Color.Black else Color.White) }
        Spacer(Modifier.width(10.dp))
        // static "waveform"
        Row(Modifier.weight(1f).height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            val seed = m.id.toInt()
            repeat(22) { i ->
                val h = 0.25f + ((seed * 31 + i * 17) % 75) / 100f
                Box(Modifier.weight(1f).height((28 * h).dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = if (playing) 0.95f else 0.55f)))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(fmt(duration), color = fg, style = MaterialTheme.typography.labelSmall)
    }
}
