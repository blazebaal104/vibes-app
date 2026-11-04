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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import kotlinx.coroutines.delay

private val browse = listOf(
    "Pop" to (0xFFE8115B to 0xFFFF6A3D), "Rap italiano" to (0xFF7B2FF7 to 0xFFC2187A),
    "Hip Hop" to (0xFFBA5D07 to 0xFFE1A30A), "Rock" to (0xFFE91429 to 0xFF8C1932),
    "Indie" to (0xFF608108 to 0xFF1E3264), "Elettronica" to (0xFF0D73EC to 0xFF27856A),
    "R&B" to (0xFFDC148C to 0xFF8D67AB), "Lo-fi" to (0xFF503750 to 0xFF477D95),
    "Latin" to (0xFFE1118C to 0xFFF59B23), "Anni 80" to (0xFF8D67AB to 0xFF1E3264),
    "Chill" to (0xFF27856A to 0xFF148A08), "Allenamento" to (0xFF777777 to 0xFFE8115B),
)

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

    LaunchedEffect(query) {
        if (query.isBlank()) { result = null; return@LaunchedEffect }
        delay(380)
        loading = true
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
                browse.chunked(2).forEachIndexed { ri, row ->
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { ci, (name, colors) ->
                                Box(
                                    Modifier.weight(1f).height(96.dp).enter(ri * 2 + ci).clip(RoundedCornerShape(10.dp))
                                        .background(Brush.linearGradient(listOf(Color(colors.first), Color(colors.second))))
                                        .bouncy({ query = name; app.library.addSearch(name) })
                                        .padding(12.dp)
                                ) {
                                    Text(name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                                    Box(
                                        Modifier.align(Alignment.BottomEnd).size(46.dp).rotate(22f).clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.22f))
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (loading && r == null) {
                item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            } else if (r != null && r.isEmpty) {
                item { EmptyState("🔍", "Nessun risultato", error ?: "Prova con un'altra parola.") }
            } else if (r != null) {
                if (r.tracks.isNotEmpty()) {
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
