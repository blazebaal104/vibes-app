package dev.brahmkshatriya.echo.extensions

import android.content.Context
import android.content.SharedPreferences
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.Injectable
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.providers.MessageFlowProvider
import dev.brahmkshatriya.echo.common.providers.MetadataProvider
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extensions.db.models.CurrentUser
import dev.brahmkshatriya.echo.extensions.db.models.UserEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Extension settings stored like the phone's (one preferences file per extension). */
class PrefsSettings(private val sp: SharedPreferences) : Settings {
    override fun getString(key: String) = sp.getString(key, null)
    override fun putString(key: String, value: String?) = sp.edit().putString(key, value).apply()
    override fun getStringSet(key: String) = sp.getStringSet(key, null)
    override fun putStringSet(key: String, value: Set<String>?) = sp.edit().putStringSet(key, value).apply()
    override fun getInt(key: String) = if (sp.contains(key)) sp.getInt(key, 0) else null
    override fun putInt(key: String, value: Int?) = sp.edit().apply { if (value == null) remove(key) else putInt(key, value) }.apply()
    override fun getBoolean(key: String) = if (sp.contains(key)) sp.getBoolean(key, false) else null
    override fun putBoolean(key: String, value: Boolean?) = sp.edit().apply { if (value == null) remove(key) else putBoolean(key, value) }.apply()
}

/** Saved accounts of the extensions (the phone keeps them in Room; here: one JSON file). */
object ExtensionStore {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Data(val users: List<UserEntity> = emptyList(), val current: List<CurrentUser> = emptyList())

    private val file by lazy { android.content.DesktopEnv.dataDir.resolve("extension_users.json") }
    private var data = runCatching { json.decodeFromString(Data.serializer(), file.readText()) }.getOrDefault(Data())
    private val currentFlow = MutableStateFlow(data.current)
    val current: StateFlow<List<CurrentUser>> = currentFlow

    @Synchronized private fun save(d: Data) {
        data = d; currentFlow.value = d.current
        runCatching { file.writeText(json.encodeToString(Data.serializer(), d)) }
    }

    class UserDao {
        fun observeCurrentUser(): Flow<List<CurrentUser>> = currentFlow
        suspend fun insertUsers(list: List<UserEntity>) = synchronized(ExtensionStore) {
            val keep = data.users.filter { u -> list.none { it.type == u.type && it.extId == u.extId && it.id == u.id } }
            save(data.copy(users = keep + list))
        }
        suspend fun setCurrentUser(c: CurrentUser) = synchronized(ExtensionStore) {
            save(data.copy(current = data.current.filter { !(it.type == c.type && it.extId == c.extId) } + c))
        }
        fun getUser(c: CurrentUser): UserEntity? = data.users.firstOrNull { it.type == c.type && it.extId == c.extId && it.id == c.userId }
    }

    private val dao = UserDao()
    fun userDao() = dao
}

/**
 * Desktop extension loader: the Deezer extension is compiled into the app (no APK loading), set up
 * the same way the phone's ExtensionLoader does (settings, metadata, message flow, logged-in user).
 */
class ExtensionLoader(private val context: Context) {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val messageFlow = MutableSharedFlow<Message>(extraBufferCapacity = 16)
    val db = ExtensionStore

    private val deezerMeta = Metadata(
        className = "dev.brahmkshatriya.echo.extension.DeezerExtension", path = "", importType = ImportType.BuiltIn,
        type = ExtensionType.MUSIC, id = "deezer", name = "Deezer", version = "1", description = "Deezer", author = "Vibes",
    )

    private fun settingsFor(m: Metadata): Settings = PrefsSettings(context.getSharedPreferences("${m.type.name.lowercase()}_${m.id}", Context.MODE_PRIVATE))

    private val deezer: MusicExtension = MusicExtension(deezerMeta, Injectable({ dev.brahmkshatriya.echo.extension.DeezerExtension() as ExtensionClient }, listOf<suspend ExtensionClient.() -> Unit>({
        if (this is MetadataProvider) setMetadata(deezerMeta)
        if (this is MessageFlowProvider) setMessageFlow(messageFlow)
        setSettings(settingsFor(deezerMeta))
        onInitialize()
        onExtensionSelected()
    })))

    val music: StateFlow<List<MusicExtension>> = MutableStateFlow(listOf(deezer))

    init {
        // keep the extension's logged-in user in sync with the saved current user (like the phone)
        scope.launch {
            db.current.collect { users ->
                val cur = users.firstOrNull { it.type == ExtensionType.MUSIC && it.extId == deezerMeta.id }
                val user = cur?.let { db.userDao().getUser(it)?.user }
                runCatching {
                    deezer.instance.injectOrRun("user") { if (this is LoginClient) setLoginUser(user) }
                }
            }
        }
    }
}

object ExtensionUtils {
    suspend fun <R> dev.brahmkshatriya.echo.common.Extension<*>.get(block: suspend ExtensionClient.() -> R): Result<R> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = instance.value().getOrThrow() as ExtensionClient
                client.block()
            }
        }

    suspend inline fun <reified C, R> dev.brahmkshatriya.echo.common.Extension<*>.getAs(crossinline block: suspend C.() -> R): Result<R> = get {
        val client = this as? C ?: throw ClientException.NotSupported(C::class.simpleName ?: "Unknown Class")
        block(client)
    }

    suspend inline fun <reified C, R> dev.brahmkshatriya.echo.common.Extension<*>.getIf(crossinline block: suspend C.() -> R): Result<R?> = get {
        (this as? C)?.let { block(it) }
    }

    suspend inline fun <reified T> dev.brahmkshatriya.echo.common.Extension<*>.isClient() = instance.value().getOrNull() is T
}
