package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.backend.StudioStats
import dev.brahmkshatriya.echo.vibes.backend.Upload
import dev.brahmkshatriya.echo.vibes.backend.VibesAlbum
import kotlinx.coroutines.launch

/** Id used by Route.VibesRelease for "your saved Vibes songs" instead of an album. */
const val SAVED_VIBES = "saved"

/** What the community published lately, shared by the phone home and the PC home. */
object CommunityFeed {
    data class Data(val fresh: List<Upload>, val trending: List<Upload>, val albums: List<VibesAlbum>)
    @Volatile var last: Data? = null
    @Volatile private var at = 0L

    suspend fun load(force: Boolean = false): Data {
        last?.takeIf { !force && System.currentTimeMillis() - at < 5 * 60_000 }?.let { return it }
        val s = dev.brahmkshatriya.echo.vibes.VibesGraph.social
        val d = Data(
            fresh = runCatching { s.latestUploads(20) }.getOrDefault(emptyList()),
            trending = runCatching { s.trendingUploads(20) }.getOrDefault(emptyList()).filter { it.plays > 0 || it.likes > 0 },
            albums = runCatching { s.latestAlbums(12) }.getOrDefault(emptyList()),
        )
        last = d; at = System.currentTimeMillis()
        return d
    }
}

@Composable
fun rememberCommunity(): CommunityFeed.Data? {
    var d by remember { mutableStateOf(CommunityFeed.last) }
    LaunchedEffect(Unit) { d = CommunityFeed.load() }
    return d
}

