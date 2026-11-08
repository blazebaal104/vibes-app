package dev.brahmkshatriya.echo.vibes.backend

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal Supabase client for Vibes: Auth (email + password), PostgREST, RPC, Storage and
 * Realtime (Phoenix websocket, postgres_changes). Written on OkHttp so it adds no new
 * dependency tree to the app.
 */
class Supa(context: Context) {

    companion object {
        const val URL = "https://kxgswrwitsedppjzcokh.supabase.co"
        const val KEY = "sb_publishable_Nry4enJeteEo6y1kIBzaLg_Z8jw2ivT"
        private const val TAG = "VibesSupa"
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        private val JSON_TYPE = "application/json".toMediaType()
    }

    class SupaException(message: String, val code: Int = 0) : Exception(message)

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val userId: String,
        val email: String,
    )

    private val prefs = context.getSharedPreferences("vibes_auth", Context.MODE_PRIVATE)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _session = MutableStateFlow(loadSession())
    val session = _session.asStateFlow()
    val userId get() = _session.value?.userId

    private fun loadSession(): Session? {
        val access = prefs.getString("access", null) ?: return null
        return Session(
            access, prefs.getString("refresh", "")!!, prefs.getLong("expires", 0),
            prefs.getString("uid", "")!!, prefs.getString("email", "")!!
        )
    }

    private fun saveSession(s: Session?) {
        prefs.edit {
            if (s == null) clear() else {
                putString("access", s.accessToken); putString("refresh", s.refreshToken)
                putLong("expires", s.expiresAt); putString("uid", s.userId); putString("email", s.email)
            }
        }
        _session.value = s
    }

    private fun parseSession(body: JsonObject): Session {
        val user = body["user"]?.jsonObject ?: throw SupaException("Risposta non valida")
        return Session(
            accessToken = body["access_token"]!!.jsonPrimitive.content,
            refreshToken = body["refresh_token"]!!.jsonPrimitive.content,
            expiresAt = System.currentTimeMillis() / 1000 +
                    (body["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600),
            userId = user["id"]!!.jsonPrimitive.content,
            email = user["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    // ------------------------------------------------------------------ http basics

    private suspend fun call(request: Request): Response = withContext(Dispatchers.IO) {
        http.newCall(request).execute()
    }

    private fun errorMessage(code: Int, body: String): String {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val raw = obj?.let {
            (it["msg"] ?: it["message"] ?: it["error_description"] ?: it["error"])?.jsonPrimitive?.contentOrNull
        } ?: body.take(200)
        return when {
            raw.contains("Invalid login credentials", true) -> "Email o password sbagliate"
            raw.contains("banned", true) -> "Questo account è stato bannato da un amministratore di Vibes"
            raw.contains("already registered", true) -> "Esiste già un account con questa email"
            raw.contains("Email not confirmed", true) -> "Conferma prima la tua email (controlla la posta)"
            raw.contains("Password should be", true) -> "La password deve avere almeno 6 caratteri"
            raw.contains("duplicate key", true) && raw.contains("username", true) -> "Questo username è già preso"
            raw.contains("vibes_profiles_username_check", true) -> "Username: 3-20 caratteri, solo lettere minuscole, numeri, _ e ."
            code == 401 -> "Sessione scaduta, accedi di nuovo"
            else -> raw
        }
    }

    private suspend fun execJson(request: Request): JsonElement? {
        call(request).use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw SupaException(errorMessage(r.code, body), r.code)
            if (body.isBlank()) return null
            return json.parseToJsonElement(body)
        }
    }

    private fun base(path: String) = Request.Builder().url("$URL$path").header("apikey", KEY)

    private suspend fun authed(path: String): Request.Builder {
        val token = validToken() ?: throw SupaException("Non hai effettuato l'accesso", 401)
        return base(path).header("Authorization", "Bearer $token")
    }

    // ------------------------------------------------------------------ auth

    private val refreshMutex = Mutex()

    suspend fun validToken(): String? = refreshMutex.withLock {
        val s = _session.value ?: return null
        if (s.expiresAt - System.currentTimeMillis() / 1000 > 60) return s.accessToken
        return runCatching { refresh(s.refreshToken).accessToken }.getOrElse {
            Log.w(TAG, "refresh failed", it)
            if (it is SupaException && (it.code == 400 || it.code == 401)) saveSession(null)
            null
        }
    }

    private suspend fun refresh(refreshToken: String): Session {
        val body = buildJsonObject { put("refresh_token", refreshToken) }
        val res = execJson(
            base("/auth/v1/token?grant_type=refresh_token")
                .post(body.toString().toRequestBody(JSON_TYPE)).build()
        )!!.jsonObject
        return parseSession(res).also { saveSession(it) }
    }

    /** Returns true when signed in immediately, false when the email must be confirmed first. */
    /**
     * Creates the account already confirmed (server function, no e-mail) and signs in right away.
     * Falls back to the classic sign-up if the function isn't reachable.
     */
    suspend fun signUpAndSignIn(email: String, password: String) {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val ok = runCatching {
            execJson(base("/functions/v1/vibes-signup").header("Authorization", "Bearer $KEY")
                .post(body.toString().toRequestBody(JSON_TYPE)).build())
            true
        }.getOrElse { e -> if (e is SupaException && e.code in 400..499) throw e else false }
        if (ok) { signIn(email, password); return }
        if (!signUp(email, password)) signIn(email, password)
    }

    suspend fun signUp(email: String, password: String): Boolean {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val res = execJson(base("/auth/v1/signup").post(body.toString().toRequestBody(JSON_TYPE)).build())!!.jsonObject
        if (res["access_token"] != null) { saveSession(parseSession(res)); return true }
        // Supabase answers an already-registered email with a fake user that has no identities
        // (and sends no email): tell the user to sign in instead of waiting for a mail.
        val user = (res["user"] as? JsonObject) ?: res
        val identities = user["identities"] as? JsonArray
        if (identities != null && identities.isEmpty())
            throw SupaException("Esiste già un account con questa email: usa Accedi (stessa password dell'app degli amici)")
        return false
    }

    suspend fun signIn(email: String, password: String) {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val res = execJson(
            base("/auth/v1/token?grant_type=password").post(body.toString().toRequestBody(JSON_TYPE)).build()
        )!!.jsonObject
        saveSession(parseSession(res))
    }

    suspend fun resetPassword(email: String) {
        val body = buildJsonObject { put("email", email.trim()) }
        execJson(base("/auth/v1/recover").post(body.toString().toRequestBody(JSON_TYPE)).build())
    }

    fun signOut() {
        realtime.stop()
        saveSession(null)
    }

    // ------------------------------------------------------------------ PostgREST

    suspend fun select(table: String, query: String): JsonArray =
        execJson(authed("/rest/v1/$table?$query").get().build())?.jsonArray ?: JsonArray(emptyList())

    suspend fun insert(table: String, row: JsonElement, returning: Boolean = true): JsonArray {
        val req = authed("/rest/v1/$table")
            .header("Prefer", if (returning) "return=representation" else "return=minimal")
            .post(row.toString().toRequestBody(JSON_TYPE)).build()
        return execJson(req)?.jsonArray ?: JsonArray(emptyList())
    }

    suspend fun upsert(table: String, row: JsonElement) {
        val req = authed("/rest/v1/$table")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(row.toString().toRequestBody(JSON_TYPE)).build()
        execJson(req)
    }

    /** INSERT … ON CONFLICT DO NOTHING (needs only the insert policy). */
    suspend fun insertIgnore(table: String, row: JsonElement) {
        val req = authed("/rest/v1/$table")
            .header("Prefer", "resolution=ignore-duplicates,return=minimal")
            .post(row.toString().toRequestBody(JSON_TYPE)).build()
        execJson(req)
    }

    suspend fun update(table: String, query: String, patch: JsonElement) {
        val req = authed("/rest/v1/$table?$query")
            .header("Prefer", "return=minimal")
            .patch(patch.toString().toRequestBody(JSON_TYPE)).build()
        execJson(req)
    }

    suspend fun delete(table: String, query: String) {
        execJson(authed("/rest/v1/$table?$query").delete().build())
    }

    /** Calls an edge function as the signed-in user. */
    suspend fun function(name: String, body: JsonObject): JsonElement? =
        execJson(authed("/functions/v1/$name").post(body.toString().toRequestBody(JSON_TYPE)).build())

    suspend fun rpc(fn: String, args: JsonObject = JsonObject(emptyMap())): JsonElement? =
        execJson(authed("/rest/v1/rpc/$fn").post(args.toString().toRequestBody(JSON_TYPE)).build())

    // ------------------------------------------------------------------ Storage

    suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String) {
        val req = authed("/storage/v1/object/$bucket/$path")
            .header("x-upsert", "true")
            .post(bytes.toRequestBody(contentType.toMediaType())).build()
        execJson(req)
    }

    /** Streams a file (videos) instead of loading it in memory. */
    suspend fun uploadFile(bucket: String, path: String, file: java.io.File, contentType: String) {
        val req = authed("/storage/v1/object/$bucket/$path")
            .header("x-upsert", "true")
            .post(file.asRequestBody(contentType.toMediaType())).build()
        execJson(req)
    }

    suspend fun download(bucket: String, path: String): ByteArray {
        val req = authed("/storage/v1/object/authenticated/$bucket/$path").get().build()
        call(req).use { r ->
            if (!r.isSuccessful) throw SupaException(errorMessage(r.code, r.body.string()), r.code)
            return r.body.bytes()
        }
    }

    suspend fun removeObject(bucket: String, path: String) {
        val body = buildJsonObject { put("prefixes", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(path)) }) }
        val req = authed("/storage/v1/object/$bucket")
            .method("DELETE", body.toString().toRequestBody(JSON_TYPE)).build()
        runCatching { execJson(req) }
    }

    // ------------------------------------------------------------------ Realtime

    data class Change(val table: String, val type: String, val record: JsonObject?, val old: JsonObject?)

    val realtime = Realtime()

    inner class Realtime {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var socket: WebSocket? = null
        private var heartbeat: Job? = null
        private var reconnect: Job? = null
        private val ref = AtomicInteger(1)
        private var wanted = false
        private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64)
        val changes = _changes.asSharedFlow()
        val connected = MutableStateFlow(false)

        /** One channel per table, each filtered server-side to rows that concern me. */
        private fun topics(uid: String) = listOf(
            "vibes_messages" to "receiver_id=eq.$uid",
            "vibes_messages" to "sender_id=eq.$uid",
            "vibes_game_lobbies" to "guest_id=eq.$uid",
            "vibes_game_lobbies" to "host_id=eq.$uid",
            "vibes_friend_requests" to "to_id=eq.$uid",
            "vibes_friendships" to "user_id=eq.$uid",
            "vibes_follows" to "followee_id=eq.$uid",
            "vibes_follows" to "follower_id=eq.$uid",
            "vibes_listen_invites" to "to_user=eq.$uid",
        )

        fun start() {
            wanted = true
            if (socket != null) return
            scope.launch { connect() }
        }

        fun stop() {
            wanted = false
            heartbeat?.cancel(); reconnect?.cancel()
            socket?.close(1000, null); socket = null
            connected.value = false
        }

        private suspend fun connect() {
            val token = validToken() ?: return
            val uid = userId ?: return
            val wsUrl = URL.replace("https://", "wss://") + "/realtime/v1/websocket?apikey=$KEY&vsn=1.0.0"
            val req = Request.Builder().url(wsUrl).build()
            socket = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected.value = true
                    topics(uid).forEachIndexed { i, (table, filter) ->
                        val payload = buildJsonObject {
                            put("config", buildJsonObject {
                                put("broadcast", buildJsonObject { put("self", false) })
                                put("presence", buildJsonObject { put("key", "") })
                                put("postgres_changes", buildJsonArray {
                                    add(buildJsonObject {
                                        put("event", "*"); put("schema", "public")
                                        put("table", table); put("filter", filter)
                                    })
                                })
                            })
                            put("access_token", token)
                        }
                        send(webSocket, "realtime:vibes-$i", "phx_join", payload)
                    }
                    heartbeat?.cancel()
                    heartbeat = scope.launch {
                        while (isActive) {
                            delay(25_000)
                            send(webSocket, "phoenix", "heartbeat", JsonObject(emptyMap()))
                            // keep the channel token fresh
                            validToken()?.let { t ->
                                topics(uid).indices.forEach { i ->
                                    send(webSocket, "realtime:vibes-$i", "access_token",
                                        buildJsonObject { put("access_token", t) })
                                }
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    if (msg["event"]?.jsonPrimitive?.contentOrNull != "postgres_changes") return
                    val data = msg["payload"]?.jsonObject?.get("data")?.jsonObject ?: return
                    val table = data["table"]?.jsonPrimitive?.contentOrNull ?: return
                    val type = data["type"]?.jsonPrimitive?.contentOrNull ?: return
                    val record = data["record"] as? JsonObject
                    val old = data["old_record"] as? JsonObject
                    _changes.tryEmit(Change(table, type, record, old))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "realtime failure", t); dropped()
                }
            })
        }

        private fun dropped() {
            connected.value = false
            heartbeat?.cancel()
            socket = null
            if (!wanted) return
            reconnect?.cancel()
            reconnect = scope.launch { delay(4_000); if (wanted && socket == null) connect() }
        }

        private fun send(ws: WebSocket, topic: String, event: String, payload: JsonObject) {
            val m = buildJsonObject {
                put("topic", topic); put("event", event); put("payload", payload)
                put("ref", ref.getAndIncrement().toString())
            }
            ws.send(m.toString())
        }
    }
}

// small JSON helpers used across the Vibes backend
fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
