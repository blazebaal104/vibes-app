package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.ui.Avatar
import dev.brahmkshatriya.echo.vibes.ui.LocalApp
import dev.brahmkshatriya.echo.vibes.ui.PlatformUI
import dev.brahmkshatriya.echo.vibes.ui.Route
import dev.brahmkshatriya.echo.vibes.ui.VibesLogo

/** Logo, back/forward, home + search in the middle, the social things and your profile on the right. */
@Composable
fun TopBar(searchFocus: FocusRequester, onBack: () -> Unit, onForward: () -> Unit) {
    val app = LocalApp.current
    val nav = app.nav
    val desk = LocalDesk.current
    val me by app.social.me.collectAsState()
    val unread by app.social.unread.collectAsState()
    val requests by app.social.requests.collectAsState()
    val badge = unread.values.sum() + requests.size
    val query = PlatformUI.searchQuery!!
    Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        VibesLogo(Modifier.size(34.dp))
        Text("Vibes", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(start = 8.dp, end = 14.dp))
        NavArrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Indietro", nav.canGoBack) { onBack() }
        Spacer(Modifier.width(6.dp))
        NavArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Avanti", desk.forward.isNotEmpty()) { onForward() }
        Spacer(Modifier.width(16.dp))
        // the Vibes sections in one glass pill: Home, Studio, Giochi, Amici
        Row(Modifier.height(46.dp).deskGlass(androidx.compose.foundation.shape.RoundedCornerShape(23.dp), dark = 0.3f).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val home = nav.tab == Route.Home
            Segment(if (home) Icons.Rounded.Home else Icons.Outlined.Home, "Home", home) { nav.selectTab(Route.Home); query.value = "" }
            Segment(Icons.Rounded.Tune, "Studio", nav.tab == Route.Studio) { nav.selectTab(Route.Studio) }
            Segment(Icons.Rounded.SportsEsports, "Giochi", nav.tab == Route.Games) { nav.selectTab(Route.Games) }
            Segment(Icons.Rounded.Forum, "Amici", nav.tab == Route.Friends, badge) { nav.selectTab(Route.Friends) }
        }
        Spacer(Modifier.weight(1f))
        var focused by remember { mutableStateOf(false) }
        var hover by remember { mutableStateOf(false) }
        val border by animateColorAsState(if (focused) Color.White.copy(alpha = 0.8f) else if (hover) Color.White.copy(alpha = 0.3f) else Color.Transparent, label = "sb")
        Row(
            Modifier.widthIn(min = 220.dp, max = 460.dp).weight(2f, fill = false).height(46.dp)
                .deskGlass(CircleShape, dark = 0.3f).border(1.5.dp, border, CircleShape).hovered { hover = it }
                .clickable { searchFocus.requestFocus() }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = if (focused) Color.White else Desk.sub, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.value.isEmpty()) Text("Brani, artisti, amici…", color = Desk.sub, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                BasicTextField(
                    query.value, { query.value = it; if (it.isNotEmpty() && nav.tab != Route.Search) nav.selectTab(Route.Search) },
                    singleLine = true, cursorBrush = SolidColor(Color.White),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).onFocusChanged { focused = it.isFocused }
                        .onPreviewKeyEvent { e -> if (e.key == Key.Enter) { if (query.value.isNotBlank()) app.library.addSearch(query.value.trim()); false } else false },
                )
            }
            if (query.value.isNotEmpty()) Icon(Icons.Rounded.Close, "Cancella", tint = Desk.sub, modifier = Modifier.size(20.dp).clickable { query.value = "" })
            DeskIcon(Icons.Rounded.Explore, "Sfoglia generi", active = nav.tab == Route.Search && query.value.isEmpty(), size = 34.dp, iconSize = 20.dp) {
                query.value = ""; nav.selectTab(Route.Search)
            }
        }
        Spacer(Modifier.weight(1f))
        DeskIcon(Icons.Rounded.Group, "Attività degli amici", active = desk.right == RightPanel.Friends, size = 42.dp, iconSize = 22.dp) { desk.toggleRight(RightPanel.Friends) }
        Spacer(Modifier.width(8.dp))
        ContextMenuBox(items = {
            listOf(
                MenuItem("Profilo", Icons.Rounded.Person) { nav.open(Route.Profile(null)) },
                MenuItem("Le tue statistiche", Icons.Rounded.BarChart) { nav.open(Route.Stats) },
                MenuItem("Cronologia", Icons.Rounded.History) { nav.open(Route.History) },
                MenuItem("Scaricati", Icons.Rounded.Download) { nav.open(Route.Downloads) },
                MenuItem("Importa playlist", Icons.Rounded.SystemUpdateAlt) { nav.open(Route.Import) },
                null,
                MenuItem("Impostazioni", Icons.Rounded.Settings) { nav.open(Route.Settings) },
            )
        }) { open ->
            Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(VibesGradient)).padding(2.dp).pointerHoverIcon(HandCursor).clickable { open() },
                contentAlignment = Alignment.Center) { Avatar(me, 40.dp) }
        }
        // the essentials one click away (they were only inside the profile menu)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.height(46.dp).deskGlass(CircleShape, dark = 0.3f).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(36.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                dev.brahmkshatriya.echo.vibes.ui.ActivityHeart(36.dp) { nav.open(Route.Activity) }
            }
            DeskIcon(Icons.Rounded.BarChart, "Le tue statistiche", active = nav.current == Route.Stats, size = 36.dp, iconSize = 21.dp) { nav.open(Route.Stats) }
            DeskIcon(Icons.Rounded.History, "Cronologia", active = nav.current == Route.History, size = 36.dp, iconSize = 21.dp) { nav.open(Route.History) }
            DeskIcon(Icons.Rounded.Settings, "Impostazioni", active = nav.current == Route.Settings, size = 36.dp, iconSize = 21.dp) { nav.open(Route.Settings) }
        }
    }
}

/** One section of the top pill: the selected one is filled with the Vibes gradient. */
@Composable
private fun Segment(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    var hover by remember { mutableStateOf(false) }
    val sel by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, label = "seg")
    Row(
        Modifier.height(38.dp).clip(CircleShape)
            .background(Brush.linearGradient(VibesGradient.map { it.copy(alpha = sel) }))
            .background(if (hover && !selected) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .hovered { hover = it }.pointerHoverIcon(HandCursor).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        if (badge > 0) Box(Modifier.padding(start = 6.dp).clip(CircleShape).background(Color.White).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text("$badge", color = Color.Black, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NavArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
        .then(if (enabled) Modifier.pointerHoverIcon(HandCursor).clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (enabled) Color.White else Desk.sub.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
    }
}

