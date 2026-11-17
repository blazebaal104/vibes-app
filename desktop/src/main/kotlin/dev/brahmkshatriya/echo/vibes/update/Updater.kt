package dev.brahmkshatriya.echo.vibes.update

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String = "",
    val title: String = "Aggiornamento",
    val notes: List<String> = emptyList(),
    val apk: String = "",
    val size: Long = 0,
    val date: String? = null,
    @SerialName("apk_pc") val apkPc: String? = null,
    @SerialName("size_pc") val sizePc: Long = 0,
    /** Windows installer of the same build, in parts (storage limit 50 MB per file) */
    @SerialName("exe_parts") val exeParts: List<String> = emptyList(),
    @SerialName("size_exe") val sizeExe: Long = 0,
) {
    val forPc: Boolean get() = true
    val url: String get() = exeParts.firstOrNull() ?: ""
    val bytes: Long get() = sizeExe
}

/**
 * Windows updates: every build publishes Vibes-N.exe next to the phone APK; latest.json says which
 * is newest. "Installa ora" downloads the installer, starts it and closes Vibes so it can be replaced.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    companion object {
        const val LATEST = "${Supa.URL}/storage/v1/object/public/vibes-updates/latest.json"
    }

    /** PC: always a fixed connection (no mobile-data warning). */
    val onWifi: Boolean get() = true

    sealed interface Phase {
        data object Idle : Phase
        data class Downloading(val progress: Float) : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    private val sp = context.getSharedPreferences("vibes_update", Context.MODE_PRIVATE)
    private val _pending = MutableStateFlow<UpdateInfo?>(null)
    val pending = _pending.asStateFlow()
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase = _phase.asStateFlow()

    val installedCode: Int get() = dev.brahmkshatriya.echo.BuildConfig.VERSION_CODE
    val installedName: String get() = "build $installedCode"

    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        if (installedCode <= 0) return@withContext null // development run
        val info = http.newCall(Request.Builder().url("$LATEST?t=${System.currentTimeMillis()}").header("Cache-Control", "no-cache").build())
            .execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                Supa.json.decodeFromString(UpdateInfo.serializer(), r.body.string())
            }
        if (info.exeParts.isEmpty() || info.versionCode <= installedCode) return@withContext null
        if (!force && System.currentTimeMillis() < sp.getLong("snooze_${info.versionCode}", 0)) return@withContext null
        info
    }

    private fun exeFile(info: UpdateInfo) = File(File(context.cacheDir, "updates").apply { mkdirs() }, "Vibes-${info.versionCode}.exe")

    fun show(info: UpdateInfo) { _phase.value = if (exeFile(info).length() == info.bytes && info.bytes > 0) Phase.Ready else Phase.Idle; _pending.value = info }
    fun close() { _pending.value = null }
    fun later() {
        _pending.value?.let { sp.edit().putLong("snooze_${it.versionCode}", System.currentTimeMillis() + 12 * 3600_000L).apply() }
        _pending.value = null
    }

    suspend fun download(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val file = exeFile(info)
        runCatching {
            _phase.value = Phase.Downloading(0f)
            val tmp = File(file.path + ".part")
            val total = info.bytes
            var done = 0L; var lastEmit = 0L
            tmp.outputStream().use { out ->
                for (part in info.exeParts) {
                    http.newCall(Request.Builder().url(part).build()).execute().use { r ->
                        if (!r.isSuccessful) error("Download non riuscito (${r.code})")
                        r.body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); done += n
                                if (total > 0 && done - lastEmit > 256 * 1024) { lastEmit = done; _phase.value = Phase.Downloading(done.toFloat() / total) }
                            }
                        }
                    }
                }
            }
            if (total > 0 && tmp.length() != total) error("Download incompleto, riprova")
            file.delete(); tmp.renameTo(file)
            file.parentFile?.listFiles()?.filter { it.name.startsWith("Vibes-") && it != file }?.forEach { it.delete() }
            _phase.value = Phase.Ready
        }.onFailure { _phase.value = Phase.Failed(it.message ?: "Download non riuscito") }
    }

    fun canInstall(): Boolean = true
    fun openInstallPermission() {}

    /** Starts the installer and quits, so Windows can replace the app's files. */
    fun install(info: UpdateInfo) {
        val f = exeFile(info)
        runCatching {
            ProcessBuilder(f.absolutePath).start()
            Thread.sleep(400)
            kotlin.system.exitProcess(0)
        }.onFailure { _phase.value = Phase.Failed("Non riesco ad avviare l'installazione: ${it.message}") }
    }
}
