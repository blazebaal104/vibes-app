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
import androidx.compose.material.icons.rounded.PhotoCamera
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
    val following by app.social.following.collectAsState()
    val followers by app.social.followers.collectAsState()
    val people by app.social.people.collectAsState()
    LaunchedEffect(Unit) { runCatching { app.social.refreshFriends(); app.social.refreshChats(); app.social.refreshFollows() } }
    val newFollowers = followers.filter { it !in following }.mapNotNull { people[it] }

    val sorted = friends.sortedByDescending { last[it.id]?.createdAt ?: "" }
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Amici", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { nav.open(Route.AddFriend) }) { Icon(Icons.Rounded.PersonAdd, "Trova persone", modifier = Modifier.size(28.dp)) }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    me?.let { id ->
                        Pill("${followers.size} follower", false, { nav.open(Route.Follows(id, true)) })
                        Pill("${following.size} seguiti", false, { nav.open(Route.Follows(id, false)) })
                    }
                }
                Text("Chi vi seguite a vicenda siete amici: potete chattare e sfidarvi.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (newFollowers.isNotEmpty()) {
                item { SectionTitle("Ti seguono") }
                items(newFollowers, key = { "nf" + it.id }) { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p, 48.dp, Modifier.bouncy({ nav.open(Route.Profile(p.id)) }))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text("@${p.username} ha iniziato a seguirti", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FollowButton(p.id, compact = true)
                    }
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
                EmptyState("👋", "Ancora nessun amico", "Segui qualcuno: quando ti segue anche lui diventate amici e potete chattare.") {
                    BigButton("Trova persone", { nav.open(Route.AddFriend) }, Modifier.width(220.dp))
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
                                m?.let { preview(it, me) } ?: f.liveNow?.let { "🎧 ${it.song.title} – ${it.song.artist}" + if (!it.playing) " (in pausa)" else "" } ?: "Tocca per scrivere",
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
                    Text("Trova persone", style = MaterialTheme.typography.headlineSmall)
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
                        BigButton("Segui", {
                            busy = true
                            scope.launch {
                                runCatching { app.social.followByCode(code) }
                                    .onSuccess { nav.toast("Ora segui ${it.name} ✨"); code = ""; nav.open(Route.Profile(it.id)) }
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
                    SearchField(query, { query = it }, "@username")
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
                    FollowButton(p.id, compact = true)
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
    val storyGroups by app.social.stories.collectAsState()
    val myFollowing by app.social.following.collectAsState()
    val myFollowers by app.social.followers.collectAsState()
    var counts by remember(p.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(p.id, myFollowing.size, myFollowers.size) {
        counts = runCatching { app.social.followLists(p.id) }.getOrNull()?.let { it.first.size to it.second.size }
    }
    val group = storyGroups.firstOrNull { it.user.id == p.id }

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
                    StoryAvatar(p, 128.dp, group?.let { !it.allSeen }, Modifier.bouncy({ if (group != null) nav.open(Route.Stories(p.id)) }))
                    Text(p.name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 14.dp))
                    Text("@${p.username}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (p.bio.isNotBlank()) Text(p.bio, style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        Column(Modifier.bouncy({ nav.open(Route.Follows(p.id, true)) }), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${counts?.first ?: "–"}", style = MaterialTheme.typography.titleLarge)
                            Text("follower", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.bouncy({ nav.open(Route.Follows(p.id, false)) }), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${counts?.second ?: "–"}", style = MaterialTheme.typography.titleLarge)
                            Text("seguiti", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isMe) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${friends.size}", style = MaterialTheme.typography.titleLarge)
                            Text("amici", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!isMe && p.id in myFollowers && p.id !in myFollowing) Text("Ti segue", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    if (!isMe) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FollowButton(p.id)
                        if (isFriend) {
                            Pill("Messaggio", true, { nav.open(Route.Chat(p.id)) })
                            Pill("Sfida 🎮", false, { nav.open(Route.Games) })
                        }
                    }
                }
            }
            item(key = "live") { LiveCard(p, isMe) }
            if (isMe) item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileButton("📊", "Statistiche", Modifier.weight(1f)) { nav.open(Route.Stats) }
                    ProfileButton("🕘", "Cronologia", Modifier.weight(1f)) { nav.open(Route.History) }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileButton("➕", "Nuova storia", Modifier.weight(1f)) { nav.open(Route.NewStory()) }
                    ProfileButton("✨", "Recap storie", Modifier.weight(1f)) { nav.open(Route.StoryRecap(java.time.YearMonth.now().toString())) }
                }
            }
            if (!isMe && p.privacy.weekStats) item { FriendWeekCard(p, Modifier.padding(16.dp)) }
            if (isMe || p.privacy.favorites) item { FavoritesSection(p, isMe) }
            item(key = "uploads") { ProfileUploads(p, isMe) }
            item {
                SectionTitle("Top 4")
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0 until 4).forEach { i ->
                        val s = p.top4.getOrNull(i)
                        Column(Modifier.weight(1f).bouncy({
                            if (s != null && !isMe) app.engine.play(s)
                            else if (isMe) pickingSlot = i
                        }, if (s != null) ({ app.engine.play(s) }) else null)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.7f).glass(RoundedCornerShape(8.dp)),
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
            if (isMe) item {
                val r = remember { runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.listens.records() }.getOrNull() }
                r?.takeIf { it.mostMinutes > 0 }?.let { RecordsCard(it, Modifier.padding(16.dp)) }
            } else if (p.privacy.records) p.records?.takeIf { it.mostMinutes > 0 }?.let { r -> item { RecordsCard(r, Modifier.padding(16.dp)) } }
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
    var uploading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            runCatching {
                val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { squareJpeg(context, uri, 512) }
                app.social.uploadAvatar(jpeg)
            }.onSuccess { nav.toast("Foto aggiornata") }
                .onFailure { nav.toast("Caricamento non riuscito: ${it.message}") }
            uploading = false
        }
    }
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
                    Box {
                        Avatar(p.copy(avatarEmoji = emoji, avatarColor = color), 120.dp,
                            Modifier.bouncy({ photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }))
                        if (uploading) androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center))
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.PhotoCamera, "Cambia foto", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Cambia foto", false, { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        if (p.avatarUrl != null) Pill("Usa emoji", false, { scope.launch { runCatching { app.social.removeAvatar() } } })
                    }
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

