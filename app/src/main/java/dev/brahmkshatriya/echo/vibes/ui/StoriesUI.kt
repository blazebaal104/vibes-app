package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Story
import dev.brahmkshatriya.echo.vibes.backend.StoryStyle
import dev.brahmkshatriya.echo.vibes.games.ClipPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private const val STORY_MS = 15_000

val storyGradients = listOf(
    "#B84DFF" to "#1A0B2E", "#FF2E97" to "#2A0716", "#FF8A2B" to "#2B1203", "#1ED760" to "#05210F",
    "#35D0F5" to "#061F2A", "#FFD43B" to "#2A2203", "#FF3B5C" to "#26060C", "#7C8CFF" to "#0B0F2E",
)

fun timeAgo(iso: String?): String = runCatching {
    val d = Duration.between(Instant.parse(iso), Instant.now())
    when {
        d.toMinutes() < 1 -> "ora"
        d.toMinutes() < 60 -> "${d.toMinutes()} min"
        else -> "${d.toHours()} h"
    }
}.getOrDefault("")

/** Avatar with the Instagram-like ring: coloured if there are unseen stories, grey if all seen. */
@Composable
fun StoryAvatar(p: Profile?, size: androidx.compose.ui.unit.Dp, ring: Boolean?, modifier: Modifier = Modifier) {
    val ringBrush = when (ring) {
        true -> Brush.sweepGradient(listOf(Color(0xFFFF2E97), Color(0xFFFF8A2B), Color(0xFFFFD43B), Color(0xFFB84DFF), Color(0xFFFF2E97)))
        false -> Brush.linearGradient(listOf(Color.Gray.copy(alpha = 0.6f), Color.Gray.copy(alpha = 0.6f)))
        null -> null
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (ringBrush != null) Box(Modifier.size(size + 8.dp).clip(CircleShape).background(ringBrush))
        if (ringBrush != null) Box(Modifier.size(size + 3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background))
        Avatar(p, size)
    }
}

