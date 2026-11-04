package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.backend.Message
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.notify.VibesNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun shortTime(iso: String?): String {
    val t = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val z = t.atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    return if (z.toLocalDate() == today) z.format(DateTimeFormatter.ofPattern("HH:mm"))
    else z.format(DateTimeFormatter.ofPattern("dd/MM"))
}

fun preview(m: Message, me: String?): String {
    val prefix = if (m.senderId == me) "Tu: " else ""
    return prefix + when (m.kind) {
        "track" -> "🎵 " + (m.payload?.let { runCatching { dev.brahmkshatriya.echo.vibes.backend.Supa.json.decodeFromJsonElement(SongRef.serializer(), it).title }.getOrNull() } ?: "Brano")
        "playlist" -> "📀 Playlist"
        "challenge" -> "🎮 Sfida a ${VibesNotifier.gameName(m.payload?.get("game")?.toString()?.trim('"'))}"
        "image" -> "📷 Foto"
        "video" -> "🎬 Video"
        "audio" -> "🎤 Messaggio vocale"
        else -> m.body
    }
}

@Composable
fun Ticks(m: Message, modifier: Modifier = Modifier) {
    val color = when {
        m.readAt != null -> androidx.compose.ui.graphics.Color(0xFF34B7F1)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        m.pending -> "🕓"
        m.deliveredAt != null || m.readAt != null -> "✓✓"
        else -> "✓"
    }
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
fun FriendsScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val friends by app.social.friends.collectAsState()
    val requests by app.social.requests.collectAsState()
    val last by app.social.lastMessages.collectAsState()
    val unread by app.social.unread.collectAsState()
    val me = app.social.uid
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { app.social.refreshFriends(); app.social.refreshChats() } }

    val sorted = friends.sortedByDescending { last[it.id]?.createdAt ?: "" }
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Amici", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { nav.open(Route.AddFriend) }) { Icon(Icons.Rounded.PersonAdd, "Aggiungi amico", modifier = Modifier.size(28.dp)) }
                }
            }
            if (requests.isNotEmpty()) {
                item { SectionTitle("Richieste di amicizia") }
                items(requests, key = { it.first.id }) { (req, p) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p, 48.dp, Modifier.bouncy({ nav.open(Route.Profile(p.id)) }))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text("@${p.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill("Accetta", true, { scope.launch { runCatching { app.social.accept(req.id) }.onSuccess { nav.toast("Ora siete amici 🎉") } } })
                        Spacer(Modifier.width(6.dp))
                        Pill("No", false, { scope.launch { runCatching { app.social.decline(req.id) } } })
                    }
                }
            }
            if (friends.isEmpty()) item {
                EmptyState("👋", "Ancora nessun amico", "Condividi il tuo codice invito o cerca un username per iniziare a chattare e scambiarvi musica.") {
                    BigButton("Aggiungi amici", { nav.open(Route.AddFriend) }, Modifier.width(220.dp))
                }
            }
            itemsIndexed(sorted, key = { _, f -> f.id }) { i, f ->
                val m = last[f.id]
                val n = unread[f.id] ?: 0
                Row(
                    Modifier.fillMaxWidth().enter(i).bouncy({ nav.open(Route.Chat(f.id)) }, { nav.open(Route.Profile(f.id)) })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(f, 54.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (m != null && m.senderId == me) { Ticks(m); Spacer(Modifier.width(4.dp)) }
                            Text(
                                m?.let { preview(it, me) } ?: f.nowPlaying?.let { "🎧 ${it.title} – ${it.artist}" } ?: "Tocca per scrivere",
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (n > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (n > 0) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(shortTime(m?.createdAt), style = MaterialTheme.typography.labelSmall,
                            color = if (n > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (n > 0) Box(
                            Modifier.padding(top = 4.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) { Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

fun inviteText(p: Profile) =
    "Aggiungimi su Vibes! 🎧\nIl mio codice è ${p.inviteCode}\nOppure apri: vibes://invite/${p.inviteCode}"

@Composable
fun AddFriendScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val me by app.social.me.collectAsState()
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var sent by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val friends by app.social.friends.collectAsState()

    LaunchedEffect(query) {
        delay(350)
        results = runCatching { app.social.searchUsers(query) }.getOrDefault(emptyList())
    }

    GradientBackground {
        LazyColumn(Modifier.imePadding(), contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Aggiungi amici", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                Column(
                    Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, LocalVibes.current.bgTop)))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Il tuo codice invito", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(me?.inviteCode ?: "…", color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(vertical = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("Copia", false, { me?.let { copyText(context, it.inviteCode); nav.toast("Codice copiato") } })
                        Pill("Condividi link", false, { me?.let { shareToApps(context, inviteText(it)) } })
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Hai il codice di un amico?", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VibesField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) }, "Codice", Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        BigButton("Aggiungi", {
                            busy = true
                            scope.launch {
                                runCatching { app.social.addByCode(code) }
                                    .onSuccess { nav.toast("Ora tu e ${it.name} siete amici 🎉"); code = ""; nav.open(Route.Chat(it.id)) }
                                    .onFailure { nav.toast(it.message ?: "Codice non valido") }
                                busy = false
                            }
                        }, Modifier.width(120.dp), enabled = code.length >= 6, loading = busy)
                    }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Cerca per username", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    VibesField(query, { query = it }, "@username")
                }
            }
            items(results, key = { it.id }) { p ->
                val isFriend = friends.any { it.id == p.id }
                Row(Modifier.fillMaxWidth().clickable { nav.open(Route.Profile(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("@${p.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        isFriend -> Text("Amici ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        p.id in sent -> Text("Richiesta inviata", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        else -> Pill("Aggiungi", true, {
                            scope.launch {
                                runCatching { app.social.sendRequest(p.id) }
                                    .onSuccess { sent = sent + p.id; nav.toast("Richiesta inviata a ${p.name}") }
                                    .onFailure { nav.toast(it.message ?: "Errore") }
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(userId: String?) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val meProfile by app.social.me.collectAsState()
    val friends by app.social.friends.collectAsState()
    val isMe = userId == null || userId == meProfile?.id
    var other by remember { mutableStateOf<Profile?>(null) }
    var removing by remember { mutableStateOf(false) }
    var pickingSlot by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(userId) { if (!isMe && userId != null) other = runCatching { app.social.profile(userId) }.getOrNull() }
    val p = if (isMe) meProfile else other
    if (p == null) { Box(Modifier.fillMaxWidth().padding(80.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }; return }
    val isFriend = friends.any { it.id == p.id }

    GradientBackground(tint = parseColor(p.avatarColor)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Spacer(Modifier.weight(1f))
                    if (isMe) {
                        IconButton(onClick = { shareToApps(context, inviteText(p)) }) { Icon(Icons.Rounded.Share, "Condividi profilo") }
                        IconButton(onClick = { nav.open(Route.EditProfile) }) { Icon(Icons.Rounded.Edit, "Modifica") }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(p, 128.dp)
                    Text(p.name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 14.dp))
                    Text("@${p.username}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.bio.isNotBlank()) Text(p.bio, style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp))
                    if (isMe) Text("${friends.size} amici", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    if (!isMe) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (isFriend) {
                            Pill("Messaggio", true, { nav.open(Route.Chat(p.id)) })
                            Pill("Sfida 🎮", false, { nav.open(Route.Games) })
                            Pill("Rimuovi", false, { removing = true })
                        } else Pill("Aggiungi amico", true, {
                            scope.launch { runCatching { app.social.sendRequest(p.id) }.onSuccess { nav.toast("Richiesta inviata") } }
                        })
                    }
                }
            }
            p.nowPlaying?.let { np ->
                item {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LocalVibes.current.card)
                            .bouncy({ app.engine.play(np) }).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(np.cover, Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (isMe) "Stai ascoltando" else "Sta ascoltando", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("${np.title} – ${np.artist}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        EqualizerBars(Modifier.size(18.dp))
                    }
                }
            }
            item {
                SectionTitle("Top 4")
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0 until 4).forEach { i ->
                        val s = p.top4.getOrNull(i)
                        Column(Modifier.weight(1f).bouncy({
                            if (s != null && !isMe) app.engine.play(s)
                            else if (isMe) pickingSlot = i
                        }, if (s != null) ({ app.engine.play(s) }) else null)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)).background(LocalVibes.current.card),
                                contentAlignment = Alignment.Center) {
                                if (s != null) Cover(s.cover, Modifier.fillMaxWidth().aspectRatio(0.7f), RoundedCornerShape(8.dp))
                                else Text(if (isMe) "+" else "—", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(s?.title ?: "", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Text(
                    if (isMe) "Tocca uno slot per scegliere le tue 4 canzoni preferite." else "Tocca una copertina per ascoltarla.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text("Rimuovere ${p.name} dagli amici?") },
        confirmButton = { TextButton(onClick = { removing = false; scope.launch { runCatching { app.social.removeFriend(p.id) }; nav.back() } }) { Text("Rimuovi") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Annulla") } }
    )
    pickingSlot?.let { slot ->
        SongPickerDialog(onDismiss = { pickingSlot = null }) { song ->
            val list = (meProfile?.top4.orEmpty()).toMutableList()
            if (slot < list.size) list[slot] = song else list.add(song)
            pickingSlot = null
            scope.launch { runCatching { app.social.updateProfile(top4 = list.distinctBy { it.key }) }.onFailure { nav.toast(it.message ?: "Errore") } }
        }
    }
}

/** Search a song with the music engine and pick it (Top 4, games…). */
@Composable
fun SongPickerDialog(onDismiss: () -> Unit, onPick: (SongRef) -> Unit) {
    val app = LocalApp.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SongRef>>(emptyList()) }
    LaunchedEffect(q) {
        delay(350)
        if (q.isBlank()) { results = app.library.recent.value.take(10); return@LaunchedEffect }
        results = runCatching {
            val ext = app.engine.extensionId()
            app.engine.search(q).tracks.take(15).map { SongRef.from(ext, it) }
        }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli una canzone") },
        text = {
            Column {
                VibesField(q, { q = it }, "Cerca")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(360.dp)) {
                    items(results, key = { it.key }) { s ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(s.cover, Modifier.size(44.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}

@Composable
fun EditProfileScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val scope = rememberCoroutineScope()
    val me by app.social.me.collectAsState()
    val p = me ?: return
    var name by remember { mutableStateOf(p.displayName) }
    var bio by remember { mutableStateOf(p.bio) }
    var emoji by remember { mutableStateOf(p.avatarEmoji) }
    var color by remember { mutableStateOf(p.avatarColor) }
    var saving by remember { mutableStateOf(false) }
    GradientBackground(tint = parseColor(color)) {
        LazyColumn(Modifier.imePadding(), contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Modifica profilo", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(p.copy(avatarEmoji = emoji, avatarColor = color), 120.dp)
                    Spacer(Modifier.height(16.dp))
                    AvatarPicker(emoji, color, { emoji = it }, { color = it })
                    Spacer(Modifier.height(18.dp))
                    VibesField(name, { name = it.take(30) }, "Nome visualizzato")
                    Spacer(Modifier.height(12.dp))
                    VibesField(bio, { bio = it.take(160) }, "Bio", singleLine = false, maxLines = 4)
                    Spacer(Modifier.height(22.dp))
                    BigButton("Salva", {
                        saving = true
                        scope.launch {
                            runCatching { app.social.updateProfile(name, bio, emoji, color) }
                                .onSuccess { nav.toast("Profilo aggiornato"); nav.back() }
                                .onFailure { nav.toast(it.message ?: "Errore") }
                            saving = false
                        }
                    }, Modifier.fillMaxWidth(), loading = saving)
                }
            }
        }
    }
}
