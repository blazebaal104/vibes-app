package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FavoriteBorder
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.vibes.backend.UploadActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Like Instagram's heart: who liked or saved the songs you published. A red dot on the heart when
 * something new arrived since you last opened it. Checked with one tiny request while the app is open.
 */
object UploadActivityFeed {
    private val _items = MutableStateFlow<List<UploadActivity>>(emptyList())
    val items: StateFlow<List<UploadActivity>> = _items
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread
    @Volatile private var lastCheck = 0L

    private fun seenFile() = runCatching { File(VibesGraph.filesDir, "activity_seen.txt") }.getOrNull()
    private fun seenAt(): String = runCatching { seenFile()?.readText()?.trim() }.getOrNull().orEmpty()

    /** At most once a minute unless [force]. */
    suspend fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60_000) return
        lastCheck = now
        val list = runCatching { VibesGraph.social.activity(60) }.getOrNull() ?: return
        _items.value = list
        val seen = seenAt()
        _unread.value = list.count { it.createdAt > seen }
    }

    fun markSeen() {
        val newest = _items.value.maxOfOrNull { it.createdAt } ?: return
        runCatching { seenFile()?.writeText(newest) }
        _unread.value = 0
    }
}

/** The heart button with its red dot (home on the phone, top bar on the PC). */
@Composable
fun ActivityHeart(size: Dp = 48.dp, onOpen: () -> Unit) {
    val unread by UploadActivityFeed.unread.collectAsState()
    LaunchedEffect(Unit) { UploadActivityFeed.refresh() }
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.FavoriteBorder, "Mi piace ai tuoi brani")
        if (unread > 0) Box(
            Modifier.align(Alignment.Center).offset(x = 8.dp, y = 8.dp).size(10.dp).clip(CircleShape)
                .background(Color(0xFFFF2D55)).border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
        )
    }
}

@Composable
fun ActivityScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val items by UploadActivityFeed.items.collectAsState()
    var people by remember { mutableStateOf<Map<String, Profile>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        UploadActivityFeed.refresh(force = true)
        loading = false
        UploadActivityFeed.markSeen()
    }
    LaunchedEffect(items) {
        val ids = items.map { it.userId }.distinct().filter { it !in people }
        if (ids.isNotEmpty()) people = people + ids.mapNotNull { runCatching { app.social.profile(it) }.getOrNull() }.associateBy { it.id }
    }
    GradientBackground(tint = Color(0xFFFF2D55)) {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Attività", style = MaterialTheme.typography.headlineSmall)
                }
                Text("Chi ha messo mi piace o salvato i brani che hai pubblicato.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (items.isEmpty()) item {
                if (loading) Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
                else EmptyState("🤍", "Ancora niente", "Quando qualcuno mette ❤️ o salva un tuo brano lo vedi qui.")
            }
            itemsIndexed(items, key = { i, a -> "${a.kind}${a.userId}${a.uploadId}$i" }) { i, a ->
                val p = people[a.userId]
                Row(
                    Modifier.fillMaxWidth().enter(i).clickable { nav.open(Route.Profile(a.userId)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(p, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            (p?.name ?: "Qualcuno") + if (a.kind == "like") " ha messo ❤️ a" else " ha salvato",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text("\"${a.title}\" · ${timeAgo(a.createdAt)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Cover(a.cover, Modifier.size(44.dp), RoundedCornerShape(8.dp))
                }
            }
        }
    }
}
