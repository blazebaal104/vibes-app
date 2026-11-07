package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.vibes.listen.LobbySync
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Friends tab: create a lobby + pending invitations. */
@Composable
fun LobbyEntryCard() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val invites by VibesGraph.lobbies.invites.collectAsState()
    val current by VibesGraph.lobbies.lobby.collectAsState()
    LaunchedEffect(Unit) { VibesGraph.lobbies.refreshInvites() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        invites.forEach { inv ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8E7BFF), Color(0xFFFF4FD8)))).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("🎧", fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Text("${app.social.friendName(inv.fromUser)} ti invita ad ascoltare insieme", color = Color.White,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("Entra", color = Color.Black, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(Color.White).bouncy({ app.nav.open(Route.ListenLobby(inv.lobbyId)) })
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(LocalVibes.current.cardHigh)
            .bouncy({
                val open = current
                if (open != null && open.id.isNotBlank()) app.nav.open(Route.ListenLobby(open.id))
                else scope.launch {
                    runCatching { VibesGraph.lobbies.create("Lobby di ${app.social.me.value?.name ?: "Vibes"}") }
                        .onSuccess { app.nav.open(Route.ListenLobby(it.id)) }
                        .onFailure { app.nav.toast(it.message ?: "Errore") }
                }
            }).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🎉", fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (current != null) "Torna alla tua lobby" else "Crea lobby d'ascolto", style = MaterialTheme.typography.titleMedium)
                Text("Tutti nella stessa stanza, stessa musica nello stesso istante: per cantare insieme",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ListenLobbyScreen(id: String) {
    val app = LocalApp.current
    val lobbies = VibesGraph.lobbies
    val scope = rememberCoroutineScope()
    val lobby by lobbies.lobby.collectAsState()
    val members by lobbies.members.collectAsState()
    val err by lobbies.syncError.collectAsState()
    val profiles = remember { mutableStateMapOf<String, Profile>() }
    var picking by remember { mutableStateOf(false) }
    var pickPlaylist by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var crownFor by remember { mutableStateOf<String?>(null) }
    var calib by remember { mutableStateOf(lobbies.calibrationMs.toFloat()) }

    LaunchedEffect(id) {
        runCatching {
            if (members.none { it.userId == app.social.uid } || lobby?.id != id) lobbies.join(id, app.engine)
            else lobbies.enter(id, app.engine)
        }.onFailure { app.nav.toast(it.message ?: "Non riesco a entrare nella lobby"); app.nav.back() }
    }
    LaunchedEffect(members) {
        members.forEach { m -> if (m.userId !in profiles) runCatching { app.social.profile(m.userId) }.getOrNull()?.let { profiles[m.userId] = it } }
    }
    val l = lobby
    val isHost = lobbies.isHost
    val state = l?.state
    val now = lobbies.serverNow()
    val pos = if (state != null && now != null && state.queue.isNotEmpty())
        LobbySync.expected(state.index, state.anchorPos, state.anchorServer, state.playing, now, state.queue.map { it.duration }) else null
    val song = pos?.let { state.queue.getOrNull(it.index) }

    GradientBackground(tint = Color(0xFF8E7BFF)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { app.nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Column(Modifier.weight(1f)) {
                        Text(l?.name ?: "Lobby", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (isHost) "👑 Sei il re: scegli tu la musica" else "👑 ${profiles[l?.host]?.name ?: "Il re"} sceglie la musica",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Esci", color = Color(0xFFFF5C7A), fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).bouncy({ lobbies.leave(); app.nav.back() }).padding(12.dp))
                }
            }
            // now playing
            item {
                Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalVibes.current.cardHigh).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (song == null) {
                        Text("🎶", fontSize = 48.sp)
                        Text(if (isHost) "Scegli cosa ascoltare" else "In attesa che il re scelga la musica…",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Cover(song.cover, Modifier.size(200.dp), RoundedCornerShape(16.dp))
                        Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp))
                        Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val e = err
                        Text(
                            when {
                                pos.posMs < 0 -> "Si parte tutti insieme tra ${(-pos.posMs / 100) / 10.0} s…"
                                state.playing.not() -> "In pausa"
                                e == null -> "Mi sincronizzo…"
                                abs(e) <= 40 -> "✅ In sync con il gruppo (±${abs(e)} ms)"
                                abs(e) <= 100 -> "🟡 Quasi in sync (${abs(e)} ms)"
                                else -> "🔄 Recupero ${abs(e)} ms…"
                            },
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (isHost) {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconButton(onClick = { scope.launch { lobbies.skip(-1) } }) { Icon(Icons.Rounded.SkipPrevious, "Precedente", Modifier.size(34.dp)) }
                            Box(Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF8E7BFF), Color(0xFFFF4FD8))))
                                .bouncy({ scope.launch { lobbies.togglePlay() } }), contentAlignment = Alignment.Center) {
                                Icon(if (state?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pausa", tint = Color.White, modifier = Modifier.size(36.dp))
                            }
                            IconButton(onClick = { scope.launch { lobbies.skip(1) } }) { Icon(Icons.Rounded.SkipNext, "Successivo", Modifier.size(34.dp)) }
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("🔎 Brano", true, { picking = true })
                            Pill("📚 Playlist", false, { pickPlaylist = true })
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nella lobby (${members.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Pill("+ Invita amici", true, { inviting = true })
                }
            }
            items(members, key = { it.userId }) { m ->
                val p = profiles[m.userId]
                Row(Modifier.fillMaxWidth().bouncy({ if (isHost && m.userId != app.social.uid) crownFor = m.userId }).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text((p?.name ?: "…") + if (m.userId == app.social.uid) " (tu)" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (m.userId == l?.host) Text("👑", fontSize = 22.sp)
                }
            }
            // Bluetooth speakers add their own delay: let each phone nudge itself
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Ritardo di questo telefono: ${calib.toInt()} ms", style = MaterialTheme.typography.labelLarge)
                    Text("Se usi una cassa o cuffie Bluetooth e senti l'audio in ritardo rispetto agli altri, sposta a destra.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(calib, { calib = it }, valueRange = -200f..400f, onValueChangeFinished = { lobbies.calibrationMs = calib.toLong() })
                }
            }
        }
    }

    if (picking) GlobalSongPicker(onDismiss = { picking = false }) { s ->
        picking = false
        scope.launch { lobbies.play(listOf(s)) }
    }
    if (pickPlaylist) {
        val pls by app.library.playlists.collectAsState()
        AlertDialog(onDismissRequest = { pickPlaylist = false }, title = { Text("Fai partire una playlist") },
            text = {
                LazyColumn(Modifier.height(380.dp)) {
                    items(pls.filter { it.songs.isNotEmpty() }, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().bouncy({ pickPlaylist = false; scope.launch { lobbies.play(p.songs) } }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Cover(p.cover ?: p.songs.firstOrNull()?.cover, Modifier.size(44.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("${p.name} · ${p.songs.size}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { pickPlaylist = false }) { Text("Chiudi") } })
    }
    if (inviting) {
        val friends by app.social.friends.collectAsState()
        val chosen = remember { mutableStateMapOf<String, Boolean>() }
        AlertDialog(onDismissRequest = { inviting = false }, title = { Text("Invita amici") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(friends.filter { f -> members.none { it.userId == f.id } }, key = { it.id }) { f ->
                        Row(Modifier.fillMaxWidth().bouncy({ chosen[f.id] = chosen[f.id] != true }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(f, 36.dp); Spacer(Modifier.width(10.dp))
                            Text(f.name, modifier = Modifier.weight(1f))
                            Checkbox(chosen[f.id] == true, { chosen[f.id] = it })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = chosen.filterValues { it }.keys.toList()
                    inviting = false
                    scope.launch { lobbies.invite(ids); app.nav.toast("Invito mandato a ${ids.size} amici 🎧") }
                }) { Text("Invita") }
            }, dismissButton = { TextButton(onClick = { inviting = false }) { Text("Annulla") } })
    }
    crownFor?.let { uid ->
        AlertDialog(onDismissRequest = { crownFor = null }, title = { Text("👑 Passare la corona?") },
            text = { Text("${profiles[uid]?.name ?: "Questo amico"} diventerà il re e sceglierà la musica.") },
            confirmButton = { TextButton(onClick = { crownFor = null; scope.launch { runCatching { lobbies.transfer(uid) }.onFailure { app.nav.toast(it.message ?: "Errore") } } }) { Text("Passa la corona") } },
            dismissButton = { TextButton(onClick = { crownFor = null; scope.launch { lobbies.kick(uid) } }) { Text("Togli dalla lobby") } })
    }
}
