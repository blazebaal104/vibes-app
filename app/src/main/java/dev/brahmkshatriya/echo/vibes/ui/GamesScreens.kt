package dev.brahmkshatriya.echo.vibes.ui

import android.os.Build
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
                GameCard(
                    "Indovina il brano", "Ascolta 0,1 secondi. Poi 1, 2, 5, 10. Riconosci la canzone prima che finiscano i tentativi.",
                    "🎯", listOf(GameColors.Easy, GameColors.Medium, GameColors.Hard, GameColors.Impossible),
                    onSolo = { nav.open(Route.Guess(null)) }, onChallenge = { challengeGame = "guess" }, index = 0
                )
            }
            item {
                GameCard(
                    "Chi canta?", "8 brani, 4 artisti tra cui scegliere, 10 secondi a domanda. Più sei veloce, più punti fai.",
                    "🎤", listOf(Color(0xFF7B2FF7), Color(0xFFFF2E97), Color(0xFFFF7A59)),
                    onSolo = { nav.open(Route.BlindTest(null)) }, onChallenge = { challengeGame = "blindtest" }, index = 1
                )
            }
        }
    }
    challengeGame?.let { g -> ChallengeDialog(g) { challengeGame = null } }
}

@Composable
private fun GameCard(title: String, text: String, emoji: String, colors: List<Color>, onSolo: () -> Unit, onChallenge: () -> Unit, index: Int) {
    Column(
        Modifier.padding(16.dp).fillMaxWidth().enter(index).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(colors.map { it.copy(alpha = 0.85f) }))
            .padding(20.dp)
    ) {
        Text(emoji, fontSize = 44.sp)
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(top = 6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Gioca da solo", onSolo, Modifier.weight(1f), container = Color.White, content = Color(0xFF111111))
            BigButton("Sfida un amico", onChallenge, Modifier.weight(1f), container = Color.Black.copy(alpha = 0.35f), content = Color.White)
        }
    }
}

