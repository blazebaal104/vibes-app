package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import android.os.Build
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import dev.brahmkshatriya.echo.vibes.games.GameSource
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Lobby
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.vibes.backend.Supa
import dev.brahmkshatriya.echo.vibes.backend.str
import dev.brahmkshatriya.echo.vibes.games.ClipPlayer
import dev.brahmkshatriya.echo.vibes.games.Difficulty
import dev.brahmkshatriya.echo.vibes.games.Matching
import dev.brahmkshatriya.echo.vibes.games.clipSteps
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic.GameTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

fun Difficulty.color(): Color = when (this) {
    Difficulty.EASY -> GameColors.Easy
    Difficulty.MEDIUM -> GameColors.Medium
    Difficulty.HARD -> GameColors.Hard
    Difficulty.IMPOSSIBLE -> GameColors.Impossible
}

private val trackList = ListSerializer(GameTrack.serializer())

// =====================================================================================  hub

@Composable
fun GamesScreen() {
    val app = LocalApp.current
    val nav = app.nav
    var challengeGame by remember { mutableStateOf<String?>(null) }
    var challengeSource by remember { mutableStateOf(GameSource.Random) }
    var setupFor by remember { mutableStateOf<String?>(null) } // "solo" | "challenge"
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Text("Giochi", style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                Text("Metti alla prova le tue orecchie. Da solo o contro i tuoi amici.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                // PC: the three games side by side (on the phone, one under the other)
                val cards: List<@Composable (Modifier, Boolean) -> Unit> = listOf(
                    { m, st -> GameCard(
                    "Indovina il brano", "Ascolta 0,1 secondi. Poi 1, 2, 5, 10. Riconosci la canzone prima che finiscano i tentativi.",
                    "🎯", listOf(GameColors.Easy, GameColors.Medium, GameColors.Hard, GameColors.Impossible),
                    onSolo = { setupFor = "solo" }, onChallenge = { setupFor = "challenge" }, index = 0, modifier = m, stretch = st
                ) },
                    { m, st -> GameCard(
                    "Chi canta?", "8 brani, 4 artisti tra cui scegliere, 10 secondi a domanda. Più sei veloce, più punti fai.",
                    "🎤", listOf(Color(0xFF7B2FF7), Color(0xFFFF2E97), Color(0xFFFF7A59)),
                    onSolo = { nav.open(Route.BlindTest(null)) }, onChallenge = { challengeGame = "blindtest" }, index = 1, modifier = m, stretch = st
                ) },
                    { m, st -> GameCard(
                    "Strati", "Prima senti solo bassi e cassa, poi a ogni tentativo entrano altri strumenti. Indovina prima che arrivi la canzone intera.",
                    "🎚️", listOf(Color(0xFF00C2A8), Color(0xFF1E6BFF), Color(0xFF7B2FF7)),
                    onSolo = { nav.open(Route.Layers) }, onChallenge = null, index = 2, modifier = m, stretch = st
                ) },
                )
                if (PlatformUI.isDesktop) Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max).padding(horizontal = 8.dp)) {
                    cards.forEach { it(Modifier.weight(1f).fillMaxHeight(), true) }
                } else Column { cards.forEach { it(Modifier.fillMaxWidth(), false) } }
            }
            item { GameLeaderboards() }
        }
    }
    challengeGame?.let { g -> ChallengeDialog(g, if (g == "guess") challengeSource else GameSource.Random) { challengeGame = null } }
    setupFor?.let { mode ->
        GameSetupSheet(
            onDismiss = { setupFor = null },
            action = if (mode == "solo") "Gioca" else "Scegli chi sfidare",
        ) { src ->
            setupFor = null
            if (mode == "solo") nav.open(Route.Guess(null, src)) else { challengeSource = src; challengeGame = "guess" }
        }
    }
}

