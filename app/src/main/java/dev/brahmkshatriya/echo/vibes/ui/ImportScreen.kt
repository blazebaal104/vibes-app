package dev.brahmkshatriya.echo.vibes.ui

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.Importer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val serviceColors = mapOf("Spotify" to Color(0xFF1ED760), "Deezer" to Color(0xFFA238FF), "YouTube Music" to Color(0xFFFF0033), "YouTube" to Color(0xFFFF0033), "Apple Music" to Color(0xFFFA2D48))

/**
 * "Trasferisci playlist": paste a Spotify / Deezer / YouTube Music link → Vibes reads it and shows a
 * preview (name, cover, every song and what it was matched to). Nothing is saved until you confirm.
 */
@Composable
fun ImportScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Importer.Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Importer.Preview?>(null) }
    var name by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<String?>(null) }
    val items = remember { mutableStateListOf<Importer.PreviewItem>() }
    val included = remember { mutableStateListOf<Boolean>() }
    var replacing by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val service = VibesGraph.importer.detect(link)

    // reads the playlist as soon as a supported link is pasted
    LaunchedEffect(link) {
        preview = null; error = null; progress = null; items.clear(); included.clear()
        if (service == null) return@LaunchedEffect
        delay(500)
        runCatching { VibesGraph.importer.preview(link) { p -> progress = p } }
            .onSuccess { p ->
                preview = p; name = p.name; cover = p.cover
                items.addAll(p.items); included.addAll(p.items.map { it.match != null })
            }
            .onFailure { error = it.message ?: "Non riesco a leggere questa playlist" }
        progress = null
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            runCatching {
                val jpeg = withContext(Dispatchers.IO) { squareJpeg(context, u, 800) }
                cover = app.social.uploadPublicImage(jpeg, "cover_import")
            }.onFailure { nav.toast("Immagine non valida") }
        }
    }
    val accent = serviceColors[preview?.service ?: service] ?: MaterialTheme.colorScheme.primary

    GradientBackground(tint = accent) {
        LazyColumn(Modifier.imePadding(), contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Trasferisci playlist", style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        listOf("Spotify", "Deezer", "YouTube Music", "Apple Music").forEach { n ->
                            val c = serviceColors[n]!!
                            Box(Modifier.clip(CircleShape).background(c.copy(alpha = if (service == n || (n == "YouTube Music" && service == "YouTube")) 0.55f else 0.2f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(n, style = MaterialTheme.typography.labelLarge, color = if (service == n) Color.White else c)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchField(link, { link = it }, "Incolla il link della playlist", Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Pill("Incolla", false, {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { link = it }
                        })
                    }
                    if (link.isNotBlank() && service == null) Text("Link non supportato: usa una playlist di Spotify, Deezer, YouTube Music o Apple Music.",
                        color = Color(0xFFFF7A8F), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    error?.let { Text(it, color = Color(0xFFFF7A8F), modifier = Modifier.padding(top = 10.dp)) }
                    AnimatedVisibility(progress != null) {
                        val p = progress
                        Column(Modifier.padding(top = 16.dp)) {
                            Text(p?.stage ?: "", style = MaterialTheme.typography.titleSmall)
                            if (p != null && p.total > 0) {
                                LinearProgressIndicator(progress = { p.done.toFloat() / p.total },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(8.dp).clip(CircleShape), color = accent)
                                Text("${p.done} di ${p.total} · trovati ${p.found}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = accent)
                        }
                    }
                }
            }
            // ---------------------------------------------------------------- preview
            preview?.let { p ->
                item {
                    Column(Modifier.padding(16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp), tint = accent).padding(16.dp)) {
                        Text("Anteprima da ${p.service}", style = MaterialTheme.typography.labelLarge, color = accent)
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(104.dp).clip(RoundedCornerShape(10.dp)).clickable {
                                coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) {
                                Cover(cover, Modifier.size(104.dp), RoundedCornerShape(10.dp))
                                Text("Cambia", fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.5f)).padding(vertical = 3.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                VibesField(name, { name = it }, "Nome della playlist")
                                val ok = items.count { it.match != null }
                                Text("$ok trovati · ${items.size - ok} non trovati · ${included.count { it }} selezionati",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        Text("Controlla i brani: tocca il cerchio per escluderne uno, \"Cerca\" per sceglierlo a mano.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                itemsIndexed(items, key = { i, _ -> "it$i" }) { i, it ->
                    val m = it.match
                    Row(Modifier.fillMaxWidth().clickable { if (m != null) included[i] = !included[i] }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (included.getOrElse(i) { false }) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                            tint = if (included.getOrElse(i) { false }) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Cover(m?.cover, Modifier.size(46.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            if (m != null) {
                                Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(m.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else Text("Non trovato", style = MaterialTheme.typography.titleSmall, color = Color(0xFFFF7A8F))
                            Text("originale: ${it.title} – ${it.artist}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                        Pill("Cerca", false, { replacing = i })
                    }
                }
                item {
                    var offline by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).bouncy({ offline = !offline }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Scarica per ascoltarla offline", style = MaterialTheme.typography.titleSmall)
                            Text("I brani restano sul telefono: niente internet, niente dati", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        androidx.compose.material3.Switch(offline, { offline = it })
                    }
                    BigButton("Aggiungi a Vibes (${included.count { it }} brani)", {
                        saving = true
                        val songs = items.mapIndexedNotNull { i, it -> if (included.getOrElse(i) { false }) it.match else null }
                        val pl = VibesGraph.importer.save(name, cover, songs)
                        if (offline) dev.brahmkshatriya.echo.vibes.music.VibesOffline.download(pl.songs)
                        saving = false
                        nav.toast("\"${pl.name}\" aggiunta: ${pl.songs.size} brani 🎉")
                        nav.back(); nav.open(Route.Playlist(pl.id))
                    }, Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), enabled = included.any { it }, loading = saving,
                        container = accent, content = Color.White)
                }
            }
            if (preview == null && progress == null && error == null && link.isBlank()) item {
                Text("Incolla il link di una playlist o di un album: prima di salvarla vedrai nome, copertina e tutti i brani trovati.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    }
    replacing?.let { i ->
        GlobalSongPicker(onDismiss = { replacing = null }) { s: SongRef ->
            items[i] = items[i].copy(match = s); included[i] = true; replacing = null
        }
    }
}
