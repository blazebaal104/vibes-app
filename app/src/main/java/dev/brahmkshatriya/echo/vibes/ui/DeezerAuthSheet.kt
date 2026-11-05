package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.music.DeezerAuth

/** Shown when Deezer refuses to stream (session expired / never linked): explains and links to login. */
@Composable
fun DeezerAuthHost() {
    val app = LocalApp.current
    val needed by DeezerAuth.needed.collectAsState()
    val logged by app.engine.deezerLoggedIn.collectAsState(initial = null)
    LaunchedEffect(logged) { DeezerAuth.loggedIn = logged }
    val show = needed && app.nav.current !is Route.DeezerLogin
    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(remember { MutableInteractionSource() }, null) { DeezerAuth.dismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(remember { MutableInteractionSource() }, null) { }
                    .navigationBarsPadding().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔑", fontSize = 46.sp)
                Text(
                    if (logged == false) "Collega Deezer per ascoltare" else "Deezer si è scollegato",
                    style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Vibes riproduce i brani completi tramite un account Deezer (va bene anche gratuito). " +
                            (if (logged == false) "Collegane uno e la musica parte subito."
                            else "La sessione è scaduta: ricollegalo. Con email e password si rinnova da sola."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(20.dp))
                BigButton("Collega Deezer", {
                    DeezerAuth.dismiss()
                    app.nav.open(Route.DeezerLogin)
                }, Modifier.fillMaxWidth())
                Text(
                    "Più tardi", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp).bouncy({ DeezerAuth.dismiss() }).padding(8.dp)
                )
            }
        }
    }
}
