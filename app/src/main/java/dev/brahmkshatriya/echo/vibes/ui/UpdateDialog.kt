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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.launch

/** Checks for a new version on start and shows the update panel. */
@Composable
fun UpdateHost() {
    val updater = VibesGraph.updater
    // mandatory updates: always check at start and every time the app comes back to the foreground
    var checks by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) { checks++; onPauseOrDispose { } }
    LaunchedEffect(checks) {
        runCatching { updater.check(force = true) }.getOrNull()?.let { new ->
            // also when a panel is already open: a newer version replaces the one shown
            val cur = updater.pending.value
            if (cur == null || new.versionCode > cur.versionCode) updater.show(new)
        }
    }
    val info by updater.pending.collectAsState()
    val phase by updater.phase.collectAsState()
    val scope = rememberCoroutineScope()
    var needPermission by remember { mutableStateOf(false) }
    var wantInstall by remember { mutableStateOf(false) }
    fun installNow(u: dev.brahmkshatriya.echo.vibes.update.UpdateInfo) {
        if (updater.canInstall()) updater.install(u) else { needPermission = true; updater.openInstallPermission() }
    }
    // coming back from the "install unknown apps" screen: continue
    LifecycleResumeEffect(needPermission) {
        if (needPermission && updater.canInstall()) { needPermission = false; info?.let { updater.install(it) } }
        onPauseOrDispose { }
    }
    // one tap: download, then the installer opens by itself
    LaunchedEffect(phase, wantInstall) {
        if (wantInstall && phase is Updater.Phase.Ready) { wantInstall = false; info?.let { installNow(it) } }
    }
    // no way around it: back does nothing while an update is pending
    androidx.activity.compose.BackHandler(enabled = info != null) { }

    AnimatedVisibility(info != null, enter = fadeIn(), exit = fadeOut()) {
        val haze = LocalHaze.current
        Box(Modifier.fillMaxSize()) {
            // the whole app, blurred, behind the card
            Box(
                Modifier.fillMaxSize()
                    .then(if (haze != null) Modifier.hazeEffect(haze) { blurRadius = 28.dp; noiseFactor = 0.05f } else Modifier)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(remember { MutableInteractionSource() }, null) { }
            )
            val u = info ?: return@Box
            val accent = MaterialTheme.colorScheme.primary
            Column(
                Modifier.align(Alignment.Center).statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 16.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.background)
            ) {
                Box(
                    Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.35f), Color.Transparent)))
                        .padding(20.dp)
                ) {
                    val bob = remember { androidx.compose.animation.core.Animatable(40f) }
                    LaunchedEffect(Unit) { bob.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 120f)) }
                    Column {
                        Text("🚀", fontSize = 46.sp, modifier = Modifier.graphicsLayer { translationY = bob.value })
                        Text("Aggiornamento necessario", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(top = 8.dp))
                        Text(u.title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Text("Versione ${u.versionName.ifBlank { u.versionCode.toString() }}" +
                                (if (u.bytes > 0) " · ${"%.1f".format(u.bytes / 1_048_576f)} MB" else "") +
                                " · tu hai la ${updater.installedName}",
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                // the notes scroll; the install button below always stays visible
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cosa c'è di nuovo", style = MaterialTheme.typography.titleMedium)
                    u.notes.forEachIndexed { i, n ->
                        Row(Modifier.enter(i), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(accent))
                            Spacer(Modifier.width(10.dp))
                            Text(n, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
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
                    // on mobile data say how much it costs, so nobody burns 45 MB without knowing
                    if (p !is Updater.Phase.Ready && !updater.onWifi && u.bytes > 0) Text(
                        "Sei su rete mobile: l'aggiornamento usa circa ${u.bytes / 1_000_000} MB di dati. Col Wi-Fi si scarica da solo.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    BigButton(
                        if (p !is Updater.Phase.Ready && !updater.onWifi && u.bytes > 0) "Scarica e installa (${u.bytes / 1_000_000} MB)" else "Installa ora",
                        {
                            if (p is Updater.Phase.Ready) installNow(u)
                            else { wantInstall = true; scope.launch { updater.download(u) } }
                        },
                        Modifier.fillMaxWidth(), loading = p is Updater.Phase.Downloading
                    )
                }
            }
        }
    }
}
