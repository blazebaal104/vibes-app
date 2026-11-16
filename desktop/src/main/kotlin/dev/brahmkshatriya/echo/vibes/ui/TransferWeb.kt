package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * PC: the desktop app can't hold a web page, so TuneMyMusic opens in the browser; the user moves
 * the playlist to Deezer there and pastes the Deezer link back in "Trasferisci playlist".
 */
@Composable
fun TransferWeb(onBack: () -> Unit, @Suppress("UNUSED_PARAMETER") onPlaylist: (String) -> Unit) {
    val uri = LocalUriHandler.current
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Porta la playlist intera", style = MaterialTheme.typography.headlineSmall)
            Text("Si apre TuneMyMusic nel browser: sposta la playlist da Spotify a Deezer, poi copia il link della playlist Deezer e incollalo qui in Vibes.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(18.dp))
            Pill("Apri TuneMyMusic", true, { runCatching { uri.openUri("https://www.tunemymusic.com/it/transfer") } })
            Spacer(Modifier.height(10.dp))
            Pill("Fatto, torno a Vibes", false, onBack)
            Text("Powered by TuneMyMusic", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp))
        }
    }
}
