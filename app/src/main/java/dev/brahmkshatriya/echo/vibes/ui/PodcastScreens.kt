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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic.PodcastInfo
import kotlinx.coroutines.delay

/** A Deezer podcast as the item the media page opens (the Deezer engine loads its episodes). */
fun PodcastInfo.toShow(): Album = Album(
    id = id.toString(),
    title = title,
    type = Album.Type.Show,
    cover = picture?.toImageHolder(),
    trackCount = 200,
    description = description,
    subtitle = "Podcast",
    extras = mapOf("__TYPE__" to "show"),
)

private object PodcastCache {
    @Volatile var chart: List<PodcastInfo>? = null
    @Volatile var at = 0L
    suspend fun chart(): List<PodcastInfo> {
        chart?.takeIf { System.currentTimeMillis() - at < 6 * 3600_000L }?.let { return it }
        val l = runCatching { VibesGraph.deezer.podcastChart(40) }.getOrDefault(emptyList())
        if (l.isNotEmpty()) { chart = l; at = System.currentTimeMillis() }
        return l
    }
}

@Composable
fun PodcastCard(p: PodcastInfo, size: androidx.compose.ui.unit.Dp = 150.dp, onOpen: () -> Unit = {}) {
    val nav = LocalApp.current.nav
    val tap = rememberAmbientTap(p.picture)
    Column(Modifier.width(size).ambientBounds(tap).bouncy({ tap.fire(); onOpen(); nav.open(Route.Media(p.toShow())) })) {
        Cover(p.picture, Modifier.size(size), RoundedCornerShape(14.dp))
        Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (p.fans > 0) Text("${fmtInt(p.fans)} follower", style = MaterialTheme.typography.bodySmall, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PodcastCarousel(list: List<PodcastInfo>, onOpen: () -> Unit = {}) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(list, key = { _, p -> p.id }) { i, p -> Box(Modifier.enter(i)) { PodcastCard(p, onOpen = onOpen) } }
    }
}

/** Home: the podcasts of the moment, with "Vedi tutti" to the podcast page. */
@Composable
fun PodcastHomeShelf() {
    var list by remember { mutableStateOf(PodcastCache.chart.orEmpty()) }
    LaunchedEffect(Unit) { list = PodcastCache.chart() }
    if (list.isEmpty()) return
    val nav = LocalApp.current.nav
    Column {
        SectionTitle("🎙️ Podcast del momento", action = "Vedi tutti") { nav.open(Route.Podcasts) }
        PodcastCarousel(list.take(15))
    }
}

private val podcastTopics = listOf(
    "😂" to "Comicità", "🔪" to "True crime", "🏛️" to "Storia", "🎵" to "Musica", "⚽" to "Sport",
    "💻" to "Tecnologia", "🧠" to "Psicologia", "📰" to "Attualità", "🎬" to "Cinema", "💼" to "Business",
)

/** Podcasts: search, topics and the chart. Opening one shows its episodes (played by the Deezer engine). */
@Composable
fun PodcastsScreen() {
    val nav = LocalApp.current.nav
    var query by remember { mutableStateOf("") }
    var chart by remember { mutableStateOf(PodcastCache.chart.orEmpty()) }
    var results by remember { mutableStateOf<List<PodcastInfo>?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { chart = PodcastCache.chart() }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(300); loading = true
        results = runCatching { VibesGraph.deezer.searchPodcasts(query, 40) }.getOrDefault(emptyList())
        loading = false
    }
    GradientBackground(tint = Color(0xFF1FB58F)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Podcast", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item { SearchField(query, { query = it }, "Cerca un podcast", Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(podcastTopics, key = { it.second }) { (e, t) -> Pill("$e $t", query == t, { query = if (query == t) "" else t }) }
                }
            }
            val r = results
            if (r != null) {
                if (loading) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
                else if (r.isEmpty()) item { EmptyState("🎙️", "Nessun podcast trovato", "Prova con un'altra parola o con il nome del podcast.") }
                else itemsIndexed(r, key = { _, p -> "r" + p.id }) { i, p -> PodcastRow(p, Modifier.enter(i)) }
            } else {
                if (chart.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
                else {
                    item { SectionTitle("In classifica") }
                    item { PodcastCarousel(chart.take(12)) }
                    item { SectionTitle("Altri da scoprire") }
                    itemsIndexed(chart.drop(12), key = { _, p -> "c" + p.id }) { i, p -> PodcastRow(p, Modifier.enter(i)) }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun PodcastRow(p: PodcastInfo, modifier: Modifier = Modifier) {
    val nav = LocalApp.current.nav
    Row(modifier.fillMaxWidth().bouncy({ nav.open(Route.Media(p.toShow())) }).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Cover(p.picture, Modifier.size(64.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