/** "Con quali brani?": random, one artist, or one genre. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GameSetupSheet(onDismiss: () -> Unit, action: String, onStart: (GameSource) -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf(GameSource.Random) }
    var query by remember { mutableStateOf("") }
    var artists by remember { mutableStateOf<List<dev.brahmkshatriya.echo.vibes.music.DeezerPublic.ArtistInfo>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) { artists = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        // the most famous first (the one you almost always mean), the small homonyms below
        artists = runCatching { VibesGraph.deezer.searchArtists(query, 20) }.getOrDefault(emptyList())
            .sortedByDescending { it.fans }
    }
    val accent = when (tab) { 1 -> Color(0xFFFF2E97); 2 -> Color(0xFF35D0F5); else -> GameColors.Easy }
    VibesSheet(onDismiss, LocalVibes.current.card) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
            Text("Con quali brani giochi?", style = MaterialTheme.typography.headlineSmall)
            Text("La difficoltà si adatta: le hit sono facili, i pezzi meno noti difficili.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            // segmented selector with a sliding highlight
            androidx.compose.foundation.layout.BoxWithConstraints(
                Modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(LocalVibes.current.cardHigh)
            ) {
                val w = maxWidth / 3
                val x by androidx.compose.animation.core.animateDpAsState(w * tab, spring(dampingRatio = 0.7f, stiffness = 500f), label = "seg")
                val c by animateColorAsState(accent, label = "segc")
                Box(Modifier.offset(x = x).width(w).fillMaxHeight().padding(4.dp).clip(CircleShape).background(c))
                Row(Modifier.fillMaxSize()) {
                    listOf("🎲 Casuale", "🎤 Artista", "🎸 Genere").forEachIndexed { i, l ->
                        Box(Modifier.weight(1f).fillMaxHeight().bouncy({
                            tab = i; warn = null
                            if (i == 0) source = GameSource.Random
                        }), contentAlignment = Alignment.Center) {
                            Text(l, style = MaterialTheme.typography.labelLarge,
                                color = if (tab == i) Color(0xFF111111) else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            androidx.compose.animation.AnimatedContent(tab, label = "setup") { t ->
                when (t) {
                    0 -> Column(Modifier.fillMaxWidth().height(300.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("🎲", fontSize = 56.sp)
                        Text("Brani scelti per te", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                        Text("Un mix tra quello che ascolti tu e le hit del momento.", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    1 -> Column(Modifier.fillMaxWidth().height(300.dp)) {
                        SearchField(query, { query = it }, "Cerca un artista…")
                        val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                        LazyColumn(Modifier.padding(top = 8.dp)) {
                            artists.firstOrNull()?.let { top ->
                                item(key = "top") {
                                    val sel = source.kind == GameSource.ARTIST && source.id == top.id
                                    // the most famous: big, on top
                                    Row(
                                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(18.dp))
                                            .background(if (sel) accent.copy(alpha = 0.28f) else LocalVibes.current.cardHigh)
                                            .border(if (sel) 2.dp else 0.dp, accent, RoundedCornerShape(18.dp))
                                            .bouncy({ kb?.hide(); source = GameSource(GameSource.ARTIST, top.id, top.name, top.picture); warn = null })
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Cover(top.picture, Modifier.size(84.dp), CircleShape)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(top.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text("${"%,d".format(top.fans).replace(',', '.')} fan", style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = accent, modifier = Modifier.size(30.dp))
                                    }
                                }
                            }
                            items(artists.drop(1), key = { it.id }) { a ->
                                val sel = source.kind == GameSource.ARTIST && source.id == a.id
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .background(if (sel) accent.copy(alpha = 0.22f) else Color.Transparent)
                                        .bouncy({ kb?.hide(); source = GameSource(GameSource.ARTIST, a.id, a.name, a.picture); warn = null })
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Cover(a.picture, Modifier.size(42.dp), CircleShape)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${"%,d".format(a.fans).replace(',', '.')} fan", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = accent)
                                }
                            }
                        }
                    }
                    else -> androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dev.brahmkshatriya.echo.vibes.music.Genres.all.forEach { g ->
                            val sel = source.kind == GameSource.GENRE && source.name == g.name
                            val sc by animateFloatAsState(if (sel) 1.06f else 1f, spring(dampingRatio = 0.4f), label = "gs")
                            Box(
                                Modifier.scale(sc).clip(RoundedCornerShape(14.dp))
                                    .background(Brush.linearGradient(listOf(Color(g.colors.first), Color(g.colors.second))))
                                    .border(if (sel) 2.5.dp else 0.dp, Color.White, RoundedCornerShape(14.dp))
                                    .bouncy({ source = GameSource(GameSource.GENRE, 0, g.name); warn = null })
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) { Text(g.name, style = MaterialTheme.typography.titleSmall, color = Color.White) }
                        }
                    }
                }
            }
            warn?.let { Text(it, color = Color(0xFFFF7A8F), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(12.dp))
            val ready = tab == 0 || (tab == 1 && source.kind == GameSource.ARTIST) || (tab == 2 && source.kind == GameSource.GENRE)
            BigButton(
                if (ready && source.kind != GameSource.RANDOM) "$action · ${source.label}" else action,
                {
                    checking = true
                    scope.launch {
                        val n = VibesGraph.picker.count(source)
                        checking = false
                        if (n < 4) warn = "Non ci sono abbastanza brani con anteprima per ${source.label}: prova con un altro."
                        else onStart(source)
                    }
                },
                Modifier.fillMaxWidth(), enabled = ready, loading = checking,
                container = accent, content = Color(0xFF111111)
            )
        }
    }
}

@Composable
private fun GameCard(title: String, text: String, emoji: String, colors: List<Color>, onSolo: () -> Unit, onChallenge: (() -> Unit)?, index: Int,
                     modifier: Modifier = Modifier.fillMaxWidth(), stretch: Boolean = false) {
    Column(
        modifier.padding(if (stretch) 8.dp else 16.dp).enter(index).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(colors.map { it.copy(alpha = 0.85f) }))
            .padding(20.dp)
    ) {
        Text(emoji, fontSize = 44.sp)
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(top = 6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        if (stretch) Spacer(Modifier.weight(1f))
        // narrow cards (PC, side by side): buttons one under the other so the text never wraps
        if (stretch) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Gioca da solo", onSolo, Modifier.fillMaxWidth(), container = Color.White, content = Color(0xFF111111))
            if (onChallenge != null) BigButton("Sfida un amico", onChallenge, Modifier.fillMaxWidth(), container = Color.Black.copy(alpha = 0.35f), content = Color.White)
            else Spacer(Modifier.height(52.dp))
        } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Gioca da solo", onSolo, Modifier.weight(1f), container = Color.White, content = Color(0xFF111111))
            if (onChallenge != null) BigButton("Sfida un amico", onChallenge, Modifier.weight(1f), container = Color.Black.copy(alpha = 0.35f), content = Color.White)
        }
    }
}

@Composable
private fun ChallengeDialog(game: String, source: GameSource, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val friends by app.social.friends.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chi vuoi sfidare?") },
        text = {
            if (friends.isEmpty()) Text("Aggiungi prima qualche amico dalla scheda Amici.")
            else LazyColumn(Modifier.height(320.dp)) {
                items(friends, key = { it.id }) { f ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                        busy = f.id
                        scope.launch {
                            runCatching { createChallenge(game, f, source) }
                                .onSuccess { lobby ->
                                    onDismiss()
                                    app.nav.open(if (game == "blindtest") Route.BlindTest(lobby.id) else Route.Guess(lobby.id))
                                }
                                .onFailure { app.nav.toast(it.message ?: "Errore"); busy = null }
                        }
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(f, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (busy == f.id) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

private suspend fun createChallenge(game: String, friend: Profile, source: GameSource = GameSource.Random): Lobby {
    val social = VibesGraph.social
    val picker = VibesGraph.picker
    val state = if (game == "guess") {
        val tracks = Difficulty.entries.mapNotNull { picker.pick(it, source) }
        if (tracks.size < 4) throw IllegalStateException("Non riesco a preparare i brani, controlla la connessione")
        buildJsonObject { put("tracks", Supa.json.encodeToJsonElement(trackList, tracks)) }
    } else {
        val level = dev.brahmkshatriya.echo.vibes.games.BlindLevel.recommended()
        val (tracks, options) = dev.brahmkshatriya.echo.vibes.games.BlindQuiz.build(picker, level)
        if (tracks.size < 8) throw IllegalStateException("Non riesco a preparare i brani, controlla la connessione")
        buildJsonObject {
            put("level", level.name)
            put("tracks", Supa.json.encodeToJsonElement(trackList, tracks))
            put("options", Supa.json.encodeToJsonElement(ListSerializer(ListSerializer(String.serializer())), options))
        }
    }
    val lobby = social.createLobby(game, friend.id, state)
    social.send(friend.id, "challenge", "", buildJsonObject { put("lobby_id", lobby.id); put("game", game) })
    return lobby
}

// =====================================================================================  shared bits

@Composable
private fun GameTopBar(title: String, color: Color, right: String? = null) {
    val nav = LocalApp.current.nav
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Esci") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (right != null) Box(Modifier.padding(end = 12.dp).clip(CircleShape).background(color).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(right, color = Color(0xFF111111), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/** Lobby helpers: live lobby state for multiplayer screens. */
@Composable
private fun rememberLobby(lobbyId: String?): Lobby? {
    val app = LocalApp.current
    var lobby by remember { mutableStateOf<Lobby?>(null) }
    if (lobbyId == null) return null
    LaunchedEffect(lobbyId) {
        lobby = runCatching { app.social.lobby(lobbyId) }.getOrNull()
        val me = app.social.uid
        val l = lobby
        if (l != null && l.guestId == me && l.status == "waiting") {
            lobby = runCatching { app.social.updateLobby(l.id, "playing", buildJsonObject { put("guest_joined", true) }) }.getOrDefault(l)
        }
    }
    LaunchedEffect(lobbyId) {
        app.social.lobbyChanges.collect { if (it.id == lobbyId) lobby = it }
    }
    // fallback polling in case realtime is slow
    LaunchedEffect(lobbyId) {
        while (true) { delay(4000); runCatching { app.social.lobby(lobbyId) }.getOrNull()?.let { lobby = it } }
    }
    return lobby
}

