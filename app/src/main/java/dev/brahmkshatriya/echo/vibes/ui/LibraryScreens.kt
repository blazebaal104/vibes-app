package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.vibes.backend.SharedPlaylist
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.SongRef.Companion.url
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.music.VibesPlaylist
import kotlinx.coroutines.launch

@Composable
fun PlaylistArt(p: VibesPlaylist, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) {
    val covers = p.songs.mapNotNull { it.cover }.distinct().take(4)
    val c = parseColor(p.color)
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.45f)))), contentAlignment = Alignment.Center) {
        if (covers.size >= 4) Column(Modifier.matchParentSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Cover(covers[0], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp))
                Cover(covers[1], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp))
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Cover(covers[2], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp))
                Cover(covers[3], Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp))
            }
        } else Text(p.emoji, fontSize = 34.sp)
    }
}

@Composable
fun LibraryScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val me by app.social.me.collectAsState()
    val playlists by app.library.playlists.collectAsState()
    val liked by app.library.liked.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }

    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(
                    Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(me, 36.dp, Modifier.bouncy({ nav.open(Route.Profile(null)) }))
                    Spacer(Modifier.width(12.dp))
                    Text("La tua libreria", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "Crea playlist", modifier = Modifier.size(28.dp)) }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Pill("Tutto", filter == 0, { filter = 0 }) }
                    item { Pill("Playlist", filter == 1, { filter = 1 }) }
                    item { Pill("Da amici", filter == 2, { filter = 2 }) }
                }
            }
            if (filter != 2) item {
                LibraryRow(
                    title = "Brani che ti piacciono", subtitle = "Playlist · ${liked.size} brani",
                    art = {
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(6.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF4A1FB8), Color(0xFF8E8EE5)))),
                            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Favorite, null, tint = Color.White) }
                    }
                ) { nav.open(Route.Liked) }
            }
            val shown = when (filter) {
                2 -> playlists.filter { it.from != null }
                else -> playlists
            }
            itemsIndexed(shown, key = { _, p -> p.id }) { i, p ->
                LibraryRow(
                    p.name, (if (p.from != null) "Da ${p.from} · " else "Playlist · ") + "${p.songs.size} brani",
                    art = { PlaylistArt(p, Modifier.size(64.dp), RoundedCornerShape(6.dp)) },
                    modifier = Modifier.enter(i)
                ) { nav.open(Route.Playlist(p.id)) }
            }
            if (playlists.isEmpty()) item {
                EmptyState("📀", "Crea la tua prima playlist", "Metti insieme i brani che ami e condividili con gli amici.") {
                    BigButton("Crea playlist", { creating = true }, Modifier.width(200.dp))
                }
            }
        }
    }
    if (creating) PlaylistDialog(null, { creating = false }) { name, emoji, color ->
        val p = app.library.createPlaylist(name, emoji, color)
        creating = false
        nav.open(Route.Playlist(p.id))
    }
}

