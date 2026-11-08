package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.brahmkshatriya.echo.vibes.backend.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Small shield shown next to an admin's name. */
@Composable
fun AdminBadge(modifier: Modifier = Modifier) {
    Text("🛡️ Admin", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

/**
 * Moderation panel for admins, opened from someone's profile: edit name/username/bio, change or
 * remove the photo, delete stories and published songs, ban / unban. The database only accepts these
 * changes from accounts in vibes_admins, so this is safe even if somebody else reached the dialog.
 */
@Composable
fun AdminDialog(target: Profile, onDismiss: () -> Unit, onChanged: (Profile?) -> Unit) {
    val app = LocalApp.current
    val nav = app.nav
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(target.id) { mutableStateOf(target.displayName) }
    var username by remember(target.id) { mutableStateOf(target.username) }
    var bio by remember(target.id) { mutableStateOf(target.bio) }
    var reason by remember(target.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    val targetIsAdmin = target.id in app.social.admins.value

    fun run(done: String, block: suspend () -> Unit) {
        busy = true
        scope.launch {
            runCatching { block() }
                .onSuccess { nav.toast(done); onChanged(runCatching { app.social.refreshPerson(target.id) }.getOrNull()) }
                .onFailure { nav.toast(it.message ?: "Errore") }
            busy = false
        }
    }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) run("Foto cambiata") {
            val jpeg = withContext(Dispatchers.IO) { squareJpeg(context, uri, 512) }
            app.social.adminSetAvatar(target.id, jpeg)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("🛡️ Modera @${target.username}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (target.banned) Text("⛔ Bannato" + (target.banReason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                VibesField(name, { name = it }, "Nome")
                VibesField(username, { username = it.lowercase().trim() }, "Username")
                VibesField(bio, { bio = it }, "Bio", singleLine = false, maxLines = 3)
                Pill("Salva profilo", true, {
                    run("Profilo aggiornato") {
                        app.social.adminPatch(target.id, buildJsonObject {
                            put("display_name", name.trim()); put("username", username.trim()); put("bio", bio.trim().take(160))
                        })
                    }
                })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Cambia foto", false, {
                        picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    if (target.avatarUrl != null) Pill("Togli foto", false, { run("Foto rimossa") { app.social.adminRemoveAvatar(target.id) } })
                }
                Text("Contenuti", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Elimina storie", false, { confirm = "Eliminare tutte le storie di @${target.username}?" to { app.social.adminDeleteStories(target.id) } })
                    Pill("Elimina brani", false, { confirm = "Eliminare tutti i brani pubblicati da @${target.username}?" to { app.social.adminDeleteUploads(target.id) } })
                }
                if (targetIsAdmin) Text("È un amministratore: non può essere bannato.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (target.banned) Pill("Togli il ban", true, { run("Ban rimosso") { app.social.adminUnban(target.id) } })
                else {
                    Text("Ban", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    VibesField(reason, { reason = it }, "Motivo (lo vede l'utente)")
                    Pill("⛔ Banna account", false, {
                        confirm = "Bannare @${target.username}? Viene disconnesso, non può più accedere né pubblicare e le sue storie vengono cancellate." to {
                            app.social.adminBan(target.id, reason.trim())
                        }
                    })
                }
                if (busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Chiudi") } },
    )

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sei sicuro?") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { confirm = null; run("Fatto") { action() } }) { Text("Conferma", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annulla") } },
        )
    }
}

/** Shown instead of the app when an admin banned this account. */
@Composable
fun BannedScreen(reason: String?) {
    val app = LocalApp.current
    GradientBackground {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⛔", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(12.dp))
            Text("Account bannato", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Un amministratore di Vibes ha bloccato questo account.", textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!reason.isNullOrBlank()) Text("Motivo: $reason", textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(24.dp))
            Pill("Esci", true, { app.supa.signOut() })
        }
    }
}
