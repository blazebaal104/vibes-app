package dev.brahmkshatriya.echo.vibes.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.playback.renderer.Karaoke
import dev.brahmkshatriya.echo.vibes.karaoke.KaraokeHD
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One line of the karaoke view: text shown on screen and its time window. */
data class KaraokeLine(val text: String, val start: Long, val end: Long)

/** Synced lyrics for the song, from the Deezer extension (null = none, empty list = only unsynced). */
suspend fun loadKaraokeLines(app: AppState, song: SongRef): List<KaraokeLine>? = withContext(Dispatchers.IO) {
    runCatching {
        withTimeout(15_000) {
            val ext = app.engine.extension()
            val track = song.toTrack()
            val found = ext.getAs<LyricsClient, List<Lyrics>> { searchTrackLyrics(ext.id, track).loadAll() }.getOrThrow()
            val first = found.firstOrNull() ?: return@withTimeout null
            val full = if (first.lyrics == null) ext.getAs<LyricsClient, Lyrics> { loadLyrics(first) }.getOrThrow() else first
            when (val l = full.lyrics) {
                is Lyrics.Timed -> l.list.filter { it.text.isNotBlank() }.map { KaraokeLine(it.text.trim(), it.startTime, it.endTime) }
                is Lyrics.WordByWord -> l.list.mapNotNull { words ->
                    if (words.isEmpty()) null
                    else KaraokeLine(words.joinToString("") { it.text }.trim(), words.first().startTime, words.last().endTime)
                }.filter { it.text.isNotBlank() }
                else -> emptyList()
            }
        }
    }.getOrNull()
}

/**
 * Karaoke mode: the lead vocal is removed in real time (KaraokeProcessor) and the synced lyrics
 * scroll with the current line lit up. Leaving the screen turns the voice back on.
 */