@Composable
fun LibraryRow(title: String, subtitle: String, art: @Composable () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().bouncy(onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        art()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private val playlistEmojis = listOf("🎵", "🔥", "🌙", "☀️", "🚗", "💪", "💔", "🎉", "😴", "🌊", "🍂", "✨")

@Composable
fun PlaylistDialog(editing: VibesPlaylist?, onDismiss: () -> Unit, onDone: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var emoji by remember { mutableStateOf(editing?.emoji ?: "🎵") }
    var color by remember { mutableStateOf(editing?.color ?: avatarColors.random()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "Nuova playlist" else "Modifica playlist") },
        text = {
            Column {
                VibesField(name, { name = it.take(60) }, "Nome")
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(playlistEmojis) { e ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (e == emoji) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else LocalVibes.current.card)
                                .clickable { emoji = e }, contentAlignment = Alignment.Center
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(avatarColors) { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(parseColor(c))
                                .then(if (c == color) Modifier.background(Color.White.copy(alpha = 0.35f)) else Modifier)
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(name, emoji, color) }) { Text(if (editing == null) "Crea" else "Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

/** Header shared by playlist / album / artist pages: big art, title, play + shuffle. */
@Composable
fun CollectionHeader(
    title: String, subtitle: String, art: @Composable () -> Unit,
    onPlay: () -> Unit, onShuffle: () -> Unit, actions: @Composable () -> Unit = {},
) {
    val nav = LocalApp.current.nav
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        IconButton(onClick = { nav.back() }, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { art() }
        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions()
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onShuffle) { Icon(Icons.Rounded.Shuffle, "Casuale", modifier = Modifier.size(28.dp)) }
            Box(
                Modifier.padding(end = 8.dp).size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).bouncy(onPlay),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.PlayArrow, "Riproduci", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp)) }
        }
    }
}

@Composable
fun PlaylistScreen(id: String) {
    val app = LocalApp.current
    val nav = app.nav
    val playlists by app.library.playlists.collectAsState()
    val p = playlists.firstOrNull { it.id == id }
    val playing = nowPlayingKey()
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (p == null) { EmptyState("🫥", "Playlist eliminata", ""); return }

    GradientBackground(tint = parseColor(p.color)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                CollectionHeader(
                    p.name, "${p.songs.size} brani" + (p.from?.let { " · da $it" } ?: ""),
                    art = { PlaylistArt(p, Modifier.size(220.dp)) },
                    onPlay = { app.engine.playAll(p.songs) }, onShuffle = { app.engine.playAll(p.songs, shuffle = true) },
                    actions = {
                        IconButton(onClick = { nav.share = ShareItem.Playlist(p) }) { Icon(Icons.Rounded.Send, "Condividi") }
                        IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Modifica") }
                        IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Elimina") }
                    }
                )
            }
            if (p.songs.isEmpty()) item {
                EmptyState("➕", "Ancora vuota", "Cerca un brano e tocca ⋮ → Aggiungi a playlist.") {
                    BigButton("Cerca brani", { nav.selectTab(Route.Search) }, Modifier.width(200.dp))
                }
            }
            itemsIndexed(p.songs, key = { _, s -> s.key }) { i, s ->
                SongRow(
                    s, onClick = { app.engine.playAll(p.songs, i) }, playing = playing == s.key,
                    onMore = { nav.share = ShareItem.Song(s) }, modifier = Modifier.enter(i),
                    trailing = {
                        IconButton(onClick = { app.library.removeFromPlaylist(p.id, s.key) }) {
                            Icon(Icons.Rounded.Remove, "Rimuovi", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                )
            }
        }
    }
    if (editing) PlaylistDialog(p, { editing = false }) { n, e, c -> app.library.renamePlaylist(p.id, n, e, c); editing = false }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Eliminare ${p.name}?") },
        confirmButton = { TextButton(onClick = { deleting = false; nav.back(); app.library.deletePlaylist(p.id) }) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annulla") } }
    )
}

@Composable
fun LikedScreen() {
    val app = LocalApp.current
    val liked by app.library.liked.collectAsState()
    val playing = nowPlayingKey()
    GradientBackground(tint = Color(0xFF4A1FB8)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                CollectionHeader(
                    "Brani che ti piacciono", "${liked.size} brani",
                    art = {
                        Box(Modifier.size(220.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF4A1FB8), Color(0xFF8E8EE5)))),
                            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Favorite, null, tint = Color.White, modifier = Modifier.size(80.dp)) }
                    },
                    onPlay = { app.engine.playAll(liked) }, onShuffle = { app.engine.playAll(liked, shuffle = true) }
                )
            }
            if (liked.isEmpty()) item { EmptyState("💜", "Nessun brano", "Tocca il cuore nel player per salvare i brani qui.") }
            itemsIndexed(liked, key = { _, s -> s.key }) { i, s ->
                SongRow(s, onClick = { app.engine.playAll(liked, i) }, playing = playing == s.key,
                    onMore = { app.nav.share = ShareItem.Song(s) }, modifier = Modifier.enter(i))
            }
        }
    }
}

