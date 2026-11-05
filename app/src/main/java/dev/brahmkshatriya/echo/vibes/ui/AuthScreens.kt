package dev.brahmkshatriya.echo.vibes.ui

import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun VibesField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true,
    maxLines: Int = 1,
) {
    var visible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = modifier.fillMaxWidth(), singleLine = singleLine, maxLines = maxLines,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = if (password) ({
            androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                androidx.compose.material3.Icon(
                    if (visible) androidx.compose.material.icons.Icons.Rounded.VisibilityOff else androidx.compose.material.icons.Icons.Rounded.Visibility,
                    if (visible) "Nascondi password" else "Mostra password"
                )
            }
        }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = LocalVibes.current.card,
            unfocusedContainerColor = LocalVibes.current.card,
        )
    )
}

@Composable
fun AuthScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var signup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    GradientBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(56.dp))
            VibesLogo(Modifier.size(96.dp))
            Spacer(Modifier.height(18.dp))
            Text("Vibes", style = MaterialTheme.typography.displayMedium)
            Text(
                "La tua musica, i tuoi amici, le tue vibes.", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(36.dp))

            Row(
                Modifier.clip(CircleShape).background(LocalVibes.current.card).padding(4.dp)
            ) {
                listOf("Accedi" to false, "Registrati" to true).forEach { (t, v) ->
                    val bg by animateColorAsState(if (signup == v) MaterialTheme.colorScheme.primary else Color.Transparent, label = "tab")
                    Box(
                        Modifier.clip(CircleShape).background(bg).clickable { signup = v; error = null; info = null }
                            .padding(horizontal = 26.dp, vertical = 10.dp)
                    ) {
                        Text(
                            t, style = MaterialTheme.typography.labelLarge,
                            color = if (signup == v) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            VibesField(email, { email = it }, "Email", keyboard = KeyboardType.Email)
            Spacer(Modifier.height(12.dp))
            VibesField(pass, { pass = it }, "Password", password = true)
            AnimatedContent(signup, label = "pass2") { s ->
                if (s) Column {
                    Spacer(Modifier.height(12.dp))
                    VibesField(pass2, { pass2 = it }, "Ripeti la password", password = true)
                }
            }
            error?.let {
                Text(it, color = Color(0xFFFF5C7A), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }
            info?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
            Spacer(Modifier.height(22.dp))
            BigButton(
                if (signup) "Crea account" else "Accedi",
                loading = loading,
                enabled = email.contains("@") && pass.length >= 6,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    error = null; info = null
                    if (signup && pass != pass2) { error = "Le password non coincidono"; return@BigButton }
                    loading = true
                    scope.launch {
                        runCatching {
                            if (signup) {
                                app.supa.signUpAndSignIn(email, pass)
                            } else app.supa.signIn(email, pass)
                        }.onFailure { error = it.message ?: "Qualcosa è andato storto" }
                        loading = false
                    }
                }
            )
            if (!signup) Text(
                "Password dimenticata?", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp).clickable {
                    if (!email.contains("@")) { error = "Scrivi prima la tua email"; return@clickable }
                    scope.launch {
                        runCatching { app.supa.resetPassword(email) }
                            .onSuccess { info = "Ti abbiamo mandato un link per cambiare la password." }
                            .onFailure { error = it.message }
                    }
                }
            )
        }
    }
}

val avatarEmojis = listOf("🎧", "🎤", "🎸", "🥁", "🎹", "🎷", "🎺", "🎻", "🪩", "🔥", "⚡", "🌙", "🌈", "👾", "🦊", "🐼", "🐸", "🦄", "😎", "👑")
val avatarColors = listOf("#B84DFF", "#FF2E97", "#FF7A59", "#FFB020", "#1ED760", "#00C2A8", "#35D0F5", "#3B82F6", "#6366F1", "#E879F9")

@Composable
fun AvatarPicker(emoji: String, color: String, onEmoji: (String) -> Unit, onColor: (String) -> Unit) {
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(avatarEmojis) { e ->
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .background(if (e == emoji) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else LocalVibes.current.card)
                        .clickable { onEmoji(e) },
                    contentAlignment = Alignment.Center
                ) { Text(e, fontSize = 22.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(avatarColors) { c ->
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(parseColor(c))
                        .then(if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { onColor(c) }
                )
            }
        }
    }
}

@Composable
fun ProfileSetupScreen() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🎧") }
    var color by remember { mutableStateOf("#B84DFF") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = Regex("^[a-z0-9_.]{3,20}$").matches(username)

    GradientBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            Text("Crea il tuo profilo", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Così i tuoi amici possono trovarti.", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
            Avatar(
                dev.brahmkshatriya.echo.vibes.backend.Profile("", username, avatarColor = color, avatarEmoji = emoji),
                size = 110.dp
            )
            Spacer(Modifier.height(20.dp))
            AvatarPicker(emoji, color, { emoji = it }, { color = it })
            Spacer(Modifier.height(24.dp))
            VibesField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' }.take(20) }, "Username")
            Text(
                "3-20 caratteri: lettere minuscole, numeri, _ e .", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            VibesField(name, { name = it.take(30) }, "Nome visualizzato (facoltativo)")
            error?.let { Text(it, color = Color(0xFFFF5C7A), modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(24.dp))
            BigButton(
                "Iniziamo", loading = loading, enabled = valid, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    loading = true; error = null
                    scope.launch {
                        runCatching {
                            app.social.createProfile(username, name, emoji, color)
                            dev.brahmkshatriya.echo.vibes.VibesGraph.prefs.update { it.copy(needsOnboarding = true) }
                        }
                            .onFailure { error = it.message }
                        loading = false
                    }
                }
            )
            Text(
                "Esci", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp).clickable { app.supa.signOut() }
            )
        }
    }
}
