package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.VibesOffline

val OfflineGreen = Color(0xFF1ED760)

/** Playlist download toggle: arrow → progress ring → green tick (tap again to remove). */
@Composable
fun DownloadAllButton(songs: List<SongRef>, name: String) {
    val app = LocalApp.current
    val entries by VibesOffline.entries.collectAsState()
    val states by VibesOffline.states.collectAsState()
    val done = songs.count { entries.containsKey(it.key) }
    val busy = songs.any { states[it.key] is VibesOffline.State.Queued || states[it.key] is VibesOffline.State.Downloading }
    var confirmRemove by remember { mutableStateOf(false) }
    IconButton(onClick = {
        when {
            songs.isEmpty() -> {}
            done == songs.size -> confirmRemove = true
            else -> {
                VibesOffline.download(songs)
                app.nav.toast("Scarico ${songs.size - done} brani per ascoltarli offline")
            }
        }
    }) {
        when {
            busy -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (songs.isEmpty()) 0f else done.toFloat() / songs.size },
                    modifier = Modifier.size(26.dp), strokeWidth = 3.dp, color = OfflineGreen
                )
                Icon(Icons.Rounded.Download, null, Modifier.size(14.dp), tint = OfflineGreen)
            }
            songs.isNotEmpty() && done == songs.size -> Icon(Icons.Rounded.CheckCircle, "Scaricata", tint = OfflineGreen)
            else -> Icon(Icons.Rounded.Download, "Scarica")
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Rimuovere i download?") },
        text = { Text("«$name» non sarà più disponibile offline. Potrai riscaricarla quando vuoi.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; VibesOffline.remove(songs) }) { Text("Rimuovi") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Annulla") } }
    )
}

/** Small marker next to a song: green tick if downloaded, ring while downloading. */
@Composable
fun OfflineMark(song: SongRef) {
    val entries by VibesOffline.entries.collectAsState()
    val states by VibesOffline.states.collectAsState()
    when (val st = states[song.key]) {
        is VibesOffline.State.Downloading -> CircularProgressIndicator(
            progress = { st.progress }, modifier = Modifier.padding(end = 6.dp).size(13.dp), strokeWidth = 2.dp, color = OfflineGreen)
        is VibesOffline.State.Queued -> CircularProgressIndicator(
            modifier = Modifier.padding(end = 6.dp).size(13.dp), strokeWidth = 2.dp, color = OfflineGreen.copy(alpha = 0.5f))
        else -> if (entries.containsKey(song.key))
            Icon(Icons.Rounded.CheckCircle, "Scaricato", Modifier.padding(end = 6.dp).size(14.dp), tint = OfflineGreen)
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1_073_741_824 -> "%.1f GB".format(b / 1_073_741_824f)
    b >= 1_048_576 -> "%.0f MB".format(b / 1_048_576f)
    else -> "${b / 1024} KB"
}


/** Round download toggle for one song (full player): arrow -> progress -> green tick. */
@Composable
fun SongDownloadButton(song: SongRef) {
    val app = LocalApp.current
    val entries by VibesOffline.entries.collectAsState()
    val states by VibesOffline.states.collectAsState()
    val done = entries.containsKey(song.key)
    val st = states[song.key]
    androidx.compose.foundation.layout.Box(
        Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape).background(LocalVibes.current.cardHigh)
            .bouncy({
                when {
                    done -> { VibesOffline.remove(listOf(song)); app.nav.toast("Download rimosso") }
                    st == null || st is VibesOffline.State.Failed -> { VibesOffline.download(listOf(song)); app.nav.toast("Scarico per l'ascolto offline") }
                }
            }),
        contentAlignment = Alignment.Center
    ) {
        when {
            done -> Icon(Icons.Rounded.CheckCircle, "Scaricato", tint = OfflineGreen)
            st is VibesOffline.State.Downloading -> CircularProgressIndicator(progress = { st.progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = OfflineGreen)
            st is VibesOffline.State.Queued -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = OfflineGreen)
            else -> Icon(Icons.Rounded.Download, "Scarica")
        }
    }
}


/**
 * Shown on Home when the app opened without internet: only downloaded songs and albums can play,
 * and one tap opens them.
 */
@androidx.compose.runtime.Composable
fun OfflineBanner(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val app = LocalApp.current
    val off by app.social.offline.collectAsState()
    if (!off) return
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(OfflineGreen.copy(alpha = 0.16f))
            .clickable { app.nav.open(Route.Downloads) }
            .padding(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text("📴", fontSize = 22.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("Sei offline", style = MaterialTheme.typography.titleSmall)
            Text("Puoi ascoltare i brani e gli album scaricati. Tocca per aprirli.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.Download, null, tint = OfflineGreen)
    }
}
