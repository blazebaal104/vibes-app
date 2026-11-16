package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.vibes.backend.Records
import dev.brahmkshatriya.echo.vibes.backend.WeekSummary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val itDay = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
private val itLongDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

fun weekLabel(start: String): String = runCatching {
    val d = LocalDate.parse(start)
    val thisWeek = VibesGraph.listens.thisWeek
    when (d) {
        thisWeek -> "Questa settimana"
        thisWeek.minusWeeks(1) -> "Settimana scorsa"
        else -> "${itDay.format(d)} – ${itDay.format(d.plusDays(6))}"
    }
}.getOrDefault(start)

fun fmtInt(n: Int) = "%,d".format(Locale.ITALIAN, n)

/** Spotify-like palettes, one per week. */
private val weekPalettes = listOf(
    0xFFE8115B to 0xFF5B0B2E, 0xFF1E3264 to 0xFF0D73EC, 0xFF8D67AB to 0xFF2A1B3D, 0xFF006450 to 0xFF1DB954,
    0xFFBA5D07 to 0xFF4A1C05, 0xFF477D95 to 0xFF0E2A3A, 0xFFDC148C to 0xFF3A0A2A, 0xFF608108 to 0xFF1C2A05,
)

private fun palette(i: Int) = weekPalettes[((i % weekPalettes.size) + weekPalettes.size) % weekPalettes.size]
    .let { Color(it.first) to Color(it.second) }

// =====================================================================================  stats page

@Composable
fun StatsScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val listens by VibesGraph.listens.listens.collectAsState()
    val weeks = remember(listens.size) { VibesGraph.listens.weeks() }
    var current by remember { mutableStateOf<WeekSummary?>(null) }
    var records by remember { mutableStateOf<Records?>(null) }
    LaunchedEffect(listens.size) {
        current = runCatching { VibesGraph.listens.summary(VibesGraph.listens.thisWeek) }.getOrNull()
        records = runCatching { VibesGraph.listens.records() }.getOrNull()
    }

    Box(Modifier.fillMaxWidth().screenBg()) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Statistiche di ascolto", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    current?.let { w -> IconButton(onClick = { shareToApps(context, shareText(w)) }) { Icon(Icons.Rounded.Share, "Condividi") } }
                }
                Text("Si aggiornano ogni settimana, da lunedì a domenica. I tuoi amici vedono solo la settimana in corso.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
            }
            item {
                val w = current
                if (w == null) Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                } else if (w.plays == 0) EmptyState("🎧", "Ancora niente questa settimana", "Ascolta qualche brano: le statistiche si riempiono da sole.")
                else WeekFull(w, 0)
            }
            records?.takeIf { it.mostMinutes > 0 }?.let { r -> item { RecordsCard(r, Modifier.padding(16.dp)) } }
            val older = weeks.filter { it != VibesGraph.listens.thisWeek }
            if (older.isNotEmpty()) item { SectionTitle("Settimane precedenti") }
            items(older.size) { i -> PastWeek(older[i], i + 1) }
        }
    }
}

private fun shareText(w: WeekSummary): String = buildString {
    append("🎧 La mia settimana su Vibes\n")
    append("${fmtInt(w.minutes)} minuti di musica\n")
    w.artists.firstOrNull()?.let { append("Artista più ascoltato: ${it.name}\n") }
    w.songs.firstOrNull()?.let { append("Brano più ascoltato: ${it.song.title} – ${it.song.artist} (${it.plays} volte)\n") }
    w.genres.firstOrNull()?.let { append("Genere: ${it.name}\n") }
}

@Composable
private fun PastWeek(week: LocalDate, index: Int) {
    var summary by remember { mutableStateOf<WeekSummary?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(week) { summary = runCatching { VibesGraph.listens.summary(week) }.getOrNull() }
    val w = summary ?: return
    val (a, b) = palette(index)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).enter(index)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.horizontalGradient(listOf(a, b)))
                .bouncy({ open = !open }).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(w.artists.firstOrNull()?.image ?: w.songs.firstOrNull()?.song?.cover, Modifier.size(58.dp), CircleShape)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(weekLabel(w.weekStart), style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("${fmtInt(w.minutes)} min · ${w.artists.firstOrNull()?.name ?: "—"}", style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val rot by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "rot")
            Icon(Icons.Rounded.ExpandMore, null, tint = Color.White, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(open) { Column(Modifier.padding(top = 8.dp)) { WeekFull(w, index, inset = false) } }
    }
}

