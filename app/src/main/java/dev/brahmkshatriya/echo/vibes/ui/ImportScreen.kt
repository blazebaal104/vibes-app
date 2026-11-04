package dev.brahmkshatriya.echo.vibes.ui

import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.SongRef.Companion.url
import dev.brahmkshatriya.echo.vibes.music.Importer
import kotlinx.coroutines.launch

/** Import playlists from Spotify / Deezer links, or straight from the user's Deezer account. */
@Composable
fun ImportScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Importer.Progress?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val deezerLogged by app.engine.deezerLoggedIn.collectAsState(initial = false)
    var deezerPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var importingAll by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deezerLogged) {
        if (deezerLogged) deezerPlaylists = runCatching { app.engine.libraryPlaylists() }.getOrDefault(emptyList())
    }

    fun importDeezer(list: List<Playlist>) {
        scope.launch {
            val ext = runCatching { app.engine.extensionId() }.getOrDefault("deezer")
            list.forEachIndexed { i, p ->
                importingAll = "Importo ${i + 1} di ${list.size}: ${p.title}"
                runCatching { app.engine.allTracks(p) }.onSuccess { tracks ->
                    app.library.createPlaylist(p.title, "🎵", avatarColors.random(), tracks.map { SongRef.from(ext, it) }, cover = p.cover.url())
                }
            }
            importingAll = null
            nav.toast("Importate ${list.size} playlist 🎉")
            nav.selectTab(Route.Library)
        }
    }

    GradientBackground(tint = Color(0xFF1ED760)) {
        LazyColumn(Modifier.imePadding(), contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Importa playlist", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Da un link", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Incolla il link di una playlist o di un album di Spotify o Deezer: Vibes la ricrea con lo stesso nome, la stessa copertina e gli stessi brani.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Spotify" to Color(0xFF1ED760), "Deezer" to Color(0xFFA238FF)).forEach { (n, c) ->
                            Box(Modifier.clip(CircleShape).background(c.copy(alpha = 0.25f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(n, style = MaterialTheme.typography.labelLarge, color = c)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    VibesField(link, { link = it; error = null }, "https://open.spotify.com/playlist/…")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("Incolla", {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { link = it }
                        }, Modifier.weight(1f), container = LocalVibes.current.cardHigh, content = MaterialTheme.colorScheme.onSurface)
                        BigButton("Importa", {
                            busy = true; error = null; progress = null
                            scope.launch {
                                runCatching { VibesGraph.importer.import(link) { p -> progress = p } }
                                    .onSuccess { p -> nav.toast("\"${p.name}\" importata: ${p.songs.size} brani"); link = ""; nav.open(Route.Playlist(p.id)) }
                                    .onFailure { error = it.message ?: "Importazione non riuscita" }
                                busy = false
                            }
                        }, Modifier.weight(1f), enabled = link.contains("http"), loading = busy)
                    }
                    AnimatedVisibility(progress != null && busy) {
                        val p = progress
                        Column(Modifier.padding(top = 16.dp)) {
                            Text(p?.stage ?: "", style = MaterialTheme.typography.titleSmall)
                            if (p != null && p.total > 0) {
                                LinearProgressIndicator(
                                    progress = { p.done.toFloat() / p.total },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp).clip(CircleShape),
                                )
                                Text("${p.done} di ${p.total} · trovati ${p.found}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    error?.let { Text(it, color = Color(0xFFFF5C7A), modifier = Modifier.padding(top = 12.dp)) }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Dal tuo account Deezer", style = MaterialTheme.typography.titleLarge)
                    if (!deezerLogged) Text("Collega Deezer dalle impostazioni per vedere qui le tue playlist.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (deezerPlaylists.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        BigButton(importingAll ?: "Importa tutte (${deezerPlaylists.size})", { importDeezer(deezerPlaylists) },
                            Modifier.fillMaxWidth(), loading = importingAll != null)
                        importingAll?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            }
            items(deezerPlaylists.size) { i ->
                val p = deezerPlaylists[i]
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).enter(i), verticalAlignment = Alignment.CenterVertically) {
                    Cover(p.cover.url(), Modifier.size(54.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.trackCount?.let { "$it brani" } ?: "Playlist", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Pill("Importa", false, { importDeezer(listOf(p)) })
                }
            }
        }
    }
}