@Composable
fun KaraokeScreen(song: SongRef, playing: Boolean, onClose: () -> Unit) {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val (mainPos, mainDur) = rememberProgress()
    /** Moving from the quick filter to HD right now (both fade, a badge shows it). */
    var switching by remember { mutableStateOf(false) }
    var lines by remember(song.key) { mutableStateOf<List<KaraokeLine>?>(null) }
    var loading by remember(song.key) { mutableStateOf(true) }
    var voice by remember { mutableFloatStateOf(1f - Karaoke.strength) }
    val hdPhase by KaraokeHD.phase.collectAsState()
    val hdActive by KaraokeHD.active.collectAsState()
    val hdPlaying by KaraokeHD.playing.collectAsState()
    var hdPos by remember { mutableStateOf(0L) }
    /** The user wants music: HD may start even if the normal player cannot (e.g. Deezer refused). */
    var wantPlay by remember { mutableStateOf(playing) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val fadeMs = (VibesGraph.prefs.value.karaokeFadeSec * 1000).toLong()
    /** PC: no quick filter (the HD is ready in seconds on a real graphics card): the song keeps its
     *  voice until HD is ready, then cross-fades into it. Phone: the quick filter fades in first. */
    val useFilter = !PlatformUI.isDesktop

    fun leave() {
        // the screen closes at once; the voice comes back gradually (KaraokeFade runs on its own)
        KaraokeFade.exit(app, fadeMs)
        onClose()
    }
    DisposableEffect(Unit) {
        KaraokeFade.cancelExit()
        KaraokeHD.gain = 1f
        if (useFilter) KaraokeFade.filterIn(fadeMs)
        KaraokeHD.init(context)
        KaraokeHD.onEnded = {
            // HD reached the end of the song: hand back to the normal player, which goes to the next one
            scope.launch {
                KaraokeHD.close()
                app.engine.next(); app.engine.player.setPlaying(true)
            }
        }
        onDispose {
            if (KaraokeFade.exiting) return@onDispose
            Karaoke.enabled = false
            KaraokeHD.onEnded = null
            KaraokeHD.close()?.let { at -> app.engine.seekTo(at); app.engine.player.setPlaying(true) }
        }
    }
    BackHandler { leave() }
    // PC without the HD model (or HD failed): fall back to the quick filter, faded in
    LaunchedEffect(hdPhase is KaraokeHD.Phase.NeedsModel || hdPhase is KaraokeHD.Phase.Failed || hdPhase is KaraokeHD.Phase.DownloadingModel) {
        val noHd = hdPhase is KaraokeHD.Phase.NeedsModel || hdPhase is KaraokeHD.Phase.Failed || hdPhase is KaraokeHD.Phase.DownloadingModel
        if (!useFilter && noHd && !Karaoke.enabled) KaraokeFade.filterIn(fadeMs)
    }
    // AI separation of this song, starting from where we are
    LaunchedEffect(song.key) { KaraokeHD.open(song, app.engine.position()) }
    LaunchedEffect(song.key) {
        loading = true
        lines = loadKaraokeLines(app, song)
        loading = false
    }
    // HD position + automatic switch from the real-time filter to the AI backing track
    LaunchedEffect(song.key) {
        var tick = 0
        while (true) {
            KaraokeHD.externalPositionMs = app.engine.position()
            // the next song of the queue gets prepared in the background so it starts in HD
            if (tick++ % 10 == 0) KaraokeHD.nextInQueue = runCatching {
                app.engine.player.browser.value?.takeIf { it.hasNextMediaItem() }?.getMediaItemAt(
                    app.engine.player.browser.value!!.nextMediaItemIndex
                )?.let { mi -> SongRef.from(mi.extensionId, mi.track) }
            }.getOrNull()
            val mainPlaying = app.engine.player.playerState.current.value?.isPlaying == true
            if (mainPlaying) wantPlay = true
            if (KaraokeHD.active.value) hdPos = KaraokeHD.positionMs()
            else if (wantPlay && !switching && KaraokeHD.readyAtMs(app.engine.position())) {
                // smooth hand-over: HD fades in from the same point while the normal player fades out
                val at = app.engine.position()
                KaraokeHD.gain = 0f
                if (KaraokeHD.play(at)) {
                    // cross-fade (length in Settings): the song with its voice fades out while the
                    // backing track fades in, from the same point, so you HEAR the voice disappear
                    switching = true
                    val b = app.engine.player.browser.value
                    val v0 = b?.volume ?: 1f
                    KaraokeFade.ramp(fadeMs.coerceAtLeast(300)) { t -> KaraokeHD.gain = t; b?.volume = v0 * (1f - t) }
                    app.engine.pause()
                    b?.volume = v0
                    KaraokeHD.gain = 1f
                    switching = false
                } else KaraokeHD.gain = 1f
            }
            kotlinx.coroutines.delay(100)
        }
    }
    val pos = if (hdActive) hdPos else mainPos
    val isPlaying = if (hdActive) hdPlaying else playing
    val list = lines.orEmpty()
    // a line stays "current" until the next one starts (gaps keep the previous line lit)
    val current = list.indexOfLast { it.start <= pos + 150 }
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0614))) {
        if (song.cover != null && Build.VERSION.SDK_INT >= 31) AsyncImage(
            song.cover, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(80.dp).scale(1.4f), alpha = 0.45f
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0xCC3A1466), Color(0x99000000), Color(0xEE000000))
        )))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Esci dal karaoke", tint = Color.White) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Mic, null, tint = Color(0xFFFF4FD8), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("KARAOKE", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFF4FD8), fontWeight = FontWeight.Bold)
                    }
                    Text(song.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
                Spacer(Modifier.size(48.dp))
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // big screens (PC, tablets): lyrics on the left, the cover breathing on the beat on the right
            val wide = maxWidth > 900.dp
            Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (wide) 1.25f else 1f).fillMaxHeight()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    list.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎤", fontSize = 48.sp)
                        Text(
                            if (lines == null) "Testo non disponibile per questo brano"
                            else "Questo brano non ha il testo sincronizzato",
                            color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text("La base senza voce suona lo stesso: canta a memoria!", color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    }
                    else -> LazyColumn(
                        state = listState, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 60.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        itemsIndexed(list) { i, line ->
                            val isNow = i == current
                            val isPast = i < current
                            val color by animateColorAsState(
                                when {
                                    isNow -> Color.White
                                    isPast -> Color.White.copy(alpha = 0.35f)
                                    else -> Color.White.copy(alpha = 0.55f)
                                }, label = "kc$i"
                            )
                            val scale by animateFloatAsState(if (isNow) 1.06f else 1f, label = "ks$i")
                            // lines are not tappable: friends kept jumping around the song by touching the text
                            Column(Modifier.graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }) {
                                Text(line.text, color = color, fontSize = if (isNow) 30.sp else 26.sp, lineHeight = 34.sp,
                                    fontWeight = if (isNow) FontWeight.ExtraBold else FontWeight.Bold)
                                if (isNow && line.end > line.start) {
                                    // how far through the current line we are
                                    val p = ((pos - line.start).toFloat() / (line.end - line.start)).coerceIn(0f, 1f)
                                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                                        Box(Modifier.fillMaxWidth(p).height(4.dp).clip(CircleShape)
                                            .background(Brush.horizontalGradient(listOf(Color(0xFFFF4FD8), Color(0xFF8E7BFF)))))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (wide) BeatStage(song, pos, isPlaying, Modifier.weight(1f).fillMaxHeight(),
                caption = if (hdActive) "🎤 Karaoke HD" else "🎤 Karaoke")
            }
            }
            // where we are in the song (drag to move)
            KaraokeProgress(pos, mainDur.takeIf { it > 0 } ?: song.duration ?: 0L) { to ->
                if (hdActive) KaraokeHD.seekTo(to) else app.engine.seekTo(to)
            }
            KaraokeControls(
                playing = isPlaying,
                onPrev = {
                    if (hdActive && KaraokeHD.positionMs() > 3000) KaraokeHD.seekTo(0)
                    else { KaraokeHD.stopPlayback(); app.engine.previous(); app.engine.player.setPlaying(true) }
                },
                onToggle = {
                    when {
                        hdActive -> KaraokeHD.toggle()
                        isPlaying -> { wantPlay = false; app.engine.togglePlay() }
                        KaraokeHD.readyAtMs(app.engine.position()) -> { wantPlay = true; KaraokeHD.play(app.engine.position()) }
                        else -> { wantPlay = true; app.engine.togglePlay() }
                    }
                },
                onNext = { KaraokeHD.stopPlayback(); app.engine.next(); app.engine.player.setPlaying(true) },
                onStop = {
                    // stop: everything paused and back to the start of the song
                    wantPlay = false
                    if (hdActive) { KaraokeHD.seekTo(0); KaraokeHD.pause() }
                    else { app.engine.pause(); app.engine.seekTo(0) }
                }
            )
            androidx.compose.animation.AnimatedVisibility(switching) {
                Text("✨ Passo alla versione HD", color = Color(0xFFFF4FD8), fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            HdStatus(hdPhase, hdActive, startProgress = KaraokeHD.startProgress(pos), songProgress = KaraokeHD.songProgress(), onDownload = { KaraokeHD.downloadModel(song, app.engine.position()) },
                onRetry = { KaraokeHD.retry(song, app.engine.position()) })
            val bgState by KaraokeHD.background.collectAsState()
            bgState?.let { b ->
                Text("Preparo in anticipo: ${b.title ?: "prossima canzone"} · ${(b.progress * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 24.dp), maxLines = 1)
            }
            // voice amount (real-time filter): 0 = no voice, 1 = original song
            // the quick filter's voice slider: phone only (on PC the HD takes over in seconds)
            if (!hdActive && Karaoke.enabled && !PlatformUI.isDesktop) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Voce originale", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text(if (voice < 0.05f) "tolta" else "${(voice * 100).toInt()}%", color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelLarge)
                }
                Slider(voice, { voice = it; Karaoke.strength = 1f - it }, valueRange = 0f..1f)
            }
        }
    }
}


