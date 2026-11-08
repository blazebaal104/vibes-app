package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PC: Deezer's site opens in the browser (sign up or log in there), then the user comes back and
 * logs in with email + password in the previous screen.
 */
@Composable
fun DeezerWebLogin(signup: Boolean, onBack: () -> Unit, onArl: suspend (String) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
            Text(if (signup) "Crea il tuo account Deezer" else "Accedi a Deezer", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxWidth().padding(24.dp).widthIn(max = 520.dp).align(Alignment.CenterHorizontally),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("🌐", fontSize = 56.sp)
            Text(
                if (signup) "1. Si apre deezer.com nel browser: crea l'account gratis (con email e password).\n" +
                        "2. Torna qui e accedi con la stessa email e password."
                else "Accedi qui con l'email e la password del tuo account Deezer.\n" +
                        "Se su Deezer entri con Google o Apple, imposta prima una password dal sito di Deezer.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            BigButton(if (signup) "Apri deezer.com e crea l'account" else "Apri deezer.com", {
                android.content.DesktopEnv.open(if (signup) "https://www.deezer.com/it/register" else "https://www.deezer.com/it/login")
            }, Modifier.fillMaxWidth())
            BigButton("Ho fatto: accedi con email e password", onBack, Modifier.fillMaxWidth(),
                container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurface)
        }
    }
}
