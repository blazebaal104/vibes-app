package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.FeedbackItem
import dev.brahmkshatriya.echo.vibes.backend.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val kinds = listOf("bug" to "🐞 Problema", "idea" to "💡 Idea", "altro" to "💬 Altro")
private fun kindLabel(k: String) = kinds.firstOrNull { it.first == k }?.second ?: k
private fun statusLabel(s: String) = when (s) { "visto" -> "Visto 👀"; "risolto" -> "Risolto ✅"; else -> "Inviato" }

@Composable
private fun StatusChip(s: String) {
    val c = when (s) { "risolto" -> Color(0xFF1ED760); "visto" -> Color(0xFFFFC145); else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Box(Modifier.clip(CircleShape).background(c.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(statusLabel(s), color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FeedbackCard(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(LocalVibes.current.card).padding(14.dp)) { content() }
}

/** Settings → "Segnala un problema o suggerisci un'idea": write it, attach a screenshot, see the answers. */
@Composable
fun FeedbackScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("bug") }
    var text by remember { mutableStateOf("") }
    var shot by remember { mutableStateOf<ByteArray?>(null) }
    var sending by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<List<FeedbackItem>?>(null) }
    suspend fun reload() { mine = runCatching { VibesGraph.feedback.mine() }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { reload() }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { shot = runCatching { withContext(Dispatchers.IO) { scaledJpeg(context, uri, 1600) } }.getOrNull() }
    }
    val version = VibesGraph.updater.installedCode
    val platform = if (PlatformUI.isDesktop) "PC (${System.getProperty("os.name") ?: "Windows"})" else "Android ${android.os.Build.VERSION.RELEASE}"
    val device = if (PlatformUI.isDesktop) "${System.getProperty("os.version") ?: ""} · ${Runtime.getRuntime().availableProcessors()} core"
    else "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Aiutaci a migliorare Vibes", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                Text("Hai trovato qualcosa che non va o hai un'idea? Scrivilo qui: lo leggiamo tutti e ti rispondiamo in questa pagina.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(kinds) { (k, label) -> Pill(label, kind == k, { kind = k }) }
                }
            }
            item {
                OutlinedTextField(
                    text, { if (it.length <= 4000) text = it },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 150.dp),
                    placeholder = {
                        Text(when (kind) {
                            "bug" -> "Cosa è successo? Cosa stavi facendo quando è successo? Succede sempre o solo a volte?"
                            "idea" -> "Cosa vorresti in Vibes? Come lo useresti?"
                            else -> "Scrivi qui"
                        })
                    },
                    shape = RoundedCornerShape(18.dp),
                )
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val s = shot
                    if (s == null) Pill("🖼  Allega uno screenshot", false, {
                        picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) else {
                        Box {
                            AsyncImage(s, "Screenshot allegato", contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)))
                            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                                .clickable { shot = null }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, "Togli", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("Screenshot allegato", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Text("Con il messaggio inviamo anche versione dell'app ($version) e dispositivo ($platform · $device), per capire meglio il problema.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                Button(
                    onClick = {
                        sending = true
                        scope.launch {
                            runCatching { VibesGraph.feedback.send(kind, text, shot, "build $version", platform, device, null) }
                                .onSuccess { text = ""; shot = null; nav.toast("Grazie! L'abbiamo ricevuto 💜"); reload() }
                                .onFailure { nav.toast("Invio non riuscito: ${it.message}") }
                            sending = false
                        }
                    },
                    enabled = text.trim().length >= 3 && !sending,
                    modifier = Modifier.padding(16.dp).fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text("Invia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            val list = mine.orEmpty()
            if (list.isNotEmpty()) {
                item { SectionTitle("Le tue segnalazioni") }
                items(list, key = { it.id }) { f ->
                    FeedbackCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(kindLabel(f.kind), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Text(timeAgo(f.created_at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            StatusChip(f.status)
                        }
                        Text(f.message, style = MaterialTheme.typography.bodyMedium, maxLines = 6, modifier = Modifier.padding(top = 6.dp))
                        f.reply?.let { r ->
                            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)).padding(10.dp)) {
                                Text("Risposta del team Vibes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                Text(r, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Admins only: every report, newest first; mark seen / fixed, answer, delete. */
@Composable
fun FeedbackAdminScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<FeedbackItem>?>(null) }
    var filter by remember { mutableStateOf("nuovo") }
    val people = remember { mutableStateMapOf<String, Profile?>() }
    var open by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        all = runCatching { VibesGraph.feedback.all() }.getOrDefault(emptyList())
        all.orEmpty().map { it.user_id }.distinct().filter { it !in people }.forEach { id -> people[id] = runCatching { app.social.profile(id) }.getOrNull() }
    }
    LaunchedEffect(Unit) { reload() }
    if (!app.social.isAdmin) { LaunchedEffect(Unit) { nav.back() }; return }
    val list = all.orEmpty().filter { filter == "tutte" || it.status == filter }
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Segnalazioni ricevute", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("nuovo" to "Nuove", "visto" to "Viste", "risolto" to "Risolte", "tutte" to "Tutte")) { (k, l) ->
                        val n = if (k == "tutte") all.orEmpty().size else all.orEmpty().count { it.status == k }
                        Pill("$l ($n)", filter == k, { filter = k })
                    }
                }
            }
            if (all == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            else if (list.isEmpty()) item { EmptyState("📭", "Niente qui", "Nessuna segnalazione in questa lista.") }
            items(list, key = { it.id }) { f ->
                val who = people[f.user_id]
                var reply by remember(f.id) { mutableStateOf(f.reply ?: "") }
                FeedbackCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = if (open == f.id) null else f.id }) {
                        Avatar(who, 36.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(who?.name ?: "Utente", style = MaterialTheme.typography.titleSmall)
                            Text("${kindLabel(f.kind)} · ${timeAgo(f.created_at)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusChip(f.status)
                    }
                    Text(f.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp), maxLines = if (open == f.id) 100 else 4)
                    f.image_url?.let { url ->
                        AsyncImage(url, "Screenshot", contentScale = if (open == f.id) ContentScale.Fit else ContentScale.Crop,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = if (open == f.id) 600.dp else 140.dp).clip(RoundedCornerShape(12.dp))
                                .clickable { open = if (open == f.id) null else f.id })
                    }
                    Text(listOfNotNull(f.app_version, f.platform, f.device).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    if (open == f.id || f.status == "nuovo") {
                        OutlinedTextField(reply, { reply = it }, modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
                            placeholder = { Text("Rispondi (lo vede nell'app)") }, shape = RoundedCornerShape(14.dp))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        fun set(status: String) = scope.launch {
                            runCatching { VibesGraph.feedback.update(f.id, status, reply) }
                                .onSuccess { nav.toast(if (status == "risolto") "Segnata come risolta" else "Salvata"); reload() }
                                .onFailure { nav.toast("Non riuscito: ${it.message}") }
                        }
                        if (f.status != "visto") Pill("👀 Vista", false, { set("visto") })
                        if (f.status != "risolto") Pill("✅ Risolta", false, { set("risolto") })
                        if (reply != (f.reply ?: "") && reply.isNotBlank()) Pill("💬 Invia risposta", true, { set(if (f.status == "nuovo") "visto" else f.status) })
                        Spacer(Modifier.weight(1f))
                        var confirm by remember(f.id) { mutableStateOf(false) }
                        if (confirm) TextButton({ scope.launch { runCatching { VibesGraph.feedback.delete(f.id) }; reload() } }) { Text("Elimina?", color = Color(0xFFFF6B7A)) }
                        else IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.Delete, "Elimina", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
