package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.vibes.theme.VibesTheme
import dev.brahmkshatriya.echo.vibes.theme.VibesThemes
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.supa.session.collectAsState()
    val deezer by app.engine.deezerLoggedIn.collectAsState(initial = false)
    var themes by remember { mutableStateOf(VibesThemes.all(context)) }
    var editor by remember { mutableStateOf<VibesTheme?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    fun choose(t: VibesTheme) {
        VibesThemes.apply(context.getSettings(), t)
        app.theme = t
    }

    GradientBackground {
        LazyColumn(contentPadding = ScreenPadding) {
            item {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                    Text("Impostazioni", style = MaterialTheme.typography.headlineSmall)
                }
            }
            // feedback first: easy to find when something goes wrong
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)).clickable { nav.open(Route.Feedback) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🐞", style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Segnala un problema o suggerisci un'idea", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Scrivici cosa non va: ti rispondiamo qui nell'app", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (app.social.isAdmin) item {
                var nuove by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(Unit) { nuove = runCatching { dev.brahmkshatriya.echo.vibes.VibesGraph.feedback.all().count { it.status == "nuovo" } }.getOrNull() }
                SettingRow("Segnalazioni ricevute (admin)", nuove?.let { if (it == 0) "Nessuna nuova" else "$it nuove da leggere" } ?: "Carico…", "Apri") {
                    nav.open(Route.FeedbackAdmin)
                }
            }
            item { SectionTitle("Tema") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(themes, key = { it.id }) { t ->
                        ThemeCard(t, t.id == app.theme.id, { choose(t) }, if (!t.builtin) ({ editor = t }) else null)
                    }
                    item {
                        Box(
                            Modifier.size(110.dp, 160.dp).clip(RoundedCornerShape(18.dp))
                                .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                                .bouncy({ creating = true }),
                            contentAlignment = Alignment.Center
                        ) { Text("+\nCrea tema", textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
            item {
                Text("Tieni premuto un tema creato da te per modificarlo o eliminarlo.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
            vibesSettings()
            item { SectionTitle("Musica") }
            item {
                SettingRow(
                    "Account Deezer",
                    if (deezer) "Collegato: puoi cercare e ascoltare tutto" else "Non collegato",
                    action = if (deezer) "Scollega" else "Collega"
                ) {
                    if (deezer) scope.launch { app.engine.logoutDeezer(); nav.toast("Deezer scollegato") }
                    else nav.open(Route.DeezerLogin)
                }
            }
            item { SectionTitle("Account") }
            item { SettingRow("Email", session?.email ?: "", null) {} }
            item { SettingRow("Profilo", "Nome, avatar, bio e Top 4", "Apri") { nav.open(Route.Profile(null)) } }
            item { SettingRow("Esci", "Esci dal tuo account Vibes", "Esci") { confirmLogout = true } }
            item {
                Text(
                    "Vibes · basata sul motore di Gladix ed Echo",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    if (creating || editor != null) ThemeEditor(editor, onDismiss = { creating = false; editor = null }, onDelete = editor?.let { e ->
        {
            VibesThemes.deleteCustom(context, e.id)
            themes = VibesThemes.all(context)
            app.theme = VibesThemes.current(context) ?: VibesThemes.presets.first()
            editor = null
        }
    }) { t ->
        VibesThemes.saveCustom(context, t)
        themes = VibesThemes.all(context)
        choose(t)
        creating = false; editor = null
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Uscire da Vibes?") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; app.supa.signOut() }) { Text("Esci") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Annulla") } }
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, action: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) Text(action, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ThemeCard(t: VibesTheme, selected: Boolean, onClick: () -> Unit, onLong: (() -> Unit)?) {
    val accent = Color(t.accent)
    val text = if (t.dark) Color.White else Color(0xFF15121A)
    Box(
        Modifier.size(110.dp, 160.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(t.bgTop), if (t.amoled && t.dark) Color.Black else Color(t.bgBottom))))
            .then(if (selected) Modifier.border(3.dp, accent, RoundedCornerShape(18.dp)) else Modifier)
            .bouncy(onClick, onLong)
            .padding(12.dp)
    ) {
        Column {
            Box(Modifier.size(56.dp, 18.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.size(70.dp, 6.dp).clip(CircleShape).background(text.copy(alpha = 0.6f)))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(50.dp, 6.dp).clip(CircleShape).background(text.copy(alpha = 0.35f)))
        }
        Text(t.name, color = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.BottomStart))
        if (selected) Box(
            Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Check, null, tint = if (accent.luminanceSafe() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(16.dp)) }
    }
}

private fun Color.luminanceSafe() = (0.299f * red + 0.587f * green + 0.114f * blue)

private val accentChoices = listOf(
    "#FF2E97", "#FF4D4D", "#FF7A59", "#FFB020", "#FFE14D", "#B5E61D", "#1ED760", "#00C2A8",
    "#35D0F5", "#3B82F6", "#6366F1", "#B84DFF", "#E879F9", "#C2410C", "#FFFFFF",
)
private val bgChoices = listOf(
    "#000000", "#0A0014", "#12051C", "#1A0B1E", "#04121F", "#0B1A10", "#1C1C1C", "#2B1B0E",
    "#4A1670", "#6B1F4F", "#0B4062", "#0F3D24", "#3A0CA3", "#7A1C1C", "#F6DDB8", "#FFF8EE", "#E8F0FF",
)

@Composable
fun ThemeEditor(editing: VibesTheme?, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (VibesTheme) -> Unit) {
    val base = editing ?: LocalApp.current.theme
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var accent by remember { mutableIntStateOf(base.accent) }
    var top by remember { mutableIntStateOf(base.bgTop) }
    var bottom by remember { mutableIntStateOf(base.bgBottom) }
    var mode by remember { mutableIntStateOf(if (!base.dark) 0 else if (base.amoled) 2 else 1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "Crea il tuo tema" else "Modifica tema") },
        text = {
            LazyColumn(Modifier.imePadding()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(16.dp))
                            .background(Brush.verticalGradient(listOf(Color(top), if (mode == 2) Color.Black else Color(bottom)))),
                        contentAlignment = Alignment.Center
                    ) { Box(Modifier.size(96.dp, 30.dp).clip(CircleShape).background(Color(accent))) }
                    Spacer(Modifier.height(12.dp))
                    VibesField(name, { name = it.take(24) }, "Nome del tema")
                }
                item { ColorRow("Colore principale", accentChoices, accent) { accent = it } }
                item { ColorRow("Sfondo – sopra", bgChoices, top) { top = it } }
                item { ColorRow("Sfondo – sotto", bgChoices, bottom) { bottom = it } }
                item {
                    Text("Base", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Chiaro", "Scuro", "Nero").forEachIndexed { i, t -> Pill(t, mode == i, { mode = i }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    VibesTheme(
                        id = editing?.id ?: "custom_${System.currentTimeMillis()}",
                        name = name.trim().ifEmpty { "Il mio tema" }, accent = accent,
                        dark = mode != 0, amoled = mode == 2, bgTop = top, bgBottom = bottom,
                    )
                )
            }) { Text("Salva e usa") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Elimina") }
                TextButton(onClick = onDismiss) { Text("Annulla") }
            }
        }
    )
}

