package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.games.Difficulty
import dev.brahmkshatriya.echo.vibes.games.EngineClip
import dev.brahmkshatriya.echo.vibes.games.Matching
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic.GameTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Each layer = a higher low-pass cutoff: first only kick and bass, then more and more instruments. */
private val layers = listOf(
    120f to "Solo bassi e cassa",
    350f to "+ basso e voce profonda",
    1100f to "+ voci e strumenti",
    3500f to "+ chitarre e synth",
    0f to "Canzone completa",
)
private const val LAYER_CLIP_MS = 8000L

@Composable
fun LayersGameScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = remember { EngineClip(app.engine, context, layered = true) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        app.engine.pause(); VibesGraph.studio.stop()
        clip.onStateChange = { playing = it }
        onDispose { clip.release() }
    }
    var round by remember { mutableIntStateOf(0) }
    var layer by remember { mutableIntStateOf(0) }
    var track by remember { mutableStateOf<GameTrack?>(null) }
    var state by remember { mutableStateOf("loading") } // loading | playing | won | lost | done
    var points by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<GameTrack>>(emptyList()) }
    val wrong = remember { mutableStateOf(listOf<String>()) }
    val difficulty = Difficulty.entries[round.coerceIn(0, 3)]
    val color by animateColorAsState(difficulty.color(), label = "lc")

    fun start() {
        state = "loading"; layer = 0; query = ""; suggestions = emptyList(); wrong.value = emptyList()
        scope.launch {
            val t = VibesGraph.picker.pick(difficulty)
            track = t
            if (t == null) { state = "done"; return@launch }
            clip.load(t); clip.setCutoff(layers[0].first)
            state = "playing"
        }
    }
    LaunchedEffect(Unit) { start() }
    LaunchedEffect(query) {
        if (query.length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(220)
        suggestions = runCatching { VibesGraph.deezer.search(query, 6) }.getOrDefault(emptyList())
    }
    fun playLayer() { clip.setCutoff(layers[layer].first); clip.playClip(LAYER_CLIP_MS) }
    fun finish(won: Boolean) {
        state = if (won) "won" else "lost"
        if (won) points += (layers.size - layer) * 100 * (round + 1)
        clip.setCutoff(0f); clip.playFull()
    }
    fun nextRound() { clip.stop(); if (round >= 3) state = "done" else { round++; start() } }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color.copy(alpha = 0.55f), MaterialTheme.colorScheme.background)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                Text("Strati", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).background(color).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(difficulty.label, color = Color.Black, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (state == "done") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("🎚️", fontSize = 80.sp)
                    Text("$points punti", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(20.dp))
                    BigButton("Gioca ancora", { round = 0; points = 0; start() }, Modifier.width(220.dp))
                    Spacer(Modifier.height(10.dp))
                    BigButton("Torna ai giochi", { nav.back() }, Modifier.width(220.dp), container = LocalVibes.current.cardHigh, content = MaterialTheme.colorScheme.onSurface)
                }
                LaunchedEffect(Unit) { runCatching { app.social.submitScore("layers", points) } }
                if (points > 0 && LocalSettings.current.confetti) Confetti()
                return@Column
            }
            Text("Brano ${round + 1} di 4 · $points punti", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
            Spacer(Modifier.height(18.dp))
            // layer stack: each bar lights up as instruments get added
            layers.forEachIndexed { i, (_, label) ->
                val on = i <= layer
                val w by animateFloatAsState(if (on) 1f else 0.35f, spring(dampingRatio = 0.6f), label = "lw$i")
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(38.dp).glass(RoundedCornerShape(10.dp))) {
                        Box(Modifier.fillMaxWidth(w).height(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) color.copy(alpha = 0.35f + 0.13f * i) else Color.Transparent))
                        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i == layer && playing) EqualizerBars(Modifier.padding(start = 8.dp).size(18.dp), color = color)
                }
            }
            Spacer(Modifier.weight(1f))
            when (state) {
                "loading" -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = color) }
                "won", "lost" -> track?.let { t ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state == "won") "Indovinata! 🎉" else "Era…", style = MaterialTheme.typography.titleLarge)
                        Cover(t.cover, Modifier.padding(10.dp).size(150.dp).graphicsLayer { }, RoundedCornerShape(12.dp))
                        Text(t.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        BigButton(if (round >= 3) "Risultato" else "Prossimo brano", { nextRound() }, Modifier.fillMaxWidth(), container = color, content = Color.Black)
                    }
                    if (state == "won" && LocalSettings.current.confetti) Confetti()
                }
                else -> Column {
                    wrong.value.lastOrNull()?.let { Text("❌ $it", color = Color(0xFFFF7A8F), style = MaterialTheme.typography.bodySmall) }
                    suggestions.take(4).forEach { g ->
                        Row(Modifier.fillMaxWidth().clickable {
                            val t = track ?: return@clickable
                            if (Matching.isCorrect(g, t)) finish(true)
                            else {
                                wrong.value = wrong.value + "${g.title} – ${g.artist}"; query = ""
                                if (layer < layers.lastIndex) { layer++; playLayer() } else finish(false)
                            }
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(g.cover, Modifier.size(38.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("${g.title} – ${g.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(CircleShape).background(color.copy(alpha = 0.2f)).bouncy({
                            if (layer < layers.lastIndex) { layer++; playLayer() } else finish(false)
                        }).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(if (layer < layers.lastIndex) "+ Strumenti" else "Mi arrendo", color = color, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(70.dp).clip(CircleShape).background(color).bouncy({ if (playing) clip.stop() else playLayer() }),
                            contentAlignment = Alignment.Center) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(40.dp))
                        }
                    }
                    VibesField(query, { query = it }, "Scrivi il titolo…")
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