/** A song published on Vibes as a card (cover, title, artist, plays). */
@Composable
fun UploadCard(u: Upload, list: List<Upload>, index: Int, size: androidx.compose.ui.unit.Dp = 150.dp) {
    val app = LocalApp.current
    val cur by app.engine.player.playerState.current.collectAsState()
    val playing = cur?.track?.id == dev.brahmkshatriya.echo.vibes.music.VibesUploads.PREFIX + u.id
    Column(Modifier.width(size).bouncy({ playUploads(app, list, index) })) {
        Box {
            Cover(u.cover, Modifier.size(size), RoundedCornerShape(10.dp))
            if (playing && cur?.isPlaying == true) EqualizerBars(Modifier.align(Alignment.Center).size(28.dp), color = Color.White)
            Text("VIBES", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Brush.linearGradient(listOf(Color(0xFF8A5CFF), Color(0xFFFF4D8D))), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp))
        }
        Text(u.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text("${u.artist} · ${fmtInt(u.plays)} ascolti", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun VibesAlbumCard(a: VibesAlbum, size: androidx.compose.ui.unit.Dp = 150.dp) {
    val nav = LocalApp.current.nav
    val tap = rememberAmbientTap(a.cover)
    Column(Modifier.width(size).ambientBounds(tap).bouncy({ tap.fire(); nav.open(Route.VibesRelease(a.id)) })) {
        Cover(a.cover, Modifier.size(size), RoundedCornerShape(10.dp))
        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text("Album · ${a.artist} · ${a.tracks.size} brani", style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Home: music published by Vibes users (new songs, trending, albums). */
@Composable
fun VibesCommunityShelves() {
    val d = rememberCommunity() ?: return
    val nav = LocalApp.current.nav
    if (d.fresh.isEmpty() && d.albums.isEmpty()) return
    Column {
        if (d.fresh.isNotEmpty()) {
            SectionTitle("🎙️ Nuovi su Vibes", action = "Studio") { nav.open(Route.Studio) }
            Text("Musica pubblicata da chi usa Vibes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(d.fresh, key = { _, u -> "vf" + u.id }) { i, u -> Box(Modifier.enter(i)) { UploadCard(u, d.fresh, i) } }
            }
        }
        if (d.trending.size >= 3) {
            SectionTitle("🔥 Di tendenza su Vibes")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(d.trending, key = { _, u -> "vt" + u.id }) { i, u -> Box(Modifier.enter(i)) { UploadCard(u, d.trending, i) } }
            }
        }
        if (d.albums.isNotEmpty()) {
            SectionTitle("💿 Album della community")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(d.albums, key = { _, a -> "va" + a.id }) { i, a -> Box(Modifier.enter(i)) { VibesAlbumCard(a) } }
            }
        }
    }
}

/** An album published on Vibes (or, with [SAVED_VIBES], the Vibes songs you saved). */
@Composable
fun VibesReleaseScreen(id: String) {
    val app = LocalApp.current
    val nav = app.nav
    val scope = rememberCoroutineScope()
    var album by remember { mutableStateOf<VibesAlbum?>(null) }
    var songs by remember { mutableStateOf<List<Upload>?>(null) }
    val saved by VibesUploadSaves.ids.collectAsState()
    var likes by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(id, if (id == SAVED_VIBES) saved.size else 0) {
        if (id == SAVED_VIBES) {
            VibesUploadSaves.ensure()
            val byId = runCatching { app.social.uploadsByIds(saved) }.getOrDefault(emptyList()).associateBy { it.id }
            songs = saved.mapNotNull { byId[it] }
        } else {
            val a = runCatching { app.social.albumById(id) }.getOrNull()
            album = a
            val byId = a?.let { runCatching { app.social.uploadsByIds(it.tracks) }.getOrNull() }.orEmpty().associateBy { it.id }
            songs = a?.tracks?.mapNotNull { byId[it] }.orEmpty()
        }
        likes = runCatching { app.social.myLikes() }.getOrDefault(emptySet())
    }
    val title = if (id == SAVED_VIBES) "Salvati su Vibes" else album?.title ?: ""
    val cover = if (id == SAVED_VIBES) songs?.firstOrNull()?.cover else album?.cover
    val isMine = album?.userId != null && album?.userId == app.social.uid
    GradientBackground(tint = Color(0xFF8A5CFF)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Spacer(Modifier.weight(1f))
                    if (isMine) IconButton(onClick = {
                        scope.launch { runCatching { app.social.deleteAlbum(id) }.onSuccess { nav.toast("Album eliminato (i brani restano)"); nav.back() } }
                    }) { Icon(Icons.Rounded.Delete, "Elimina album") }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(cover, Modifier.size(220.dp), RoundedCornerShape(14.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                    val sub = if (id == SAVED_VIBES) "Brani della community che hai salvato" else listOfNotNull(
                        album?.artist, "Album su Vibes", songs?.let { "${it.size} brani" }, album?.genre?.ifBlank { null }).joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    album?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    val l = songs.orEmpty()
                    if (l.isNotEmpty()) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("▶  Riproduci", { playUploads(app, l, 0) }, Modifier.width(150.dp))
                        BigButton("🔀  Casuale", { playUploads(app, l.shuffled(), 0) }, Modifier.width(150.dp),
                            container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val l = songs
            if (l == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            else if (l.isEmpty()) item { EmptyState("🎙️", if (id == SAVED_VIBES) "Niente di salvato" else "Album non trovato",
                if (id == SAVED_VIBES) "Premi il segnalibro su un brano della community per ritrovarlo qui." else "Forse è stato eliminato.") }
            else itemsIndexed(l, key = { _, u -> u.id }) { i, u ->
                UploadRow(u, null, Modifier.enter(i), liked = u.id in likes, onLike = {
                    val on = u.id !in likes
                    likes = if (on) likes + u.id else likes - u.id
                    scope.launch { runCatching { app.social.like(u.id, on) } }
                }) { playUploads(app, l, i) }
            }
        }
    }
}

// =====================================================================================  studio analytics

/**
 * Studio > Statistiche: like YouTube Studio for your songs. Plays are real plays (a third of the song
 * heard, at most one every 10 minutes per person), plus listeners, minutes, saves and likes.
 */
@Composable
fun StudioStatsScreen() {
    val app = LocalApp.current
    val nav = app.nav
    var days by remember { mutableStateOf(28) }
    var stats by remember { mutableStateOf<StudioStats?>(null) }
    var mine by remember { mutableStateOf<List<Upload>?>(null) }
    var albums by remember { mutableStateOf<List<VibesAlbum>>(emptyList()) }
    var sort by remember { mutableStateOf(0) } // 0 ascolti, 1 periodo, 2 salvati, 3 preferiti
    LaunchedEffect(Unit) {
        val me = app.social.uid
        mine = me?.let { runCatching { app.social.uploadsOf(it) }.getOrNull() }.orEmpty()
        albums = me?.let { runCatching { app.social.albumsOf(it) }.getOrNull() }.orEmpty()
    }
    LaunchedEffect(days) { stats = runCatching { app.social.studioStats(days) }.getOrNull() }
    val list = mine
    val accent = Color(0xFF2BB6FF)
    GradientBackground(tint = accent) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Column {
                        Text("Statistiche", style = MaterialTheme.typography.headlineSmall)
                        Text("Come va la tua musica su Vibes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(16.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7 to "7 giorni", 28 to "28 giorni", 90 to "90 giorni", 365 to "Un anno").forEach { (d, l) ->
                        item { Pill(l, days == d, { days = d }) }
                    }
                }
            }
            if (list == null) item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
            else if (list.isEmpty()) item {
                EmptyState("📊", "Ancora niente da contare", "Pubblica un singolo o un album: qui vedrai ascolti, ascoltatori, salvati e preferiti.") {
                    BigButton("Pubblica", { nav.open(Route.Publish) }, Modifier.width(160.dp))
                }
            } else {
                val st = stats
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("Ascolti", st?.playsPeriod?.let(::fmtInt) ?: "…", "negli ultimi $days giorni", Modifier.weight(1f), accent)
                            StatCard("Ascoltatori", st?.listenersPeriod?.let(::fmtInt) ?: "…", "persone diverse", Modifier.weight(1f), Color(0xFF8A5CFF))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("Salvati", fmtInt(list.sumOf { it.saves }), "in totale", Modifier.weight(1f), Color(0xFFFF8A2B))
                            StatCard("Preferiti", fmtInt(list.sumOf { it.likes }), "❤️ in totale", Modifier.weight(1f), Color(0xFFFF4D8D))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatCard("Ascolti totali", fmtInt(list.sumOf { it.plays }), "da sempre", Modifier.weight(1f), Color(0xFF1FB58F))
                            StatCard("Minuti ascoltati", st?.minutes?.let { fmtInt(it.toInt()) } ?: "…", "della tua musica", Modifier.weight(1f), Color(0xFFE0B020))
                        }
                    }
                }
                st?.let { s ->
                    item {
                        Column(Modifier.padding(16.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp)) {
                            Text("Ascolti al giorno", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            PlaysChart(s.daily.map { it.plays }, accent, Modifier.fillMaxWidth().height(140.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Text(s.daily.firstOrNull()?.day?.drop(5)?.replace('-', '/') ?: "", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text("oggi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("I tuoi brani", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                    LazyRow(contentPadding = PaddingValues(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Più ascoltati", "Nel periodo", "Più salvati", "Più amati").forEachIndexed { i, l -> item { Pill(l, sort == i, { sort = i }) } }
                    }
                }
                val per = st?.perTrack.orEmpty()
                val sorted = when (sort) {
                    1 -> list.sortedByDescending { per[it.id]?.period ?: 0 }
                    2 -> list.sortedByDescending { it.saves }
                    3 -> list.sortedByDescending { it.likes }
                    else -> list.sortedByDescending { it.plays }
                }
                itemsIndexed(sorted, key = { _, u -> "s" + u.id }) { i, u ->
                    TrackStatsRow(i + 1, u, per[u.id]?.period ?: 0, per[u.id]?.listeners ?: 0, Modifier.enter(i)) { playUploads(app, sorted, i) }
                }
                if (albums.isNotEmpty()) {
                    item { SectionTitle("I tuoi album") }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(albums, key = { _, a -> a.id }) { _, a -> VibesAlbumCard(a, 130.dp) }
                        }
                    }
                }
                item {
                    Text("Un ascolto conta quando qualcuno sente almeno un terzo del brano (massimo uno ogni 10 minuti a persona).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, sub: String, modifier: Modifier, color: Color) {
    Column(modifier.glass(RoundedCornerShape(18.dp), tint = color)
        .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)), RoundedCornerShape(18.dp)).padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaysChart(values: List<Int>, color: Color, modifier: Modifier) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val empty = values.all { it == 0 }
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            if (values.isEmpty()) return@Canvas
            val gap = if (values.size > 60) 1f else 3.dp.toPx()
            val w = (size.width - gap * (values.size - 1)) / values.size
            values.forEachIndexed { i, v ->
                val h = if (v == 0) 2.dp.toPx() else (size.height * v / max).coerceAtLeast(4.dp.toPx())
                drawRoundRect(
                    if (v == 0) color.copy(alpha = 0.18f) else color,
                    topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h),
                    cornerRadius = CornerRadius(w / 3, w / 3),
                )
            }
        }
        if (empty) Text("Ancora nessun ascolto in questo periodo", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
        else Text("max ${fmtInt(max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun TrackStatsRow(n: Int, u: Upload, period: Int, listeners: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().bouncy(onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$n", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(26.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Cover(u.cover, Modifier.size(50.dp), RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(u.album, if (u.visibility != "public") "🔒 follower" else null).joinToString(" · ").ifBlank { "Singolo" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
                Text("▶ ${fmtInt(u.plays)}", style = MaterialTheme.typography.labelMedium)
                Text("+${fmtInt(period)} nel periodo", style = MaterialTheme.typography.labelMedium, color = Color(0xFF2BB6FF))
                Text("👤 ${fmtInt(listeners)}", style = MaterialTheme.typography.labelMedium)
                Text("🔖 ${fmtInt(u.saves)}", style = MaterialTheme.typography.labelMedium)
                Text("❤️ ${fmtInt(u.likes)}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