private fun Lobby.tracks(): List<GameTrack> =
    state["tracks"]?.let { runCatching { Supa.json.decodeFromJsonElement(trackList, it) }.getOrNull() }.orEmpty()

private fun Lobby.roundWinners(): Map<Int, Pair<String, String>> =
    (state["rounds"] as? JsonObject)?.mapNotNull { (k, v) ->
        val o = v.jsonObject
        val idx = k.removePrefix("r").toIntOrNull() ?: return@mapNotNull null
        idx to ((o.str("winner") ?: "") to (o.str("reason") ?: ""))
    }?.toMap().orEmpty()

@Composable
private fun WaitingFor(name: String, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "wait")
        val s by t.animateFloat(0.9f, 1.1f, androidx.compose.animation.core.infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "s")
        Text("⏳", fontSize = 64.sp, modifier = Modifier.scale(s))
        Text("In attesa di $name…", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Text("Gli è arrivata la notifica della sfida. Appena entra si parte!", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        BigButton("Esci", onCancel, Modifier.width(160.dp), container = LocalVibes.current.cardHigh, content = MaterialTheme.colorScheme.onSurface)
    }
}

// =====================================================================================  Indovina il brano

private enum class RoundState { LOADING, PLAYING, WON, LOST, DONE }

@Composable
fun GuessGameScreen(lobbyId: String?, source: GameSource? = null) {
    val src = source ?: GameSource.Random
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = app.social.uid
    val lobby = rememberLobby(lobbyId)
    val multi = lobbyId != null
    val friends by app.social.friends.collectAsState()

    val clip = remember { dev.brahmkshatriya.echo.vibes.games.EngineClip(app.engine, context) }
    var clipPlaying by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        app.engine.pause()
        clip.onStateChange = { clipPlaying = it }
        onDispose { clip.release() }
    }

    var round by remember { mutableIntStateOf(0) }           // 0..3 = easy..impossible
    var track by remember { mutableStateOf<GameTrack?>(null) }
    var step by remember { mutableIntStateOf(0) }            // index in clipSteps
    val attempts = remember { mutableStateListOf<String>() } // "⏭ Saltato" / "❌ <guess>"
    var state by remember { mutableStateOf(RoundState.LOADING) }
    var wins by remember { mutableIntStateOf(0) }
    var points by remember { mutableIntStateOf(0) }
    var revealNote by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<GameTrack>>(emptyList()) }

    val difficulty = Difficulty.entries[round.coerceIn(0, 3)]
    val color by animateColorAsState(difficulty.color(), label = "diff")
    val otherId = lobby?.let { if (it.hostId == me) it.guestId else it.hostId }
    val otherName = friends.firstOrNull { it.id == otherId }?.name ?: "il tuo amico"

    fun startRound(t: GameTrack?) {
        track = t; step = 0; attempts.clear(); query = ""; suggestions = emptyList()
        if (t == null) { state = RoundState.DONE; return }
        clip.load(t)
        state = RoundState.PLAYING
    }

    fun nextRoundLocal() {
        clip.stop()
        if (round >= 3) { state = RoundState.DONE; return }
        round++
        state = RoundState.LOADING
        scope.launch { startRound(VibesGraph.picker.pick(Difficulty.entries[round], src)) }
    }

    fun finishRound(won: Boolean, note: String) {
        if (state != RoundState.PLAYING) return
        state = if (won) RoundState.WON else RoundState.LOST
        if (won) { wins++; points += (clipSteps.size - step) * 100 * (round + 1) }
        revealNote = note
        // the song keeps playing "in your head"; when it ends we move on by ourselves
        clip.playFull { if (!multi && (state == RoundState.WON || state == RoundState.LOST)) nextRoundLocal() }
    }

    // ---- single player start
    LaunchedEffect(Unit) {
        if (!multi) startRound(VibesGraph.picker.pick(Difficulty.EASY, src))
    }

    // ---- multiplayer: drive rounds from the lobby
    val winners = lobby?.roundWinners().orEmpty()
    LaunchedEffect(lobby?.state, lobby?.status) {
        val l = lobby ?: return@LaunchedEffect
        val tracks = l.tracks()
        if (tracks.isEmpty() || l.status == "waiting") return@LaunchedEffect
        val claimed = winners.size
        winners[round]?.let { (winner, reason) ->
            if (state == RoundState.PLAYING) {
                val iWon = winner == me
                val note = when {
                    iWon && reason == "guess" -> "Hai indovinato per primo!"
                    iWon && reason == "giveup" -> "$otherName si è arreso"
                    iWon -> "$otherName ha finito i tentativi"
                    reason == "guess" -> "$otherName l'ha indovinata prima di te"
                    reason == "giveup" -> "Ti sei arreso"
                    else -> "Hai finito i tentativi"
                }
                state = if (iWon) RoundState.WON else RoundState.LOST
                if (iWon) { wins++; points += (clipSteps.size - step) * 100 * (round + 1) }
                revealNote = note
                clip.playFull()
            }
        }
        if (state == RoundState.LOADING && track == null) {
            round = claimed.coerceAtMost(3)
            if (claimed >= 4) state = RoundState.DONE else startRound(tracks[round])
        }
    }

    fun claim(winner: String?, reason: String) {
        val l = lobby ?: return
        if (winner == null) return
        scope.launch { runCatching { app.social.claimRound(l.id, round, winner, reason) } }
    }

    /** Wrong guess or skip: unlock a longer clip, or lose the round after the 10 s one. */
    fun advance() {
        if (step >= clipSteps.lastIndex) {
            clip.stop()
            if (multi) claim(otherId, "failed") else finishRound(false, "Tentativi finiti")
        } else step++
    }

    fun skipOrFail(label: String) { attempts += label; advance() }

    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun hideKeyboard() { keyboard?.hide(); focus.clearFocus(force = true) }

    // skipped while the clip plays: it goes on to the new mark instead of stopping on the old one
    LaunchedEffect(step) { clip.extendTo(clipSteps[step]) }
    // guessed / lost: the keyboard goes away at once, the reveal is not covered
    LaunchedEffect(state) { if (state != RoundState.PLAYING) hideKeyboard() }

    fun onGuess(g: GameTrack) {
        val t = track ?: return
        hideKeyboard()
        query = ""; suggestions = emptyList()
        if (Matching.isCorrect(g, t)) {
            clip.stop()
            if (multi) claim(me, "guess") else finishRound(true, "Corretto!")
        } else {
            attempts += "❌ ${g.title} – ${g.artist}"
            advance()
        }
    }

    LaunchedEffect(query) {
        if (query.length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(250)
        // playing with an artist: only their songs (main or feat); typos forgiven
        suggestions = VibesGraph.picker.suggest(query, src)
    }

    // advance to the next multiplayer round a few seconds after the reveal
    LaunchedEffect(state, winners.size) {
        if (!multi || (state != RoundState.WON && state != RoundState.LOST)) return@LaunchedEffect
        delay(6000)
        clip.stop()
        val l = lobby ?: return@LaunchedEffect
        if (winners.size >= 4) {
            state = RoundState.DONE
            if (l.hostId == me) runCatching { app.social.updateLobby(l.id, "finished", JsonObject(emptyMap())) }
        } else {
            round = winners.size.coerceAtMost(3)
            startRound(l.tracks().getOrNull(round))
        }
    }

    // ------------------------------------------------------------------ UI
    if (multi && lobby != null && lobby.status == "waiting" && lobby.hostId == me) {
        GradientBackground(tint = color) {
            Column { GameTopBar("Indovina il brano", color); WaitingFor(otherName) { nav.back() } }
        }
        return
    }

    val blurred = state == RoundState.WON || state == RoundState.LOST
    Box(Modifier.fillMaxSize().screenBg()) {
        Box(Modifier.fillMaxSize().then(if (blurred && Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier)) {
            GradientBackground(tint = color) {
                Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                    GameTopBar(
                        if (multi) "Sfida con $otherName" else "Indovina il brano", color,
                        if (state == RoundState.DONE) null else difficulty.label
                    )
                    if (multi) Text(
                        "Tu $wins · ${winners.count { it.value.first == otherId }} $otherName",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    when (state) {
                        RoundState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.material3.CircularProgressIndicator(color = color)
                                Text("Scelgo un brano per te…", modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                        RoundState.DONE -> GameOver(
                            emoji = if (wins >= 3) "🏆" else if (wins >= 1) "👏" else "🙈",
                            title = if (multi) when {
                                wins > winners.count { it.value.first == otherId } -> "Hai vinto!"
                                wins < winners.count { it.value.first == otherId } -> "Ha vinto $otherName"
                                else -> "Pareggio!"
                            } else "$wins su 4 indovinati",
                            subtitle = "Facile, medio, difficile, impossibile: ogni livello conta.",
                            submit = "guess" to points,
                            onAgain = if (!multi) ({ wins = 0; points = 0; round = 0; state = RoundState.LOADING; scope.launch { startRound(VibesGraph.picker.pick(Difficulty.EASY, src)) } }) else null,
                        )
                        else -> GuessBoard(
                            difficulty = difficulty, color = color, step = step, attempts = attempts,
                            playing = clipPlaying, position = { clip.position() }, roundLabel = "Brano ${round + 1} di 4" + if (src.kind != GameSource.RANDOM) " · ${src.label}" else "",
                            // play / stop: pressing while it plays stops it (it does not restart)
                            onPlay = { if (clipPlaying) clip.stop() else clip.playClip(clipSteps[step]) },
                            query = query, onQuery = { query = it }, suggestions = suggestions,
                            onPick = { onGuess(it) },
                            onSkip = { skipOrFail("⏭ Saltato") },
                            onGiveUp = if (multi) ({ clip.stop(); claim(otherId, "giveup") }) else null,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(blurred, enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut()) {
            track?.let { t ->
                Reveal(t, state == RoundState.WON, revealNote, color,
                    onNext = { if (multi) clip.stop() else nextRoundLocal() })
            }
        }
        if (state == RoundState.WON && LocalSettings.current.confetti) Confetti()
    }
}

@Composable
private fun GuessBoard(
    difficulty: Difficulty, color: Color, step: Int, attempts: List<String>, playing: Boolean, roundLabel: String,
    position: () -> Long = { 0L },
    onPlay: () -> Unit, query: String, onQuery: (String) -> Unit, suggestions: List<GameTrack>,
    onPick: (GameTrack) -> Unit, onSkip: () -> Unit, onGiveUp: (() -> Unit)?,
) {
    // with the keyboard open there is no room for everything: the attempts collapse into dots so the
    // text field, the suggestions and the play button stay visible right above the keyboard
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val ime = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text(roundLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        Spacer(Modifier.height(if (ime) 8.dp else 14.dp))
        AnimatedVisibility(ime) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                (0 until clipSteps.size).forEach { i ->
                    val a = attempts.getOrNull(i)
                    Box(Modifier.padding(horizontal = 4.dp).size(if (i == attempts.size) 14.dp else 10.dp).clip(CircleShape).background(
                        when {
                            a == null && i == attempts.size -> color
                            a == null -> LocalVibes.current.cardHigh
                            a.startsWith("❌") -> Color(0xFFFF7A8F)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }))
                }
            }
        }
        // attempts
        AnimatedVisibility(!ime) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until clipSteps.size).forEach { i ->
                val a = attempts.getOrNull(i)
                Box(
                    Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (a != null) LocalVibes.current.cardHigh else LocalVibes.current.card)
                        .border(1.dp, if (i == attempts.size) color.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(a ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (a?.startsWith("❌") == true) Color(0xFFFF7A8F) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        // suggestions: ALL of them, in a scrollable list right above the controls (or right above the
        // field while the keyboard is open). The keyboard goes down with the phone's back/down arrow or
        // when you scroll the list, so every result can be reached.
        val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) kb?.hide() }
        LaunchedEffect(suggestions) { listState.scrollToItem(0) }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
            if (suggestions.isNotEmpty()) LazyColumn(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LocalVibes.current.cardHigh),
                state = listState,
            ) {
                items(suggestions, key = { it.id }) { g ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(g) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(g.cover, Modifier.size(38.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(g.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(g.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
        // with the keyboard open the controls step aside: the results sit right on the field
        AnimatedVisibility(!ime || suggestions.isEmpty()) { Column {
        // timeline 0..10 s with markers at each unlock
        ClipTimeline(step, color, playing, position)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val last = step >= clipSteps.lastIndex
                Row(
                    Modifier.clip(CircleShape).background(if (last) Color(0xFFFF3B5C).copy(alpha = 0.22f) else color.copy(alpha = 0.18f))
                        .border(1.dp, if (last) Color(0xFFFF3B5C) else color.copy(alpha = 0.6f), CircleShape)
                        .bouncy(onSkip).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (last) "Mi arrendo" else "Salta +${fmtStep(clipSteps.getOrNull(step + 1)?.minus(clipSteps[step]))}",
                        style = MaterialTheme.typography.labelLarge, color = if (last) Color(0xFFFF7A8F) else color)
                }
            }
            val s by animateFloatAsState(if (playing) 0.92f else 1f, spring(dampingRatio = 0.4f), label = "pl")
            Box(
                Modifier.size(78.dp).scale(s).clip(CircleShape).background(color).bouncy(onPlay),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Stop" else "Ascolta",
                    tint = Color(0xFF111111), modifier = Modifier.size(46.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text(fmtStep(clipSteps[step]), style = MaterialTheme.typography.titleLarge, color = color)
            }
        }
        Spacer(Modifier.height(16.dp))
        } }
        Row(
            Modifier.fillMaxWidth().height(52.dp).glass(RoundedCornerShape(12.dp))
                .border(1.5.dp, color, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = color)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Scrivi il titolo…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(query, onQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(color), modifier = Modifier.fillMaxWidth(),
                    // the keyboard's "done" key just closes it, to look through the results
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { kb?.hide() }))
            }
            if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Cancella", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(CircleShape).clickable { onQuery("") }.padding(6.dp))
        }
        if (onGiveUp != null) {
            Spacer(Modifier.height(10.dp))
            BigButton("Non lo so", onGiveUp, Modifier.fillMaxWidth(), container = Color(0xFFFF3B5C), content = Color.White)
        }
        Spacer(Modifier.height(14.dp))
    }
}

private fun fmtStep(ms: Long?): String = when {
    ms == null -> "—"
    ms < 1000 -> "0,${ms / 100}s"
    else -> "${ms / 1000}s"
}

@Composable
private fun ClipTimeline(step: Int, color: Color, playing: Boolean, position: () -> Long = { 0L }) {
    val max = clipSteps.last().toFloat()
    // unlocked region grows with a springy animation when you skip
    val unlocked by animateFloatAsState(clipSteps[step] / max, spring(dampingRatio = 0.55f, stiffness = 220f), label = "unl")
    val limit by androidx.compose.runtime.rememberUpdatedState(clipSteps[step] / max)
    // Smooth like YouTube: the player reports its position in steps, so between two reports the bar
    // keeps moving with the clock (one value per frame) and never goes back. When the clip stops,
    // the bar stays exactly where the sound stopped.
    var progressValue by remember { mutableStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) { progressValue = (position() / max).coerceIn(0f, limit); return@LaunchedEffect }
        progressValue = 0f
        var lastPos = -1L; var lastAt = 0L
        while (true) {
            val frame = androidx.compose.runtime.withFrameMillis { it }
            val p = position()
            if (p != lastPos) { lastPos = p; lastAt = frame }
            val est = (lastPos + (frame - lastAt).coerceAtMost(250)) / max
            progressValue = maxOf(progressValue, est.coerceIn(0f, limit))
        }
    }
    val progress = object { val value get() = progressValue }
    // dark, desaturated version of the difficulty colour for the "not played yet" part
    val dim = androidx.compose.ui.graphics.lerp(color, Color(0xFF0E0E12), 0.62f)
    val locked = LocalVibes.current.cardHigh
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    // 15 dp bar with a round playhead, drawn in the draw phase only (no recomposition per frame)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val h = 15.dp.toPx()
        val top = (size.height - h) / 2
        val r = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
        val bar = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, top, size.width, top + h, r))
        }
        clipPath(bar) {
            drawRect(locked, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, h))
            drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width * unlocked, h))
            val pw = size.width * progress.value
            if (pw > 0f) drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(pw, h))
            // small gaps at each unlock point
            clipSteps.dropLast(1).forEachIndexed { i, ms ->
                val x = size.width * (ms / max)
                drawRect(
                    if (i < step) Color.Black.copy(alpha = 0.45f) else tick.copy(alpha = 0.35f),
                    topLeft = androidx.compose.ui.geometry.Offset(x - 1f, top),
                    size = androidx.compose.ui.geometry.Size(2f, h)
                )
            }
        }
        // playhead
        if (playing || progress.value > 0f) {
            val x = (size.width * progress.value).coerceIn(0f, size.width)
            drawCircle(if (playing) Color.White else color, radius = 12.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, size.height / 2))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("0s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("Sbloccati ${fmtStep(clipSteps[step])}", style = MaterialTheme.typography.labelSmall, color = color)
        Spacer(Modifier.weight(1f))
        Text("10s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Reveal(t: GameTrack, won: Boolean, note: String, color: Color, onNext: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            Text(if (won) "Corretto! 🎉" else "Peccato!", style = MaterialTheme.typography.displaySmall, color = if (won) color else Color.White)
            Text(note, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(22.dp))
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "p")
            val s by pulse.animateFloat(1f, 1.04f, androidx.compose.animation.core.infiniteRepeatable(tween(520), androidx.compose.animation.core.RepeatMode.Reverse), label = "s")
            Cover(t.cover, Modifier.size(240.dp).scale(s), RoundedCornerShape(16.dp))
            Spacer(Modifier.height(18.dp))
            Text(t.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            Text(t.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(24.dp))
            BigButton("Avanti ⏭", onNext, Modifier.width(200.dp), container = Color.White, content = Color(0xFF111111))
        }
    }
}

