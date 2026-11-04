package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.update.Updater
import kotlinx.coroutines.launch

/** Checks for a new version on start and shows the update panel. */
@Composable
fun UpdateHost() {
    val updater = VibesGraph.updater
    val prefs = LocalSettings.current
    LaunchedEffect(Unit) {
        if (prefs.autoUpdate) runCatching { updater.check() }.getOrNull()?.let { updater.show(it) }
    }
    val info by updater.pending.collectAsState()
    val phase by updater.phase.collectAsState()
    val scope = rememberCoroutineScope()
    var needPermission by remember { mutableStateOf(false) }
    // coming back from the "install unknown apps" screen: continue
    LifecycleResumeEffect(needPermission) {
        if (needPermission && updater.canInstall()) { needPermission = false; info?.let { updater.install(it) } }
        onPauseOrDispose { }
    }

    AnimatedVisibility(info != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(remember { MutableInteractionSource() }, null) { },
            contentAlignment = Alignment.BottomCenter
        ) {
            val u = info ?: return@Box
            val accent = MaterialTheme.colorScheme.primary
            androidx.compose.animation.AnimatedVisibility(
                true, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it }
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // header
                    Box(
                        Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.35f), Color.Transparent)))
                            .padding(20.dp)
                    ) {
                        val bob = remember { androidx.compose.animation.core.Animatable(40f) }
                        LaunchedEffect(Unit) { bob.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 120f)) }
                        Column {
                            Text("🚀", fontSize = 46.sp, modifier = Modifier.graphicsLayer { translationY = bob.value })
                            Text("Nuova versione disponibile", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(top = 8.dp))
                            Text(u.title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                            Text("Versione ${u.versionName.ifBlank { u.versionCode.toString() }}" +
                                    (if (u.size > 0) " · ${"%.1f".format(u.size / 1_048_576f)} MB" else "") +
                                    " · tu hai la ${updater.installedName}",
                                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                        }
                        IconButton(onClick = { updater.close() }, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Rounded.Close, "Chiudi", tint = Color.White)
                        }
                    }
                    // notes
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cosa c'è di nuovo", style = MaterialTheme.typography.titleMedium)
                        u.notes.forEachIndexed { i, n ->
                            Row(Modifier.enter(i), verticalAlignment = Alignment.Top) {
                                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(accent))
                                Spacer(Modifier.width(10.dp))
                                Text(n, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    // progress / actions
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
                        val p = phase
                        if (p is Updater.Phase.Downloading) {
                            val prog by animateFloatAsState(p.progress, label = "dl")
                            LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), color = accent)
                            Text("Scarico… ${(p.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                        }
                        if (p is Updater.Phase.Failed) Text(p.message, color = Color(0xFFFF5C7A), modifier = Modifier.padding(bottom = 8.dp))
                        if (needPermission) Text(
                            "Consenti a Vibes di installare app nella schermata che si è aperta, poi torna qui: l'installazione parte da sola.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        AnimatedContent(p is Updater.Phase.Ready, transitionSpec = { scaleIn(initialScale = 0.9f) + fadeIn() togetherWith fadeOut() }, label = "btn") { ready ->
                            BigButton(
                                if (ready) "Installa ora" else "Scarica ora",
                                {
                                    if (ready) {
                                        if (updater.canInstall()) updater.install(u) else { needPermission = true; updater.openInstallPermission() }
                                    } else scope.launch { updater.download(u) }
                                },
                                Modifier.fillMaxWidth(), loading = p is Updater.Phase.Downloading
                            )
                        }
                        Text("Più tardi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).clip(CircleShape)
                                .clickable { updater.later() }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}
