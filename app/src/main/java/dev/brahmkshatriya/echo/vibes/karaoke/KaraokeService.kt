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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification("Karaoke HD", "Preparo…", 0f)
        runCatching {
            if (Build.VERSION.SDK_INT >= 35) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibes:karaoke").apply { acquire(6 * 60 * 60 * 1000L) }
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (true) {
                delay(1000)
                if (!KaraokeHD.busy.value) { stopSelf(); break }
                val (title, text, p) = KaraokeHD.statusLine()
                runCatching { nm.notify(ID, notification(title, text, p)) }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(title: String, text: String, progress: Float): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }
}
