package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.FollowedArtist

/** Artist from the Deezer extension → the Deezer public id (numeric id, or search by name). */
suspend fun resolveArtistId(a: Artist): Long? =
    a.id.substringAfterLast(':').toLongOrNull()?.takeIf { VibesGraph.deezer.artistInfo(it)?.name.equals(a.name, true) }
        ?: VibesGraph.deezer.searchArtists(a.name, 5).let { list -> (list.firstOrNull { it.name.equals(a.name, true) } ?: list.firstOrNull())?.id }

@Composable
fun ArtistFromEngine(a: Artist) {
    var id by remember { mutableStateOf<Long?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(a.id) { id = runCatching { resolveArtistId(a) }.getOrNull(); if (id == null) failed = true }
    val got = id
    when {
        got != null -> ArtistScreen(got, a.name)
        failed -> MediaScreen(a)
        else -> Box(Modifier.fillMaxWidth().padding(80.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
    }
}

@Composable
fun ArtistScreen(id: Long, name: String = "") {
    val app = LocalApp.current
    val nav = app.nav
    val followed by app.library.artists.collectAsState()
    val isFollowing = followed.any { it.id == id }
    var info by remember { mutableStateOf<DeezerPublic.ArtistInfo?>(null) }
    var top by remember { mutableStateOf<List<SongRef>?>(null) }
    var albums by remember { mutableStateOf<List<DeezerPublic.AlbumBrief>>(emptyList()) }
    var related by remember { mutableStateOf<List<DeezerPublic.ArtistInfo>>(emptyList()) }
    val playing = nowPlayingKey()
    LaunchedEffect(id) {
        info = runCatching { VibesGraph.deezer.artistInfo(id) }.getOrNull()
        top = runCatching { VibesGraph.deezer.artistTop(id, 50).map { it.toSongRef() } }.getOrDefault(emptyList())
        albums = runCatching { VibesGraph.deezer.artistAlbums(id, info?.name ?: name) }.getOrDefault(emptyList())
        related = runCatching {
            VibesGraph.deezer.related(id).take(12).mapNotNull { (rid, _) -> VibesGraph.deezer.artistInfo(rid) }
        }.getOrDefault(emptyList())
    }
    val title = info?.name ?: name
    val songs = top.orEmpty()
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Box(Modifier.fillMaxWidth()) {
                    // PC: a taller banner with rounded corners that fades into the page (no hard band)
                    val pc = PlatformUI.isDesktop
                    val h = if (pc) 380.dp else 320.dp
                    val shape = if (pc) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp) else RoundedCornerShape(0.dp)
                    Cover(info?.picture, Modifier.fillMaxWidth().height(h), shape)
                    Box(Modifier.fillMaxWidth().height(h).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.3f), Color.Transparent,
                        if (pc) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.background))))
                    IconButton(onClick = { nav.back() }, modifier = Modifier.statusBarsPadding().padding(4.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro", tint = Color.White)
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(title, style = MaterialTheme.typography.displaySmall, color = Color.White)
                        info?.let { Text("${fmtInt(it.fans)} fan", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f)) }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).bouncy({ app.engine.playAll(songs) }),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.PlayArrow, "Riproduci", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp)) }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { app.engine.playAll(songs, shuffle = true) }) { Icon(Icons.Rounded.Shuffle, "Casuale", modifier = Modifier.size(28.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Pill(if (isFollowing) "Segui già ✓" else "Segui", !isFollowing, {
                        if (isFollowing) app.library.unfollowArtist(id)
                        else {
                            app.library.followArtist(FollowedArtist(id, title, info?.picture, albums.firstOrNull()?.date ?: ""))
                            nav.toast("Ti avviso quando $title pubblica qualcosa di nuovo 🔔")
                        }
                    })
                }
            }
            if (top == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            if (songs.isNotEmpty()) item { SectionTitle("Popolari") }
            itemsIndexed(songs.take(10), key = { i, s -> "t$i${s.key}" }) { i, s ->
                SongRow(s, index = i + 1, playing = playing == s.key, onClick = { app.engine.playAll(songs, i) },
                    onMore = { nav.share = ShareItem.Song(s) }, modifier = Modifier.enter(i))
            }
            if (albums.isNotEmpty()) {
                item { SectionTitle("Discografia") }
                item { AlbumCarousel(albums) }
            }
            if (related.isNotEmpty()) {
                item { SectionTitle("Artisti simili") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(related, key = { it.id }) { r ->
                            Column(Modifier.width(110.dp).bouncy({ nav.open(Route.DzArtist(r.id, r.name)) }), horizontalAlignment = Alignment.CenterHorizontally) {
                                Cover(r.picture, Modifier.size(110.dp), CircleShape)
                                Text(r.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCarousel(albums: List<DeezerPublic.AlbumBrief>, showArtist: Boolean = false) {
    val nav = LocalApp.current.nav
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(albums, key = { it.id }) { al ->
            Column(Modifier.width(140.dp).bouncy({ nav.open(Route.DzAlbum(al.id)) })) {
                Cover(al.cover, Modifier.size(140.dp))
                Text(al.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(
                    (if (showArtist) al.artist + " · " else "") + al.date.take(4) + " · " +
                            when (al.type) { "single" -> "Singolo"; "ep" -> "EP"; "compile" -> "Raccolta"; else -> "Album" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
    }
}

@Composable
fun DzAlbumScreen(id: Long) {
    val app = LocalApp.current
    val nav = app.nav
    var album by remember { mutableStateOf<DeezerPublic.AlbumInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(id) { album = runCatching { VibesGraph.deezer.album(id) }.getOrNull(); if (album == null) failed = true }
    val playing = nowPlayingKey()
    val a = album
    val songs = a?.tracks.orEmpty().map { it.toSongRef().copy(album = a?.title) }
    LaunchedEffect(id, a != null) { if (a != null) app.library.touch("al:$id") }
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                CollectionHeader(
                    a?.title ?: "", (songs.firstOrNull()?.artist ?: "") + " · ${songs.size} brani",
                    art = { Cover(a?.cover, Modifier.size(220.dp), RoundedCornerShape(8.dp)) },
                    onPlay = { app.engine.playAll(songs) },
                    onShuffle = { app.engine.playAll(songs, shuffle = true) },
                    actions = {
                        // an album is saved as itself (in the library, not editable), never copied into a playlist
                        val albums by app.library.albums.collectAsState()
                        val saved = albums.any { it.id == id }
                        if (a != null) IconButton(onClick = {
                            if (saved) { app.library.removeAlbum(id); nav.toast("Tolto dalla libreria") }
                            else {
                                app.library.saveAlbum(dev.brahmkshatriya.echo.vibes.music.SavedAlbum(id, a.title, a.artist.ifBlank { songs.firstOrNull()?.artist.orEmpty() }, a.cover, a.artistId))
                                nav.toast("Album salvato nella libreria")
                            }
                        }) { Icon(if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (saved) "Togli dalla libreria" else "Salva nella libreria",
                            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                        // the whole album offline in one tap (it lands in Scaricati)
                        if (songs.isNotEmpty()) DownloadAllButton(songs, a?.title ?: "Album")
                    }
                )
            }
            if (a == null && !failed) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            if (failed) item { EmptyState("💿", "Album non disponibile", "") }
            itemsIndexed(songs, key = { i, s -> "$i${s.key}" }) { i, s ->
                SongRow(s, index = i + 1, playing = playing == s.key, onClick = { app.engine.playAll(songs, i) },
                    onMore = { nav.share = ShareItem.Song(s) }, modifier = Modifier.enter(i))
            }
        }
    }
}

/** Home shelf: latest releases from the artists you follow. */
@Composable
fun FollowedReleasesShelf() {
    val app = LocalApp.current
    val followed by app.library.artists.collectAsState()
    var releases by remember { mutableStateOf<List<DeezerPublic.AlbumBrief>>(emptyList()) }
    LaunchedEffect(followed.size) {
        if (followed.isEmpty()) { releases = emptyList(); return@LaunchedEffect }
        val cutoff = java.time.LocalDate.now().minusDays(60).toString()
        releases = followed.take(15).flatMap { f ->
            runCatching { VibesGraph.deezer.artistAlbums(f.id, f.name).filter { it.date >= cutoff }.take(3) }.getOrDefault(emptyList())
        }.sortedByDescending { it.date }.take(15)
    }
    if (releases.isEmpty()) return
    Column {
        SectionTitle("Nuove uscite dai tuoi artisti")
        AlbumCarousel(releases, showArtist = true)
    }
}
