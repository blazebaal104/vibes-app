package dev.brahmkshatriya.echo.vibes.diag

import android.content.Context
import android.os.Build
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.vibes.VibesGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Sends Vibes errors (songs that fail to play, crashes) to the developer's Supabase table
 * vibes_error_logs, so problems can be fixed without access to the phone's logs.
 * Crashes are written to a file first and uploaded at the next start.
 */
object ErrorReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = ArrayDeque<Pair<String, Long>>()
    private var installed = false
    private lateinit var crashFile: File

    fun install(context: Context) {
        if (installed) return
        installed = true
        crashFile = File(context.filesDir, "vibes_last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile.writeText("${t.name}\n${e.stackTraceToString()}".take(12_000)) }
            previous?.uncaughtException(t, e)
        }
        // upload the crash of the previous run, if any
        scope.launch {
            runCatching {
                if (crashFile.exists()) {
                    val txt = crashFile.readText()
                    crashFile.delete()
                    send("crash", txt.lineSequence().drop(1).firstOrNull() ?: "crash", txt)
                }
            }
        }
    }

    /** Reports a problem; identical messages are sent at most once every 10 minutes. */
    fun report(kind: String, message: String, error: Throwable? = null) {
        val key = "$kind|$message"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.removeAll { now - it.second > 600_000 }
            if (recent.any { it.first == key } || recent.size > 30) return
            recent.addLast(key to now)
        }
        scope.launch { send(kind, message, error?.stackTraceToString() ?: "") }
    }

    private suspend fun send(kind: String, message: String, detail: String) {
        runCatching {
            val uid = VibesGraph.supa.session.value?.userId ?: return
            VibesGraph.supa.insert("vibes_error_logs", buildJsonObject {
                put("user_id", uid)
                put("app_version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                put("build_type", BuildConfig.BUILD_TYPE)
                put("kind", kind)
                put("message", message.take(1000))
                put("detail", detail.take(12_000))
                put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            }, false)
        }
    }
}