/** A full week, Spotify listening-stats style: big top artist, minutes, top songs, artists, genre, playlists, highlight. */
@Composable
fun WeekFull(w: WeekSummary, index: Int, inset: Boolean = true) {
    val app = LocalApp.current
    val (a, b) = palette(index)
    val pad = if (inset) Modifier.padding(horizontal = 16.dp) else Modifier
    Column(pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (inset) Text(weekLabel(w.weekStart), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp))

        // ---- top artist hero
        w.artists.firstOrNull()?.let { top ->
            val grow = remember { Animatable(0.8f) }
            LaunchedEffect(top.name) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.verticalGradient(listOf(a, b))).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Il tuo artista più ascoltato", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(16.dp))
                Cover(top.image, Modifier.size(190.dp).graphicsLayer { scaleX = grow.value; scaleY = grow.value }, CircleShape)
                Text(top.name, style = MaterialTheme.typography.headlineLarge, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp))
                Text("${fmtInt(top.minutes)} minuti", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }

        // ---- minutes
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Minuti ascoltati", fmtInt(w.minutes), a, Modifier.weight(1f), animate = w.minutes)
            StatTile("Brani ascoltati", fmtInt(w.plays), b, Modifier.weight(1f), animate = w.plays)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Giorni di ascolto", "${w.activeDays}/7", LocalVibes.current.cardHigh, Modifier.weight(1f))
            StatTile("Artisti nuovi", fmtInt(w.newArtists), LocalVibes.current.cardHigh, Modifier.weight(1f))
        }

        // ---- top songs
        if (w.songs.isNotEmpty()) StatCard("Brani più ascoltati") {
            w.songs.take(5).forEachIndexed { i, rs ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).bouncy({ app.engine.playAll(w.songs.map { it.song }, i) })
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(30.dp), color = if (i == 0) a else MaterialTheme.colorScheme.onSurface)
                    Cover(rs.song.cover, Modifier.size(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rs.song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(rs.song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${rs.plays} ${if (rs.plays == 1) "volta" else "volte"}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---- top artists
        if (w.artists.size > 1) StatCard("Artisti più ascoltati") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(w.artists.take(8).size) { i ->
                    val ar = w.artists[i]
                    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            Cover(ar.image, Modifier.size(92.dp), CircleShape)
                            Box(Modifier.align(Alignment.BottomStart).size(26.dp).clip(CircleShape).background(a), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                        }
                        Text(ar.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp))
                        Text("${fmtInt(ar.minutes)} min", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---- genre
        if (w.genres.isNotEmpty()) {
            val total = w.genres.sumOf { it.minutes }.coerceAtLeast(1)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(b, a))).padding(18.dp)
            ) {
                Text("Il tuo genere", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                Text(w.genres.first().name, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                    lineHeight = 44.sp, modifier = Modifier.padding(vertical = 6.dp))
                w.genres.forEach { g ->
                    val pct = g.minutes * 100 / total
                    val anim = remember { Animatable(0f) }
                    LaunchedEffect(g.name) { anim.animateTo(pct / 100f, tween(800, easing = FastOutSlowInEasing)) }
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g.name, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.width(110.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxWidth(anim.value).fillMaxHeight().clip(CircleShape).background(Color.White))
                        }
                        Text("$pct%", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.padding(start = 8.dp).width(38.dp),
                            textAlign = TextAlign.End)
                    }
                }
            }
        }

        // ---- playlists
        if (w.playlists.isNotEmpty()) StatCard("Playlist più ascoltate") {
            w.playlists.forEachIndexed { i, pl ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(26.dp))
                    Cover(pl.cover, Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(pl.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${fmtInt(pl.minutes)} min", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---- highlight
        highlight(w)?.let { (emoji, text) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LocalVibes.current.cardHigh).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emoji, fontSize = 38.sp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Momento clou", style = MaterialTheme.typography.labelLarge, color = a)
                    Text(text, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

private fun highlight(w: WeekSummary): Pair<String, String>? {
    val top = w.songs.firstOrNull()
    return when {
        top != null && top.plays >= 10 -> "🔁" to "Non riesci a staccarti da \"${top.song.title}\": ${top.plays} ascolti in una settimana."
        w.newArtists >= 5 -> "🧭" to "Hai scoperto ${w.newArtists} artisti nuovi."
        w.activeDays == 7 -> "🔥" to "Musica ogni giorno della settimana, 7 su 7."
        w.minutes >= 600 -> "⏱️" to "Più di ${w.minutes / 60} ore di musica in 7 giorni."
        top != null -> "🎧" to "Il brano della settimana è \"${top.song.title}\"."
        else -> null
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier, animate: Int? = null) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(animate) { if (animate != null) anim.animateTo(animate.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(color).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
        Text(if (animate != null) fmtInt(anim.value.toInt()) else value, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
            color = Color.White, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
fun RecordsCard(r: Records, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp)) {
        Text("🏆 Record", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        r.mostPlaysSong?.let { s ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).bouncy({ app.engine.play(s) }).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Cover(s.cover, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Più ascolti di un brano in una settimana", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("${s.title} – ${s.artist}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    r.mostPlaysWeek?.let { Text(weekLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text("${r.mostPlays}×", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(LocalVibes.current.cardHigh).padding(12.dp)) {
                Text("Più minuti in una settimana", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtInt(r.mostMinutes), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                r.mostMinutesWeek?.let { Text(weekLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(LocalVibes.current.cardHigh).padding(12.dp)) {
                Text("Giorni di fila", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${r.longestStreakDays} 🔥", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("record personale", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What friends see on a profile: this week only (compact). */
@Composable
fun FriendWeekCard(p: Profile, modifier: Modifier = Modifier) {
    val w = p.statsWeek ?: return
    if (w.weekStart != VibesGraph.listens.thisWeek.toString() || w.plays == 0) return
    val app = LocalApp.current
    val (a, b) = palette(0)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(a, b))).padding(18.dp)) {
        Text("Questa settimana", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtInt(w.minutes), fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(" minuti", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
        }
        w.artists.firstOrNull()?.let { ar ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(ar.image, Modifier.size(54.dp), CircleShape)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Artista più ascoltato", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                    Text(ar.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
        }
        w.songs.firstOrNull()?.let { rs ->
            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.2f))
                .bouncy({ app.engine.play(rs.song) }).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(rs.song.cover, Modifier.size(46.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Brano più ascoltato", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                    Text("${rs.song.title} – ${rs.song.artist}", style = MaterialTheme.typography.titleSmall, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${rs.plays}×", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        w.genres.firstOrNull()?.let {
            Text("Genere: ${it.name}", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// =====================================================================================  history

@Composable
fun HistoryScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val listens by VibesGraph.listens.listens.collectAsState()
    var clearing by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val byDay = remember(listens.size) {
        listens.asReversed().groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.entries.toList()
    }
    val playing = nowPlayingKey()
    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Cronologia", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (listens.isNotEmpty()) IconButton(onClick = { clearing = true }) { Icon(Icons.Rounded.DeleteSweep, "Cancella cronologia") }
                }
            }
            if (byDay.isEmpty()) item { EmptyState("🕘", "Nessun ascolto ancora", "Qui trovi tutto quello che ascolti, giorno per giorno.") }
            byDay.forEach { (day, ls) ->
                item(key = "d$day") {
                    val today = LocalDate.now()
                    val label = when (day) { today -> "Oggi"; today.minusDays(1) -> "Ieri"; else -> itLongDay.format(day).replaceFirstChar { it.uppercase() } }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Text("${fmtInt((ls.sumOf { it.ms } / 60_000).toInt())} min", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(ls.size, key = { "${day}_${ls[it].at}" }) { i ->
                    val l = ls[i]
                    val time = Instant.ofEpochMilli(l.at).atZone(zone).toLocalTime()
                    Row(
                        Modifier.fillMaxWidth().bouncy({ app.engine.play(l.song) }, { nav.share = ShareItem.Song(l.song) })
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(l.song.cover, Modifier.size(50.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (playing == l.song.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(l.song.artist + (l.ctx?.let { " · da $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("%02d:%02d".format(time.hour, time.minute), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (clearing) AlertDialog(
        onDismissRequest = { clearing = false },
        title = { Text("Cancellare la cronologia?") },
        text = { Text("Spariscono anche le statistiche delle settimane passate. I record restano solo se ancora calcolabili.") },
        confirmButton = { TextButton(onClick = { clearing = false; VibesGraph.listens.clear(); VibesGraph.onListened(force = true) }) { Text("Cancella") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Annulla") } }
    )
}
