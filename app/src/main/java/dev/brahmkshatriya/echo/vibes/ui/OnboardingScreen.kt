package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.FavArtist
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic.ArtistInfo
import dev.brahmkshatriya.echo.vibes.music.FollowedArtist
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First run: "who do you listen to?" → Vibes builds playlists from those artists (and similar
 * ones), follows them for new releases and sets the favourite artist on the profile.
 */
@Composable
fun OnboardingScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var popular by remember { mutableStateOf<List<ArtistInfo>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ArtistInfo>>(emptyList()) }
    val chosen = remember { mutableStateListOf<ArtistInfo>() }
    var building by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val global = runCatching { VibesGraph.deezer.chartArtists(40) }.getOrDefault(emptyList())
        val italian = listOf("Sfera Ebbasta", "Anna", "Geolier", "Lazza", "Shiva", "Tedua", "Måneskin", "Annalisa", "Mahmood", "Ultimo",
            "Marracash", "Tony Effe", "Elodie", "Olly", "Rose Villain", "Tananai").mapNotNull { n ->
            runCatching { VibesGraph.deezer.searchArtists(n, 1).firstOrNull() }.getOrNull()
        }
        popular = (italian + global).distinctBy { it.id }
    }
    LaunchedEffect(q) {
        if (q.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        results = runCatching { VibesGraph.deezer.searchArtists(q, 12) }.getOrDefault(emptyList())
    }

    fun finish() {
        building = true
        scope.launch {
            val lib = app.library
            val all = mutableListOf<SongRef>()
            chosen.take(6).forEachIndexed { i, a ->
                stage = "Creo il mix di ${a.name}…"
                runCatching {
                    val own = VibesGraph.deezer.artistTop(a.id, 12).map { it.toSongRef() }
                    val similar = VibesGraph.deezer.related(a.id).take(5).flatMap { (rid, _) ->
                        runCatching { VibesGraph.deezer.artistTop(rid, 3).map { it.toSongRef() } }.getOrDefault(emptyList())
                    }
                    val mix = (own.take(8) + similar).shuffled() .distinctBy { it.key }
                    all += own.take(4) + similar.take(4)
                    if (mix.isNotEmpty()) lib.createPlaylist("Mix · ${a.name}", "✨", avatarColors.random(), mix, cover = a.picture)
                    lib.followArtist(FollowedArtist(a.id, a.name, a.picture))
                }
                progress = (i + 1f) / (chosen.size.coerceAtMost(6) + 1)
            }
            stage = "Ultimi ritocchi…"
            if (all.isNotEmpty()) lib.createPlaylist("Il tuo mix Vibes", "💜", "#B84DFF", all.shuffled().distinctBy { it.key })
            chosen.firstOrNull()?.let { runCatching { app.social.setFavArtist(FavArtist(it.id, it.name, it.picture)) } }
            progress = 1f
            delay(600)
            VibesGraph.prefs.update { it.copy(needsOnboarding = false) }
            app.nav.toast("Le tue playlist sono pronte in Libreria 🎉")
            if (dev.brahmkshatriya.echo.vibes.music.DeezerAuth.loggedIn == false) app.nav.open(Route.DeezerLogin)
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A1466), MaterialTheme.colorScheme.background)))) {
        AnimatedContent(building, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "ob",
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp)) { b ->
            if (b) Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎧", fontSize = 72.sp)
                Text("Preparo la tua musica", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                Text(stage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                val p by animateFloatAsState(progress, label = "obp")
                LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 20.dp).fillMaxWidth().height(10.dp).clip(CircleShape))
            } else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text("Chi ascolti di più?", style = MaterialTheme.typography.headlineLarge)
                    Text("Scegli almeno 3 artisti: ti creo subito delle playlist con loro e con artisti simili.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                    SearchField(q, { q = it }, "Cerca un artista")
                }
                val list = if (q.isNotBlank()) results else popular
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (list.isEmpty()) item(span = { GridItemSpan(3) }) {
                        Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
                    }
                    items(list, key = { it.id }) { a ->
                        val sel = chosen.any { it.id == a.id }
                        val sc by animateFloatAsState(if (sel) 1.08f else 1f, spring(dampingRatio = 0.4f, stiffness = 400f), label = "s${a.id}")
                        Column(Modifier.bouncy({ if (sel) chosen.removeAll { it.id == a.id } else chosen += a }),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(contentAlignment = Alignment.BottomEnd) {
                                Cover(a.picture, Modifier.size(96.dp).graphicsLayer { scaleX = sc; scaleY = sc }
                                    .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier), CircleShape)
                                if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.White))
                            }
                            Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                Column(Modifier.padding(16.dp)) {
                    BigButton(if (chosen.size < 3) "Scegline ancora ${3 - chosen.size}" else "Continua (${chosen.size})", { finish() },
                        Modifier.fillMaxWidth(), enabled = chosen.size >= 3)
                    Spacer(Modifier.height(6.dp))
                    Text("Salta", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp)
                            .bouncy({ VibesGraph.prefs.update { it.copy(needsOnboarding = false) } }))
                }
            }
        }
    }
}
