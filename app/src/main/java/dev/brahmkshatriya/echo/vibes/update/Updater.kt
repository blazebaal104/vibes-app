package dev.brahmkshatriya.echo.vibes.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    val apk: String,
    val size: Long = 0,
    val date: String? = null,
    /** Build for PCs (Android emulators like BlueStacks / LDPlayer run x86_64). */
    @kotlinx.serialization.SerialName("apk_pc") val apkPc: String? = null,
    @kotlinx.serialization.SerialName("size_pc") val sizePc: Long = 0,
) {
    /** True on an emulator on a computer: it needs the x86_64 build. */
    val forPc: Boolean get() = apkPc != null && android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true
    val url: String get() = if (forPc) apkPc!! else apk
    val bytes: Long get() = if (forPc) sizePc else size
}

/**
 * In-app updates: every build of the repo publishes latest.json + the APK to Supabase Storage
 * (public bucket vibes-updates). On start Vibes compares versionCode with its own and offers the update.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    companion object {
        const val LATEST = "${Supa.URL}/storage/v1/object/public/vibes-updates/latest.json"
    }

    sealed interface Phase {
        data object Idle : Phase
        data class Downloading(val progress: Float) : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    private val sp = context.getSharedPreferences("vibes_update", Context.MODE_PRIVATE)

    private val _pending = MutableStateFlow<UpdateInfo?>(null)
    /** The update the dialog is showing (null = no dialog). */
    val pending = _pending.asStateFlow()
    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase = _phase.asStateFlow()

    private val pkg get() = context.packageManager.getPackageInfo(context.packageName, 0)
    val installedCode: Int get() = runCatching { if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else @Suppress("DEPRECATION") pkg.versionCode }.getOrDefault(0)
    val installedName: String get() = "build $installedCode"

    /** Returns the newer version, if any. [force] ignores "più tardi". */
    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = http.newCall(Request.Builder().url("$LATEST?t=${System.currentTimeMillis()}").header("Cache-Control", "no-cache").build())
            .execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                Supa.json.decodeFromString(UpdateInfo.serializer(), r.body.string())
            }
        if (info.versionCode <= installedCode) return@withContext null
        if (!force && System.currentTimeMillis() < sp.getLong("snooze_${info.versionCode}", 0)) return@withContext null
        info
    }

    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** On Wi-Fi (or any unmetered network). */
    val onWifi: Boolean get() = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        !cm.isActiveNetworkMetered
    }.getOrDefault(false)

    fun show(info: UpdateInfo) {
        _phase.value = if (apkFile(info).length() == info.bytes && info.bytes > 0) Phase.Ready else Phase.Idle
        _pending.value = info
        // DATA: on Wi-Fi the update downloads by itself in the background, so pressing
        // "Installa" never spends mobile data (it is ~45 MB each time)
        if (_phase.value == Phase.Idle && onWifi) bg.launch { download(info) }
    }

    fun close() { _pending.value = null }

    /** "Più tardi": ask again in 12 hours. */
    fun later() {
        _pending.value?.let { sp.edit().putLong("snooze_${it.versionCode}", System.currentTimeMillis() + 12 * 3600_000L).apply() }
        _pending.value = null
    }

    private fun apkFile(info: UpdateInfo) = File(File(context.cacheDir, "apks").apply { mkdirs() }, "Vibes-${info.versionCode}.apk")

    private val dlLock = kotlinx.coroutines.sync.Mutex()

    // one download at a time (the Wi-Fi background one and the button share it)
    suspend fun download(info: UpdateInfo) = dlLock.withLock { downloadNow(info) }

    private suspend fun downloadNow(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val file = apkFile(info)
        if (info.bytes > 0 && file.length() == info.bytes) { _phase.value = Phase.Ready; return@withContext }
        runCatching {
            _phase.value = Phase.Downloading(0f)
            http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                if (!r.isSuccessful) error("Download non riuscito (${r.code})")
                val total = r.body.contentLength().takeIf { it > 0 } ?: info.bytes
                val tmp = File(file.path + ".part")
                r.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastEmit > 128 * 1024) {
                                lastEmit = done
                                _phase.value = Phase.Downloading(done.toFloat() / total)
                            }
                        }
                    }
                }
                tmp.renameTo(file)
            }
            // old downloads
            file.parentFile?.listFiles()?.filter { it.name.startsWith("Vibes-") && it != file }?.forEach { it.delete() }
            _phase.value = Phase.Ready
        }.onFailure { _phase.value = Phase.Failed(it.message ?: "Download non riuscito") }
    }

    /** True if Android lets Vibes install APKs; otherwise opens the permission screen. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= 26) context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(info: UpdateInfo) {
        val file = apkFile(info)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