@Composable
private fun GameOver(emoji: String, title: String, subtitle: String, onAgain: (() -> Unit)?, submit: Pair<String, Int>? = null) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    var board by remember { mutableStateOf<List<Pair<Profile, Int>>?>(null) }
    LaunchedEffect(submit) {
        val (game, sc) = submit ?: return@LaunchedEffect
        runCatching { app.social.submitScore(game, sc) }
        board = runCatching { app.social.leaderboard(game) }.getOrNull()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(emoji, fontSize = 80.sp)
        Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        submit?.let { (game, sc) ->
            Text("$sc punti", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            board?.takeIf { it.isNotEmpty() }?.let { Leaderboard(it, app.social.uid, Modifier.padding(vertical = 14.dp)) }
            Pill("Condividi punteggio", false, {
                shareToApps(context, "🎧 Ho fatto $sc punti a ${if (game == "guess") "Indovina il brano" else "Chi canta?"} su Vibes. Riesci a battermi?")
            })
            Spacer(Modifier.height(14.dp))
        }
        if (onAgain != null) BigButton("Gioca ancora", onAgain, Modifier.width(220.dp))
        Spacer(Modifier.height(10.dp))
        BigButton("Torna ai giochi", { nav.back() }, Modifier.width(220.dp), container = LocalVibes.current.cardHigh, content = MaterialTheme.colorScheme.onSurface)
    }
}

