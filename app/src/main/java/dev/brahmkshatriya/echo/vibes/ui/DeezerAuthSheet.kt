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
import dev.chrisbanes.haze.hazeEffect

/**
 * Floating "collega Deezer" panel. MANDATORY (no close, no "più tardi", back does nothing, app
 * blurred behind) when the account has no Deezer linked or Deezer keeps refusing the songs: Vibes
 * cannot play anything without it. Optional only for a single refused song on a linked account.
 */
@Composable
fun DeezerAuthHost() {
    val app = LocalApp.current
    val needed by DeezerAuth.needed.collectAsState()
    val failing by DeezerAuth.failingState.collectAsState()
    val logged by app.engine.deezerLoggedIn.collectAsState(initial = null)
    LaunchedEffect(logged) {
        DeezerAuth.loggedIn = logged
        // linked on paper: check with Deezer that the session still works (twice, so a slow
        // credential load at start-up never shows the panel to someone whose account is fine)
        if (logged == true) {
            kotlinx.coroutines.delay(3000)
            if (app.engine.probeDeezer() == false) {
                kotlinx.coroutines.delay(5000)
                if (app.engine.probeDeezer() == false) DeezerAuth.sessionDead()
            }
        }
    }
    val mandatory = logged == false || failing
    val show = logged != null && (needed || mandatory) && app.nav.current !is Route.DeezerLogin
    androidx.activity.compose.BackHandler(enabled = show && mandatory) { }
    fun go(signup: Boolean) {
        DeezerAuth.dismiss()
        DeezerAuth.startWeb = signup
        app.nav.open(Route.DeezerLogin)
    }
    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut()) {
        val haze = LocalHaze.current
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (haze != null) Modifier.hazeEffect(haze) { blurRadius = 28.dp; noiseFactor = 0.05f } else Modifier)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(remember { MutableInteractionSource() }, null) { if (!mandatory) DeezerAuth.dismiss() }
            )
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 22.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(remember { MutableInteractionSource() }, null) { }
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🎧", fontSize = 50.sp)
                Text(
                    if (logged == false) "Ultimo passo: collega Deezer" else "Ricollega Deezer",
                    style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "La musica di Vibes arriva da Deezer. Crea un account gratis o accedi con il tuo: " +
                            "ci vuole un minuto e lo fai qui dentro, poi Vibes si collega da solo." +
                            (if (logged != false) "\nLa tua sessione Deezer è scaduta." else ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(20.dp))
                BigButton("Crea account Deezer gratis", { go(true) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                BigButton("Ho già Deezer: accedi", { go(false) }, Modifier.fillMaxWidth(),
                    container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurface)
                if (!mandatory) Text(
                    "Più tardi", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 10.dp).bouncy({ DeezerAuth.dismiss() }).padding(8.dp)
                )
            }
        }
    }
}