/** The stories bar at the top of Home. */
@Composable
fun StoriesRow() {
    val app = LocalApp.current
    val nav = app.nav
    val me by app.social.me.collectAsState()
    val groups by app.social.stories.collectAsState()
    var recapMonth by remember { mutableStateOf<YearMonth?>(null) }
    LaunchedEffect(Unit) {
        runCatching { app.social.refreshFollows(); app.social.refreshStories() }
        // first week of the month: last month's recap bubble (if I posted stories)
        val today = LocalDate.now()
        if (today.dayOfMonth <= 7) {
            val last = YearMonth.from(today).minusMonths(1)
            val n = runCatching { storiesOfMonth(last).size }.getOrDefault(0)
            if (n > 0) recapMonth = last
        }
    }
    val mine = groups.firstOrNull { it.user.id == me?.id }
    val others = groups.filter { it.user.id != me?.id }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 6.dp)) {
        item(key = "me") {
            Column(Modifier.width(76.dp).bouncy({ if (mine != null) nav.open(Route.Stories(mine.user.id)) else nav.open(Route.NewStory()) },
                { nav.open(Route.NewStory()) }), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    StoryAvatar(me, 64.dp, if (mine != null) !mine.allSeen else null)
                    Box(Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(2.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { nav.open(Route.NewStory()) },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, "Nuova storia", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                }
                Text("La tua storia", style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
        recapMonth?.let { m ->
            item(key = "recap") {
                Column(Modifier.width(76.dp).bouncy({ nav.open(Route.StoryRecap(m.toString())) }), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).clip(CircleShape)
                        .background(Brush.sweepGradient(listOf(Color(0xFF1ED760), Color(0xFF35D0F5), Color(0xFFB84DFF), Color(0xFF1ED760)))),
                        contentAlignment = Alignment.Center) { Text("✨", fontSize = 30.sp) }
                    Text("Recap ${m.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)}", style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        items(others, key = { it.user.id }) { g ->
            Column(Modifier.width(76.dp).bouncy({ nav.open(Route.Stories(g.user.id)) }), horizontalAlignment = Alignment.CenterHorizontally) {
                StoryAvatar(g.user, 64.dp, !g.allSeen)
                Text(g.user.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// =====================================================================================  viewer

@Composable
fun StoryViewerScreen(startUserId: String) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by app.social.stories.collectAsState()
    val me = app.social.uid
    val friends by app.social.friends.collectAsState()
    val order = remember { groups.map { it.user.id } }
    var userIndex by remember { mutableIntStateOf(order.indexOf(startUserId).coerceAtLeast(0)) }
    val group = groups.firstOrNull { it.user.id == order.getOrNull(userIndex) }
    var storyIndex by remember(userIndex) {
        mutableIntStateOf(group?.stories?.indexOfFirst { it.id !in group.seen }?.takeIf { it >= 0 } ?: 0)
    }
    var paused by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var viewers by remember { mutableStateOf<List<Profile>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clip = remember { ClipPlayer(context) }
    DisposableEffect(Unit) { app.engine.pause(); onDispose { clip.release() } }

    if (group == null || group.stories.isEmpty()) { LaunchedEffect(Unit) { nav.back() }; return }
    val story = group.stories[storyIndex.coerceIn(0, group.stories.lastIndex)]
    val mine = group.user.id == me

    fun next() {
        if (storyIndex < group.stories.lastIndex) storyIndex++
        else if (userIndex < order.lastIndex) { userIndex++ }
        else nav.back()
    }
    fun prev() {
        if (storyIndex > 0) storyIndex--
        else if (userIndex > 0) userIndex--
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(story.id) {
        if (!mine) scope.launch { app.social.markStorySeen(story.id) }
        progress.snapTo(0f)
        story.style.preview?.let { clip.load(it); clip.playFull() }
    }
    LaunchedEffect(story.id, paused) {
        if (paused) { clip.stop(); progress.stop(); return@LaunchedEffect }
        if (progress.value > 0f) story.style.preview?.let { clip.playFull() }
        progress.animateTo(1f, tween(((1f - progress.value) * STORY_MS).toInt(), easing = LinearEasing))
        next()
    }

    val top = parseColor(story.style.top); val bottom = parseColor(story.style.bottom)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))))
        AsyncImage(story.song.cover, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(60.dp).background(Color.Black.copy(alpha = 0.2f)), alpha = 0.45f)
        // tap zones: left = back, right = forward, hold = pause
        Box(Modifier.fillMaxSize().pointerInput(story.id) {
            detectTapGestures(
                onTap = { o -> if (o.x < size.width / 3) prev() else next() },
                onPress = { paused = true; tryAwaitRelease(); paused = false },
            )
        })
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // progress bars
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.stories.forEachIndexed { i, _ ->
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                        val f = when { i < storyIndex -> 1f; i == storyIndex -> progress.value; else -> 0f }
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Color.White))
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(group.user, 36.dp, Modifier.bouncy({ nav.back(); nav.open(Route.Profile(group.user.id)) }))
                Spacer(Modifier.width(10.dp))
                Text(if (mine) "La tua storia" else group.user.name, style = MaterialTheme.typography.titleSmall, color = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(timeAgo(story.createdAt), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                if (mine) IconButton(onClick = { paused = true; confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Elimina", tint = Color.White) }
                IconButton(onClick = { nav.back() }) { Icon(Icons.Rounded.Close, "Chiudi", tint = Color.White) }
            }
            Spacer(Modifier.weight(1f))
            AnimatedContent(story.id, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() }, label = "story",
                modifier = Modifier.align(Alignment.CenterHorizontally)) { _ ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 28.dp)) {
                    Cover(story.song.cover, Modifier.size(270.dp).shadow(30.dp, RoundedCornerShape(14.dp)), RoundedCornerShape(14.dp))
                    Text(story.song.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 20.dp), maxLines = 2)
                    Text(story.song.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                    if (story.caption.isNotBlank()) Text(story.caption, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mine) {
                    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable {
                        paused = true
                        scope.launch { viewers = runCatching { app.social.storyViewers(story.id) }.getOrDefault(emptyList()) }
                    }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Visibility, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Visualizzazioni", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.weight(1f))
                } else if (friends.any { it.id == group.user.id }) {
                    Box(Modifier.weight(1f).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 4.dp)) {
                        androidx.compose.foundation.text.BasicTextField(
                            reply, { reply = it; paused = it.isNotEmpty() },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White), singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            decorationBox = { inner ->
                                if (reply.isEmpty()) Text("Rispondi a ${group.user.name}…", color = Color.White.copy(alpha = 0.7f))
                                inner()
                            }
                        )
                    }
                    if (reply.isNotBlank()) IconButton(onClick = {
                        val text = reply; reply = ""; paused = false
                        scope.launch {
                            runCatching { app.social.send(group.user.id, "text", "↩️ Alla tua storia \"${story.song.title}\": $text") }
                                .onSuccess { nav.toast("Risposta inviata") }
                        }
                    }) { Icon(Icons.AutoMirrored.Rounded.Send, "Invia", tint = Color.White) }
                    else Text("🔥", fontSize = 26.sp, modifier = Modifier.padding(horizontal = 8.dp).clip(CircleShape).clickable {
                        scope.launch { runCatching { app.social.send(group.user.id, "text", "🔥 alla tua storia \"${story.song.title}\"") }; nav.toast("🔥 inviato") }
                    })
                } else Spacer(Modifier.weight(1f))
                Row(Modifier.padding(start = 8.dp).clip(CircleShape).background(Color.White).clickable {
                    clip.stop(); nav.back(); app.engine.play(story.song)
                }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Text("Ascolta", color = Color.Black, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    viewers?.let { list ->
        AlertDialog(
            onDismissRequest = { viewers = null; paused = false },
            title = { Text("👁 ${list.size} visualizzazioni") },
            text = {
                if (list.isEmpty()) Text("Ancora nessuno.")
                else LazyColumn(Modifier.height(320.dp)) {
                    items(list, key = { it.id }) { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p, 40.dp); Spacer(Modifier.width(12.dp))
                            Column { Text(p.name, style = MaterialTheme.typography.titleSmall); Text("@${p.username}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewers = null; paused = false }) { Text("Chiudi") } }
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false; paused = false },
        title = { Text("Eliminare questa storia?") },
        text = { Text("Sparisce subito per tutti (nel tuo recap del mese non conta più).") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            scope.launch { runCatching { app.social.deleteStory(story.id) }; paused = false }
        }) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false; paused = false }) { Text("Annulla") } }
    )
}

// =====================================================================================  composer

@Composable
fun NewStoryScreen(initial: SongRef?) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var song by remember { mutableStateOf(initial) }
    var caption by remember { mutableStateOf("") }
    var gradient by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(initial == null) }
    var posting by remember { mutableStateOf(false) }
    val clip = remember { ClipPlayer(context) }
    var preview by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { clip.release() } }
    LaunchedEffect(song?.key) {
        preview = null
        song?.let { s ->
            preview = runCatching { VibesGraph.deezer.previewFor(s) }.getOrNull()
            preview?.let { clip.load(it); clip.playFull() }
        }
    }

    val (top, bottom) = storyGradients[gradient]
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(parseColor(top), parseColor(bottom))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.Rounded.Close, "Annulla", tint = Color.White) }
                Text("Nuova storia", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
                Text("Cambia brano", color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(CircleShape).clickable { picking = true }.padding(12.dp))
            }
            Spacer(Modifier.weight(1f))
            val s = song
            if (s != null) Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(s.cover, Modifier.size(240.dp).shadow(30.dp, RoundedCornerShape(14.dp)), RoundedCornerShape(14.dp))
                Text(s.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                Text(s.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
                Box(Modifier.padding(top = 16.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)) {
                    androidx.compose.foundation.text.BasicTextField(
                        caption, { caption = it.take(80) },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center) {
                                if (caption.isEmpty()) Text("Scrivi qualcosa…", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp)
                                inner()
                            }
                        }
                    )
                }
            } else Text("Scegli un brano", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable { picking = true })
            Spacer(Modifier.weight(1f))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(storyGradients.size) { i ->
                    val (a, b) = storyGradients[i]
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(parseColor(a), parseColor(b))))
                        .border(if (i == gradient) 3.dp else 1.dp, Color.White, CircleShape).clickable { gradient = i })
                }
            }
            BigButton("Pubblica storia", {
                val sng = song ?: return@BigButton
                posting = true
                scope.launch {
                    runCatching { app.social.postStory(sng, caption.trim(), StoryStyle(top, bottom, preview)) }
                        .onSuccess { nav.toast("Storia pubblicata ✨"); nav.back() }
                        .onFailure { nav.toast(it.message ?: "Non sono riuscito a pubblicarla") }
                    posting = false
                }
            }, Modifier.fillMaxWidth().padding(16.dp), enabled = song != null, loading = posting,
                container = Color.White, content = Color.Black)
        }
    }
    if (picking) SongPickerDialog(onDismiss = { picking = false; if (song == null) nav.back() }) { picked -> song = picked; picking = false }
}