@Composable
fun MediaScreen(item: EchoMediaItem) {
    val app = LocalApp.current
    val nav = app.nav
    var full by remember { mutableStateOf(item) }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var extId by remember { mutableStateOf(MusicEngine.DEEZER) }
    val playing = nowPlayingKey()
    LaunchedEffect(item.id) {
        runCatching { extId = app.engine.extensionId(); app.engine.open(item) }
            .onSuccess { (f, t) -> full = f; tracks = t }
            .onFailure { tracks = emptyList(); nav.toast(it.message ?: "Errore di caricamento") }
    }
    val round = item is Artist
    val songs = tracks.orEmpty().map { SongRef.from(extId, it) }
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                CollectionHeader(
                    full.title,
                    when (full) {
                        is Artist -> "Artista"
                        is Album -> (full as Album).artists.joinToString(", ") { it.name }
                        else -> full.subtitle ?: "Playlist"
                    } + (tracks?.let { " · ${it.size} brani" } ?: ""),
                    art = {
                        Cover(full.cover.url(), Modifier.size(220.dp), if (round) CircleShape else RoundedCornerShape(8.dp))
                    },
                    onPlay = { tracks?.let { app.engine.play(extId, it, 0, full) } },
                    onShuffle = { tracks?.let { app.engine.play(extId, it.shuffled(), 0, full) } },
                    actions = {
                        if (songs.isNotEmpty()) IconButton(onClick = {
                            val p = app.library.createPlaylist(full.title, "🎵", avatarColors.random(), songs)
                            nav.toast("Salvata in libreria"); nav.open(Route.Playlist(p.id))
                        }) { Icon(Icons.Rounded.Add, "Salva in libreria") }
                    }
                )
            }
            if (tracks == null) item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            }
            itemsIndexed(songs, key = { i, s -> "$i${s.key}" }) { i, s ->
                SongRow(
                    s, index = if (item is Album) i + 1 else null, playing = playing == s.key,
                    onClick = { app.engine.play(extId, tracks!!, i, full) },
                    onMore = { nav.share = ShareItem.Song(s) }, modifier = Modifier.enter(i)
                )
            }
        }
    }
}

@Composable
fun SharedPlaylistScreen(id: String) {
    val app = LocalApp.current
    val nav = app.nav
    var p by remember { mutableStateOf<SharedPlaylist?>(null) }
    var owner by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        runCatching { app.social.sharedPlaylist(id) }.onSuccess { sp ->
            p = sp; failed = sp == null
            sp?.let { owner = runCatching { app.social.profile(it.ownerId)?.name }.getOrNull() }
        }.onFailure { failed = true }
    }
    val pl = p
    if (pl == null) {
        if (failed) EmptyState("😕", "Playlist non trovata", "Il link potrebbe essere scaduto.")
        else Box(Modifier.fillMaxWidth().padding(80.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        return
    }
    val asLocal = VibesPlaylist(name = pl.name, songs = pl.tracks)
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                CollectionHeader(
                    pl.name, "${pl.tracks.size} brani" + (owner?.let { " · condivisa da $it" } ?: ""),
                    art = { PlaylistArt(asLocal, Modifier.size(220.dp)) },
                    onPlay = { app.engine.playAll(pl.tracks) }, onShuffle = { app.engine.playAll(pl.tracks, shuffle = true) },
                )
            }
            item {
                BigButton("Salva nella mia libreria", {
                    val saved = app.library.createPlaylist(pl.name, "🎁", avatarColors.random(), pl.tracks, from = owner ?: "un amico")
                    nav.toast("Playlist salvata 🎉"); nav.open(Route.Playlist(saved.id))
                }, Modifier.fillMaxWidth().padding(16.dp))
            }
            itemsIndexed(pl.tracks, key = { i, s -> "$i${s.key}" }) { i, s ->
                SongRow(s, onClick = { app.engine.playAll(pl.tracks, i) }, modifier = Modifier.enter(i))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(song: SongRef, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val playlists by app.library.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Aggiungi a playlist", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            LibraryRow("Nuova playlist", "Crea e aggiungi",
                art = { Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(LocalVibes.current.cardHigh), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null) } }
            ) { creating = true }
            LibraryRow("Brani che ti piacciono", if (app.library.isLiked(song.key)) "Già salvato" else "Aggiungi ai preferiti",
                art = { Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF4A1FB8)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Favorite, null, tint = Color.White) } }
            ) { if (!app.library.isLiked(song.key)) app.library.toggleLike(song); app.nav.toast("Aggiunto ai preferiti"); onDismiss() }
            playlists.forEach { p ->
                LibraryRow(p.name, "${p.songs.size} brani", art = { PlaylistArt(p, Modifier.size(56.dp), RoundedCornerShape(6.dp)) }) {
                    app.library.addToPlaylist(p.id, song); app.nav.toast("Aggiunto a ${p.name}"); onDismiss()
                }
            }
        }
    }
    if (creating) PlaylistDialog(null, { creating = false }) { n, e, c ->
        app.library.createPlaylist(n, e, c, listOf(song)); creating = false
        app.nav.toast("Playlist creata"); onDismiss()
    }
}

