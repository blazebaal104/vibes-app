package dev.brahmkshatriya.echo.vibes.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.vibes.VibesActivity
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Message
import dev.brahmkshatriya.echo.vibes.backend.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WhatsApp-style notifications for Vibes: one conversation notification per friend, with the
 * text, the song they recommended, the playlist they shared or the game they challenged you to.
 * Listens to Supabase Realtime while the app process is alive (push via Firebase comes later).
 */
object VibesNotifier {
    private const val CHANNEL_MESSAGES = "vibes_messages"
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val history = mutableMapOf<String, MutableList<Pair<String, Long>>>()

    fun start(context: Context) {
        if (started) return
        started = true
        createChannels(context)
        scope.launch {
            VibesGraph.social.incoming.collect { m -> runCatching { show(context, m) } }
        }
        scope.launch { runCatching { checkReleases(context) } }
    }

    private const val CHANNEL_RELEASES = "vibes_releases"

    /** New albums/singles from followed artists (checked at most every 6 hours). */
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
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RELEASES, "Nuove uscite", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Nuovi album e singoli degli artisti che segui"
            })
        found.take(5).forEach { al ->
            val pi = PendingIntent.getActivity(context, al.id.toInt(),
                Intent(context, VibesActivity::class.java).apply {
                    action = Intent.ACTION_VIEW; data = android.net.Uri.parse("vibes://album/${al.id}")
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL_RELEASES)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Nuovo ${if (al.type == "single") "singolo" else if (al.type == "ep") "EP" else "album"} di ${al.artist}")
                .setContentText(al.title)
                .setAutoCancel(true).setContentIntent(pi).build()
            runCatching { NotificationManagerCompat.from(context).notify(("rel" + al.id).hashCode(), n) }
        }
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messaggi e sfide", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messaggi, brani consigliati, playlist condivise e sfide dei tuoi amici"
            }
        )
    }

    fun text(m: Message): String = when (m.kind) {
        "track" -> {
            val title = m.payload?.str("title") ?: "un brano"
            val artist = m.payload?.str("artist")
            "ti ha consigliato $title" + (artist?.let { " – $it" } ?: "") +
                    (m.body.takeIf { it.isNotBlank() }?.let { "\n“$it”" } ?: "")
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

    private fun link(m: Message): Uri = when (m.kind) {
        "challenge" -> Uri.parse("vibes://challenge/${m.payload?.str("game") ?: "guess"}/${m.payload?.str("lobby_id")}")
        else -> Uri.parse("vibes://chat/${m.senderId}")
    }

    private fun show(context: Context, m: Message) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = VibesGraph.prefs.value
        val allowed = when (m.kind) {
            "challenge" -> prefs.notifyChallenges
            "track", "playlist" -> prefs.notifySongs
            else -> prefs.notifyMessages
        }
        if (!allowed) return
        val social = VibesGraph.social
        val name = social.friendName(m.senderId)
        val line = text(m)
        val lines = history.getOrPut(m.senderId) { mutableListOf() }
        lines += line to System.currentTimeMillis()
        while (lines.size > 6) lines.removeAt(0)

        val sender = Person.Builder().setName(name).setKey(m.senderId).build()
        val me = Person.Builder().setName("Tu").build()
        val style = NotificationCompat.MessagingStyle(me)
        lines.forEach { (t, at) -> style.addMessage(t, at, sender) }

        val intent = Intent(context, VibesActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = link(m)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, m.senderId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setContentTitle(name)
            .setContentText(line)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(m.senderId.hashCode(), n)
    }

    fun clear(context: Context, friendId: String) {
        history.remove(friendId)
        NotificationManagerCompat.from(context).cancel(friendId.hashCode())
    }
}