/** HD state under the lyrics: download offer, preparation progress, or the "HD" badge. */
@Composable
private fun HdStatus(phase: KaraokeHD.Phase, active: Boolean, startProgress: Float, songProgress: Float, onDownload: () -> Unit, onRetry: () -> Unit) {
    val pink = Color(0xFFFF4FD8)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)).padding(14.dp)) {
        when {
            active -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HD", color = Color.Black, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(CircleShape).background(pink).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(10.dp))
                Text("Voce tolta con l'intelligenza artificiale", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            phase is KaraokeHD.Phase.NeedsModel -> {
                Text("Karaoke HD", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Toglie la voce del tutto, anche riverbero e acuti, con un'AI che gira sul dispositivo. Si scarica una volta sola (66 MB, meglio col Wi-Fi).",
                    color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                BigButton("Scarica karaoke HD", onDownload, Modifier.fillMaxWidth(), container = pink, content = Color.Black)
            }
            phase is KaraokeHD.Phase.DownloadingModel -> {
                Text("Scarico il karaoke HD… ${(phase.progress * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.LinearProgressIndicator(progress = { phase.progress }, color = pink,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(CircleShape))
            }
            phase is KaraokeHD.Phase.Fetching || phase is KaraokeHD.Phase.Separating -> {
                // the bar is how close HD is to STARTING (100% = it switches), not the whole song
                val fetching = phase is KaraokeHD.Phase.Fetching
                val target = if (fetching) 0.05f * ((phase as KaraokeHD.Phase.Fetching).progress) else 0.05f + 0.95f * startProgress
                val p by androidx.compose.animation.core.animateFloatAsState(target, androidx.compose.animation.core.tween(600), label = "hdp")
                Text(if (fetching) "Preparo la versione HD…" else "Tolgo la voce con l'AI… ${(p * 100).toInt()}%",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Text("Intanto senti il filtro veloce: a 100% passa all'HD da sola" +
                        (if (!fetching && songProgress > 0f) " · brano separato ${(songProgress * 100).toInt()}%" else ""),
                    color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(progress = { p }, color = pink,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(CircleShape))
            }
            phase is KaraokeHD.Phase.Ready -> Text("Versione HD pronta: parte appena premi play", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            phase is KaraokeHD.Phase.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HD non riuscito: ${phase.message}. Intanto senti il filtro veloce.",
                    color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("Riprova", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(CircleShape).background(pink).bouncy(onRetry).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            else -> {}
        }
    }
}


/** Playlist action: separates every song for karaoke HD in the background. */
@Composable
fun KaraokePrepButton(songs: List<SongRef>) {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { KaraokeHD.init(context) }
    val cached by KaraokeHD.cached.collectAsState()
    val bg by KaraokeHD.background.collectAsState()
    val ready = songs.count { it.key in cached }
    val all = songs.isNotEmpty() && ready == songs.size
    IconButton(onClick = {
        when {
            songs.isEmpty() || all -> app.nav.toast(if (all) "Già pronta per il karaoke HD 🎤" else "Playlist vuota")
            !KaraokeHD.hasModel() -> app.nav.toast("Prima scarica il karaoke HD: apri il karaoke 🎤 dal player")
            else -> {
                KaraokeHD.preparePlaylist(songs)
                app.nav.toast("Preparo ${songs.size - ready} brani per il karaoke HD. Meglio col telefono in carica")
            }
        }
    }) {
        Box(contentAlignment = Alignment.Center) {
            if (bg != null && !all) CircularProgressIndicator(
                progress = { if (songs.isEmpty()) 0f else ready.toFloat() / songs.size },
                modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp, color = Color(0xFFFF4FD8)
            )
            Icon(Icons.Rounded.Mic, "Prepara per il karaoke", tint = if (all) Color(0xFFFF4FD8) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(if (bg != null && !all) 16.dp else 24.dp))
        }
    }
}


/** Big centred transport: previous, play/pause, next, stop. */
@Composable
private fun KaraokeControls(playing: Boolean, onPrev: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onStop, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Rounded.Stop, "Stop", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(30.dp))
        }
        IconButton(onClick = onPrev, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, "Precedente", tint = Color.White, modifier = Modifier.size(38.dp))
        }
        Box(
            Modifier.size(72.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFFF4FD8), Color(0xFF8E7BFF))))
                .bouncy(onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausa" else "Play",
                tint = Color.White, modifier = Modifier.size(40.dp))
        }
        IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, "Successivo", tint = Color.White, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.size(52.dp))
    }
}