@Composable
private fun ColorRow(label: String, colors: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(colors) { hex ->
                val c = parseColor(hex)
                val sel = c.toArgb() == selected
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(c)
                        .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onPick(c.toArgb()) }
                )
            }
        }
    }
}

@Composable
fun DeezerLoginScreen() {
    val app = LocalApp.current
    val nav = app.nav
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var arl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf(dev.brahmkshatriya.echo.vibes.music.DeezerAuth.takeStartWeb()) } // true = sign up, false = log in on deezer.com

    web?.let { signup ->
        GradientBackground {
            DeezerWebLogin(signup, onBack = { web = null }) { arl ->
                app.engine.loginWithArl(arl)
                dev.brahmkshatriya.echo.vibes.music.DeezerAuth.linked()
                nav.toast("Deezer collegato 🎉 ora puoi ascoltare tutto")
                nav.back()
            }
        }
        return
    }

    GradientBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 20.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
                Text("Collega Deezer", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "La musica di Vibes arriva da Deezer. Non hai un account? Crealo qui dentro in un minuto, è gratis: " +
                        "appena hai finito Vibes si collega da solo.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            BigButton("Crea account Deezer gratis", { web = true }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            BigButton("Ho già Deezer: accedi", { web = false }, Modifier.fillMaxWidth(),
                container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(26.dp))
            Text("Oppure inserisci i dati qui", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(
                "Vibes usa il tuo account Deezer per cercare e riprodurre la musica. Le credenziali restano solo sul telefono.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Email e password", tab == 0, { tab = 0; error = null })
                Pill("Codice ARL", tab == 1, { tab = 1; error = null })
            }
            Spacer(Modifier.height(18.dp))
            if (tab == 0) {
                VibesField(email, { email = it }, "Email Deezer", keyboard = androidx.compose.ui.text.input.KeyboardType.Email)
                Spacer(Modifier.height(12.dp))
                VibesField(pass, { pass = it }, "Password Deezer", password = true)
            } else {
                VibesField(arl, { arl = it.trim() }, "ARL")
                Text(
                    "Se il login con password non funziona (a volte Deezer chiede un captcha): accedi a deezer.com dal browser, " +
                            "apri i cookie del sito e copia il valore del cookie \"arl\".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            error?.let { Text(it, color = Color(0xFFFF5C7A), modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(22.dp))
            BigButton(
                "Collega", loading = loading, modifier = Modifier.fillMaxWidth(),
                enabled = if (tab == 0) email.contains("@") && pass.isNotEmpty() else arl.length > 40,
                onClick = {
                    loading = true; error = null
                    scope.launch {
                        runCatching { if (tab == 0) app.engine.loginWithPassword(email, pass) else app.engine.loginWithArl(arl) }
                            .onSuccess { dev.brahmkshatriya.echo.vibes.music.DeezerAuth.linked(); nav.toast("Deezer collegato 🎉"); nav.back() }
                            .onFailure {
                                error = (it.message ?: "Accesso non riuscito") +
                                        if (tab == 0) "\nProva con il codice ARL." else ""
                            }
                        loading = false
                    }
                }
            )
        }
    }
}
