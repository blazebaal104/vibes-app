package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.layout.heightIn

import androidx.compose.ui.text.font.FontWeight

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
    // songs found so far, while the search runs (the list fills in live)
    var partial by remember { mutableStateOf<List<Importer.PreviewItem?>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<String?>(null) }
    val items = remember { mutableStateListOf<Importer.PreviewItem>() }
    val included = remember { mutableStateListOf<Boolean>() }
    var replacing by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val service = VibesGraph.importer.detect(link)

    // the "playlist longer than 100" help page, and playlists read from a file
    var longHelp by remember { mutableStateOf(false) }
    var fileChoices by remember { mutableStateOf<List<Importer.Source>>(emptyList()) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun run(block: suspend () -> Importer.Preview) {
        job?.cancel()
        preview = null; error = null; progress = null; items.clear(); included.clear(); partial = emptyList()
        job = scope.launch {
            runCatching { block() }
                .onSuccess { p ->
                    preview = p; name = p.name; cover = p.cover
                    items.addAll(p.items); included.addAll(p.items.map { it.match != null })
                    // Spotify showed only the first 100: explain how to bring the whole playlist
                    if (p.partial) longHelp = true
                }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: "Non riesco a leggere questa playlist" }
            progress = null
        }
    }
    fun runSource(src: Importer.Source) = run { VibesGraph.importer.previewSource("File", src, { p -> progress = p }) { l -> partial = l } }
    // reads the playlist as soon as a supported link (or copied songs / a list) is pasted
    LaunchedEffect(link) {
        if (service == null) { if (link.isNotBlank()) { job?.cancel(); preview = null; progress = null }; return@LaunchedEffect }
        delay(500)
        run { VibesGraph.importer.preview(link, { p -> progress = p }) { l -> partial = l } }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(u)!!.use { it.readBytes().decodeToString() } }
                val fname = u.lastPathSegment?.substringAfterLast('/') ?: "Playlist"
                VibesGraph.importer.fromFile(fname, text)
            }.onSuccess { list ->
                longHelp = false; link = ""
                if (list.size == 1) runSource(list.first()) else fileChoices = list
            }.onFailure { error = "File non leggibile: ${it.message}" }
        }
    }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
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
                    if (progress == null && preview == null) Text("Playlist lunga, un file o una lista di brani? Ecco come ›", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp).clickable { longHelp = true })
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
            // ---------------------------------------------------------------- live list while searching
            if (preview == null && progress != null) {
                val live = partial.filterNotNull()
                items(live.size.coerceAtMost(400), key = { "live$it" }) { i ->
                    val it = live[live.size - 1 - i]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(it.match?.cover, Modifier.size(40.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.match?.title ?: it.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (it.match != null) it.match.artist else "Ancora da trovare…", style = MaterialTheme.typography.bodySmall,
                                color = if (it.match != null) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFFFB86B), maxLines = 1)
                        }
                    }
                }
            }
            // ---------------------------------------------------------------- preview
            preview?.let { p ->
                if (p.partial) item {
                    Text("Qui ci sono solo i primi 100 brani: Spotify non ci fa leggere il resto. Tocca qui per portare la playlist intera ›",
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB86B),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { longHelp = true })
                }
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (!it.sure) Text("  da controllare", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB86B))
                                }
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
    if (longHelp) LongPlaylistHelp(
        partial = preview?.partial == true,
        onClose = { longHelp = false },
        onPaste = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { link = it }
            longHelp = false
        },
        onFile = { filePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream", "*/*")) },
        onOpen = { runCatching { uri.openUri(it) } },
    )
    if (fileChoices.isNotEmpty()) VibesSheet({ fileChoices = emptyList() }) {
        Text("Quale playlist importo?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(fileChoices.size) { i ->
                val src = fileChoices[i]
                Row(Modifier.fillMaxWidth().clickable { fileChoices = emptyList(); runSource(src) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("🎵", style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(src.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${src.items?.size ?: 0} brani", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Shown when a Spotify playlist has more than 100 songs (or from "Playlist lunga?"): why only 100
 * arrived, and three ways to bring the whole playlist, step by step.
 */
@Composable
private fun LongPlaylistHelp(partial: Boolean, onClose: () -> Unit, onPaste: () -> Unit, onFile: () -> Unit, onOpen: (String) -> Unit) {
    val green = Color(0xFF1ED760)
    VibesSheet(onClose) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 680.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Text(if (partial) "Questa playlist ha più di 100 brani" else "Playlist lunghe, file e liste", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Spotify fa leggere a Vibes solo i primi 100 brani di una playlist. Le altre app ci riescono perché hanno un accordo speciale con Spotify. " +
                    "Ma puoi portare la playlist intera in uno di questi modi:",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            }
            item {
                HelpCard("1", "Il più semplice: passa da TuneMyMusic", "Funziona anche dal telefono", green, listOf(
                    "Apri TuneMyMusic e scegli Spotify come origine",
                    "Incolla il link della tua playlist e scegli Deezer (o YouTube Music) come destinazione",
                    "Copia il link della nuova playlist Deezer e incollalo qui: Vibes la importa intera, a qualsiasi lunghezza",
                )) { Pill("Apri TuneMyMusic", true, { onOpen("https://www.tunemymusic.com/it/transfer") }) }
            }
            item {
                HelpCard("2", "Dal computer: copia tutti i brani", "Il più veloce se hai un PC", MaterialTheme.colorScheme.primary, listOf(
                    "Apri la playlist nell'app Spotify per computer o su open.spotify.com",
                    "Clicca su un brano, poi premi Ctrl + A (seleziona tutti) e Ctrl + C (copia)",
                    "Torna qui e premi Incolla: Vibes legge ogni brano copiato",
                )) { Pill("Incolla i brani copiati", true, onPaste) }
            }
            item {
                HelpCard("3", "Da un file", "Esportazione di Spotify, Exportify, TuneMyMusic, Soundiiz", Color(0xFFFFB86B), listOf(
                    "Esportazione ufficiale: su Spotify, Account → Privacy → Scarica i tuoi dati. Arriva per email: dentro c'è Playlist1.json con tutte le tue playlist",
                    "Oppure un file CSV da Exportify, TuneMyMusic o Soundiiz",
                    "Scegli il file qui: se ci sono più playlist, scegli quale importare",
                )) {
                    Pill("Scegli il file", true, onFile)
                    Spacer(Modifier.width(8.dp))
                    Pill("Exportify", false, { onOpen("https://exportify.app") })
                }
            }
            item {
                Text("Hai una lista scritta? Incolla le righe nel formato \"Artista - Titolo\", una per brano.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                if (partial) Pill("Va bene così, tengo i primi 100", false, onClose)
            }
        }
    }
}

@Composable
private fun HelpCard(n: String, title: String, subtitle: String, color: Color, steps: List<String>, actions: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.12f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text(n, color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        steps.forEachIndexed { i, st ->
            Row(Modifier.padding(top = 8.dp)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                Text(st, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}
