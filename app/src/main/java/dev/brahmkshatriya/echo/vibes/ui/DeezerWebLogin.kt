package dev.brahmkshatriya.echo.vibes.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * deezer.com inside Vibes: the user signs up (free) or logs in like on the website, and as soon as
 * Deezer sets its session cookie ("arl") Vibes picks it up and links the account. No codes to copy.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeezerWebLogin(signup: Boolean, onBack: () -> Unit, onArl: suspend (String) -> Unit) {
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val cm = CookieManager.getInstance()
        // start clean so an old, expired session is not picked up again
        cm.removeAllCookies(null); cm.flush()
        var tried = ""
        while (true) {
            delay(800)
            val arl = cm.getCookie("https://www.deezer.com")?.split(";")
                ?.map { it.trim() }?.firstOrNull { it.startsWith("arl=") }?.removePrefix("arl=")
            if (arl != null && arl.length > 100 && arl != tried) {
                tried = arl
                linking = true; error = null
                runCatching { onArl(arl) }.onFailure { error = it.message ?: "Collegamento non riuscito, riprova"; linking = false }
                if (error == null) return@LaunchedEffect
            }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
            Column {
                Text(if (signup) "Crea il tuo account Deezer" else "Accedi a Deezer", style = MaterialTheme.typography.titleLarge)
                Text("Gratis · appena entri, Vibes si collega da solo", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF5C7A), modifier = Modifier.padding(horizontal = 16.dp)) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    loadUrl(if (signup) "https://www.deezer.com/it/register" else "https://www.deezer.com/it/login")
                }
            }, modifier = Modifier.fillMaxSize())
            if (linking) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.glass(blur = true).padding(24.dp)) {
                    CircularProgressIndicator()
                    Text("Collego Deezer a Vibes…", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
