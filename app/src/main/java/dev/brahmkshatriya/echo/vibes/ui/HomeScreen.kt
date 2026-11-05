package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.SongRef.Companion.url
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import java.util.Calendar

@Composable
fun nowPlayingKey(): String? {
    val app = LocalApp.current
    val cur by app.engine.player.playerState.current.collectAsState()
    return cur?.let { "${it.mediaItem.extensionId}:${it.track.id}" }
}

/** Little "rise and fade in" used for list entries, staggered by [index]. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    if (LocalSettings.current.reduceMotion) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(420, delayMillis = (index * 45).coerceAtMost(400))) }
    return this.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 40f
    }
}

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val me by app.social.me.collectAsState()
    val recent by app.library.recent.collectAsState()
    val friends by app.social.friends.collectAsState()
    val loggedIn by app.engine.deezerLoggedIn.collectAsState(initial = true)
    val prefs = LocalSettings.current
    var shelves by remember { mutableStateOf<List<MusicEngine.UiShelf>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var extId by remember { mutableStateOf(MusicEngine.DEEZER) }

    var recs by remember { mutableStateOf<List<dev.brahmkshatriya.echo.vibes.music.Recommender.Section>>(emptyList()) }
    LaunchedEffect(recent.size / 5) {
        recs = runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.recommender.sections() }.getOrDefault(emptyList())
    }
    LaunchedEffect(loggedIn) {
        runCatching { extId = app.engine.extensionId(); app.engine.home() }
            .onSuccess { shelves = it; error = null }
            .onFailure { error = it.message; shelves = emptyList() }
    }

    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..12 -> "Buongiorno"
        in 13..17 -> "Buon pomeriggio"
        else -> "Buonasera"
    }

    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(
                    Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(me, 38.dp, Modifier.bouncy({ nav.open(Route.Profile(null)) }))
                    Spacer(Modifier.width(12.dp))
                    Text(greeting, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { nav.open(Route.Settings) }) { Icon(Icons.Rounded.Settings, "Impostazioni") }
                }
            }
            item(key = "stories") { StoriesRow() }
            if (!loggedIn) item {
                DeezerCard(Modifier.padding(16.dp).enter(0)) { nav.open(Route.DeezerLogin) }
            }
            if (recent.isNotEmpty()) item {
                QuickGrid(recent.take(6)) { song -> app.engine.play(song) }
            }
            if (prefs.homeRecommendations) recs.forEachIndexed { si, sec ->
                item(key = "rec_t$si") {
                    Column(Modifier.enter(si)) {
                        SectionTitle(sec.title)
                        sec.subtitle?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
                        }
                    }
                }
                item(key = "rec_c$si") { SongCarousel(sec.songs) }
            }
            val listening = if (prefs.homeFriends) friends.filter { it.nowPlaying != null } else emptyList()
            if (listening.isNotEmpty()) {
                item { SectionTitle("I tuoi amici ascoltano") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(listening, key = { it.id }) { f ->
                            val song = f.nowPlaying!!
                            Column(Modifier.width(132.dp).bouncy({ app.engine.play(song) }, { nav.open(Route.Profile(f.id)) })) {
                                Box {
                                    Cover(song.cover, Modifier.size(132.dp), RoundedCornerShape(10.dp))
                                    Avatar(f, 34.dp, Modifier.align(Alignment.BottomEnd).padding(6.dp))
                                }
                                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            when {
                shelves == null -> item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
                shelves!!.isEmpty() && loggedIn -> item {
                    EmptyState("📡", "Niente da mostrare", error ?: "Controlla la connessione e riprova.")
                }
            }
            shelves?.forEachIndexed { i, shelf ->
                item(key = "t$i") { SectionTitle(shelf.title, Modifier.enter(i)) }
                item(key = "s$i") {
                    if (shelf.tracks.isNotEmpty()) TrackCarousel(extId, shelf.tracks)
                    else MediaCarousel(shelf.items) { nav.open(Route.Media(it)) }
                }
            }
        }
    }
}

@Composable
private fun DeezerCard(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, LocalVibes.current.bgTop)))
            .bouncy(onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🎶", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Collega Deezer", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
            Text("Serve per cercare e ascoltare la musica.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun QuickGrid(songs: List<SongRef>, onClick: (SongRef) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        songs.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { c, s ->
                    Row(
                        Modifier.weight(1f).height(56.dp).enter(r * 2 + c).clip(RoundedCornerShape(6.dp))
                            .background(LocalVibes.current.cardHigh).bouncy({ onClick(s) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(s.cover, Modifier.size(56.dp), RoundedCornerShape(0.dp))
                        Text(
                            s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp)
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun TrackCarousel(extId: String, tracks: List<Track>) {
    val app = LocalApp.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(tracks.size) { i ->
            val t = tracks[i]
            val song = SongRef.from(extId, t)
            Column(
                Modifier.width(150.dp).enter(i).bouncy(
                    { app.engine.play(extId, tracks, i) },
                    { app.nav.share = ShareItem.Song(song) }
                )
            ) {
                Cover(song.cover, Modifier.size(150.dp), RoundedCornerShape(8.dp))
                Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp))
                Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MediaCarousel(items: List<EchoMediaItem>, onClick: (EchoMediaItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items.size) { i -> MediaTile(items[i], Modifier.enter(i)) { onClick(items[i]) } }
    }
}

@Composable
fun MediaTile(item: EchoMediaItem, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 150.dp, onClick: () -> Unit) {
    val round = item is Artist
    Column(modifier.width(size).bouncy(onClick), horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start) {
        Cover(item.cover.url(), Modifier.size(size), if (round) CircleShape else RoundedCornerShape(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        val sub = when (item) {
            is Artist -> "Artista"
            is Album -> "Album · " + item.artists.joinToString(", ") { it.name }
            is Playlist -> "Playlist"
            else -> item.subtitle.orEmpty()
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
fun SongCarousel(songs: List<SongRef>) {
    val app = LocalApp.current
    val playing = nowPlayingKey()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(songs.size) { i ->
            val song = songs[i]
            Column(Modifier.width(140.dp).enter(i).bouncy({ app.engine.playAll(songs, i) }, { app.nav.share = ShareItem.Song(song) })) {
                Box {
                    Cover(song.cover, Modifier.size(140.dp), RoundedCornerShape(10.dp))
                    if (playing == song.key) EqualizerBars(Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
