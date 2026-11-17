package dev.brahmkshatriya.echo.vibes.backend

import dev.brahmkshatriya.echo.vibes.VibesPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import java.io.File

/**
 * Everything that is "yours" but isn't the library follows the account too: the Vibes settings
 * (minus the ones that belong to the screen of each device) and the favourite samples of the Studio.
 *
 * One small row per item on the server (`vibes_user_state`), stamped with when it was changed.
 * A change here goes up 1.5 s later and again when the app is closed; when the app opens or comes
 * back to the front it asks only for the stamps (a few bytes) and downloads an item only if the
 * other device changed it after this one. The newest change wins.
 */
class UserStateSync(
    private val supa: Supa,
    private val prefs: VibesPrefs,
    private val context: android.content.Context,
) {
    private val filesDir: File = context.filesDir
    private val json = Supa.json
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null
    private val stampsFile = File(filesDir, "user_state_stamps.json")
    private val favFile = File(filesDir, "fav_samples.json")

    /** Bumps when something arrived from the other device (screens re-read their data). */
    val version = MutableStateFlow(0)

    /** key -> when this device last changed it (or took the server's copy), and the server stamp seen. */
    private val local = HashMap<String, Long>()
    private val seen = HashMap<String, Long>()

    init {
        runCatching {
            val o = json.parseToJsonElement(stampsFile.readText()).jsonObject
            o["local"]?.jsonObject?.forEach { (k, v) -> v.jsonPrimitive.longOrNull?.let { local[k] = it } }
            o["seen"]?.jsonObject?.forEach { (k, v) -> v.jsonPrimitive.longOrNull?.let { seen[k] = it } }
        }
        prefs.onUserChange = { changed(SETTINGS) }
        dev.brahmkshatriya.echo.vibes.theme.VibesThemes.onUserChange = { changed(THEME) }
    }

    private fun saveStamps() = runCatching {
        stampsFile.writeText(buildJsonObject {
            put("local", buildJsonObject { local.forEach { (k, v) -> put(k, v) } })
            put("seen", buildJsonObject { seen.forEach { (k, v) -> put(k, v) } })
        }.toString())
    }

    /** Something changed on this device: upload shortly (several quick changes = one upload). */
    fun changed(key: String) {
        synchronized(local) { local[key] = System.currentTimeMillis(); saveStamps() }
        pending?.cancel()
        pending = scope.launch { delay(1500); runCatching { sync() } }
    }

    fun flush() { pending?.cancel(); scope.launch { runCatching { sync() } } }

    suspend fun sync() = mutex.withLock {
        val me = supa.userId ?: return@withLock
        val rows = supa.select("vibes_user_state", "user_id=eq.$me&select=key,updated_at")
        val remote = rows.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val k = o.str("key") ?: return@mapNotNull null
            k to (o["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L)
        }.toMap()
        var got = false
        for (key in KEYS) {
            // first time on this account: what this device has becomes the account's copy
            if (remote[key] == null && (local[key] ?: 0L) == 0L) local[key] = System.currentTimeMillis()
            val l = local[key] ?: 0L
            val r = remote[key] ?: 0L
            when {
                r > l && r != seen[key] -> {
                    val row = supa.select("vibes_user_state", "user_id=eq.$me&key=eq.$key&select=value,updated_at")
                        .firstOrNull() as? JsonObject ?: continue
                    row["value"]?.let { apply(key, it) }
                    local[key] = r; seen[key] = r; got = true
                }
                l > r -> {
                    val value = read(key) ?: continue
                    supa.upsert("vibes_user_state", buildJsonObject {
                        put("user_id", me); put("key", key); put("value", value); put("updated_at", l)
                    })
                    seen[key] = l
                }
                else -> seen[key] = r
            }
        }
        saveStamps()
        if (got) version.value++
    }

    private fun read(key: String): JsonElement? = when (key) {
        SETTINGS -> {
            val all = json.encodeToJsonElement(dev.brahmkshatriya.echo.vibes.VibesSettings.serializer(), prefs.value).jsonObject
            JsonObject(all.filterKeys { it !in DEVICE_ONLY })
        }
        FAV_SAMPLES -> runCatching { json.parseToJsonElement(favFile.readText()) }.getOrDefault(JsonArray(emptyList()))
        THEME -> {
            val sp = context.getSettings()
            buildJsonObject {
                put("id", sp.getString(dev.brahmkshatriya.echo.vibes.theme.VibesThemes.THEME_ID_KEY, null))
                put("custom", sp.getString(dev.brahmkshatriya.echo.vibes.theme.VibesThemes.CUSTOM_THEMES_KEY, "[]"))
            }
        }
        else -> null
    }

    private fun apply(key: String, value: JsonElement) {
        when (key) {
            SETTINGS -> {
                val obj = value as? JsonObject ?: return
                val mine = json.encodeToJsonElement(dev.brahmkshatriya.echo.vibes.VibesSettings.serializer(), prefs.value).jsonObject
                val merged = JsonObject(mine + obj.filterKeys { it !in DEVICE_ONLY && it in mine })
                runCatching { json.decodeFromJsonElement(dev.brahmkshatriya.echo.vibes.VibesSettings.serializer(), merged) }
                    .getOrNull()?.let { prefs.setFromSync(it) }
            }
            FAV_SAMPLES -> if (value is JsonArray) runCatching { favFile.writeText(value.toString()) }
            THEME -> runCatching {
                val o = value as? JsonObject ?: return
                val sp = context.getSettings()
                o.str("custom")?.let { c -> sp.edit().putString(dev.brahmkshatriya.echo.vibes.theme.VibesThemes.CUSTOM_THEMES_KEY, c).commit() }
                val id = o.str("id") ?: return
                val t = dev.brahmkshatriya.echo.vibes.theme.VibesThemes.all(context).firstOrNull { it.id == id } ?: return
                if (sp.getString(dev.brahmkshatriya.echo.vibes.theme.VibesThemes.THEME_ID_KEY, null) != id)
                    dev.brahmkshatriya.echo.vibes.theme.VibesThemes.apply(sp, t)
            }
        }
    }

    companion object {
        const val SETTINGS = "settings"
        const val FAV_SAMPLES = "fav_samples"
        const val THEME = "theme" // chosen theme + the themes you made
        val KEYS = listOf(SETTINGS, FAV_SAMPLES, THEME)

        /** These depend on the screen/hardware of each device, so each one keeps its own. */
        val DEVICE_ONLY = setOf(
            "textScale", "background", "backgroundDim", "backgroundBlur", "reduceMotion", "haptics",
            "needsOnboarding", "liquidGlass", "glassBlur", "glassShine", "autoUpdate",
        )
    }
}
