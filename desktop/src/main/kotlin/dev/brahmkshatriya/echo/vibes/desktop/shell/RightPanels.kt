package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.FollowedArtist
import dev.brahmkshatriya.echo.vibes.ui.Avatar
import dev.brahmkshatriya.echo.vibes.ui.Cover
import dev.brahmkshatriya.echo.vibes.ui.EqualizerBars
import dev.brahmkshatriya.echo.vibes.ui.KaraokeLine
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.Route
import dev.brahmkshatriya.echo.vibes.ui.ShareItem
import dev.brahmkshatriya.echo.vibes.ui.currentSong
import dev.brahmkshatriya.echo.vibes.ui.fmt
import dev.brahmkshatriya.echo.vibes.ui.loadKaraokeLines
import dev.brahmkshatriya.echo.vibes.ui.openArtistOf
import kotlinx.coroutines.launch

@Composable
fun RightPanelHost(width: Dp, modifier: Modifier = Modifier) {
    val desk = LocalDesk.current
    Box(modifier.width(width).fillMaxHeight().deskGlass()) {
        when (desk.right) {
            RightPanel.NowPlaying -> NowPlayingPanel()
            RightPanel.Queue -> QueuePanel()
            RightPanel.Friends -> FriendsPanel()
            RightPanel.None -> {}
        }
    }
}

/** The right panel reduced to a strip: the current cover and the panel buttons, one click to open. */
@Composable
fun RightStrip() {
    val desk = LocalDesk.current
    val now = currentSong()
    Column(Modifier.width(58.dp).fillMaxHeight().deskGlass().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DeskIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Apri il pannello") { desk.expandRight() }
        now?.let { (song, _) ->
            Cover(song.cover, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable { desk.right = RightPanel.NowPlaying; desk.expandRight() }, RoundedCornerShape(8.dp))
        }
        DeskIcon(Icons.Rounded.Lyrics, "Testo", active = desk.right == RightPanel.NowPlaying) { desk.right = RightPanel.NowPlaying; desk.expandRight() }
        DeskIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Coda", active = desk.right == RightPanel.Queue) { desk.right = RightPanel.Queue; desk.expandRight() }
        DeskIcon(Icons.Rounded.Group, "Amici", active = desk.right == RightPanel.Friends) { desk.right = RightPanel.Friends; desk.expandRight() }
    }
}

@Composable
private fun PanelTitle(title: String, onClose: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        extra()
        val desk = LocalDesk.current
        DeskIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Riduci") { desk.collapseRight() }
    }
}

/** The lyrics of the song, cached per song (both the preview card and the big lyrics view use them). */
object LyricsCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<KaraokeLine>>()
    fun get(key: String) = cache[key]
    suspend fun load(app: dev.brahmkshatriya.echo.vibes.ui.AppState, s: SongRef): List<KaraokeLine> {
        cache[s.key]?.let { return it }
        val l = loadKaraokeLines(app, s).orEmpty()
        cache[s.key] = l
        return l
    }
}

// ---------------------------------------------------------------- now playing

