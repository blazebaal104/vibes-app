package dev.brahmkshatriya.echo.vibes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.backend.SharedPlaylist
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import kotlinx.coroutines.launch

fun externalText(song: SongRef): String {
    val web = if (song.ext == MusicEngine.DEEZER) "\nhttps://www.deezer.com/track/${song.id}" else ""
    return "🎧 Ascolta \"${song.title}\" di ${song.artist} su Vibes$web"
}

fun shareToApps(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(send, "Condividi con…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Vibes", text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(item: ShareItem, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val friends by app.social.friends.collectAsState()
    val selected = remember { mutableStateListOf<String>() }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    val title: String; val subtitle: String; val cover: String?
    when (item) {
        is ShareItem.Song -> { title = item.song.title; subtitle = item.song.artist; cover = item.song.cover }
        is ShareItem.Playlist -> { title = item.playlist.name; subtitle = "Playlist · ${item.playlist.songs.size} brani"; cover = item.playlist.songs.firstOrNull()?.cover }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(cover, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Text("Invia a un amico", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp))
            if (friends.isEmpty()) Text(
                "Non hai ancora amici su Vibes. Aggiungili dalla scheda Amici con il tuo codice invito.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(friends, key = { it.id }) { f ->
                    val sel = f.id in selected
                    val s by animateFloatAsState(if (sel) 0.9f else 1f, label = "sel")
                    Column(
                        Modifier.width(68.dp).bouncy({ if (sel) selected.remove(f.id) else selected.add(f.id) }),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box {
                            Avatar(f, 60.dp, Modifier.scale(s).then(
                                if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier))
                            if (sel) Box(
                                Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                        }
                        Text(f.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (selected.isNotEmpty()) Column(Modifier.padding(16.dp)) {
                VibesField(note, { note = it.take(300) }, "Aggiungi un messaggio (facoltativo)")
                Spacer(Modifier.height(10.dp))
                BigButton(
                    "Invia" + if (selected.size > 1) " a ${selected.size} amici" else "",
                    loading = sending, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        sending = true
                        scope.launch {
                            val result = runCatching {
                                when (item) {
                                    is ShareItem.Song -> selected.forEach { app.social.sendSong(it, item.song, note) }
                                    is ShareItem.Playlist -> {
                                        val snap = app.social.publishPlaylist(SharedPlaylist(name = item.playlist.name, tracks = item.playlist.songs))
                                        selected.forEach { app.social.sendPlaylist(it, snap, note) }
                                    }
                                }
                            }
                            sending = false
                            result.onSuccess { app.nav.toast("Inviato ✓"); onDismiss() }
                                .onFailure { app.nav.toast(it.message ?: "Invio non riuscito") }
                        }
                    }
                )
            }

            Text("Altre opzioni", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp))
            when (item) {
                is ShareItem.Song -> {
                    ShareOption(Icons.Rounded.PlaylistAdd, "Aggiungi a playlist") { onDismiss(); app.nav.addToPlaylist = item.song }
                    ShareOption(Icons.Rounded.Share, "Condividi su altre app") { shareToApps(context, externalText(item.song)) }
                    ShareOption(Icons.Rounded.ContentCopy, "Copia link") {
                        copyText(context, externalText(item.song)); app.nav.toast("Link copiato")
                    }
                }
                is ShareItem.Playlist -> {
                    ShareOption(Icons.Rounded.Share, "Condividi su altre app") {
                        scope.launch {
                            runCatching { app.social.publishPlaylist(SharedPlaylist(name = item.playlist.name, tracks = item.playlist.songs)) }
                                .onSuccess {
                                    shareToApps(context, "🎶 Ti ho condiviso la playlist \"${it.name}\" su Vibes: vibes://playlist/${it.id}")
                                }.onFailure { app.nav.toast(it.message ?: "Errore") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().bouncy(onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(LocalVibes.current.cardHigh), contentAlignment = Alignment.Center) { Icon(icon, null) }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
