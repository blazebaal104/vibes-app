package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.SongRef.Companion.url
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.music.SavedAlbum
import dev.brahmkshatriya.echo.vibes.music.VibesPlaylist
import dev.brahmkshatriya.echo.vibes.ui.Avatar
import dev.brahmkshatriya.echo.vibes.ui.Cover
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.PlaylistArt
import dev.brahmkshatriya.echo.vibes.ui.PlaylistDialog
import dev.brahmkshatriya.echo.vibes.ui.Route
import dev.brahmkshatriya.echo.vibes.ui.ShareItem
import dev.brahmkshatriya.echo.vibes.ui.StoriesRow
import dev.brahmkshatriya.echo.vibes.ui.parseColor
import kotlinx.coroutines.launch
import java.util.Calendar

// ---------------------------------------------------------------- shared page parts

/** Big header of playlist / album pages: cover, type, huge title, details. */
@Composable
fun PageHeader(type: String, title: String, art: @Composable (Modifier) -> Unit, details: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 24.dp), verticalAlignment = Alignment.Bottom) {
        art(Modifier.size(232.dp).shadow(28.dp, RoundedCornerShape(20.dp)))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(type, color = Color.White, style = MaterialTheme.typography.labelLarge)
            BoxWithConstraints {
                val size = when { title.length > 40 -> 34.sp; title.length > 22 -> 48.sp; maxWidth.value < 520 -> 48.sp; else -> 76.sp }
                Text(title, color = Color.White, fontSize = size, lineHeight = size * 1.05f, fontWeight = FontWeight.ExtraBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 8.dp))
            }
            details()
        }
    }
}

