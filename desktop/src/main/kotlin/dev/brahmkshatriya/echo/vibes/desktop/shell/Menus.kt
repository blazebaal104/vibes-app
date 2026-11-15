package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.ui.AppState
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.PlaylistDialog
import dev.brahmkshatriya.echo.vibes.ui.Route
import dev.brahmkshatriya.echo.vibes.ui.ShareItem
import dev.brahmkshatriya.echo.vibes.ui.avatarColors
import dev.brahmkshatriya.echo.vibes.ui.openArtistOf
import kotlinx.coroutines.launch

/** One line of a menu. */
data class MenuItem(val label: String, val icon: ImageVector? = null, val danger: Boolean = false, val submenu: (@Composable (close: () -> Unit) -> Unit)? = null, val onClick: () -> Unit = {})

/**
 * A box that opens [items] at the mouse on right click (and, with [openOnClick], on left click too:
 * the "⋯" buttons).
 */
@Composable
fun ContextMenuBox(
    items: () -> List<MenuItem?>,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(open: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(DpOffset.Zero) }
    var h by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Box(
        modifier.onSizeChanged { h = it.height }
            .onPointerEvent(PointerEventType.Press) { e ->
                if (e.buttons.isSecondaryPressed) {
                    val p = e.changes.first().position
                    with(density) { at = DpOffset(p.x.toDp(), (p.y - h).toDp()) }
                    open = true
                }
            }
    ) {
        content { at = DpOffset.Zero; open = true }
        if (open) MenuPopup(items(), { open = false }, at)
    }
}

@Composable
fun MenuPopup(items: List<MenuItem?>, onClose: () -> Unit, offset: DpOffset = DpOffset.Zero) {
    var sub by remember { mutableStateOf<MenuItem?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { dev.brahmkshatriya.echo.vibes.ui.PlatformUI.popups++; onDispose { dev.brahmkshatriya.echo.vibes.ui.PlatformUI.popups-- } }
    DropdownMenu(
        expanded = true, onDismissRequest = onClose, offset = offset,
        modifier = Modifier.background(Color(0xFF282828)).widthIn(min = 230.dp),
    ) {
        val s = sub
        if (s?.submenu != null) {
            MenuRow(MenuItem("Indietro", Icons.AutoMirrored.Rounded.ArrowBack) { sub = null })
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            s.submenu.invoke(onClose)
        } else items.forEach { item ->
            if (item == null) HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 2.dp))
            else MenuRow(item) { if (item.submenu != null) sub = item else { onClose(); item.onClick() } }
        }
    }
}

