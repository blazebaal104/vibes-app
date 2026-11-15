package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewHeadline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef.Companion.url
import dev.brahmkshatriya.echo.vibes.music.FollowedArtist
import dev.brahmkshatriya.echo.vibes.music.LibraryFolder
import dev.brahmkshatriya.echo.vibes.music.SavedAlbum
import dev.brahmkshatriya.echo.vibes.music.VibesPlaylist
import dev.brahmkshatriya.echo.vibes.ui.Cover
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.PlaylistArt
import dev.brahmkshatriya.echo.vibes.ui.PlaylistDialog
import dev.brahmkshatriya.echo.vibes.ui.Route
import kotlinx.coroutines.launch

/** One thing in the library list. */
private sealed interface LibItem {
    val key: String; val title: String; val sortTime: Long; val author: String
    data object Liked : LibItem { override val key = "liked"; override val title = "Brani che ti piacciono"; override val sortTime = Long.MAX_VALUE; override val author = "" }
    data object Downloads : LibItem { override val key = "downloads"; override val title = "Scaricati"; override val sortTime = Long.MAX_VALUE - 1; override val author = "" }
    data class Pl(val p: VibesPlaylist, val inFolder: Boolean = false) : LibItem { override val key = "pl:${p.id}"; override val title = p.name; override val sortTime = p.createdAt; override val author = p.from ?: "" }
    data class Al(val a: SavedAlbum) : LibItem { override val key = "al:${a.id}"; override val title = a.title; override val sortTime = a.savedAt; override val author = a.artist }
    data class Ar(val a: FollowedArtist) : LibItem { override val key = "ar:${a.id}"; override val title = a.name; override val sortTime = 0L; override val author = a.name }
    /** A playlist of the user's Deezer account. */
    data class Dz(val p: dev.brahmkshatriya.echo.common.models.Playlist) : LibItem { override val key = "dz:${p.id}"; override val title = p.title; override val sortTime = 1L; override val author = "Deezer" }
    data class Fo(val f: LibraryFolder) : LibItem { override val key = "fo:${f.id}"; override val title = f.name; override val sortTime = 0L; override val author = "" }
}

private val sortNames = listOf("Recenti", "Aggiunti di recente", "Ordine alfabetico", "Autore")
private val filterNames = listOf("Playlist", "Album", "Artisti", "Cartelle")

/**
 * "La tua libreria" on the left, like Spotify: playlists, folders, saved albums and artists with
 * filters, search and sorting; collapses to covers only; right click on anything for its menu.
 */