/** Reads an image, crops it to a centred square, scales to [size] px and encodes JPEG. */
fun squareJpeg(context: android.content.Context, uri: android.net.Uri, size: Int): ByteArray {
    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    // respect EXIF rotation from the camera
    val rotation = runCatching {
        when (android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
    if (rotation != 0) bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
        android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
    val side = minOf(bmp.width, bmp.height)
    val square = android.graphics.Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
    val scaled = android.graphics.Bitmap.createScaledBitmap(square, size, size, true)
    return java.io.ByteArrayOutputStream().use { out -> scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out); out.toByteArray() }
}

@Composable
private fun ProfileButton(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.glass(RoundedCornerShape(16.dp)).bouncy(onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

/** Favourite artist, favourite song by that artist and a pinned (saveable) playlist. */
@Composable
private fun FavoritesSection(p: Profile, isMe: Boolean) {
    val app = LocalApp.current
    val nav = app.nav
    val scope = rememberCoroutineScope()
    var pickArtist by remember { mutableStateOf(false) }
    var pickSong by remember { mutableStateOf(false) }
    var pickPlaylist by remember { mutableStateOf(false) }
    if (!isMe && p.favArtist == null && p.favSong == null && p.favPlaylist == null) return
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionTitle("Preferiti", Modifier.padding(start = 0.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // artist
            Column(
                Modifier.weight(1f).glass(RoundedCornerShape(18.dp))
                    .bouncy({
                        if (isMe) pickArtist = true
                        else p.favArtist?.let { a -> scope.launch {
                            runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.deezer.artistTop(a.id, 25).map { it.toSongRef() } }
                                .onSuccess { app.engine.playAll(it) }
                        } }
                    })
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Artista preferito", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val a = p.favArtist
                if (a != null) Cover(a.image, Modifier.size(96.dp), CircleShape)
                else Box(Modifier.size(96.dp).clip(CircleShape).background(LocalVibes.current.cardHigh), contentAlignment = Alignment.Center) {
                    Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(a?.name ?: "Scegli", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp))
            }
            // song by that artist
            Column(
                Modifier.weight(1f).glass(RoundedCornerShape(18.dp))
                    .bouncy({
                        val s = p.favSong
                        if (isMe) { if (p.favArtist == null) pickArtist = true else pickSong = true }
                        else if (s != null) app.engine.play(s)
                    }, p.favSong?.let { s -> { app.engine.play(s) } })
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(p.favArtist?.let { "Il brano di ${it.name}" } ?: "Brano preferito", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                val s = p.favSong
                if (s != null) Cover(s.cover, Modifier.size(96.dp), RoundedCornerShape(10.dp))
                else Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(LocalVibes.current.cardHigh), contentAlignment = Alignment.Center) {
                    Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(s?.title ?: "Scegli", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        // pinned playlist
        val fp = p.favPlaylist
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp))
                .bouncy({ if (fp != null) nav.open(Route.SharedPlaylist(fp.sharedId)) else if (isMe) pickPlaylist = true },
                    if (isMe) ({ pickPlaylist = true }) else null)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (fp != null) {
                if (fp.cover != null) Cover(fp.cover, Modifier.size(64.dp), RoundedCornerShape(10.dp))
                else Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(parseColor(fp.color)), contentAlignment = Alignment.Center) {
                    Text(fp.emoji, fontSize = 28.sp)
                }
            } else Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(LocalVibes.current.cardHigh), contentAlignment = Alignment.Center) {
                Text("+", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Playlist preferita", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fp?.name ?: "Fissa una tua playlist sul profilo", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (fp != null) Text("${fp.count} brani · tocca per aprirla e salvarla", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isMe && fp != null) IconButton(onClick = { pickPlaylist = true }) { Icon(Icons.Rounded.Edit, "Cambia") }
        }
    }

    if (pickArtist) ArtistPickerDialog(onDismiss = { pickArtist = false }) { a ->
        pickArtist = false
        scope.launch {
            runCatching {
                app.social.setFavArtist(a)
                // the song must be by that artist: clear it if it isn't, then ask for one
                if (p.favSong?.artist?.contains(a.name, true) != true) app.social.setFavSong(null)
            }.onFailure { nav.toast(it.message ?: "Errore") }
            pickSong = true
        }
    }
    if (pickSong) p.favArtist?.let { a ->
        ArtistSongDialog(a, onDismiss = { pickSong = false }) { s ->
            pickSong = false
            scope.launch { runCatching { app.social.setFavSong(s) }.onFailure { nav.toast(it.message ?: "Errore") } }
        }
    }
    if (pickPlaylist) {
        val lists by app.library.playlists.collectAsState()
        AlertDialog(
            onDismissRequest = { pickPlaylist = false },
            title = { Text("Quale playlist fissi sul profilo?") },
            text = {
                if (lists.isEmpty()) Text("Crea prima una playlist nella Libreria.")
                else LazyColumn(Modifier.height(360.dp)) {
                    items(lists, key = { it.id }) { pl ->
                        Row(Modifier.fillMaxWidth().clickable {
                            pickPlaylist = false
                            scope.launch {
                                runCatching { app.social.setFavPlaylist(pl) }
                                    .onSuccess { nav.toast("\"${pl.name}\" ora è sul tuo profilo") }
                                    .onFailure { nav.toast(it.message ?: "Errore") }
                            }
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlaylistArt(pl, Modifier.size(48.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(pl.name, style = MaterialTheme.typography.titleMedium)
                                Text("${pl.songs.size} brani", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (p.favPlaylist != null) TextButton(onClick = { pickPlaylist = false; scope.launch { runCatching { app.social.setFavPlaylist(null) } } }) { Text("Rimuovi") }
                else TextButton(onClick = { pickPlaylist = false }) { Text("Chiudi") }
            }
        )
    }
}

@Composable
fun ArtistPickerDialog(onDismiss: () -> Unit, onPick: (dev.brahmkshatriya.echo.vibes.backend.FavArtist) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<dev.brahmkshatriya.echo.vibes.music.DeezerPublic.ArtistInfo>>(emptyList()) }
    LaunchedEffect(q) {
        delay(300)
        results = runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.deezer.searchArtists(q) }.getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Il tuo artista preferito") },
        text = {
            Column {
                SearchField(q, { q = it }, "Cerca un artista")
                LazyColumn(Modifier.height(320.dp).padding(top = 8.dp)) {
                    items(results, key = { it.id }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(dev.brahmkshatriya.echo.vibes.backend.FavArtist(a.id, a.name, a.picture)) }
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(a.picture, Modifier.size(46.dp), CircleShape)
                            Spacer(Modifier.width(12.dp))
                            Text(a.name, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
private fun ArtistSongDialog(a: dev.brahmkshatriya.echo.vibes.backend.FavArtist, onDismiss: () -> Unit, onPick: (SongRef) -> Unit) {
    var songs by remember { mutableStateOf<List<SongRef>?>(null) }
    LaunchedEffect(a.id) {
        songs = runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.deezer.artistTop(a.id, 50).map { it.toSongRef() }.distinctBy { it.title.lowercase() } }
            .getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Il tuo brano preferito di ${a.name}") },
        text = {
            val list = songs
            if (list == null) Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            else LazyColumn(Modifier.height(380.dp)) {
                items(list, key = { it.key }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(s.cover, Modifier.size(46.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.album ?: s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
