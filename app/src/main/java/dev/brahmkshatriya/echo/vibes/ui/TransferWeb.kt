package dev.brahmkshatriya.echo.vibes.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Links that mean "the copy of the playlist is ready": Vibes imports it from there by itself. */
private val doneRe = Regex("""https?://(?:www\.)?(?:deezer\.com/(?:[a-z]{2}/)?playlist/\d+|link\.deezer\.com/\S+|deezer\.page\.link/\S+|music\.youtube\.com/playlist\?list=[\w-]+)""")

/**
 * TuneMyMusic inside Vibes (no browser tabs): the user moves the long Spotify playlist to Deezer
 * there; as soon as TuneMyMusic opens the new Deezer playlist, Vibes takes the link, closes this
 * page and imports the whole playlist. Shown clearly as TuneMyMusic's service ("Powered by").
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TransferWeb(onBack: () -> Unit, onPlaylist: (String) -> Unit) {
    var loading by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
            Column(Modifier.weight(1f)) {
                Text("Porta la playlist intera", style = MaterialTheme.typography.titleLarge)
                Text("Spostala su Deezer: appena è pronta, Vibes la importa da solo", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (loading in 1..99) LinearProgressIndicator(progress = { loading / 100f }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportMultipleWindows(false) // "open in new tab" stays here
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { loading = newProgress }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            val hit = doneRe.find(url)?.value
                            if (hit != null && !done) { done = true; onPlaylist(hit); return true }
                            // Deezer / YouTube apps links: stay inside, never jump to another app
                            return !url.startsWith("http")
                        }
                    }
                    loadUrl("https://www.tunemymusic.com/it/transfer")
                }
            }, modifier = Modifier.fillMaxSize())
            if (done) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.glass(blur = true).padding(24.dp)) {
                    CircularProgressIndicator()
                    Text("Playlist pronta: la importo in Vibes…", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        // whose service this is, always in sight
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 5.dp)) {
                Text("Powered by TuneMyMusic", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