// =====================================================================================  Chi canta?

@Composable
private fun BlindLevelPicker(
    selected: dev.brahmkshatriya.echo.vibes.games.BlindLevel,
    onSelect: (dev.brahmkshatriya.echo.vibes.games.BlindLevel) -> Unit,
    onPlay: () -> Unit,
) {
    val suggested = remember { dev.brahmkshatriya.echo.vibes.games.BlindLevel.recommended() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Text("Scegli il livello", style = MaterialTheme.typography.headlineSmall)
        Text("Quello consigliato dipende da quanta musica hai ascoltato questa settimana.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        dev.brahmkshatriya.echo.vibes.games.BlindLevel.entries.forEach { lv ->
            val on = lv == selected
            Row(
                Modifier.padding(vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else LocalVibes.current.cardHigh)
                    .bouncy({ onSelect(lv) }).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(lv.emoji, fontSize = 28.sp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(lv.label, style = MaterialTheme.typography.titleMedium)
                        if (lv == suggested) Text("  · consigliato", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("${lv.hint} · ${lv.seconds} s a domanda", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (on) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(18.dp))
        BigButton("Gioca", onPlay, Modifier.fillMaxWidth())
    }
}

@Composable
fun BlindTestScreen(lobbyId: String?) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = app.social.uid
    val lobby = rememberLobby(lobbyId)
    val multi = lobbyId != null
    val friends by app.social.friends.collectAsState()
    val clip = remember { ClipPlayer(context) }
    DisposableEffect(Unit) { app.engine.pause(); onDispose { clip.release() } }

    var tracks by remember { mutableStateOf<List<GameTrack>>(emptyList()) }
    var options by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var answered by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    val timer = remember { Animatable(1f) }
    val otherId = lobby?.let { if (it.hostId == me) it.guestId else it.hostId }
    val otherName = friends.firstOrNull { it.id == otherId }?.name ?: "il tuo amico"
    val otherScore = lobby?.state?.str("s_$otherId")?.toIntOrNull() ?: 0
    val otherDone = lobby?.state?.str("done_$otherId") == "true"
    // level: chosen before a solo game (the suggested one is preselected); a challenge uses the one it was made with
    var level by remember { mutableStateOf<dev.brahmkshatriya.echo.vibes.games.BlindLevel?>(null) }
    var choosing by remember { mutableStateOf(dev.brahmkshatriya.echo.vibes.games.BlindLevel.recommended()) }
    val playLevel = if (multi) dev.brahmkshatriya.echo.vibes.games.BlindLevel.of(lobby?.state?.str("level")) else level
    val seconds = (playLevel ?: dev.brahmkshatriya.echo.vibes.games.BlindLevel.EASY).seconds

    // load questions
    LaunchedEffect(lobby?.id, multi, level) {
        if (tracks.isNotEmpty()) return@LaunchedEffect
        if (!multi && level == null) return@LaunchedEffect
        if (multi) {
            val l = lobby ?: return@LaunchedEffect
            tracks = l.tracks()
            options = l.state["options"]?.let {
                runCatching { Supa.json.decodeFromJsonElement(ListSerializer(ListSerializer(String.serializer())), it) }.getOrNull()
            }.orEmpty()
        } else {
            val (t, o) = dev.brahmkshatriya.echo.vibes.games.BlindQuiz.build(VibesGraph.picker, level!!)
            options = o
            tracks = t
        }
    }

    fun report(final: Boolean) {
        val l = lobby ?: return
        scope.launch {
            runCatching {
                app.social.updateLobby(l.id, null, buildJsonObject {
                    put("s_$me", score.toString()); if (final) put("done_$me", "true")
                })
            }
        }
    }

    // each question: play the clip, run a 10 s timer
    LaunchedEffect(index, tracks.size) {
        val t = tracks.getOrNull(index) ?: return@LaunchedEffect
        answered = null
        clip.load(t.preview)
        clip.playClip(seconds * 1000L)
        timer.snapTo(1f)
        timer.animateTo(0f, tween(seconds * 1000, easing = LinearEasing))
        if (answered == null) answered = ""   // time's up
    }
    LaunchedEffect(answered) {
        if (answered == null) return@LaunchedEffect
        delay(1600)
        clip.stop()
        if (index >= tracks.lastIndex) { finished = true; report(true) } else index++
    }

    if (multi && lobby != null && lobby.status == "waiting" && lobby.hostId == me) {
        GradientBackground { Column { GameTopBar("Chi canta?", MaterialTheme.colorScheme.primary); WaitingFor(otherName) { nav.back() } } }
        return
    }

    GradientBackground(tint = Color(0xFF7B2FF7)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            GameTopBar("Chi canta?", GameColors.Medium, "$score pt")
            if (multi) Text("$otherName: $otherScore pt" + if (otherDone) " (finito)" else "",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            val t = tracks.getOrNull(index)
            when {
                !multi && level == null -> BlindLevelPicker(choosing, { choosing = it }) { level = choosing }
                finished -> {
                    val title = if (!multi) "$score punti" else when {
                        !otherDone -> "Aspettiamo $otherName…"
                        score > otherScore -> "Hai vinto! 🏆"
                        score < otherScore -> "Ha vinto $otherName"
                        else -> "Pareggio!"
                    }
                    GameOver(if (!multi || score >= otherScore) "🏆" else "🎧", title,
                        if (multi) "Tu $score · $otherName $otherScore" else "Quanti artisti hai riconosciuto in 8 brani.",
                        if (!multi) ({ score = 0; index = 0; finished = false; tracks = emptyList(); level = null }) else null,
                        submit = if (!multi || otherDone) "blind" to score * 100 else null)
                    if (score > 0 && (!multi || (otherDone && score > otherScore)) && LocalSettings.current.confetti) Confetti()
                }
                t == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                else -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Text("Domanda ${index + 1} di ${tracks.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(LocalVibes.current.cardHigh)) {
                        Box(Modifier.fillMaxWidth(timer.value).fillMaxHeight().clip(CircleShape)
                            .background(Brush.horizontalGradient(listOf(GameColors.Impossible, GameColors.Medium, GameColors.Easy))))
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.align(Alignment.CenterHorizontally)) {
                        val revealed = answered != null
                        if (revealed) Cover(t.cover, Modifier.size(200.dp), RoundedCornerShape(16.dp))
                        else Box(Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)).background(LocalVibes.current.cardHigh), contentAlignment = Alignment.Center) {
                            EqualizerBars(Modifier.size(70.dp))
                        }
                    }
                    if (answered != null) Text("${t.title} – ${t.artist}", style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    Spacer(Modifier.weight(1f))
                    options.getOrNull(index).orEmpty().forEach { opt ->
                        val isRight = opt == t.artist
                        val bg = when {
                            answered == null -> LocalVibes.current.cardHigh
                            isRight -> GameColors.Easy
                            opt == answered -> GameColors.Impossible
                            else -> LocalVibes.current.card
                        }
                        Box(
                            Modifier.padding(vertical = 5.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                                .bouncy({
                                    if (answered != null) return@bouncy
                                    answered = opt
                                    if (isRight) score += ((100 + (900 * timer.value).toInt()) * (playLevel?.points ?: 1f)).toInt()
                                    if (multi) report(false)
                                }),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(opt, style = MaterialTheme.typography.titleMedium,
                                color = if (answered != null && (isRight || opt == answered)) Color(0xFF111111) else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}


/** Friends' best scores, gold/silver/bronze. */
@Composable
fun Leaderboard(rows: List<Pair<Profile, Int>>, me: String?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(12.dp)) {
        Text("🏆 Classifica amici", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        rows.take(10).forEachIndexed { i, (p, sc) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (p.id == me) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(when (i) { 0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}" }, fontSize = 18.sp, modifier = Modifier.width(32.dp))
                Avatar(p, 30.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (p.id == me) "Tu" else p.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("$sc", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun GameLeaderboards() {
    val app = LocalApp.current
    var guess by remember { mutableStateOf<List<Pair<Profile, Int>>>(emptyList()) }
    var blind by remember { mutableStateOf<List<Pair<Profile, Int>>>(emptyList()) }
    var layersBoard by remember { mutableStateOf<List<Pair<Profile, Int>>>(emptyList()) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        guess = runCatching { app.social.leaderboard("guess") }.getOrDefault(emptyList())
        blind = runCatching { app.social.leaderboard("blind") }.getOrDefault(emptyList())
        layersBoard = runCatching { app.social.leaderboard("layers") }.getOrDefault(emptyList())
    }
    if (guess.isEmpty() && blind.isEmpty() && layersBoard.isEmpty()) return
    Column(Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Pill("Indovina il brano", tab == 0, { tab = 0 })
            Pill("Chi canta?", tab == 1, { tab = 1 })
            Pill("Strati", tab == 2, { tab = 2 })
        }
        val rows = when (tab) { 0 -> guess; 1 -> blind; else -> layersBoard }
        if (rows.isEmpty()) Text("Nessun punteggio ancora: gioca per entrare in classifica!", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Leaderboard(rows, app.social.uid)
    }
}
