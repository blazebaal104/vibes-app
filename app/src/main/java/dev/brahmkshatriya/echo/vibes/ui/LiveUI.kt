package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.backend.LiveListen
import dev.brahmkshatriya.echo.vibes.backend.Profile
import kotlinx.coroutines.delay

fun fmtTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Polls someone's live state every 2.5 s while visible. */
@Composable
fun rememberLive(userId: String, initial: LiveListen?): LiveListen? {
    val app = LocalApp.current
    var live by remember(userId) { mutableStateOf(initial) }
    LaunchedEffect(userId) {
        while (true) {
            runCatching { app.social.live(userId) }.onSuccess { live = it }
            delay(2500)
        }
    }
    return live?.takeIf { it.isFresh }
}

/** Live "sta ascoltando" card on a profile: breathing cover, sound bars, real-time progress, listen along. */
@Composable
fun LiveCard(p: Profile, isMe: Boolean) {
    val app = LocalApp.current
    val nav = app.nav
    val live = rememberLive(p.id, p.live)
    val session by app.along.session.collectAsState()
    val together = session?.friend?.id == p.id
    AnimatedVisibility(live != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val l = live ?: return@AnimatedVisibility
        var now by remember { mutableLongStateOf(l.positionNow()) }
        LaunchedEffect(l) { while (true) { withFrameMillis { }; now = l.positionNow() } }
        val breathe by rememberInfiniteTransition(label = "lb").animateFloat(1f, if (l.playing) 1.06f else 1f,
            infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lbv")
        val accent = parseColor(p.avatarColor)
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), LocalVibes.current.card)))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (l.playing) Color(0xFF1ED760) else Color(0xFFFFB020)))
                Spacer(Modifier.width(6.dp))
                Text(
                    when { !l.playing -> "In pausa"; isMe -> "Stai ascoltando"; else -> "Sta ascoltando ora" } +
                            (l.ctx?.let { " · da $it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                if (l.playing) EqualizerBars(Modifier.size(18.dp))
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(l.song.cover, Modifier.size(64.dp).graphicsLayer { scaleX = breathe; scaleY = breathe }, RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(l.song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    l.queue.firstOrNull()?.let {
                        Text("Dopo: ${it.title}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // real-time slider
            val dur = l.duration.coerceAtLeast(1)
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
                Box(Modifier.fillMaxWidth((now.toFloat() / dur).coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(accent))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(fmtTime(now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(fmtTime(l.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isMe) {
                val bg by animateColorAsState(if (together) accent else Color.White, label = "tg")
                Row(
                    Modifier.padding(top = 10.dp).fillMaxWidth().clip(CircleShape).background(bg)
                        .bouncy({ if (together) app.along.stop() else { app.along.start(p); nav.toast("Ascolti insieme a ${p.name} 🎧") } })
                        .padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Headphones, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (together) "Smetti di ascoltare insieme" else "Ascolta insieme", color = Color.Black,
                        style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** Shown above the mini player while you're listening along with someone. */
@Composable
fun AlongBanner() {
    val app = LocalApp.current
    val session by app.along.session.collectAsState()
    AnimatedVisibility(session != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val s = session ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .glass(RoundedCornerShape(14.dp), tint = parseColor(s.friend.avatarColor), blur = true)
                .background(parseColor(s.friend.avatarColor).copy(alpha = 0.45f)).clickable { app.nav.open(Route.Profile(s.friend.id)) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(s.friend, 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Ascolti insieme a ${s.friend.name}" + when {
                    s.live == null -> " · in attesa…"
                    !s.live.playing -> " · in pausa"
                    else -> ""
                },
                style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (s.live?.playing == true) EqualizerBars(Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Close, "Esci", tint = Color.White, modifier = Modifier.size(20.dp).clip(CircleShape).clickable { app.along.stop() })
        }
    }
}