@Composable
fun LibraryPanel(width: Dp, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val nav = app.nav
    val desk = LocalDesk.current
    val playlists by app.library.playlists.collectAsState()
    val albums by app.library.albums.collectAsState()
    val artists by app.library.artists.collectAsState()
    val folders by app.library.folders.collectAsState()
    val opened by app.library.opened.collectAsState()
    val liked by app.library.liked.collectAsState()
    val off by dev.brahmkshatriya.echo.vibes.music.VibesOffline.entries.collectAsState()
    val cur by app.engine.player.playerState.current.collectAsState()
    val collapsed = width < 140.dp
    var filter by remember { mutableStateOf(-1) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<LibraryFolder?>(null) }
    var editing by remember { mutableStateOf<VibesPlaylist?>(null) }
    var deleting by remember { mutableStateOf<VibesPlaylist?>(null) }
    val openFolders = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    // the playlists of the Deezer account, like on the phone
    val deezerLogged by app.engine.deezerLoggedIn.collectAsState(initial = false)
    var deezer by remember { mutableStateOf<List<dev.brahmkshatriya.echo.common.models.Playlist>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(deezerLogged) {
        if (deezerLogged) deezer = runCatching { app.engine.libraryPlaylists() }.getOrDefault(emptyList())
    }

    val inFolder = folders.flatMap { it.items }.toSet()
    val items: List<LibItem> = run {
        fun recent(k: String, fallback: Long) = opened[k] ?: (fallback / 1000)
        val pls = playlists.filter { filter == 0 || "pl:${it.id}" !in inFolder }.map { LibItem.Pl(it) }
        val all = when (filter) {
            0 -> pls + deezer.map { LibItem.Dz(it) }
            1 -> albums.map { LibItem.Al(it) }
            2 -> artists.map { LibItem.Ar(it) }
            3 -> folders.map { LibItem.Fo(it) }
            else -> folders.map { LibItem.Fo(it) } + pls + albums.map { LibItem.Al(it) } + artists.map { LibItem.Ar(it) } + deezer.map { LibItem.Dz(it) }
        }.filter { query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true) }
        val sorted = when (desk.librarySort) {
            1 -> all.sortedByDescending { it.sortTime }
            2 -> all.sortedBy { it.title.lowercase() }
            3 -> all.sortedBy { it.author.lowercase() }
            else -> all.sortedByDescending { if (it is LibItem.Fo) Long.MAX_VALUE - 2 else recent(it.key, it.sortTime) }
        }
        val pinned = if ((filter == -1 || filter == 0) && query.isBlank()) listOf(LibItem.Liked, LibItem.Downloads) else emptyList()
        pinned + sorted
    }
    val ctx = VibesGraph.playContext

    Column(modifier.width(width).fillMaxHeight().deskGlass()) {
        // header
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            DeskIcon(Icons.Rounded.LibraryMusic, if (collapsed) "Espandi la libreria" else "Comprimi la libreria", size = 36.dp, iconSize = 24.dp) {
                desk.libraryWidth = if (collapsed) 300f else 72f; desk.save()
            }
            if (!collapsed) {
                Text("La tua libreria", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 6.dp))
                ContextMenuBox(items = {
                    listOf(
                        MenuItem("Playlist", Icons.Rounded.MusicNote) { creating = true },
                        MenuItem("Cartella", Icons.Rounded.Folder) { newFolder = true },
                    )
                }) { open ->
                    Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).pointerHoverIcon(HandCursor).clickable { open() }
                        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("Crea", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                DeskIcon(if (desk.libraryWidth > 420) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.GridView,
                    if (desk.libraryWidth > 420) "Riduci" else "Mostra tutta la libreria") { nav.open(Route.Library) }
            }
        }
        if (collapsed) Box(Modifier.padding(horizontal = 14.dp)) {
            ContextMenuBox(items = { listOf(MenuItem("Playlist", Icons.Rounded.MusicNote) { creating = true }, MenuItem("Cartella", Icons.Rounded.Folder) { newFolder = true }) }) { open ->
                DeskIcon(Icons.Rounded.Add, "Crea playlist o cartella", size = 44.dp, iconSize = 24.dp) { open() }
            }
        }
        if (!collapsed) {
            // filters
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filter >= 0) item {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).pointerHoverIcon(HandCursor).clickable { filter = -1 },
                        contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Togli filtro", tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
                items(filterNames.size) { i -> if (filter == -1 || filter == i) Chip(filterNames[i], filter == i) { filter = if (filter == i) -1 else i } }
            }
            // search + sort + view
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) Row(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = Desk.sub, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Cerca nella libreria", color = Desk.sub, style = MaterialTheme.typography.bodySmall)
                        BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(Color.White),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White), modifier = Modifier.fillMaxWidth())
                    }
                    Icon(Icons.Rounded.Close, "Chiudi ricerca", tint = Desk.sub, modifier = Modifier.size(16.dp).clickable { query = ""; searching = false })
                } else {
                    DeskIcon(Icons.Rounded.Search, "Cerca nella libreria") { searching = true }
                    Spacer(Modifier.weight(1f))
                }
                ContextMenuBox(items = {
                    sortNames.mapIndexed { i, n -> MenuItem(if (desk.librarySort == i) "✓  $n" else "     $n") { desk.librarySort = i; desk.save() } } + listOf(null) +
                        listOf(
                            MenuItem((if (desk.libraryView == 0) "✓  " else "     ") + "Elenco", Icons.AutoMirrored.Rounded.List) { desk.libraryView = 0; desk.save() },
                            MenuItem((if (desk.libraryView == 1) "✓  " else "     ") + "Compatto", Icons.Rounded.ViewHeadline) { desk.libraryView = 1; desk.save() },
                        )
                }) { open ->
                    Row(Modifier.clip(RoundedCornerShape(4.dp)).pointerHoverIcon(HandCursor).clickable { open() }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(sortNames[desk.librarySort], color = Desk.sub, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        Icon(if (desk.libraryView == 1) Icons.Rounded.ViewHeadline else Icons.AutoMirrored.Rounded.List, null, tint = Desk.sub, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            items.forEach { it ->
                item(key = it.key) {
                    when (it) {
                        LibItem.Liked -> LibRow(collapsed, "Brani che ti piacciono", "Playlist · ${liked.size} brani", { m -> LikedArt(m) },
                            active = nav.current == Route.Liked, onOpen = { nav.open(Route.Liked) }, onPlay = { app.engine.playAll(liked) })
                        LibItem.Downloads -> LibRow(collapsed, "Scaricati", "Offline · ${off.size} brani",
                            { m -> Box(m.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF0E7A3A), Color(0xFF1ED760)))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Download, null, tint = Color.White) } },
                            active = nav.current == Route.Downloads, onOpen = { nav.open(Route.Downloads) })
                        is LibItem.Pl -> PlaylistRow(it.p, collapsed, ctx?.id == it.p.id && cur?.isPlaying == true,
                            menu = { playlistMenu(app, it.p, scope, { editing = it.p }, { deleting = it.p }) })
                        is LibItem.Al -> LibRow(collapsed, it.a.title, "Album · ${it.a.artist}", { m -> Cover(it.a.cover, m, RoundedCornerShape(12.dp)) },
                            cover = it.a.cover, active = nav.current == Route.DzAlbum(it.a.id),
                            menu = { albumMenu(app, it.a.id, it.a.title, it.a.artist, it.a.cover) },
                            onOpen = { nav.open(Route.DzAlbum(it.a.id)) },
                            onPlay = { scope.launch { VibesGraph.deezer.album(it.a.id)?.let { a -> app.engine.playAll(a.tracks.map { t -> t.toSongRef().copy(album = a.title) }); app.library.touch(it.key) } } })
                        is LibItem.Dz -> {
                            val cover = it.p.cover.url()
                            LibRow(collapsed, it.p.title, "Playlist Deezer" + (it.p.trackCount?.let { n -> " · $n brani" } ?: ""),
                                { m -> Cover(cover, m, RoundedCornerShape(12.dp)) }, cover = cover,
                                active = nav.current == Route.Media(it.p),
                                onOpen = { nav.open(Route.Media(it.p)); app.library.touch(it.key) },
                                onPlay = { scope.launch { Actions.playMedia(app, it.p) } })
                        }
                        is LibItem.Ar -> LibRow(collapsed, it.a.name, "Artista", { m -> Cover(it.a.picture, m, CircleShape) }, round = true, cover = it.a.picture,
                            active = nav.current == Route.DzArtist(it.a.id, it.a.name),
                            menu = { listOf(MenuItem("Non seguire più", Icons.Rounded.PersonRemove) { app.library.unfollowArtist(it.a.id) }) },
                            onOpen = { nav.open(Route.DzArtist(it.a.id, it.a.name)); app.library.touch(it.key) })
                        is LibItem.Fo -> {
                            val open = openFolders[it.f.id] == true
                            LibRow(collapsed, it.f.name, "Cartella · ${it.f.items.size} playlist",
                                { m -> Box(m.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                                    Icon(if (open) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null, tint = Desk.sub) } },
                                trailing = { Icon(if (open) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight, null, tint = Desk.sub, modifier = Modifier.size(18.dp)) },
                                menu = {
                                    listOf(
                                        MenuItem("Rinomina", Icons.Rounded.Edit) { renaming = it.f },
                                        MenuItem("Elimina cartella", Icons.Rounded.Delete, danger = true) { app.library.deleteFolder(it.f.id); app.nav.toast("Cartella eliminata (le playlist restano)") },
                                    )
                                },
                                onOpen = { openFolders[it.f.id] = !open })
                        }
                    }
                }
                // a folder's playlists, indented under it
                if (it is LibItem.Fo && openFolders[it.f.id] == true) {
                    val inside = it.f.items.mapNotNull { k -> playlists.firstOrNull { p -> "pl:${p.id}" == k } }
                    inside.forEach { p ->
                        item(key = "${it.key}/${p.id}") {
                            Box(Modifier.padding(start = if (collapsed) 0.dp else 18.dp)) {
                                PlaylistRow(p, collapsed, ctx?.id == p.id && cur?.isPlaying == true, menu = { playlistMenu(app, p, scope, { editing = p }, { deleting = p }) })
                            }
                        }
                    }
                    if (inside.isEmpty() && !collapsed) item(key = "${it.key}/empty") {
                        Text("Vuota: tasto destro su una playlist → Sposta in una cartella", color = Desk.sub, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 30.dp, top = 4.dp, bottom = 8.dp))
                    }
                }
            }
            if (items.size <= 2 && query.isBlank() && !collapsed) item {
                Column(Modifier.padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Desk.raised).padding(16.dp)) {
                    Text("Crea la tua prima playlist", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("È facile, ti aiutiamo noi.", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                    Box(Modifier.clip(CircleShape).background(Color.White).pointerHoverIcon(HandCursor).clickable { creating = true }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("Crea playlist", color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (creating) PlaylistDialog(null, { creating = false }) { n, e, c ->
        val p = app.library.createPlaylist(n, e, c); creating = false; nav.open(Route.Playlist(p.id))
    }
    editing?.let { p -> PlaylistDialog(p, { editing = null }) { n, e, c -> app.library.renamePlaylist(p.id, n, e, c); editing = null } }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Eliminare ${p.name}?") },
            text = { Text("La playlist verrà tolta dalla tua libreria.") },
            confirmButton = { TextButton({ app.library.deletePlaylist(p.id); deleting = null; app.nav.toast("Playlist eliminata") }) { Text("Elimina") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Annulla") } },
        )
    }
    if (newFolder || renaming != null) {
        var name by remember { mutableStateOf(renaming?.name ?: "") }
        AlertDialog(
            onDismissRequest = { newFolder = false; renaming = null },
            title = { Text(if (renaming != null) "Rinomina cartella" else "Nuova cartella") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Nome della cartella") }) },
            confirmButton = {
                TextButton({
                    renaming?.let { app.library.renameFolder(it.id, name.ifBlank { it.name }) } ?: app.library.createFolder(name)
                    newFolder = false; renaming = null
                }) { Text(if (renaming != null) "Salva" else "Crea") }
            },
            dismissButton = { TextButton({ newFolder = false; renaming = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun PlaylistRow(p: VibesPlaylist, collapsed: Boolean, playing: Boolean, menu: () -> List<MenuItem?>) {
    val app = LocalApp.current
    LibRow(collapsed, p.name, (if (p.collabId != null) "Condivisa" else "Playlist") + " · " + (p.from ?: "Tu") + " · ${p.songs.size} brani",
        { m -> PlaylistArt(p, m, RoundedCornerShape(12.dp)) }, cover = p.cover ?: p.songs.firstOrNull()?.cover,
        active = app.nav.current == Route.Playlist(p.id), playing = playing, menu = menu,
        onOpen = { app.nav.open(Route.Playlist(p.id)) },
        onPlay = { app.engine.playAll(p.songs, ctx = p); app.library.touch("pl:${p.id}") })
}

@Composable
private fun LibRow(
    collapsed: Boolean, title: String, subtitle: String, art: @Composable (Modifier) -> Unit,
    round: Boolean = false, cover: String? = null, active: Boolean = false, playing: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    menu: (() -> List<MenuItem?>)? = null,
    onPlay: (() -> Unit)? = null, onOpen: () -> Unit,
) {
    var hover by remember { mutableStateOf(false) }
    val bg = when { active -> Color.White.copy(alpha = 0.1f); hover -> Color.White.copy(alpha = 0.06f); else -> Color.Transparent }
    val size = if (LocalDesk.current.libraryView == 1 && !collapsed) 0.dp else 48.dp
    ContextMenuBox(items = { menu?.invoke() ?: emptyList() }) { _ ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).hovered { hover = it }.hoverTint(cover)
                .pointerHoverIcon(HandCursor).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = if (size == 0.dp) 7.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (size > 0.dp) Box(Modifier.size(size)) {
                art(Modifier.fillMaxSize())
                if (hover && onPlay != null) Box(Modifier.fillMaxSize().clip(if (round) CircleShape else RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onPlay), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Riproduci", tint = Color.White) }
            }
            if (!collapsed) {
                Column(Modifier.weight(1f).padding(start = if (size > 0.dp) 12.dp else 4.dp)) {
                    Text(title, color = if (playing) MaterialTheme.colorScheme.primary else Color.White, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (size > 0.dp) Text(subtitle, color = Desk.sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (playing) Icon(Icons.AutoMirrored.Rounded.VolumeUp, "In riproduzione", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                trailing?.invoke()
            }
        }
    }
}
