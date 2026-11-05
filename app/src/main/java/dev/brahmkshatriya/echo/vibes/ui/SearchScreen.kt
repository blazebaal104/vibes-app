package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.music.Genre
import dev.brahmkshatriya.echo.vibes.music.Genres
import kotlinx.coroutines.delay

@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val nav = app.nav
    var query by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<dev.brahmkshatriya.echo.vibes.music.VibesSearch.Result?>(null) }
    var loading by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(0) } // 0 tutto, 1 brani, 2 artisti, 3 album, 4 playlist
    val searches by app.library.searches.collectAsState()
    val playing = nowPlayingKey()

    var genre by remember { mutableStateOf<Genre?>(null) }
    var genreSongs by remember { mutableStateOf<List<SongRef>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { result = null; genre = null; loading = false; return@LaunchedEffect }
        delay(250)
        loading = true
        // "pop", "rap", "lofi"… → songs OF that genre, not songs with that word in the title
        genre = Genres.match(query)
        genreSongs = genre?.let { g -> runCatching { Genres.tracks(app.deezer, g).map { it.toSongRef() } }.getOrDefault(emptyList()) }.orEmpty()
        result = runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.search.search(query) }.getOrNull()
        loading = false
    }
    val r = result
    fun saveQuery() { if (query.isNotBlank()) app.library.addSearch(query.trim()) }

    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Text("Cerca", style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp, bottom = 12.dp))
            }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp)
                        .glass(RoundedCornerShape(16.dp), fallback = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Artisti, brani, album o playlist", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { saveQuery() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Cancella", tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp).clickable { query = "" })
                }
            }
            if (query.isNotBlank()) item {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = listOf("Tutto", "Brani", "Artisti", "Album", "Playlist")
                    items(labels.size) { i -> Pill(labels[i], filter == i, { filter = i }) }
                }
            }

            if (query.isBlank()) {
                if (searches.isNotEmpty()) {
                    item { SectionTitle("Ricerche recenti", action = "Cancella") { app.library.clearSearches() } }
                    itemsIndexed(searches) { _, s ->
                        Row(Modifier.fillMaxWidth().clickable { query = s }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(14.dp))
                            Text(s, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                item { SectionTitle("Sfoglia tutto") }
                Genres.all.filter { it.browse }.chunked(2).forEachIndexed { ri, row ->
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { ci, g -> GenreTile(g, Modifier.weight(1f).enter(ri * 2 + ci)) { nav.open(Route.Genre(g.name)) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else if (r == null && loading) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            } else if (r == null || (r.isEmpty && genreSongs.isEmpty())) {
                if (!loading) item { EmptyState("🔍", "Nessun risultato per \"$query\"", "Controlla come l'hai scritto o prova con meno parole.") }
            } else {
                if (genre != null && filter == 0) {
                    item { GenreBanner(genre!!) { nav.open(Route.Genre(genre!!.name)) } }
                    if (genreSongs.isNotEmpty()) {
                        item { SectionTitle("Brani ${genre!!.name}", action = "Vedi tutti") { nav.open(Route.Genre(genre!!.name)) } }
                        itemsIndexed(genreSongs.take(6), key = { _, s -> "g" + s.key }) { i, song ->
                            SongRow(song, playing = playing == song.key, onClick = { saveQuery(); app.engine.playAll(genreSongs, i) },
                                onMore = { nav.share = ShareItem.Song(song) }, modifier = Modifier.enter(i))
                        }
                    }
                }
                // ---- top result (Spotify style)
                if (filter == 0) r.top?.let { top ->
                    item { SectionTitle("Risultato migliore") }
                    item { TopResultCard(top, r.songs) { saveQuery() } }
                }
                if ((filter == 0 || filter == 1) && r.songs.isNotEmpty()) {
                    if (filter == 0) item { SectionTitle("Brani") }
                    val list = if (filter == 0) r.songs.take(5) else r.songs
                    itemsIndexed(list, key = { _, s -> "t" + s.key }) { i, song ->
                        SongRow(song, playing = playing == song.key, onClick = { saveQuery(); app.engine.playAll(r.songs, i) },
                            onMore = { nav.share = ShareItem.Song(song) }, modifier = Modifier.enter(i))
                    }
                }
                if ((filter == 0 || filter == 2) && r.artists.isNotEmpty()) {
                    if (filter == 0) {
                        item { SectionTitle("Artisti") }
                        item {
                            androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(r.artists.size) { i -> ArtistBubble(r.artists[i]) { saveQuery(); nav.open(Route.DzArtist(r.artists[i].id, r.artists[i].name)) } }
                            }
                        }
                    } else itemsIndexed(r.artists, key = { _, a -> "a" + a.id }) { i, a ->
                        Row(Modifier.fillMaxWidth().enter(i).clickable { saveQuery(); nav.open(Route.DzArtist(a.id, a.name)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Cover(a.picture, Modifier.size(56.dp), androidx.compose.foundation.shape.CircleShape)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(a.name, style = MaterialTheme.typography.titleMedium)
                                Text("Artista · ${fmtInt(a.fans)} fan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if ((filter == 0 || filter == 3) && r.albums.isNotEmpty()) {
                    if (filter == 0) { item { SectionTitle("Album") }; item { AlbumCarousel(r.albums, showArtist = true) } }
                    else itemsIndexed(r.albums, key = { _, a -> "al" + a.id }) { i, al ->
                        Row(Modifier.fillMaxWidth().enter(i).clickable { saveQuery(); nav.open(Route.DzAlbum(al.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Cover(al.cover, Modifier.size(56.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(al.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text("${if (al.type == "single") "Singolo" else if (al.type == "ep") "EP" else "Album"} · ${al.artist} · ${al.date.take(4)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                if ((filter == 0 || filter == 4) && r.playlists.isNotEmpty()) {
                    if (filter == 0) item { SectionTitle("Playlist") }
                    val list = if (filter == 0) r.playlists.take(4) else r.playlists
                    itemsIndexed(list, key = { _, p -> "p" + p.id }) { i, pl ->
                        Row(Modifier.fillMaxWidth().enter(i).clickable { saveQuery(); nav.open(Route.DzPlaylist(pl.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Cover(pl.picture, Modifier.size(56.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(pl.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text("Playlist · ${pl.owner} · ${pl.count} brani", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistBubble(a: dev.brahmkshatriya.echo.vibes.music.DeezerPublic.ArtistInfo, onClick: () -> Unit) {
    Column(Modifier.width(110.dp).bouncy(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Cover(a.picture, Modifier.size(110.dp), androidx.compose.foundation.shape.CircleShape)
        Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text("${fmtInt(a.fans)} fan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Big "top result" card: the artist (round photo) or the best song, with a play button. */
@Composable
private fun TopResultCard(top: dev.brahmkshatriya.echo.vibes.music.VibesSearch.Top, songs: List<SongRef>, onUse: () -> Unit) {
    val app = LocalApp.current
    val nav = app.nav
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val (img, title, sub, round) = when (top) {
        is dev.brahmkshatriya.echo.vibes.music.VibesSearch.Top.ArtistTop -> listOf(top.a.picture, top.a.name, "Artista · ${fmtInt(top.a.fans)} fan", true)
        is dev.brahmkshatriya.echo.vibes.music.VibesSearch.Top.SongTop -> listOf(top.s.cover, top.s.title, "Brano · ${top.s.artist}", false)
    }
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp)).bouncy({
        onUse()
        when (top) {
            is dev.brahmkshatriya.echo.vibes.music.VibesSearch.Top.ArtistTop -> nav.open(Route.DzArtist(top.a.id, top.a.name))
            is dev.brahmkshatriya.echo.vibes.music.VibesSearch.Top.SongTop -> app.engine.playAll(songs, top.index)
        }
    }).padding(16.dp)) {
        Column {
            Cover(img as String?, Modifier.size(96.dp), if (round as Boolean) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(8.dp))
            Text(title as String, style = MaterialTheme.typography.headlineSmall, maxLines = 2, modifier = Modifier.padding(top = 12.dp))
            Text(sub as String, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(Modifier.align(Alignment.BottomEnd).size(52.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.primary).bouncy({
                onUse()
                when (top) {
                    is dev.brahmkshatriya.echo.vibes.music.VibesSearch.Top.ArtistTop -> scope.launch {
                        runCatching { app.deezer.artistTop(top.a.id, 30).map { it.toSongRef() } }.onSuccess { app.engine.playAll(it) }
                    }
                    is dev.brahmkshatriya.echo.vibes.music.VibesSearch.Top.SongTop -> app.engine.playAll(songs, top.index)
                }
            }), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Rounded.PlayArrow, "Riproduci", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
        }
    }
}

/** A Deezer playlist from search results. */
@Composable
fun DzPlaylistScreen(id: Long) {
    val app = LocalApp.current
    val nav = app.nav
    var info by remember { mutableStateOf<dev.brahmkshatriya.echo.vibes.music.DeezerPublic.PlaylistInfo?>(null) }
    var songs by remember { mutableStateOf<List<SongRef>?>(null) }
    val playing = nowPlayingKey()
    LaunchedEffect(id) {
        info = runCatching { app.deezer.playlistInfo(id) }.getOrNull()
        songs = runCatching { app.deezer.playlistTracks(id, 300).map { it.toSongRef() } }.getOrDefault(emptyList())
    }
    val list = songs.orEmpty()
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                CollectionHeader(info?.title ?: "", "${info?.owner ?: ""} · ${list.size} brani",
                    art = { Cover(info?.picture, Modifier.size(220.dp), RoundedCornerShape(8.dp)) },
                    onPlay = { app.engine.playAll(list) }, onShuffle = { app.engine.playAll(list, shuffle = true) },
                    actions = {
                        if (list.isNotEmpty()) androidx.compose.material3.IconButton(onClick = {
                            val p = app.library.createPlaylist(info?.title ?: "Playlist", "🎵", avatarColors.random(), list, cover = info?.picture)
                            nav.toast("Salvata in Vibes"); nav.open(Route.Playlist(p.id))
                        }) { Icon(Icons.Rounded.Add, "Salva in Vibes") }
                    })
            }
            if (songs == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            itemsIndexed(list, key = { i, s -> "$i${s.key}" }) { i, s ->
                SongRow(s, playing = playing == s.key, onClick = { app.engine.playAll(list, i) }, onMore = { nav.share = ShareItem.Song(s) }, modifier = Modifier.enter(i))
            }
        }
    }
}

@Composable
fun GenreTile(g: Genre, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val app = LocalApp.current
    var cover by remember(g.name) { mutableStateOf<String?>(null) }
    LaunchedEffect(g.name) { cover = runCatching { Genres.playlist(app.deezer, g)?.picture }.getOrNull() }
    Box(
        modifier.height(100.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(Color(g.colors.first), Color(g.colors.second))))
            .bouncy(onClick)
    ) {
        // cover of a real playlist of that genre, tilted in the corner like Spotify
        Cover(
            cover, Modifier.align(Alignment.BottomEnd).padding(end = 0.dp).size(70.dp)
                .graphicsLayer { rotationZ = 24f; translationX = 30f; translationY = 14f },
            RoundedCornerShape(6.dp), placeholder = Color.White.copy(alpha = 0.2f)
        )
        Text(g.name, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun GenreBanner(g: Genre, onClick: () -> Unit) {
    Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Color(g.colors.first), Color(g.colors.second))))
            .bouncy(onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Genere", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
            Text(g.name, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        }
        Text("Apri ›", color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun GenreScreen(name: String) {
    val app = LocalApp.current
    val nav = app.nav
    val g = Genres.all.firstOrNull { it.name == name } ?: return
    var info by remember { mutableStateOf<dev.brahmkshatriya.echo.vibes.music.DeezerPublic.PlaylistInfo?>(null) }
    var songs by remember { mutableStateOf<List<SongRef>?>(null) }
    val playing = nowPlayingKey()
    LaunchedEffect(name) {
        info = runCatching { Genres.playlist(app.deezer, g) }.getOrNull()
        songs = runCatching { Genres.tracks(app.deezer, g).map { it.toSongRef() } }.getOrDefault(emptyList())
    }
    GradientBackground(tint = Color(g.colors.first)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                CollectionHeader(
                    g.name, info?.title?.let { "Da \"$it\" · ${songs?.size ?: 0} brani" } ?: "Caricamento…",
                    art = { Cover(info?.picture, Modifier.size(220.dp), RoundedCornerShape(8.dp)) },
                    onPlay = { songs?.let { app.engine.playAll(it) } },
                    onShuffle = { songs?.let { app.engine.playAll(it, shuffle = true) } },
                    actions = {
                        if (!songs.isNullOrEmpty()) androidx.compose.material3.IconButton(onClick = {
                            val p = app.library.createPlaylist(g.name, "🎧", avatarColors.random(), songs!!, cover = info?.picture)
                            nav.toast("Salvata in libreria"); nav.open(Route.Playlist(p.id))
                        }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Add, "Salva") }
                    }
                )
            }
            if (songs == null) item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            }
            itemsIndexed(songs.orEmpty(), key = { i, s -> "$i${s.key}" }) { i, s ->
                SongRow(s, playing = playing == s.key, onClick = { app.engine.playAll(songs!!, i) },
                    onMore = { nav.share = ShareItem.Song(s) }, modifier = Modifier.enter(i))
            }
        }
    }
}