@Composable
fun NowPlayingPanel() {
    val app = LocalApp.current
    val nav = app.nav
    val desk = LocalDesk.current
    val scope = rememberCoroutineScope()
    val now = currentSong()
    val liked by app.library.liked.collectAsState()
    if (now == null) {
        Column(Modifier.fillMaxSize()) {
            PanelTitle("In riproduzione", { desk.toggleRight(RightPanel.NowPlaying) })
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Fai partire un brano: qui vedrai copertina, testo, artista, crediti e cosa viene dopo.", color = Desk.sub,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val (song, _) = now
    var details by remember(song.key) { mutableStateOf<DeezerPublic.TrackDetails?>(null) }
    var artist by remember(song.key) { mutableStateOf<DeezerPublic.ArtistInfo?>(null) }
    var lines by remember(song.key) { mutableStateOf(LyricsCache.get(song.key)) }
    LaunchedEffect(song.key) {
        val id = Actions.deezerId(song)
        details = id?.let { runCatching { VibesGraph.deezer.trackDetails(it) }.getOrNull() }
        details?.artistId?.takeIf { it > 0 }?.let { artist = runCatching { VibesGraph.deezer.artistInfo(it) }.getOrNull() }
    }
    LaunchedEffect(song.key) { if (lines == null) lines = LyricsCache.load(app, song) }
    val color = rememberCoverColor(song.cover) ?: Color(0xFF503070)
    val ctx = VibesGraph.playContext
    val isLiked = liked.any { it.key == song.key }
    val (pos, _) = rememberPlayback()
    Column(Modifier.fillMaxSize()) {
        PanelTitle(ctx?.name ?: song.album ?: "In riproduzione", { desk.toggleRight(RightPanel.NowPlaying) }) {
            ContextMenuBox(items = { songMenu(app, desk, song, scope) }) { open -> DeskIcon(Icons.Rounded.MoreHoriz, "Altre opzioni") { open() } }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // the cover opens the big song view: rounded hover glow + hand cursor, so it reads as a button
            var coverHover by remember { mutableStateOf(false) }
            val lift by androidx.compose.animation.core.animateFloatAsState(if (coverHover) 1.02f else 1f, label = "ch")
            Box(Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = lift; scaleY = lift }
                .shadow(if (coverHover) 24.dp else 8.dp, RoundedCornerShape(18.dp), ambientColor = color, spotColor = color)
                .clip(RoundedCornerShape(18.dp)).hovered { coverHover = it }.pointerHoverIcon(HandCursor)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { desk.fullPlayer = true }) {
                Cover(song.cover, Modifier.fillMaxSize(), RoundedCornerShape(18.dp))
                if (coverHover) Box(Modifier.fillMaxSize().border(2.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.OpenInFull, "Apri la vista del brano", tint = Color.White, modifier = Modifier.size(42.dp)
                        .background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape).padding(8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LinkText(song.title, color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)) {
                        scope.launch { Actions.openAlbum(app, song) }
                    }
                    ArtistLinkText(song, style = MaterialTheme.typography.bodyLarge) { i -> scope.launch { openArtistOf(app, song, i) } }
                }
                DeskIcon(Icons.Rounded.Share, "Condividi") { nav.share = ShareItem.Song(song) }
                DeskIcon(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    if (isLiked) "Rimuovi da Brani che ti piacciono" else "Salva in Brani che ti piacciono", active = isLiked) { app.library.toggleLike(song) }
            }

            // lyrics preview (like Spotify's card): click for the big lyrics view
            val l = lines.orEmpty()
            if (l.isNotEmpty()) {
                val cur = l.indexOfLast { it.start <= pos + 150 }.coerceAtLeast(0)
                Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color)
                    .pointerHoverIcon(HandCursor).clickable { desk.overlay = CenterOverlay.Lyrics }.padding(16.dp)) {
                    Text("Anteprima del testo", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    l.drop(cur).take(3).forEachIndexed { i, line ->
                        Text(line.text, color = if (i == 0) Color.White else Color.Black.copy(alpha = 0.55f), fontSize = 20.sp, lineHeight = 26.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Mostra il testo", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                }
            }

            // the artist
            artist?.let { a -> ArtistCard(a) }

            // credits
            details?.let { d ->
                Card("Riconoscimenti") {
                    val roles = d.contributors.ifEmpty { listOf(d.artist to "Main") }
                    roles.distinct().take(6).forEach { (name, role) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(when (role) { "Main" -> "Artista principale"; "Featured" -> "Artista ospite"; "" -> "Artista"; else -> role }, color = Desk.sub,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (d.album.isNotBlank()) DetailLine("Album", d.album)
                    if (d.releaseDate.isNotBlank()) DetailLine("Uscito il", d.releaseDate.split("-").reversed().joinToString("/"))
                    if (d.bpm > 0) DetailLine("BPM", d.bpm.toInt().toString())
                    if (d.durationSec > 0) DetailLine("Durata", fmt(d.durationSec * 1000L))
                }
            }

            // next in queue
            NextUp()
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Desk.sub, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Card(title: String, action: String? = null, onAction: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Desk.raised).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) LinkText(action, style = MaterialTheme.typography.labelLarge) { onAction() }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ArtistCard(a: DeezerPublic.ArtistInfo) {
    val app = LocalApp.current
    val following by app.library.artists.collectAsState()
    val isFollowing = following.any { it.id == a.id }
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Desk.raised)
        .pointerHoverIcon(HandCursor).clickable { app.nav.open(Route.DzArtist(a.id, a.name)) }) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Cover(a.picture, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent, Color.Transparent))))
            Text("Informazioni sull'artista", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp))
        }
        Column(Modifier.padding(16.dp)) {
            Text(a.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%,d fan su Deezer".format(a.fans).replace(',', '.'), color = Desk.sub, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .pointerHoverIcon(HandCursor).clickable {
                        if (isFollowing) app.library.unfollowArtist(a.id) else app.library.followArtist(FollowedArtist(a.id, a.name, a.picture))
                    }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(if (isFollowing) "Segui già" else "Segui", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** "Successivo in coda": the next song + a link to the whole queue. */
@Composable
private fun NextUp() {
    val app = LocalApp.current
    val desk = LocalDesk.current
    val changes by app.engine.player.changes.collectAsState()
    val cur by app.engine.player.playerState.current.collectAsState()
    val next = remember(changes, cur) {
        val q = app.engine.player.queue(); val i = cur?.index ?: -1
        q.getOrNull(i + 1)?.let { runCatching { SongRef.from(it.extensionId, it.track) }.getOrNull() }
    } ?: return
    Card("Successivo in coda", "Apri coda", { desk.right = RightPanel.Queue; desk.save() }) {
        QueueRow(next, playing = false, onPlay = { app.engine.next() })
    }
}

// ---------------------------------------------------------------- queue

@Composable
fun QueuePanel() {
    val app = LocalApp.current
    val desk = LocalDesk.current
    val p = app.engine.player
    val changes by p.changes.collectAsState()
    val cur by p.playerState.current.collectAsState()
    val recent by app.library.recent.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val queue = remember(changes) { p.queue().map { m -> runCatching { SongRef.from(m.extensionId, m.track) }.getOrNull() } }
    val index = cur?.index ?: -1
    val ctx = VibesGraph.playContext
    Column(Modifier.fillMaxSize()) {
        PanelTitle("Coda", { desk.toggleRight(RightPanel.Queue) })
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Coda", tab == 0) { tab = 0 }
            Chip("Recenti", tab == 1) { tab = 1 }
        }
        if (tab == 1) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(recent, key = { i, s -> "r$i${s.key}" }) { i, s ->
                    QueueRow(s, playing = false, menu = { songMenu(app, desk, s, scope) }) { app.engine.playAll(recent, i) }
                }
            }
            return@Column
        }
        val upcoming = (index + 1 until queue.size).toList()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            item { Text("In riproduzione", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
            queue.getOrNull(index)?.let { s -> item(key = "now") { QueueRow(s, playing = true, menu = { songMenu(app, desk, s, scope) }) { app.engine.togglePlay() } } }
            if (upcoming.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Successivi" + (ctx?.let { " da: ${it.name}" } ?: ""), color = Color.White, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    DeskIcon(Icons.Rounded.DeleteSweep, "Svuota la coda") { p.clearUpcoming(); app.nav.toast("Coda svuotata") }
                }
            }
            itemsIndexed(upcoming, key = { _, i -> "q$i:${queue[i]?.key}" }) { pos, qi ->
                val s = queue[qi] ?: return@itemsIndexed
                DraggableQueueRow(
                    s, onMove = { steps -> val to = (qi + steps).coerceIn(index + 1, queue.size - 1); if (to != qi) p.moveQueueItem(qi, to) },
                    menu = {
                        listOf(
                            MenuItem("Riproduci ora", Icons.Rounded.PlayArrow) { p.playAt(qi) },
                            MenuItem("Sposta in cima", Icons.Rounded.VerticalAlignTop) { p.moveQueueItem(qi, index + 1) },
                            MenuItem("Rimuovi dalla coda", Icons.Rounded.RemoveCircleOutline) { p.removeQueueItem(qi) },
                            MenuItem("Aggiungi a playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, submenu = { close -> PlaylistPicker(listOf(s), close) }),
                            null,
                        ) + songMenu(app, desk, s, scope).drop(3)
                    },
                    onPlay = { p.playAt(qi) },
                )
            }
            if (upcoming.isEmpty()) item {
                Text("Niente in coda: tasto destro su un brano → Aggiungi alla coda.", color = Desk.sub, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun QueueRow(s: SongRef, playing: Boolean, menu: (() -> List<MenuItem?>)? = null, trailing: (@Composable () -> Unit)? = null, onPlay: () -> Unit) {
    var hover by remember { mutableStateOf(false) }
    ContextMenuBox(items = { menu?.invoke() ?: emptyList() }) { open ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (hover) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                .hovered { hover = it }.pointerHoverIcon(HandCursor).clickable(onClick = onPlay).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp)) {
                Cover(s.cover, Modifier.fillMaxSize(), RoundedCornerShape(4.dp))
                if (hover) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(s.title, color = if (playing) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artist, color = Desk.sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playing) EqualizerBars(Modifier.size(14.dp))
            trailing?.invoke()
            if (hover && menu != null) DeskIcon(Icons.Rounded.MoreHoriz, "Altre opzioni") { open() }
        }
    }
}

/** A queue row with a handle: drag it up or down to move the song. */
@Composable
private fun DraggableQueueRow(s: SongRef, onMove: (Int) -> Unit, menu: () -> List<MenuItem?>, onPlay: () -> Unit) {
    var dy by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowPx = with(density) { 60.dp.toPx() }
    Box(Modifier.graphicsLayer { translationY = dy; shadowElevation = if (dy != 0f) 12f else 0f }) {
        QueueRow(s, playing = false, menu = menu, onPlay = onPlay, trailing = {
            Icon(Icons.Rounded.DragIndicator, "Trascina per spostare", tint = Desk.sub,
                modifier = Modifier.size(20.dp).pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR)))
                    .pointerInput(s.key) {
                        detectDragGestures(
                            onDragEnd = { val steps = kotlin.math.round(dy / rowPx).toInt(); dy = 0f; if (steps != 0) onMove(steps) },
                            onDragCancel = { dy = 0f },
                        ) { c, d -> c.consume(); dy += d.y }
                    })
        })
    }
}

// ---------------------------------------------------------------- friends

@Composable
fun FriendsPanel() {
    val app = LocalApp.current
    val nav = app.nav
    val desk = LocalDesk.current
    val friends by app.social.friends.collectAsState()
    val unread by app.social.unread.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PanelTitle("Attività degli amici", { desk.toggleRight(RightPanel.Friends) })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            val sorted = friends.sortedWith(compareByDescending<dev.brahmkshatriya.echo.vibes.backend.Profile> { it.liveNow?.playing == true }.thenByDescending { it.lastSeen ?: "" })
            itemsIndexed(sorted, key = { _, f -> f.id }) { _, f ->
                var hover by remember { mutableStateOf(false) }
                val live = f.liveNow
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (hover) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .hovered { hover = it }.pointerHoverIcon(HandCursor).clickable { nav.open(Route.Chat(f.id)) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Avatar(f, 44.dp)
                        if (live?.playing == true) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Color(0xFF1ED760)))
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                            (unread[f.id] ?: 0).takeIf { it > 0 }?.let { n ->
                                Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 1.dp)) {
                                    Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (live != null) {
                            Text("${live.song.title} · ${live.song.artist}", color = Desk.sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (hover) Text("▶ Ascolta anche tu", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable { app.engine.play(live.song) }.padding(top = 2.dp))
                        } else Text("Non sta ascoltando", color = Desk.sub, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Tutte le chat", false) { nav.selectTab(Route.Friends) }
                    Chip("Aggiungi amici", false) { nav.open(Route.AddFriend) }
                }
            }
        }
    }
}
