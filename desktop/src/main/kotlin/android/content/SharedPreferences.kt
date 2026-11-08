package android.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

interface SharedPreferences {
    fun getString(key: String, def: String?): String?
    fun getStringSet(key: String, def: Set<String>?): Set<String>?
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun getBoolean(key: String, def: Boolean): Boolean
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(l: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(l: OnSharedPreferenceChangeListener)

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?)
    }

    interface Editor {
        fun putString(key: String, v: String?): Editor
        fun putStringSet(key: String, v: Set<String>?): Editor
        fun putInt(key: String, v: Int): Editor
        fun putLong(key: String, v: Long): Editor
        fun putFloat(key: String, v: Float): Editor
        fun putBoolean(key: String, v: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/** SharedPreferences saved as one JSON file each in %APPDATA%\Vibes\prefs. */
object DesktopPrefs {
    private val cache = HashMap<String, FilePrefs>()
    @Synchronized fun get(name: String): SharedPreferences = cache.getOrPut(name) { FilePrefs(DesktopEnv.dataDir.resolve("prefs/$name.json")) }
}

class FilePrefs(private val file: File) : SharedPreferences {
    private val map = LinkedHashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        runCatching {
            if (file.exists()) (Json.parseToJsonElement(file.readText()) as JsonObject).forEach { (k, v) -> map[k] = decode(v) }
        }
    }

    private fun decode(v: JsonElement): Any? = when (v) {
        is JsonArray -> v.map { (it as JsonPrimitive).content }.toSet()
        is JsonObject -> v["f"]?.let { (it as JsonPrimitive).doubleOrNull?.toFloat() }
            ?: v["l"]?.let { (it as JsonPrimitive).longOrNull }
            ?: v["i"]?.let { (it as JsonPrimitive).longOrNull?.toInt() }
        is JsonPrimitive -> if (v.isString) v.content else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull
        else -> null
    }

    private fun encode(v: Any?): JsonElement = when (v) {
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Int -> JsonObject(mapOf("i" to JsonPrimitive(v)))
        is Long -> JsonObject(mapOf("l" to JsonPrimitive(v)))
        is Float -> JsonObject(mapOf("f" to JsonPrimitive(v)))
        is Set<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
        else -> kotlinx.serialization.json.JsonNull
    }

    @Synchronized private fun save() {
        runCatching {
            file.parentFile.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonObject(map.mapValues { encode(it.value) }).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    private fun num(key: String): Number? = map[key] as? Number

    @Synchronized override fun getString(key: String, def: String?) = map[key] as? String ?: def
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, def: Set<String>?) = (map[key] as? Set<String>) ?: def
    @Synchronized override fun getInt(key: String, def: Int) = num(key)?.toInt() ?: def
    @Synchronized override fun getLong(key: String, def: Long) = num(key)?.toLong() ?: def
    @Synchronized override fun getFloat(key: String, def: Float) = num(key)?.toFloat() ?: def
    @Synchronized override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    @Synchronized override fun contains(key: String) = map.containsKey(key)
    override val all: Map<String, *> @Synchronized get() = HashMap(map)

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { synchronized(listeners) { listeners += l } }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { synchronized(listeners) { listeners -= l } }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, v: String?) = apply { puts[key] = v }
        override fun putStringSet(key: String, v: Set<String>?) = apply { puts[key] = v?.toSet() }
        override fun putInt(key: String, v: Int) = apply { puts[key] = v }
        override fun putLong(key: String, v: Long) = apply { puts[key] = v }
        override fun putFloat(key: String, v: Float) = apply { puts[key] = v }
        override fun putBoolean(key: String, v: Boolean) = apply { puts[key] = v }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            val changed: Set<String>
            synchronized(this@FilePrefs) {
                if (clear) map.clear()
                removes.forEach { map.remove(it) }
                puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                changed = removes + puts.keys
                save()
            }
            val ls = synchronized(listeners) { listeners.toList() }
            changed.forEach { k -> ls.forEach { it.onSharedPreferenceChanged(this@FilePrefs, k) } }
            return true
        }
    }
}
