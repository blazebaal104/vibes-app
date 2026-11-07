package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.verticalScroll

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import kotlinx.coroutines.delay

@Composable
fun currentSong(): Pair<SongRef, Boolean>? {
    val app = LocalApp.current
    val cur by app.engine.player.playerState.current.collectAsState()
    val c = cur ?: return null
    val song = remember(c.mediaItem.mediaId) { SongRef.from(c.mediaItem.extensionId, c.track) }
    return song to c.isPlaying
}

/** Position / duration, polled while visible. */
@Composable
fun rememberProgress(): Pair<Long, Long> {
    val app = LocalApp.current
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            pos = app.engine.position(); dur = app.engine.duration()
            delay(250)
        }
    }
    return pos to dur
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun MiniPlayer() {
    val app = LocalApp.current
    val (song, playing) = currentSong() ?: return
    val (pos, dur) = rememberProgress()
    val v = LocalVibes.current
    Box(
        Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(62.dp)
            .glass(RoundedCornerShape(18.dp), tint = v.accent, blur = true, fallback = v.cardHigh)
            .background(Brush.horizontalGradient(listOf(v.accent.copy(alpha = 0.30f), Color.Transparent)))
            .clickable { app.nav.playerOpen = true }
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -12) app.nav.playerOpen = true } }
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(song.cover, Modifier.size(44.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(10.dp))
            AnimatedContent(song.key, transitionSpec = {
                (slideInHorizontally { it / 3 } + fadeIn()).togetherWith(fadeOut())
            }, label = "mini", modifier = Modifier.weight(1f)) { _ ->
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { app.nav.share = ShareItem.Song(song) }) { Icon(Icons.Rounded.Share, "Condividi") }
            IconButton(onClick = { app.engine.togglePlay() }) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausa" else "Play", modifier = Modifier.size(30.dp))
            }
        }
        // progress line along the bottom edge
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp).fillMaxWidth().height(2.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)))
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp)
            .fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).height(2.dp)
            .background(MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
fun FullPlayer() {
    val app = LocalApp.current
    val (song, playing) = currentSong() ?: run { app.nav.playerOpen = false; return }
    val (pos, dur) = rememberProgress()
    val liked by app.library.liked.collectAsState()
    val isLiked = liked.any { it.key == song.key }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    var shuffle by remember { mutableStateOf(app.engine.player.browser.value?.shuffleModeEnabled == true) }
    var repeat by remember { mutableIntStateOf(app.engine.player.browser.value?.repeatMode ?: 0) }
    var karaoke by remember { mutableStateOf(false) }
    if (karaoke) { KaraokeScreen(song, playing) { karaoke = false }; return }
    val baseScale by animateFloatAsState(if (playing) 1f else 0.86f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "cover")
    // "canvas": the cover slowly breathes while the song plays
    val breathe by androidx.compose.animation.core.rememberInfiniteTransition(label = "br").animateFloat(
        1f, 1.035f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse), label = "brv")
    val coverScale = baseScale * if (playing && LocalSettings.current.canvasCovers && !LocalSettings.current.reduceMotion) breathe else 1f
    val v = LocalVibes.current

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .clickable(enabled = false) {}
            .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d > 30) app.nav.playerOpen = false } }
    ) {
        // blurred artwork backdrop + theme gradient
        if (song.cover != null && Build.VERSION.SDK_INT >= 31) AsyncImage(
            song.cover, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(70.dp).scale(1.3f), alpha = 0.55f
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(v.bgTop.copy(alpha = 0.55f), MaterialTheme.colorScheme.background.copy(alpha = 0.75f), MaterialTheme.colorScheme.background)
        )))

        // scroll down under the controls for the synced lyrics (like Spotify)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenH = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        Column(Modifier.fillMaxWidth().height(screenH).statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { app.nav.playerOpen = false }) { Icon(Icons.Rounded.KeyboardArrowDown, "Chiudi", modifier = Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("IN RIPRODUZIONE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(song.album ?: song.artist, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { app.nav.share = ShareItem.Song(song) }) { Icon(Icons.Rounded.Share, "Condividi") }
            }
            Spacer(Modifier.weight(1f))
            AnimatedContent(song.key, transitionSpec = {
                (slideInHorizontally { it / 2 } + fadeIn()).togetherWith(fadeOut())
            }, label = "art") { _ ->
                Cover(song.cover, Modifier.fillMaxWidth().aspectRatio(1f).scale(coverScale), RoundedCornerShape(12.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val heart by animateFloatAsState(if (isLiked) 1.15f else 1f, spring(dampingRatio = 0.35f), label = "heart")
                IconButton(onClick = { app.library.toggleLike(song) }) {
                    Icon(
                        if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Mi piace",
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp).scale(heart)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val fraction = if (seeking) seekValue else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Slider(
                value = fraction,
                onValueChange = { seeking = true; seekValue = it },
                onValueChangeFinished = { app.engine.seekTo((seekValue * dur).toLong()); seeking = false },
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                ),
            )
            Row {
                Text(fmt(if (seeking) (seekValue * dur).toLong() else pos), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(fmt(dur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shuffle = !shuffle; app.engine.player.setShuffle(shuffle) }) {
                    Icon(Icons.Rounded.Shuffle, "Casuale", tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { app.engine.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Precedente", modifier = Modifier.size(40.dp))
                }
                val playScale by animateFloatAsState(if (playing) 1f else 1.06f, spring(dampingRatio = 0.4f), label = "play")
                Box(
                    Modifier.size(72.dp).scale(playScale).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)
                        .bouncy({ app.engine.togglePlay() }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pausa",
                        tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = { app.engine.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Successivo", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = {
                    repeat = (repeat + 1) % 3
                    app.engine.player.setRepeat(repeat)
                }) {
                    Icon(
                        if (repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Ripeti",
                        tint = if (repeat != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.weight(1f).height(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .bouncy({ app.nav.share = ShareItem.Song(song) }),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Share, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Condividi", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleSmall)
                }
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFFF4FD8), Color(0xFF8E7BFF))))
                        .bouncy({ karaoke = true }),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Mic, "Karaoke", tint = Color.White) }
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(LocalVibes.current.cardHigh)
                        .bouncy({ app.nav.addToPlaylist = song }),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.PlaylistAdd, "Aggiungi a playlist") }
                SongDownloadButton(song)
            }
            Spacer(Modifier.height(18.dp))
        }
        PlayerLyricsCard(song, pos, onOpenKaraoke = { karaoke = true })
        Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
        }
    }
}