/** A bar that appears at the top once the header is scrolled away: play button + title. */
@Composable
fun StickyBar(visible: Boolean, color: Color, title: String, playing: Boolean, onPlay: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(150)), exit = fadeOut(tween(120))) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(color.copy(alpha = 1f).let { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.35f) })
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayCircle(44.dp, playing, onClick = onPlay)
            Spacer(Modifier.width(14.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The page's colour is the cover's: the centre background follows it while the page is open. */
@Composable
fun PageColor(c: Color?) {
    val desk = LocalDesk.current
    DisposableEffect(c) { desk.pageColor = c; onDispose { if (desk.pageColor == c) desk.pageColor = null } }
}

fun LazyListScope.trackList(
    songs: List<SongRef>, selection: Selection, onPlay: (Int) -> Unit,
    showAlbum: Boolean = true, showCover: Boolean = true,
    removeFrom: ((SongRef) -> Unit)? = null, removeLabel: String = "Rimuovi da questa playlist",
) {
    item(key = "th") { TrackHeader(showAlbum) }
    itemsIndexed(songs, key = { i, s -> "t$i:${s.key}" }) { i, s ->
        TrackRow(i, s, { onPlay(i) }, selection, showAlbum, showCover, removeFrom?.let { r -> { r(s) } }, removeLabel)
    }
}

private fun totalTime(songs: List<SongRef>): String {
    val min = songs.sumOf { it.duration ?: 0L } / 60000
    return when { min <= 0 -> ""; min < 60 -> " · $min min"; else -> " · ${min / 60} h ${min % 60} min" }
}

/** Is the player playing something from this list right now? */
@Composable
private fun playingFrom(id: String?, songs: List<SongRef>): Boolean {
    val app = LocalApp.current
    val cur by app.engine.player.playerState.current.collectAsState()
    val c = cur ?: return false
    if (!c.isPlaying) return false
    val ctx = VibesGraph.playContext
    return if (id != null && ctx != null) ctx.id == id && c.mediaItem.mediaId in ctx.keys else songs.any { it.key == c.mediaItem.mediaId }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

// ---------------------------------------------------------------- home

@Composable
fun DeskHome() {
    val app = LocalApp.current
    val nav = app.nav
    val desk = LocalDesk.current
    val me by app.social.me.collectAsState()
    val recent by app.library.recent.collectAsState()
    val playlists by app.library.playlists.collectAsState()
    val albums by app.library.albums.collectAsState()
    val opened by app.library.opened.collectAsState()
    val liked by app.library.liked.collectAsState()
    val friends by app.social.friends.collectAsState()
    val followed by app.library.artists.collectAsState()
    val loggedIn by app.engine.deezerLoggedIn.collectAsState(initial = true)
    var filter by remember { mutableStateOf(0) }
    var recs by remember { mutableStateOf<List<dev.brahmkshatriya.echo.vibes.music.Recommender.Section>>(emptyList()) }
    var shelves by remember { mutableStateOf<List<MusicEngine.UiShelf>?>(null) }
    var extId by remember { mutableStateOf(MusicEngine.DEEZER) }
    var releases by remember { mutableStateOf<List<DeezerPublic.AlbumBrief>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(recent.size / 5) { recs = runCatching { VibesGraph.recommender.sections() }.getOrDefault(emptyList()) }
    LaunchedEffect(loggedIn) {
        shelves = runCatching { extId = app.engine.extensionId(); app.engine.home() }.getOrDefault(emptyList())
    }
    LaunchedEffect(followed.size) {
        releases = followed.take(12).mapNotNull { a -> runCatching { VibesGraph.deezer.artistAlbums(a.id, a.name).firstOrNull() }.getOrNull() }
            .sortedByDescending { it.date }
    }
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 5..12 -> "Buongiorno"; in 13..17 -> "Buon pomeriggio"; else -> "Buonasera" }
    // the playlists / albums you use most: opened recently first
    val quick = remember(playlists, albums, opened, liked.size) {
        val items = playlists.map { QuickItem.Pl(it) } + albums.map { QuickItem.Al(it) }
        listOf<QuickItem>(QuickItem.Liked) + items.sortedByDescending { opened[it.key] ?: (it.created / 1000) }.take(7)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Tutto", "Musica", "Amici").forEachIndexed { i, l -> Chip(l, filter == i) { filter = i } }
            }
        }
        item {
            Text(greeting + (me?.name?.let { ", $it" } ?: ""), color = Color.White, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 12.dp))
        }
        if (filter != 1) item { StoriesRow() }
        if (filter != 2) item {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                val cols = if (maxWidth.value < 720) 2 else 4
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.chunked(cols).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { q -> Box(Modifier.weight(1f)) { QuickTile(q) } }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (filter != 2 && !loggedIn) item {
            Row(Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary)
                .pointerHoverIcon(HandCursor).clickable { nav.open(Route.DeezerLogin) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🎶", fontSize = 30.sp); Spacer(Modifier.width(14.dp))
                Column {
                    Text("Collega Deezer", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
                    Text("Serve per cercare e ascoltare la musica.", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
                }
            }
        }
        if (filter != 2) recs.forEach { sec ->
            item { Shelf(sec.title, sec.songs, sec.subtitle) { s, w -> SongCard(s, w, sec.songs) } }
        }
        if (filter != 1) {
            val listening = friends.filter { it.liveNow?.playing == true }
            if (listening.isNotEmpty()) item {
                Shelf("I tuoi amici ascoltano", listening) { f, w ->
                    val s = f.liveNow!!.song
                    MediaCard(s.cover, s.title, "${f.name} · ${s.artist}", w, onPlay = { app.engine.play(s) }, onOpen = { nav.open(Route.Profile(f.id)) },
                        art = { m -> Box(m) { Cover(s.cover, Modifier.fillMaxSize(), RoundedCornerShape(6.dp)); Avatar(f, 40.dp, Modifier.align(Alignment.TopStart).padding(8.dp)) } })
                }
            }
        }
        if (filter != 2) {
            if (recent.isNotEmpty()) item { Shelf("Ascoltati di recente", recent.take(30)) { s, w -> SongCard(s, w, recent) } }
            if (playlists.isNotEmpty()) item {
                Shelf("Le tue playlist", playlists) { p, w ->
                    val on = playingFrom(p.id, p.songs)
                    MediaCard(p.songs.firstOrNull()?.cover, p.name, "Playlist · ${p.songs.size} brani", w,
                        playing = on,
                        art = { m -> PlaylistArt(p, m, RoundedCornerShape(6.dp)) },
                        menu = { playlistMenu(app, p, scope) },
                        onPlay = { if (on) app.engine.togglePlay() else { app.engine.playAll(p.songs, ctx = p); app.library.touch("pl:${p.id}") } },
                        onOpen = { nav.open(Route.Playlist(p.id)) })
                }
            }
            if (albums.isNotEmpty()) item {
                Shelf("I tuoi album", albums) { a, w -> AlbumCard(a.id, a.cover, a.title, a.artist, w) }
            }
            if (releases.isNotEmpty()) item {
                Shelf("Nuove uscite dagli artisti che segui", releases) { a, w ->
                    AlbumCard(a.id, a.cover, a.title, "${a.date.take(4)} · ${a.artist}", w)
                }
            }
            shelves?.forEach { shelf ->
                if (shelf.tracks.isNotEmpty()) item {
                    val songs = shelf.tracks.map { SongRef.from(extId, it) }
                    Shelf(shelf.title, songs) { s, w -> SongCard(s, w, songs) }
                } else if (shelf.items.isNotEmpty()) item {
                    Shelf(shelf.title, shelf.items) { m, w ->
                        val round = m is Artist
                        MediaCard(m.cover.url(), m.title, when (m) { is Artist -> "Artista"; is Album -> m.artists.joinToString(", ") { it.name }; else -> m.subtitle ?: "Playlist" },
                            w, round = round, onOpen = { nav.open(Route.Media(m)) },
                            art = { mm -> dev.brahmkshatriya.echo.vibes.ui.MediaArt(m, mm, if (round) androidx.compose.foundation.shape.CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) },
                            onPlay = if (round) null else { -> scope.launch { runCatching { app.engine.open(m) }.getOrNull()?.let { (f, t) -> app.engine.play(extId, t, 0, f) } }; Unit })
                    }
                }
            }
        }
    }
}

private sealed interface QuickItem {
    val key: String; val created: Long
    data object Liked : QuickItem { override val key = "liked"; override val created = Long.MAX_VALUE }
    data class Pl(val p: VibesPlaylist) : QuickItem { override val key = "pl:${p.id}"; override val created = p.createdAt }
    data class Al(val a: SavedAlbum) : QuickItem { override val key = "al:${a.id}"; override val created = a.savedAt }
}

/** Home's top grid: wide tiles, the cover's colour washes the page on hover, play button on the right. */
@Composable
private fun QuickTile(q: QuickItem) {
    val app = LocalApp.current
    val nav = app.nav
    val liked by app.library.liked.collectAsState()
    val scope = rememberCoroutineScope()
    var hover by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (hover) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f), tween(150), label = "qt")
    val (title, cover) = when (q) {
        QuickItem.Liked -> "Brani che ti piacciono" to null
        is QuickItem.Pl -> q.p.name to (q.p.cover ?: q.p.songs.firstOrNull()?.cover)
        is QuickItem.Al -> q.a.title to q.a.cover
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)).background(bg).hovered { hover = it }.hoverTint(cover)
            .pointerHoverIcon(HandCursor).clickable {
                when (q) { QuickItem.Liked -> nav.open(Route.Liked); is QuickItem.Pl -> nav.open(Route.Playlist(q.p.id)); is QuickItem.Al -> nav.open(Route.DzAlbum(q.a.id)) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (q) {
            QuickItem.Liked -> LikedArt(Modifier.size(64.dp), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            is QuickItem.Pl -> PlaylistArt(q.p, Modifier.size(64.dp), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            is QuickItem.Al -> Cover(q.a.cover, Modifier.size(64.dp), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
        }
        Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        AnimatedVisibility(hover, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
            PlayCircle(40.dp, modifier = Modifier.padding(end = 10.dp)) {
                when (q) {
                    QuickItem.Liked -> app.engine.playAll(liked)
                    is QuickItem.Pl -> { app.engine.playAll(q.p.songs, ctx = q.p); app.library.touch(q.key) }
                    is QuickItem.Al -> scope.launch {
                        VibesGraph.deezer.album(q.a.id)?.let { a -> app.engine.playAll(a.tracks.map { it.toSongRef().copy(album = a.title) }) }
                        app.library.touch(q.key)
                    }
                }
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    var hover by remember { mutableStateOf(false) }
    Box(
        Modifier.clip(CircleShape).background(when { selected -> Color.White; hover -> Color.White.copy(alpha = 0.18f); else -> Color.White.copy(alpha = 0.1f) })
            .hovered { hover = it }.pointerHoverIcon(HandCursor).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, color = if (selected) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SongCard(s: SongRef, w: androidx.compose.ui.unit.Dp, list: List<SongRef>) {
    val app = LocalApp.current
    val desk = LocalDesk.current
    val scope = rememberCoroutineScope()
    val cur by app.engine.player.playerState.current.collectAsState()
    val now = cur?.mediaItem?.mediaId == s.key && cur?.isPlaying == true
    MediaCard(s.cover, s.title, s.artist, w, playing = now,
        menu = { songMenu(app, desk, s, scope) },
        onPlay = { if (now) app.engine.togglePlay() else app.engine.playAll(list, list.indexOfFirst { it.key == s.key }.coerceAtLeast(0)) },
        onOpen = { scope.launch { Actions.openAlbum(app, s) } })
}

@Composable
fun AlbumCard(id: Long, cover: String?, title: String, subtitle: String, w: androidx.compose.ui.unit.Dp) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    MediaCard(cover, title, subtitle, w,
        menu = { albumMenu(app, id, title, subtitle, cover) },
        onPlay = { scope.launch { VibesGraph.deezer.album(id)?.let { a -> app.engine.playAll(a.tracks.map { it.toSongRef().copy(album = a.title) }); app.library.touch("al:$id") } } },
        onOpen = { app.nav.open(Route.DzAlbum(id)) })
}

fun albumMenu(app: dev.brahmkshatriya.echo.vibes.ui.AppState, id: Long, title: String, artist: String, cover: String?): List<MenuItem?> {
    val saved = app.library.isAlbumSaved(id)
    return listOf(
        MenuItem(if (saved) "Rimuovi dalla libreria" else "Salva nella libreria", if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline) {
            if (saved) app.library.removeAlbum(id) else app.library.saveAlbum(SavedAlbum(id, title, artist, cover))
        },
        MenuItem("Apri album", Icons.AutoMirrored.Rounded.QueueMusic) { app.nav.open(Route.DzAlbum(id)) },
    )
}

fun playlistMenu(app: dev.brahmkshatriya.echo.vibes.ui.AppState, p: VibesPlaylist, scope: kotlinx.coroutines.CoroutineScope, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null): List<MenuItem?> {
    val folders = app.library.folders.value
    return listOf(
        MenuItem("Aggiungi alla coda", Icons.AutoMirrored.Rounded.QueueMusic) { Actions.queue(app, p.songs, next = false) },
        onEdit?.let { MenuItem("Modifica dettagli", Icons.Rounded.Edit) { it() } },
        MenuItem("Sposta in una cartella", Icons.Rounded.Folder, submenu = { close ->
            MenuRow(MenuItem("Nessuna cartella") { app.library.moveToFolder("pl:${p.id}", null); close() })
            folders.forEach { f -> MenuRow(MenuItem(f.name, Icons.Rounded.Folder) { app.library.moveToFolder("pl:${p.id}", f.id); app.nav.toast("Spostata in ${f.name}"); close() }) }
            MenuRow(MenuItem("Nuova cartella", Icons.Rounded.Folder) {
                val f = app.library.createFolder("Nuova cartella"); app.library.moveToFolder("pl:${p.id}", f.id); close()
            })
        }),
        MenuItem("Condividi", Icons.Rounded.Share) { app.nav.share = ShareItem.Playlist(p) },
        null,
        MenuItem("Elimina", Icons.Rounded.Delete, danger = true) { onDelete?.invoke() ?: run { app.library.deletePlaylist(p.id); app.nav.toast("Playlist eliminata") } },
    )
}

// ---------------------------------------------------------------- playlist

@Composable
fun DeskPlaylistPage(id: String) {
    val app = LocalApp.current
    val nav = app.nav
    val me by app.social.me.collectAsState()
    val playlists by app.library.playlists.collectAsState()
    val p = playlists.firstOrNull { it.id == id } ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Questa playlist non c'è più", color = Desk.sub) }; return
    }
    val scope = rememberCoroutineScope()
    val cover = p.cover ?: p.songs.firstOrNull()?.cover
    val color = rememberCoverColor(cover) ?: parseColor(p.color)
    PageColor(color)
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val selection = remember { Selection() }
    val state = rememberLazyListState()
    val playing = playingFrom(p.id, p.songs)
    var recs by remember(id) { mutableStateOf<List<SongRef>>(emptyList()) }
    LaunchedEffect(id, p.songs.size / 5) {
        val seed = p.songs.shuffled().firstOrNull() ?: app.library.recent.value.firstOrNull() ?: return@LaunchedEffect
        val have = p.songs.map { it.key }.toSet()
        recs = runCatching { app.engine.similarTo(seed, 20) }.getOrDefault(emptyList()).filter { it.key !in have }.take(10)
    }
    fun play(i: Int = 0) { app.engine.playAll(p.songs, i, ctx = p); app.library.touch("pl:$id") }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                PageHeader(if (p.collabId != null) "Playlist condivisa" else "Playlist", p.name, { m -> PlaylistArt(p, m, RoundedCornerShape(20.dp)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me, 24.dp); Spacer(Modifier.width(6.dp))
                        Text((p.from?.let { "Da $it" } ?: me?.name ?: "Tu") + " · ${p.songs.size} brani" + totalTime(p.songs),
                            color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                ActionRow {
                    PlayCircle(56.dp, playing) { if (playing) app.engine.togglePlay() else if (p.songs.isNotEmpty()) play() }
                    DeskIcon(Icons.Rounded.Shuffle, "Riproduci in ordine casuale", size = 44.dp, iconSize = 30.dp) {
                        if (p.songs.isNotEmpty()) { app.engine.playAll(p.songs, shuffle = true, ctx = p); app.library.touch("pl:$id") }
                    }
                    DeskIcon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Aggiungi alla coda", size = 44.dp, iconSize = 28.dp) { Actions.queue(app, p.songs, false) }
                    ContextMenuBox(items = { playlistMenu(app, p, scope, { editing = true }, { deleting = true }) }) { open ->
                        DeskIcon(Icons.Rounded.MoreHoriz, "Altre opzioni per ${p.name}", size = 44.dp, iconSize = 30.dp) { open() }
                    }
                }
            }
            if (p.songs.isEmpty()) item {
                Column(Modifier.padding(24.dp)) {
                    Text("Ancora vuota", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Cerca un brano in alto, poi tasto destro → Aggiungi a playlist. Oppure aggiungi un consigliato qui sotto.", color = Desk.sub)
                }
            }
            trackList(p.songs, selection, ::play, removeFrom = { s -> app.library.removeFromPlaylist(id, s.key) })
            if (recs.isNotEmpty()) {
                item {
                    Column(Modifier.padding(start = 24.dp, top = 36.dp, bottom = 8.dp)) {
                        Text("Consigliati", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("In base ai brani di questa playlist", color = Desk.sub, style = MaterialTheme.typography.bodySmall)
                    }
                }
                itemsIndexed(recs, key = { _, s -> "rec" + s.key }) { _, s -> RecommendedRow(s) { app.library.addToPlaylist(id, s); recs = recs - s } }
            }
        }
        StickyBar(state.firstVisibleItemIndex > 0, color, p.name, playing) { if (playing) app.engine.togglePlay() else play() }
    }
    if (editing) PlaylistDialog(p, { editing = false }) { n, e, c -> app.library.renamePlaylist(id, n, e, c); editing = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Eliminare ${p.name}?") },
        text = { Text("La playlist verrà tolta dalla tua libreria.") },
        confirmButton = { TextButton({ deleting = false; app.library.deletePlaylist(id); nav.back(); nav.toast("Playlist eliminata") }) { Text("Elimina") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Annulla") } },
    )
}

@Composable
private fun RecommendedRow(s: SongRef, onAdd: () -> Unit) {
    val app = LocalApp.current
    var hover by remember { mutableStateOf(false) }
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(4.dp))
            .background(if (hover) Color.White.copy(alpha = 0.08f) else Color.Transparent).hovered { hover = it }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp)) {
            Cover(s.cover, Modifier.fillMaxSize(), RoundedCornerShape(4.dp))
            if (hover) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pointerHoverIcon(HandCursor).clickable { app.engine.play(s) },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Ascolta", tint = Color.White) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = Desk.sub, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text(s.album ?: "", color = Desk.sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(0.8f))
        Box(Modifier.clip(CircleShape).background(Color.Transparent).pointerHoverIcon(HandCursor).clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Aggiungi", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- album

@Composable
fun DeskAlbumPage(id: Long) {
    val app = LocalApp.current
    val nav = app.nav
    val albums by app.library.albums.collectAsState()
    var album by remember(id) { mutableStateOf<DeezerPublic.AlbumInfo?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var more by remember(id) { mutableStateOf<List<DeezerPublic.AlbumBrief>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id) {
        album = runCatching { VibesGraph.deezer.album(id) }.getOrNull(); if (album == null) failed = true
        album?.let { a -> if (a.artistId > 0) more = runCatching { VibesGraph.deezer.artistAlbums(a.artistId, a.artist) }.getOrDefault(emptyList()).filter { it.id != id } }
        app.library.touch("al:$id")
    }
    val a = album
    val songs = remember(a) { a?.tracks.orEmpty().map { it.toSongRef().copy(album = a?.title, cover = it.cover ?: a?.cover) } }
    val color = rememberCoverColor(a?.cover)
    PageColor(color)
    val saved = albums.any { it.id == id }
    val playing = playingFrom(null, songs)
    val selection = remember { Selection() }
    val state = rememberLazyListState()
    if (failed) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Album non disponibile", color = Desk.sub) }; return }
    if (a == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }; return }
    val type = when (a.type) { "single" -> "Singolo"; "ep" -> "EP"; "compile" -> "Compilation"; else -> "Album" }
    fun play(i: Int = 0) { app.engine.playAll(songs, i); app.library.touch("al:$id") }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                PageHeader(type, a.title, { m -> Cover(a.cover, m, RoundedCornerShape(20.dp)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinkText(a.artist, color = Color.White, style = MaterialTheme.typography.titleSmall) { nav.open(Route.DzArtist(a.artistId, a.artist)) }
                        Text(" · ${a.date.take(4)} · ${songs.size} brani" + totalTime(songs), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                ActionRow {
                    PlayCircle(56.dp, playing) { if (playing) app.engine.togglePlay() else play() }
                    DeskIcon(Icons.Rounded.Shuffle, "Riproduci in ordine casuale", size = 44.dp, iconSize = 30.dp) { app.engine.playAll(songs, shuffle = true) }
                    DeskIcon(if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                        if (saved) "Rimuovi dalla libreria" else "Salva nella libreria", active = saved, size = 44.dp, iconSize = 32.dp) {
                        if (saved) { app.library.removeAlbum(id); nav.toast("Rimosso dalla libreria") }
                        else { app.library.saveAlbum(SavedAlbum(id, a.title, a.artist, a.cover, a.artistId)); nav.toast("Salvato nella libreria") }
                    }
                    ContextMenuBox(items = {
                        listOf(
                            MenuItem("Aggiungi alla coda", Icons.AutoMirrored.Rounded.QueueMusic) { Actions.queue(app, songs, false) },
                            MenuItem("Aggiungi a playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, submenu = { close -> PlaylistPicker(songs, close) }),
                            MenuItem("Vai all'artista", Icons.Rounded.Person) { nav.open(Route.DzArtist(a.artistId, a.artist)) },
                        )
                    }) { open -> DeskIcon(Icons.Rounded.MoreHoriz, "Altre opzioni", size = 44.dp, iconSize = 30.dp) { open() } }
                }
            }
            trackList(songs, selection, ::play, showAlbum = false, showCover = false)
            item {
                Column(Modifier.padding(24.dp)) {
                    Text(a.date, color = Desk.sub, style = MaterialTheme.typography.bodyMedium)
                    if (a.label.isNotBlank()) Text("© ${a.label}", color = Desk.sub, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (more.isNotEmpty()) item {
                Shelf("Altro di ${a.artist}", more) { b, w -> AlbumCard(b.id, b.cover, b.title, "${b.date.take(4)} · ${b.type.replaceFirstChar { it.uppercase() }}", w) }
            }
        }
        StickyBar(state.firstVisibleItemIndex > 0, color ?: Desk.raised, a.title, playing) { if (playing) app.engine.togglePlay() else play() }
    }
}

// ---------------------------------------------------------------- liked songs

@Composable
fun DeskLikedPage() {
    val app = LocalApp.current
    val me by app.social.me.collectAsState()
    val liked by app.library.liked.collectAsState()
    val color = Color(0xFF5038A0)
    PageColor(color)
    val selection = remember { Selection() }
    val state = rememberLazyListState()
    val playing = playingFrom(null, liked)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                PageHeader("Playlist", "Brani che ti piacciono", { m -> LikedArt(m, RoundedCornerShape(20.dp), 90.dp) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me, 24.dp); Spacer(Modifier.width(6.dp))
                        Text("${me?.name ?: "Tu"} · ${liked.size} brani", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                ActionRow {
                    PlayCircle(56.dp, playing) { if (playing) app.engine.togglePlay() else app.engine.playAll(liked) }
                    DeskIcon(Icons.Rounded.Shuffle, "Riproduci in ordine casuale", size = 44.dp, iconSize = 30.dp) { app.engine.playAll(liked, shuffle = true) }
                }
            }
            trackList(liked, selection, { i -> app.engine.playAll(liked, i) },
                removeFrom = { s -> app.library.toggleLike(s) }, removeLabel = "Rimuovi da Brani che ti piacciono")
        }
        StickyBar(state.firstVisibleItemIndex > 0, color, "Brani che ti piacciono", playing) { if (playing) app.engine.togglePlay() else app.engine.playAll(liked) }
    }
}

// ---------------------------------------------------------------- library (all of it, as a grid)

@Composable
fun DeskLibraryPage() {
    val app = LocalApp.current
    val nav = app.nav
    val playlists by app.library.playlists.collectAsState()
    val albums by app.library.albums.collectAsState()
    val artists by app.library.artists.collectAsState()
    val liked by app.library.liked.collectAsState()
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp)) {
        item {
            Shelf("Playlist", listOf<VibesPlaylist?>(null) + playlists, expanded = true) { p, w ->
                if (p == null) MediaCard(null, "Brani che ti piacciono", "${liked.size} brani", w, art = { m -> LikedArt(m, RoundedCornerShape(6.dp), 60.dp) },
                    onPlay = { app.engine.playAll(liked) }, onOpen = { nav.open(Route.Liked) })
                else MediaCard(p.songs.firstOrNull()?.cover, p.name, "Playlist · ${p.songs.size} brani", w, art = { m -> PlaylistArt(p, m, RoundedCornerShape(6.dp)) },
                    menu = { playlistMenu(app, p, scope) }, onPlay = { app.engine.playAll(p.songs, ctx = p) }, onOpen = { nav.open(Route.Playlist(p.id)) })
            }
        }
        if (albums.isNotEmpty()) item { Shelf("Album", albums, expanded = true) { a, w -> AlbumCard(a.id, a.cover, a.title, a.artist, w) } }
        if (artists.isNotEmpty()) item {
            Shelf("Artisti", artists, expanded = true) { a, w -> MediaCard(a.picture, a.name, "Artista", w, round = true, onOpen = { nav.open(Route.DzArtist(a.id, a.name)) }) }
        }
    }
}


// ---------------------------------------------------------------- a Deezer playlist (from the account or the home)

@Composable
fun DeskMediaPage(item: dev.brahmkshatriya.echo.common.models.EchoMediaItem) {
    val app = LocalApp.current
    val nav = app.nav
    var full by remember(item.id) { mutableStateOf(item) }
    var songs by remember(item.id) { mutableStateOf<List<SongRef>?>(null) }
    var tracks by remember(item.id) { mutableStateOf<List<dev.brahmkshatriya.echo.common.models.Track>>(emptyList()) }
    var extId by remember { mutableStateOf(MusicEngine.DEEZER) }
    LaunchedEffect(item.id) {
        runCatching { extId = app.engine.extensionId(); Actions.mediaTracks(app, item) }
            .onSuccess { (f, t) -> full = f; tracks = t; songs = t.map { SongRef.from(extId, it) } }
            .onFailure { songs = emptyList(); nav.toast(it.message ?: "Errore di caricamento") }
    }
    val cover = full.cover.url()
    val color = rememberCoverColor(cover)
    PageColor(color)
    val list = songs.orEmpty()
    val playing = playingFrom(null, list)
    val selection = remember { Selection() }
    val state = rememberLazyListState()
    fun play(i: Int = 0) { if (tracks.isNotEmpty()) app.engine.play(extId, tracks, i, full) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                PageHeader("Playlist", full.title, { m -> Cover(cover, m, RoundedCornerShape(20.dp)) }) {
                    Text((full.subtitle?.let { "$it · " } ?: "") + (songs?.let { "${it.size} brani" + totalTime(it) } ?: "Carico…"),
                        color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                ActionRow {
                    PlayCircle(56.dp, playing) { if (playing) app.engine.togglePlay() else play() }
                    DeskIcon(Icons.Rounded.Shuffle, "Riproduci in ordine casuale", size = 44.dp, iconSize = 30.dp) {
                        if (tracks.isNotEmpty()) app.engine.play(extId, tracks.shuffled(), 0, full)
                    }
                    DeskIcon(Icons.Rounded.AddCircleOutline, "Copia nelle playlist di Vibes", size = 44.dp, iconSize = 32.dp) {
                        if (list.isNotEmpty()) {
                            val p = app.library.createPlaylist(full.title, "🎵", Actions.newPlaylistColor(), list, cover = cover)
                            nav.toast("Copiata in Vibes (${list.size} brani)"); nav.open(Route.Playlist(p.id))
                        }
                    }
                    DeskIcon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Aggiungi alla coda", size = 44.dp, iconSize = 28.dp) { Actions.queue(app, list, false) }
                }
            }
            if (songs == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            trackList(list, selection, ::play)
        }
        StickyBar(state.firstVisibleItemIndex > 0, color ?: Desk.panel, full.title, playing) { if (playing) app.engine.togglePlay() else play() }
    }
}