/**
 * Lyrics under the full player: a card with the synced lines (current one lit, auto-scrolling,
 * tap to jump) and a shortcut to the karaoke. Text comes from the Deezer lyrics service.
 */
@Composable
fun PlayerLyricsCard(song: SongRef, pos: Long, onOpenKaraoke: () -> Unit) {
    val app = LocalApp.current
    var lines by remember(song.key) { mutableStateOf<List<KaraokeLine>?>(null) }
    var loading by remember(song.key) { mutableStateOf(true) }
    LaunchedEffect(song.key) { loading = true; lines = loadKaraokeLines(app, song); loading = false }
    val list = lines.orEmpty()
    val current = list.indexOfLast { it.start <= pos + 150 }
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem((current - 1).coerceAtLeast(0)) }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF6A2BB8), Color(0xFF3A1466))))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Testo", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).bouncy(onOpenKaraoke)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Karaoke", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
            }
            list.isEmpty() -> Text(
                if (lines == null) "Testo non disponibile per questo brano" else "Questo brano non ha il testo sincronizzato",
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 20.dp)
            )
            else -> LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(380.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(list) { i, line ->
                    val color by animateColorAsState(
                        when {
                            i == current -> Color.White
                            i < current -> Color.White.copy(alpha = 0.45f)
                            else -> Color.Black.copy(alpha = 0.55f)
                        }, label = "pl$i"
                    )
                    Text(line.text, color = color, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Elapsed / length of the song, with a bar you can drag to move (karaoke had no time bar at all). */
@Composable
private fun KaraokeProgress(pos: Long, dur: Long, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(if (drag != null) (drag!! * dur).toLong() else pos), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
        Slider(frac, { drag = it }, onValueChangeFinished = { drag?.let { onSeek((it * dur).toLong()) }; drag = null },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp), enabled = dur > 0,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFF4FD8),
                inactiveTrackColor = Color.White.copy(alpha = 0.18f)))
        Text(fmt(dur), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * The fades around karaoke, so the voice never just "cuts":
 *  - [filterIn]: the quick filter takes the voice away gradually;
 *  - [exit]: leaving karaoke, the normal song (with voice) comes back while the backing track fades out.
 * Runs on its own scope, so the screen can close while the voice is still coming back.
 */
object KaraokeFade {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var job: kotlinx.coroutines.Job? = null
    @Volatile var exiting = false; private set

    suspend fun ramp(ms: Long, step: (Float) -> Unit) {
        val n = (ms / 30).coerceAtLeast(1)
        for (i in 1..n) { step(i.toFloat() / n); kotlinx.coroutines.delay(30) }
    }

    @Volatile private var gen = 0

    fun cancelExit() { if (exiting) { gen++; job?.cancel(); exiting = false } }

    fun filterIn(ms: Long) {
        val target = Karaoke.strength.takeIf { it > 0.05f } ?: 0.9f
        Karaoke.strength = 0f; Karaoke.enabled = true
        scope.launch { ramp(ms.coerceAtLeast(1)) { t -> Karaoke.strength = target * t } }
    }

    fun exit(app: AppState, ms: Long) {
        exiting = true
        val my = ++gen
        job = scope.launch {
            try {
                val b = app.engine.player.browser.value
                if (b != null && b.volume < 0.05f) b.volume = 1f // never leave the player muted
                if (KaraokeHD.active.value) {
                    // One source at a time (playing the normal player while HD was still open left it
                    // stuck at the entry point): the normal player takes over WITH the quick filter at
                    // full strength (still no voice), then the filter fades out = the voice comes back.
                    val at = KaraokeHD.positionMs()
                    ramp(150) { t -> KaraokeHD.gain = 1f - t }
                    KaraokeHD.close()
                    Karaoke.strength = 1f; Karaoke.enabled = true
                    app.engine.seekTo(at); app.engine.player.setPlaying(true)
                    kotlinx.coroutines.delay(300)
                    ramp(ms.coerceAtLeast(300)) { t -> Karaoke.strength = 1f - t }
                    Karaoke.strength = 0.9f
                } else {
                    KaraokeHD.close()?.let { at -> app.engine.seekTo(at); app.engine.player.setPlaying(true) }
                    if (Karaoke.enabled) {
                        val s0 = Karaoke.strength
                        ramp(ms.coerceAtLeast(1)) { t -> Karaoke.strength = s0 * (1f - t) }
                        Karaoke.strength = s0
                    }
                }
            } finally {
                // only if karaoke was not reopened meanwhile (that one owns the state now)
                if (my == gen) {
                    Karaoke.enabled = false
                    KaraokeHD.gain = 1f
                    KaraokeHD.onEnded = null
                    exiting = false
                }
            }
        }
    }
}
