package dev.brahmkshatriya.echo.vibes.notify

import android.content.Context
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Message
import dev.brahmkshatriya.echo.vibes.backend.str
import dev.brahmkshatriya.echo.vibes.desktop.DesktopTray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Windows notifications (system tray balloons / Action Center) for messages, invites, new releases. */
object VibesNotifier {
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        if (started) return
        started = true
        scope.launch { VibesGraph.social.incoming.collect { m -> runCatching { show(m) } } }
        scope.launch { VibesGraph.social.lobbyInvites.collect { inv -> runCatching { showLobbyInvite(inv) } } }
        scope.launch { runCatching { checkReleases(context) } }
    }

    suspend fun checkReleases(context: Context, force: Boolean = false) {
        val sp = context.getSharedPreferences("vibes_releases", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - sp.getLong("last", 0) < 6 * 3600_000L) return
        sp.edit().putLong("last", System.currentTimeMillis()).apply()
        val lib = VibesGraph.library
        val found = mutableListOf<dev.brahmkshatriya.echo.vibes.music.DeezerPublic.AlbumBrief>()
        lib.artists.value.forEach { a ->
            val albums = runCatching { VibesGraph.deezer.artistAlbums(a.id, a.name) }.getOrDefault(emptyList())
            val newest = albums.firstOrNull() ?: return@forEach
            if (a.lastSeen.isNotBlank()) found += albums.filter { it.date > a.lastSeen }
            lib.setArtistSeen(a.id, newest.date)
        }
        found.take(5).forEach { al -> DesktopTray.notify("Nuova uscita: ${al.title}", al.artist, "vibes://album/${al.id}") }
    }

    fun text(m: Message): String = when (m.kind) {
        "track" -> {
            val title = m.payload?.str("title") ?: "un brano"
            val artist = m.payload?.str("artist")
            "ti ha consigliato $title" + (artist?.let { " – $it" } ?: "") + (m.body.takeIf { it.isNotBlank() }?.let { "\n“$it”" } ?: "")
        }
        "playlist" -> "ti ha condiviso la playlist ${m.payload?.str("name") ?: ""}"
        "challenge" -> "ti ha sfidato a ${gameName(m.payload?.str("game"))}"
        "image" -> "📷 Foto"
        "video" -> "🎬 Video"
        "audio" -> "🎤 Messaggio vocale"
        else -> m.body
    }

    fun gameName(game: String?) = when (game) {
        "blindtest" -> "Chi canta?"
        else -> "Indovina il brano"
    }

    private fun show(m: Message) {
        val prefs = VibesGraph.prefs.value
        val allowed = when (m.kind) {
            "challenge" -> prefs.notifyChallenges
            "track", "playlist" -> prefs.notifySongs
            else -> prefs.notifyMessages
        }
        if (!allowed) return
        val name = VibesGraph.social.friendName(m.senderId)
        val link = if (m.kind == "challenge") "vibes://challenge/${m.payload?.str("game") ?: "guess"}/${m.payload?.str("lobby_id")}"
        else "vibes://chat/${m.senderId}"
        DesktopTray.notify(name, text(m), link)
    }

    private suspend fun showLobbyInvite(inv: dev.brahmkshatriya.echo.vibes.listen.ListenInvite) {
        runCatching { VibesGraph.lobbies.refreshInvites() }
        val name = VibesGraph.social.friendName(inv.fromUser)
        val lobby = VibesGraph.lobbies.lobbyName(inv.lobbyId) ?: "una lobby"
        DesktopTray.notify("🎧 $name ti invita ad ascoltare insieme", "Entra in «$lobby»: stessa musica, stesso istante", "vibes://lobby/${inv.lobbyId}")
    }

    fun clear(context: Context, friendId: String) {}
}
