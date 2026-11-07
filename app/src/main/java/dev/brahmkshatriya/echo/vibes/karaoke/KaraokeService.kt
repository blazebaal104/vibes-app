package dev.brahmkshatriya.echo.vibes.karaoke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps karaoke HD working while you are in another app or the screen is off: a foreground
 * service with a progress notification and a CPU wake lock. It stops by itself when there is
 * nothing left to separate or download.
 */
class KaraokeService : Service() {
    companion object {
        private const val CHANNEL = "karaoke_hd"
        private const val ID = 4207
        private const val ACTION_TOGGLE = "vibes.karaoke.TOGGLE"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, KaraokeService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Karaoke HD", NotificationManager.IMPORTANCE_LOW))
    }

    private var loopStarted = false

    private fun foregroundType(): Int = when {
        KaraokeHD.active.value && Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) KaraokeHD.toggle()
        val n = notification("Karaoke HD", "Preparo…", 0f)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, foregroundType()) else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (loopStarted) return START_NOT_STICKY
        loopStarted = true
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibes:karaoke").apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            var lastType = foregroundType()
            while (true) {
                delay(1000)
                // alive while the AI works OR the HD backing track is playing (else Android freezes the app)
                if (!KaraokeHD.busy.value && !KaraokeHD.active.value) { stopSelf(); break }
                val n = if (KaraokeHD.active.value)
                    notification("🎤 Karaoke HD", KaraokeHD.currentTitle() ?: "In riproduzione", -1f, withToggle = true)
                else KaraokeHD.statusLine().let { (title, text, p) -> notification(title, text, p) }
                val type = foregroundType()
                if (type != lastType && Build.VERSION.SDK_INT >= 29) { runCatching { startForeground(ID, n, type) }; lastType = type }
                else runCatching { nm.notify(ID, n) }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, text: String, progress: Float, withToggle: Boolean = false): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
        if (progress >= 0f) b.setProgress(100, (progress * 100).toInt(), progress == 0f)
        if (withToggle) {
            val toggle = PendingIntent.getService(this, 1, Intent(this, KaraokeService::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(
                if (KaraokeHD.playing.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (KaraokeHD.playing.value) "Pausa" else "Play", toggle
            )
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }
}