// =====================================================================================  monthly recap

private suspend fun storiesOfMonth(m: YearMonth): List<Story> {
    val zone = ZoneId.systemDefault()
    val from = m.atDay(1).atStartOfDay(zone).toInstant().toString()
    val to = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toString()
    return VibesGraph.social.myStories(from, to)
}

/** Private recap ("only you can see it"): how many stories, which songs you used most, etc. */
@Composable
fun StoryRecapScreen(month: String) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val m = remember { runCatching { YearMonth.parse(month) }.getOrDefault(YearMonth.now()) }
    var stories by remember { mutableStateOf<List<Story>?>(null) }
    var artistImage by remember { mutableStateOf<String?>(null) }
    val clip = remember { ClipPlayer(context) }
    DisposableEffect(Unit) { app.engine.pause(); onDispose { clip.release() } }
    LaunchedEffect(month) { stories = runCatching { storiesOfMonth(m) }.getOrDefault(emptyList()) }
    val list = stories
    val monthName = m.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
    if (list == null) { Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }; return }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(), contentAlignment = Alignment.Center) {
            EmptyState("📭", "Nessuna storia a $monthName", "Pubblica qualche brano nelle storie: a fine mese qui trovi il tuo recap.") {
                BigButton("Indietro", { nav.back() }, Modifier.width(200.dp))
            }
        }
        return
    }
    val bySong = list.groupBy { it.song.key }.map { (_, v) -> v.first().song to v.size }.sortedByDescending { it.second }
    val byArtist = list.groupBy { it.song.artist.split(", ").first() }.map { it.key to it.value.size }.sortedByDescending { it.second }
    val zone = ZoneId.systemDefault()
    val byDay = list.groupBy { Instant.parse(it.createdAt ?: Instant.now().toString()).atZone(zone).toLocalDate() }
        .maxByOrNull { it.value.size }
    LaunchedEffect(Unit) {
        byArtist.firstOrNull()?.let { (a, _) -> artistImage = runCatching { VibesGraph.deezer.searchArtists(a, 1).firstOrNull()?.picture }.getOrNull() }
        list.firstOrNull { it.song.key == bySong.first().first.key }?.style?.preview?.let { clip.load(it); clip.playFull() }
    }

    val slides = 5
    var page by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(page, paused) {
        if (paused) { progress.stop(); return@LaunchedEffect }
        progress.animateTo(1f, tween(((1f - progress.value) * 6500).toInt(), easing = LinearEasing))
        if (page < slides - 1) { progress.snapTo(0f); page++ }
    }
    val colors = listOf(0xFFB84DFF to 0xFF1A0B2E, 0xFFFF2E97 to 0xFF2A0716, 0xFF1ED760 to 0xFF05210F, 0xFFFF8A2B to 0xFF2B1203, 0xFF35D0F5 to 0xFF061F2A)
    val (a, b) = colors[page]
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(a), Color(b))))
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { o ->
                    if (o.x < size.width / 3) { if (page > 0) page-- } else if (page < slides - 1) page++ else nav.back()
                    paused = false
                },
                onPress = { paused = true; tryAwaitRelease(); paused = false },
            )
        }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(slides) { i ->
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(when { i < page -> 1f; i == page -> progress.value; else -> 0f }).fillMaxHeight().background(Color.White))
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔒 Solo per te · recap di $monthName", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.back() }) { Icon(Icons.Rounded.Close, "Chiudi", tint = Color.White) }
            }
            AnimatedContent(page, transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(200)) },
                label = "recap", modifier = Modifier.weight(1f).fillMaxWidth()) { p ->
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    when (p) {
                        0 -> {
                            Text("Il tuo $monthName in storie", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                            Text("${list.size}", fontSize = 120.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text(if (list.size == 1) "storia pubblicata" else "storie pubblicate", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Text("con ${bySong.size} canzoni diverse", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        1 -> {
                            val (s, n) = bySong.first()
                            Text("La canzone che hai usato di più", style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
                            Cover(s.cover, Modifier.padding(vertical = 24.dp).size(240.dp).shadow(30.dp, RoundedCornerShape(14.dp)), RoundedCornerShape(14.dp))
                            Text(s.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
                            Text(s.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f))
                            Text("$n ${if (n == 1) "volta" else "volte"} nelle tue storie", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                color = Color.White, modifier = Modifier.padding(top = 14.dp))
                        }
                        2 -> {
                            Text("Le canzoni delle tue storie", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 20.dp))
                            bySong.take(5).forEachIndexed { i, (s, n) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp).enter(i), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.width(36.dp))
                                    Cover(s.cover, Modifier.size(54.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(s.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                                    }
                                    Text("${n}×", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                        3 -> {
                            val (artist, n) = byArtist.first()
                            Text("L'artista delle tue storie", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Cover(artistImage, Modifier.padding(vertical = 24.dp).size(220.dp), CircleShape)
                            Text(artist, style = MaterialTheme.typography.displaySmall, color = Color.White, textAlign = TextAlign.Center)
                            Text("in $n ${if (n == 1) "storia" else "storie"}", style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.85f))
                        }
                        else -> {
                            val (day, ds) = byDay!!
                            Text("Il giorno più social", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Text("${day.dayOfMonth}", fontSize = 110.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text(day.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text("${ds.size} ${if (ds.size == 1) "storia" else "storie"} in un giorno", style = MaterialTheme.typography.titleMedium,
                                color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 10.dp))
                            Spacer(Modifier.height(30.dp))
                            BigButton("Fatto", { nav.back() }, Modifier.width(200.dp), container = Color.White, content = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================  followers / following

@Composable
fun FollowsScreen(userId: String, followersTab: Boolean) {
    val app = LocalApp.current
    val nav = app.nav
    val scope = rememberCoroutineScope()
    val myId = app.social.uid
    val following by app.social.following.collectAsState()
    val myFollowers by app.social.followers.collectAsState()
    var tab by remember { mutableIntStateOf(if (followersTab) 0 else 1) }
    var lists by remember { mutableStateOf<Pair<List<Profile>, List<Profile>>?>(null) }
    LaunchedEffect(userId, following.size, myFollowers.size) { lists = runCatching { app.social.followLists(userId) }.getOrNull() }
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text(if (userId == myId) "Le tue connessioni" else "Connessioni", style = MaterialTheme.typography.headlineSmall)
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("${lists?.first?.size ?: "…"} follower", tab == 0, { tab = 0 })
                    Pill("${lists?.second?.size ?: "…"} seguiti", tab == 1, { tab = 1 })
                }
            }
            val l = (if (tab == 0) lists?.first else lists?.second).orEmpty()
            if (lists != null && l.isEmpty()) item {
                EmptyState("🫂", if (tab == 0) "Ancora nessun follower" else "Non segue ancora nessuno", "")
            }
            items(l, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { nav.open(Route.Profile(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("@${p.username}" + if (p.id in myFollowers && p.id != myId) " · ti segue" else "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.id != myId) FollowButton(p.id, compact = true)
                    if (userId == myId && tab == 0) TextButton(onClick = { scope.launch { runCatching { app.social.removeFollower(p.id) } } }) { Text("Rimuovi") }
                }
            }
        }
    }
}

/** Segui / Segui anche tu / Seguito / Amici. */
@Composable
fun FollowButton(userId: String, compact: Boolean = false) {
    val app = LocalApp.current
    val nav = app.nav
    val scope = rememberCoroutineScope()
    val following by app.social.following.collectAsState()
    val followers by app.social.followers.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val iFollow = userId in following
    val followsMe = userId in followers
    val name = app.social.people.collectAsState().value[userId]?.name ?: "questa persona"
    when {
        iFollow && followsMe -> Pill(if (compact) "Amici ✓" else "Amici ✓", false, { confirm = true })
        iFollow -> Pill("Seguito", false, { confirm = true })
        else -> Pill(if (followsMe) "Segui anche tu" else "Segui", true, {
            scope.launch {
                runCatching { app.social.follow(userId) }
                    .onSuccess { if (followsMe) nav.toast("Ora siete amici 🎉 potete chattare e sfidarvi") }
                    .onFailure { nav.toast(it.message ?: "Errore") }
            }
        })
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Smettere di seguire $name?") },
        text = { Text(if (followsMe) "Non sarete più amici: niente chat e sfide finché non lo seguirai di nuovo." else "Non vedrai più le sue storie.") },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { runCatching { app.social.unfollow(userId) } } }) { Text("Smetti di seguire") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } }
    )
}