@Composable
private fun ChallengeDialog(game: String, onDismiss: () -> Unit) {
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
                            runCatching { createChallenge(game, f) }
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

private suspend fun createChallenge(game: String, friend: Profile): Lobby {
    val social = VibesGraph.social
    val picker = VibesGraph.picker
    val state = if (game == "guess") {
        val tracks = Difficulty.entries.mapNotNull { picker.pick(it) }
        if (tracks.size < 4) throw IllegalStateException("Non riesco a preparare i brani, controlla la connessione")
        buildJsonObject { put("tracks", Supa.json.encodeToJsonElement(trackList, tracks)) }
    } else {
        val tracks = (0 until 8).mapNotNull { picker.pick(if (it < 4) Difficulty.EASY else Difficulty.MEDIUM) }
        if (tracks.size < 8) throw IllegalStateException("Non riesco a preparare i brani, controlla la connessione")
        val options = tracks.map { t -> (picker.otherArtists(t.artist, 3) + t.artist).shuffled() }
        buildJsonObject {
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
fun GuessGameScreen(lobbyId: String?) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = app.social.uid
    val lobby = rememberLobby(lobbyId)
    val multi = lobbyId != null
    val friends by app.social.friends.collectAsState()

    val clip = remember { ClipPlayer(context) }
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
        clip.load(t.preview)
        state = RoundState.PLAYING
    }

    fun nextRoundLocal() {
        clip.stop()
        if (round >= 3) { state = RoundState.DONE; return }
        round++
        state = RoundState.LOADING
        scope.launch { startRound(VibesGraph.picker.pick(Difficulty.entries[round])) }
    }

    fun finishRound(won: Boolean, note: String) {
        if (state != RoundState.PLAYING) return
        state = if (won) RoundState.WON else RoundState.LOST
        if (won) wins++
        revealNote = note
        // the song keeps playing "in your head"; when it ends we move on by ourselves
        clip.playFull { if (!multi && (state == RoundState.WON || state == RoundState.LOST)) nextRoundLocal() }
    }

    // ---- single player start
    LaunchedEffect(Unit) {
        if (!multi) startRound(VibesGraph.picker.pick(Difficulty.EASY))
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
                if (iWon) wins++
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

    fun onGuess(g: GameTrack) {
        val t = track ?: return
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
        suggestions = app.deezer.search(query, 6)
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                            onAgain = if (!multi) ({ wins = 0; round = 0; state = RoundState.LOADING; scope.launch { startRound(VibesGraph.picker.pick(Difficulty.EASY)) } }) else null,
                        )
                        else -> GuessBoard(
                            difficulty = difficulty, color = color, step = step, attempts = attempts,
                            playing = clipPlaying, roundLabel = "Brano ${round + 1} di 4",
                            onPlay = { clip.playClip(clipSteps[step]) },
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
        if (state == RoundState.WON) Confetti()
    }
}

@Composable
private fun GuessBoard(
    difficulty: Difficulty, color: Color, step: Int, attempts: List<String>, playing: Boolean, roundLabel: String,
    onPlay: () -> Unit, query: String, onQuery: (String) -> Unit, suggestions: List<GameTrack>,
    onPick: (GameTrack) -> Unit, onSkip: () -> Unit, onGiveUp: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text(roundLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        // attempts
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }
        Spacer(Modifier.weight(1f))
        // timeline 0..10 s with markers at each unlock
        ClipTimeline(step, color, playing)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("+${fmtStep(clipSteps.getOrNull(step + 1))}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            val s by animateFloatAsState(if (playing) 0.92f else 1f, spring(dampingRatio = 0.4f), label = "pl")
            Box(
                Modifier.size(78.dp).scale(s).clip(CircleShape).background(color).bouncy(onPlay),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.PlayArrow, "Ascolta", tint = Color(0xFF111111), modifier = Modifier.size(46.dp)) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text(fmtStep(clipSteps[step]), style = MaterialTheme.typography.titleLarge, color = color)
            }
        }
        Spacer(Modifier.height(16.dp))
        // suggestions above the field
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(LocalVibes.current.cardHigh)) {
            suggestions.forEach { g ->
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
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(LocalVibes.current.card)
                .border(1.5.dp, color, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = color)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Scrivi il titolo…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(query, onQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(color), modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton(if (step >= clipSteps.lastIndex) "Mi arrendo" else "Salta (+${fmtStep(clipSteps.getOrNull(step + 1))})",
                onSkip, Modifier.weight(1f), container = LocalVibes.current.cardHigh, content = MaterialTheme.colorScheme.onSurface)
            if (onGiveUp != null) BigButton("Non lo so", onGiveUp, Modifier.weight(1f), container = Color(0xFFFF3B5C), content = Color.White)
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun fmtStep(ms: Long?): String = when {
    ms == null -> "—"
    ms < 1000 -> "0,1s"
    else -> "${ms / 1000}s"
}

@Composable
private fun ClipTimeline(step: Int, color: Color, playing: Boolean) {
    val max = clipSteps.last().toFloat()
    val unlocked = clipSteps[step] / max
    val progress = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            progress.snapTo(0f)
            progress.animateTo(unlocked, tween(clipSteps[step].toInt(), easing = LinearEasing))
        } else progress.snapTo(0f)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp)) {
        val w = maxWidth
        // track
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(10.dp).clip(CircleShape).background(LocalVibes.current.cardHigh))
        // unlocked part
        Box(Modifier.align(Alignment.CenterStart).width(w * unlocked.coerceAtLeast(0.012f)).height(10.dp).clip(CircleShape).background(color.copy(alpha = 0.4f)))
        // playing indicator
        Box(Modifier.align(Alignment.CenterStart).width(w * progress.value).height(10.dp).clip(CircleShape).background(color))
        // markers
        clipSteps.forEachIndexed { i, ms ->
            val x = w * (ms / max)
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = x - 2.dp).width(4.dp).fillMaxHeight()
                    .clip(CircleShape).background(if (i <= step) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("0s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun GameOver(emoji: String, title: String, subtitle: String, onAgain: (() -> Unit)?) {
    val nav = LocalApp.current.nav
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(emoji, fontSize = 80.sp)
        Text(title, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        if (onAgain != null) BigButton("Gioca ancora", onAgain, Modifier.width(220.dp))
        Spacer(Modifier.height(10.dp))
        BigButton("Torna ai giochi", { nav.back() }, Modifier.width(220.dp), container = LocalVibes.current.cardHigh, content = MaterialTheme.colorScheme.onSurface)
    }
}

// =====================================================================================  Chi canta?

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

    // load questions
    LaunchedEffect(lobby?.id, multi) {
        if (tracks.isNotEmpty()) return@LaunchedEffect
        if (multi) {
            val l = lobby ?: return@LaunchedEffect
            tracks = l.tracks()
            options = l.state["options"]?.let {
                runCatching { Supa.json.decodeFromJsonElement(ListSerializer(ListSerializer(String.serializer())), it) }.getOrNull()
            }.orEmpty()
        } else {
            val picker = VibesGraph.picker
            val t = (0 until 8).mapNotNull { picker.pick(if (it < 4) Difficulty.EASY else Difficulty.MEDIUM) }
            options = t.map { (picker.otherArtists(it.artist, 3) + it.artist).shuffled() }
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
        clip.playClip(10_000)
        timer.snapTo(1f)
        timer.animateTo(0f, tween(10_000, easing = LinearEasing))
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
                finished -> {
                    val title = if (!multi) "$score punti" else when {
                        !otherDone -> "Aspettiamo $otherName…"
                        score > otherScore -> "Hai vinto! 🏆"
                        score < otherScore -> "Ha vinto $otherName"
                        else -> "Pareggio!"
                    }
                    GameOver(if (!multi || score >= otherScore) "🏆" else "🎧", title,
                        if (multi) "Tu $score · $otherName $otherScore" else "Quanti artisti hai riconosciuto in 8 brani.",
                        if (!multi) ({ score = 0; index = 0; finished = false; tracks = emptyList() }) else null)
                    if (score > 0 && (!multi || (otherDone && score > otherScore))) Confetti()
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
                                    if (isRight) score += (100 + (900 * timer.value).toInt())
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
