package dev.brahmkshatriya.echo.vibes.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.BASS_BOOST
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.PLAYBACK_SPEED
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.globalFx
import dev.brahmkshatriya.echo.playback.listener.EffectsListener.Companion.speedRange
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.VibesSettings
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ building blocks

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary)
        )
    }
}

@Composable
fun SliderRow(
    title: String, label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0,
    subtitle: String? = null, onChange: (Float) -> Unit, onDone: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(
            value, onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
fun ChoiceRow(title: String, subtitle: String?, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it.first }) { (k, l) -> Pill(l, k == selected, { onPick(k) }) }
        }
    }
}

@Composable
private fun Header(text: String, emoji: String) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.headlineSmall)
    }
}

private fun upd(block: (VibesSettings) -> VibesSettings) = VibesGraph.prefs.update(block)

// ------------------------------------------------------------------ all the extra settings

fun LazyListScope.vibesSettings() {
    // ---------------- look
    item { Header("Aspetto", "🎨") }
    item {
        val s = LocalSettings.current
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Font", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(VibesFonts, key = { it.first }) { (k, name) ->
                    val sel = k == s.font
                    Column(
                        Modifier.width(112.dp).glass(RoundedCornerShape(16.dp))
                            .border(if (sel) 2.5.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .bouncy({ upd { it.copy(font = k) } }).padding(12.dp)
                    ) {
                        Text("Aa", style = TextStyle(fontFamily = fontFamily(k), fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        Text(name, style = TextStyle(fontFamily = fontFamily(k), fontSize = 13.sp), maxLines = 1)
                    }
                }
            }
        }
    }
    item {
        val s = LocalSettings.current
        var v by remember { mutableFloatStateOf(s.textScale) }
        SliderRow("Dimensione testo", "${(v * 100).toInt()}%", v, 0.85f..1.3f, steps = 8, onChange = { v = it }, onDone = { upd { it.copy(textScale = v) } })
    }
    item {
        val s = LocalSettings.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch {
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            val bmp = android.graphics.BitmapFactory.decodeStream(input)
                            val max = 1600
                            val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
                            val out = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                            VibesGraph.prefs.backgroundFile.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                        }
                    }
                    upd { it.copy(background = "image") }
                }.onFailure { LocalAppHolder.app?.nav?.toast("Immagine non valida") }
            }
        }
        Column {
            ChoiceRow("Sfondo", "Dietro a tutte le schermate", listOf(
                "gradient" to "Sfumato", "solid" to "Tinta unita", "amoled" to "Nero AMOLED", "image" to "La tua foto"
            ), s.background) { k ->
                if (k == "image") picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                else upd { it.copy(background = k) }
            }
            AnimatedVisibility(s.background == "image") {
                Column {
                    var dim by remember { mutableFloatStateOf(s.backgroundDim) }
                    var blur by remember { mutableFloatStateOf(s.backgroundBlur.toFloat()) }
                    SliderRow("Oscuramento", "${(dim * 100).toInt()}%", dim, 0.1f..0.9f, onChange = { dim = it }, onDone = { upd { it.copy(backgroundDim = dim) } })
                    SliderRow("Sfocatura", "${blur.toInt()}", blur, 0f..40f, onChange = { blur = it }, onDone = { upd { it.copy(backgroundBlur = blur.toInt()) } })
                    Text("Cambia foto", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                }
            }
        }
    }
    item {
        val s = LocalSettings.current
        var v by remember { mutableFloatStateOf(s.corners.toFloat()) }
        SliderRow("Angoli delle copertine", "${v.toInt()} dp", v, 0f..28f, onChange = { v = it }, onDone = { upd { it.copy(corners = v.toInt()) } })
    }
    item { val s = LocalSettings.current; SwitchRow("Sfondo reattivo ✨", "Lo sfondo vive con quello che fai: le pagine si aprono dalla copertina nel suo colore, segue il brano in ascolto e scorre quando cambi sezione", s.reactiveBackground) { b -> upd { it.copy(reactiveBackground = b) } } }
    item { val s = LocalSettings.current; SwitchRow("Liquid glass", "Barre e schede di vetro smerigliato che lasciano intravedere ciò che c'è sotto", s.liquidGlass) { b -> upd { it.copy(liquidGlass = b) } } }
    item {
        val s = LocalSettings.current
        AnimatedVisibility(s.liquidGlass) {
            Column {
                var v by remember { mutableFloatStateOf(s.glassBlur) }
                SliderRow("Intensità del vetro", when { v < 0.8f -> "leggero"; v < 1.4f -> "normale"; else -> "forte" }, v, 0.4f..2f,
                    subtitle = "Quanto è sfocato quello che passa sotto (Android 12+)", onChange = { v = it }, onDone = { upd { it.copy(glassBlur = v) } })
                SwitchRow("Riflessi animati", "Una luce che scorre piano sul vetro", s.glassShine) { b -> upd { it.copy(glassShine = b) } }
            }
        }
    }
    item { val s = LocalSettings.current; SwitchRow("Riduci animazioni", "Meno movimento, transizioni più semplici", s.reduceMotion) { b -> upd { it.copy(reduceMotion = b) } } }
    item { val s = LocalSettings.current; SwitchRow("Vibrazione al tocco", "Un piccolo feedback quando premi i bottoni", s.haptics) { b -> upd { it.copy(haptics = b) } } }
    item { val s = LocalSettings.current; SwitchRow("Copertina animata", "La copertina \"respira\" nel player a schermo intero", s.canvasCovers) { b -> upd { it.copy(canvasCovers = b) } } }
    item { val s = LocalSettings.current; SwitchRow("Coriandoli nei giochi", null, s.confetti) { b -> upd { it.copy(confetti = b) } } }

    // ---------------- home
    item { Header("Home", "🏠") }
    item { val s = LocalSettings.current; SwitchRow("Consigliati per te", "Sezioni basate su cosa ascolti", s.homeRecommendations) { b -> upd { it.copy(homeRecommendations = b) } } }
    item { val s = LocalSettings.current; SwitchRow("Cosa ascoltano gli amici", null, s.homeFriends) { b -> upd { it.copy(homeFriends = b) } } }
    item {
        val s = LocalSettings.current
        ChoiceRow("Scheda all'avvio", null, listOf("home" to "Home", "search" to "Cerca", "library" to "Libreria", "studio" to "Studio", "friends" to "Amici", "games" to "Giochi"), s.startTab) { k -> upd { it.copy(startTab = k) } }
    }

    // ---------------- playback (Gladix engine settings)
    item { Header("Riproduzione", "▶️") }
    item {
        val context = LocalContext.current
        val sp = remember { context.getSettings() }
        var fade by remember { mutableFloatStateOf(sp.getInt("true_crossfade_seconds", dev.brahmkshatriya.echo.playback.PlayerService.VIBES_DEFAULT_FADE).toFloat()) }
        SliderRow("Dissolvenza tra i brani", if (fade.toInt() == 0) "Spenta" else "${fade.toInt()} s", fade, 0f..10f, steps = 9,
            subtitle = "Il brano successivo entra mentre finisce quello attuale",
            onChange = { fade = it }, onDone = { sp.edit { putInt("true_crossfade_seconds", fade.toInt()) } })
    }
    item { EngineSwitch("Niente dissolvenza negli album", "Gli album live e i concept restano senza stacchi", "skip_fade_on_albums", true) }
    item { EngineSwitch("Normalizza il volume", "Tutti i brani allo stesso volume", "loudness_normalization", false) }
    item { EngineSwitch("Salta i silenzi", "Taglia i silenzi all'inizio e alla fine dei brani", "skip_silence", true) }
    item { EngineSwitch("Riproduzione automatica", "Quando finisce la coda, continua con brani simili", "auto_start_radio", true) }
    item { EngineSwitch("Ricorda la coda", "Riprendi da dove avevi lasciato quando riapri l'app", "keep_queue", true) }
    item { EngineSwitch("Ferma quando chiudi l'app", "Togliendo Vibes dalle app recenti la musica si ferma", "close_player", false) }
    item {
        val context = LocalContext.current
        val fx = remember { context.globalFx() }
        var idx by remember { mutableFloatStateOf(fx.getInt(PLAYBACK_SPEED, speedRange.indexOf(1f)).toFloat()) }
        val lo = speedRange.indexOf(0.5f).toFloat(); val hi = speedRange.indexOf(2f).toFloat()
        SliderRow("Velocità", "${speedRange.getOrNull(idx.toInt()) ?: 1f}×", idx.coerceIn(lo, hi), lo..hi, steps = (hi - lo).toInt() - 1,
            onChange = { idx = it }, onDone = { fx.edit { putInt(PLAYBACK_SPEED, idx.toInt()) } })
    }
    item {
        val context = LocalContext.current
        val fx = remember { context.globalFx() }
        var bass by remember { mutableFloatStateOf(fx.getInt(BASS_BOOST, 0).toFloat()) }
        SliderRow("Bassi", if (bass.toInt() == 0) "Normali" else "+${bass.toInt()}", bass, 0f..10f, steps = 9,
            onChange = { bass = it }, onDone = { fx.edit { putInt(BASS_BOOST, bass.toInt()) } })
    }

    // ---------------- quality
    item { Header("Qualità audio", "🎚️") }
    item { EngineChoice("Streaming con rete mobile", "Più alta = più dati consumati", "stream_quality", "medium",
        listOf("highest" to "Alta", "medium" to "Normale", "lowest" to "Risparmio dati")) }
    item { EngineChoice("Streaming con Wi-Fi", null, "unmetered_stream_quality", "off",
        listOf("off" to "Come rete mobile", "highest" to "Alta", "medium" to "Normale", "lowest" to "Bassa")) }
    item {
        val context = LocalContext.current
        val sp = remember { context.getSettings() }
        var size by remember { mutableFloatStateOf(sp.getInt("cache_size", 1024).toFloat()) }
        SliderRow("Spazio per la cache", "${size.toInt()} MB", size, 200f..2000f, steps = 17,
            subtitle = "I brani già ascoltati ripartono subito e senza consumare dati",
            onChange = { size = it }, onDone = { sp.edit { putInt("cache_size", size.toInt()) } })
    }

    // ---------------- privacy
    item { Header("Privacy e social", "🔒") }
    item { val s = LocalSettings.current; SwitchRow("Sessione privata", "Non salva la cronologia e non mostra cosa ascolti", s.privateSession) { b ->
        upd { it.copy(privateSession = b) }; if (b) VibesGraph.social.setNowPlaying(null)
    } }
    item { PrivacySwitch("Mostra cosa sto ascoltando", "Gli amici vedono il brano in riproduzione") { p, b -> p.copy(nowPlaying = b) } }
    item { PrivacySwitch("Statistiche della settimana", "Visibili sul tuo profilo (solo la settimana in corso)") { p, b -> p.copy(weekStats = b) } }
    item { PrivacySwitch("Record", "Più ascolti di un brano e più minuti in una settimana") { p, b -> p.copy(records = b) } }
    item { PrivacySwitch("Preferiti", "Artista, brano e playlist preferiti") { p, b -> p.copy(favorites = b) } }
    item { val s = LocalSettings.current; SwitchRow("Conferme di lettura", "Spunte blu: se le togli non le vedi neanche tu", s.readReceipts) { b -> upd { it.copy(readReceipts = b) } } }

    // ---------------- notifications
    item { Header("Notifiche", "🔔") }
    item { val s = LocalSettings.current; SwitchRow("Messaggi", null, s.notifyMessages) { b -> upd { it.copy(notifyMessages = b) } } }
    item { val s = LocalSettings.current; SwitchRow("Sfide", "Quando un amico ti sfida a un gioco", s.notifyChallenges) { b -> upd { it.copy(notifyChallenges = b) } } }
    item { val s = LocalSettings.current; SwitchRow("Brani e playlist condivisi", null, s.notifySongs) { b -> upd { it.copy(notifySongs = b) } } }
    item {
        val context = LocalContext.current
        Text("Impostazioni di sistema delle notifiche", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).clickable {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
    }

    // ---------------- data
    item { Header("Dati e spazio", "🗂️") }
    item {
        val app = LocalApp.current
        var confirm by remember { mutableStateOf(false) }
        Text("Cancella cronologia di ascolto", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().clickable { confirm = true }.padding(horizontal = 16.dp, vertical = 12.dp))
        if (confirm) androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Cancellare la cronologia?") },
            text = { Text("Spariscono anche le statistiche delle settimane passate.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; VibesGraph.listens.clear(); VibesGraph.onListened(true); app.nav.toast("Cronologia cancellata") }) { Text("Cancella") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Annulla") } }
        )
    }
    item {
        val context = LocalContext.current
        val app = LocalApp.current
        Text("Svuota cache immagini", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().clickable {
                runCatching { coil3.SingletonImageLoader.get(context).let { it.memoryCache?.clear(); it.diskCache?.clear() } }
                app.nav.toast("Cache immagini svuotata")
            }.padding(horizontal = 16.dp, vertical = 12.dp))
    }

    // ---------------- updates
    item { Header("Aggiornamenti", "⬆️") }
    item { val s = LocalSettings.current; SwitchRow("Controlla aggiornamenti all'avvio", "Ti avvisa quando c'è una nuova versione di Vibes", s.autoUpdate) { b -> upd { it.copy(autoUpdate = b) } } }
    item {
        val app = LocalApp.current
        val scope = rememberCoroutineScope()
        var checking by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().clickable(enabled = !checking) {
            checking = true
            scope.launch {
                val u = runCatching { VibesGraph.updater.check(force = true) }.getOrNull()
                checking = false
                if (u == null) app.nav.toast("Hai già l'ultima versione ✨") else VibesGraph.updater.show(u)
            }
        }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Controlla ora", style = MaterialTheme.typography.titleMedium)
                Text("Versione installata: ${VibesGraph.updater.installedName}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (checking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun EngineSwitch(title: String, subtitle: String?, key: String, default: Boolean) {
    val context = LocalContext.current
    val sp = remember { context.getSettings() }
    var v by remember { mutableStateOf(sp.getBoolean(key, default)) }
    SwitchRow(title, subtitle, v) { b -> v = b; sp.edit { putBoolean(key, b) } }
}

@Composable
private fun EngineChoice(title: String, subtitle: String?, key: String, default: String, options: List<Pair<String, String>>) {
    val context = LocalContext.current
    val sp = remember { context.getSettings() }
    var v by remember { mutableStateOf(sp.getString(key, default) ?: default) }
    ChoiceRow(title, subtitle, options, v) { k -> v = k; sp.edit { putString(key, k) } }
}

@Composable
private fun PrivacySwitch(title: String, subtitle: String, set: (dev.brahmkshatriya.echo.vibes.backend.Privacy, Boolean) -> dev.brahmkshatriya.echo.vibes.backend.Privacy) {
    val me by VibesGraph.social.me.collectAsState()
    val scope = rememberCoroutineScope()
    val p = me?.privacy ?: dev.brahmkshatriya.echo.vibes.backend.Privacy()
    val checked = when (title) {
        "Mostra cosa sto ascoltando" -> p.nowPlaying
        "Statistiche della settimana" -> p.weekStats
        "Record" -> p.records
        else -> p.favorites
    }
    SwitchRow(title, subtitle, checked) { b ->
        scope.launch {
            runCatching { VibesGraph.social.setPrivacy(set(p, b)) }
            VibesGraph.onListened(force = true)
        }
    }
}

/** Lets non-composable callbacks reach the app state (toasts). */
object LocalAppHolder {
    @Volatile var app: AppState? = null
}
