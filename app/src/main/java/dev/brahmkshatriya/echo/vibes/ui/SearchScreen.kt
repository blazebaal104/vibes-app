package dev.brahmkshatriya.echo.vibes.ui

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
    var result by remember { mutableStateOf<MusicEngine.SearchResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var extId by remember { mutableStateOf(MusicEngine.DEEZER) }
    val searches by app.library.searches.collectAsState()
    val playing = nowPlayingKey()

    var genre by remember { mutableStateOf<Genre?>(null) }
    var genreSongs by remember { mutableStateOf<List<SongRef>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isBlank()) { result = null; genre = null; return@LaunchedEffect }
        delay(380)
        loading = true
        // "pop", "rap", "lofi"… → songs OF that genre, not songs with that word in the title
        genre = Genres.match(query)
        genreSongs = genre?.let { g -> runCatching { Genres.tracks(app.deezer, g).map { it.toSongRef() } }.getOrDefault(emptyList()) }.orEmpty()
        runCatching { extId = app.engine.extensionId(); app.engine.search(query) }
            .onSuccess { result = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Text("Cerca", style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp, bottom = 12.dp))
            }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.onSurface).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.surface)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Cosa vuoi ascoltare?", color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.titleMedium)
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.surface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) app.library.addSearch(query) }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (query.isNotEmpty()) Icon(
                        Icons.Rounded.Close, "Cancella", tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable { query = "" }
                    )
                }
            }

            val r = result
            if (query.isBlank()) {
                if (searches.isNotEmpty()) {
                    item { SectionTitle("Ricerche recenti", action = "Cancella") { app.library.clearSearches() } }
                    itemsIndexed(searches) { _, s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { query = s }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
            } else if (loading && r == null) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            } else if (r != null && r.isEmpty) {
                item { EmptyState("🔍", "Nessun risultato", error ?: "Prova con un'altra parola.") }
            } else if (r != null) {
                if (genre != null) {
                    item {
                        GenreBanner(genre!!) { nav.open(Route.Genre(genre!!.name)) }
                    }
                    if (genreSongs.isNotEmpty()) {
                        item { SectionTitle("Brani ${genre!!.name}", action = "Vedi tutti") { nav.open(Route.Genre(genre!!.name)) } }
                        itemsIndexed(genreSongs.take(10), key = { _, s -> "g" + s.key }) { i, song ->
                            SongRow(song, playing = playing == song.key,
                                onClick = { app.library.addSearch(query); app.engine.playAll(genreSongs, i) },
                                onMore = { nav.share = ShareItem.Song(song) }, modifier = Modifier.enter(i))
                        }
                    }
                }
                if (r.tracks.isNotEmpty() && genre == null) {
                    item { SectionTitle("Brani") }
                    itemsIndexed(r.tracks.take(12), key = { _, t -> "t" + t.id }) { i, t ->
                        val song = SongRef.from(extId, t)
                        SongRow(
                            song, playing = playing == song.key,
                            onClick = { app.library.addSearch(query); app.engine.play(extId, r.tracks, i) },
                            onMore = { nav.share = ShareItem.Song(song) },
                            modifier = Modifier.enter(i)
                        )
                    }
                }
                if (r.artists.isNotEmpty()) {
                    item { SectionTitle("Artisti") }
                    item { MediaCarousel(r.artists) { nav.open(Route.Media(it)) } }
                }
                if (r.albums.isNotEmpty()) {
                    item { SectionTitle("Album") }
                    item { MediaCarousel(r.albums) { nav.open(Route.Media(it)) } }
                }
                if (r.playlists.isNotEmpty()) {
                    item { SectionTitle("Playlist") }
                    item { MediaCarousel(r.playlists) { nav.open(Route.Media(it)) } }
                }
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