@Composable
fun MenuRow(item: MenuItem, onClick: () -> Unit = item.onClick) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (item.danger) Color(0xFFFF6B7A) else Color.White.copy(alpha = 0.9f)
        if (item.icon != null) { Icon(item.icon, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(12.dp)) }
        Text(item.label, color = tint, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (item.submenu != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** "Aggiungi a playlist": a scrolling list of your playlists (+ a new one). */
@Composable
fun PlaylistPicker(songs: List<SongRef>, close: () -> Unit) {
    val app = LocalApp.current
    var creating by remember { mutableStateOf(false) }
    MenuRow(MenuItem("Nuova playlist", Icons.Rounded.Add) { creating = true })
    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
        app.library.playlists.value.forEach { p ->
            MenuRow(MenuItem(p.name, null) {
                songs.forEach { app.library.addToPlaylist(p.id, it) }
                app.nav.toast(if (songs.size == 1) "Aggiunto a ${p.name}" else "${songs.size} brani aggiunti a ${p.name}")
                close()
            })
        }
    }
    if (creating) PlaylistDialog(null, { creating = false }) { n, e, c ->
        app.library.createPlaylist(n, e, c, songs); creating = false
        app.nav.toast("Playlist creata"); close()
    }
}

/** Everything you can do with a song (right click / ⋯ on any song). */
fun songMenu(app: AppState, desk: DeskState, song: SongRef, scope: kotlinx.coroutines.CoroutineScope, removeFrom: (() -> Unit)? = null, removeLabel: String = "Rimuovi da questa playlist"): List<MenuItem?> {
    val liked = app.library.isLiked(song.key)
    return listOf(
        MenuItem("Aggiungi alla coda", Icons.AutoMirrored.Rounded.QueueMusic) { Actions.queue(app, listOf(song), next = false) },
        MenuItem("Riproduci dopo", Icons.Rounded.SkipNext) { Actions.queue(app, listOf(song), next = true) },
        MenuItem("Aggiungi a playlist", Icons.AutoMirrored.Rounded.PlaylistAdd, submenu = { close -> PlaylistPicker(listOf(song), close) }),
        MenuItem(if (liked) "Rimuovi da Brani che ti piacciono" else "Salva in Brani che ti piacciono",
            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { app.library.toggleLike(song) },
        removeFrom?.let { MenuItem(removeLabel, Icons.Rounded.Delete, danger = true) { it() } },
        null,
        MenuItem("Vai alla radio del brano", Icons.Rounded.Radio) { scope.launch { Actions.radio(app, song) } },
        MenuItem("Vai all'artista", Icons.Rounded.Person) { scope.launch { openArtistOf(app, song); desk.fullPlayer = false } },
        MenuItem("Vai all'album", Icons.Rounded.Album) { scope.launch { Actions.openAlbum(app, song); desk.fullPlayer = false } },
        MenuItem("Karaoke", Icons.Rounded.Mic) { app.engine.play(song); desk.karaoke = true },
        null,
        MenuItem("Condividi", Icons.Rounded.Share) { app.nav.share = ShareItem.Song(song) },
    )
}

object Actions {
    fun queue(app: AppState, songs: List<SongRef>, next: Boolean) {
        if (songs.isEmpty()) return
        songs.groupBy { it.ext }.forEach { (ext, l) -> app.engine.player.addToQueue(ext, l.map { it.toTrack() }, next) }
        app.nav.toast(if (next) "Riprodotto dopo questo brano" else if (songs.size == 1) "Aggiunto alla coda" else "${songs.size} brani aggiunti alla coda")
    }

    suspend fun radio(app: AppState, song: SongRef) {
        val similar = runCatching { app.engine.similarTo(song, 40) }.getOrDefault(emptyList())
        if (similar.isEmpty()) { app.nav.toast("Nessun brano simile trovato"); return }
        app.engine.playAll(listOf(song) + similar)
        app.nav.toast("Radio: brani come ${song.title}")
    }

    /** Deezer id of a song (its own id for Deezer songs, else matched by title + artist). */
    suspend fun deezerId(song: SongRef): Long? =
        song.id.toLongOrNull()?.takeIf { song.ext == MusicEngine.DEEZER } ?: runCatching { VibesGraph.deezer.deezerId(song) }.getOrNull()

    suspend fun openAlbum(app: AppState, song: SongRef) {
        val id = deezerId(song)?.let { VibesGraph.deezer.trackDetails(it)?.albumId }?.takeIf { it > 0 }
        if (id == null) { app.nav.toast("Album non trovato"); return }
        app.nav.open(Route.DzAlbum(id))
    }

    fun newPlaylistColor() = avatarColors.random()

    /** All the songs of an engine item (Deezer playlist / album), loading every page of a playlist. */
    suspend fun mediaTracks(app: AppState, item: dev.brahmkshatriya.echo.common.models.EchoMediaItem): Pair<dev.brahmkshatriya.echo.common.models.EchoMediaItem, List<dev.brahmkshatriya.echo.common.models.Track>> {
        val (full, first) = app.engine.open(item)
        val all = (full as? dev.brahmkshatriya.echo.common.models.Playlist)?.let { runCatching { app.engine.allTracks(it) }.getOrNull() } ?: first
        return full to all
    }

    suspend fun playMedia(app: AppState, item: dev.brahmkshatriya.echo.common.models.EchoMediaItem) {
        val (full, tracks) = runCatching { mediaTracks(app, item) }.getOrElse { app.nav.toast(it.message ?: "Errore di caricamento"); return }
        if (tracks.isNotEmpty()) app.engine.play(app.engine.extensionId(), tracks, 0, full)
    }
}

/** Every song row of the shared screens (search, artist, history...): hover highlight + the song menu. */
@Composable
fun DeskSongRow(song: SongRef, content: @Composable (open: () -> Unit) -> Unit) {
    val app = LocalApp.current
    val desk = LocalDesk.current
    val scope = rememberCoroutineScope()
    var hover by remember { mutableStateOf(false) }
    ContextMenuBox(items = { songMenu(app, desk, song, scope) }) { open ->
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (hover) Color.White.copy(alpha = 0.07f) else Color.Transparent).hovered { hover = it }) {
            content(open)
        }
    }
}
