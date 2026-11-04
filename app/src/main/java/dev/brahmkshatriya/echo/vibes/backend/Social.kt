package dev.brahmkshatriya.echo.vibes.backend

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Everything social in Vibes: my profile, friends, requests, chats, shared playlists and game
 * lobbies. Live updates come from [Supa.Realtime]; screens observe the StateFlows here.
 */
class Social(context: Context, val supa: Supa) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Supa.json

    private val _me = MutableStateFlow<Profile?>(null)
    val me = _me.asStateFlow()

    /** null = not loaded yet, false = signed in but no profile (must pick a username). */
    val hasProfile = MutableStateFlow<Boolean?>(null)

    private val _friends = MutableStateFlow<List<Profile>>(emptyList())
    val friends = _friends.asStateFlow()

    private val _requests = MutableStateFlow<List<Pair<FriendRequest, Profile>>>(emptyList())
    val requests = _requests.asStateFlow()

    /** Latest message per friend id, for the chat list. */
    private val _lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())
    val lastMessages = _lastMessages.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread = _unread.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    /** New messages addressed to me (for notifications). */
    val incoming = _incoming.asSharedFlow()

    private val _messageChanges = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    /** Inserts and tick updates of messages in any of my chats. */
    val messageChanges = _messageChanges.asSharedFlow()

    private val _lobbyChanges = MutableSharedFlow<Lobby>(extraBufferCapacity = 32)
    val lobbyChanges = _lobbyChanges.asSharedFlow()

    private val _newChallenges = MutableSharedFlow<Lobby>(extraBufferCapacity = 8)
    val newChallenges = _newChallenges.asSharedFlow()

    /** Friend id of the chat open on screen, so its messages don't notify. */
    @Volatile
    var openChat: String? = null

    val uid get() = supa.userId

    init {
        scope.launch {
            supa.realtime.changes.collect { c -> runCatching { onChange(c) } }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ lifecycle

    suspend fun bootstrap() {
        val id = uid ?: return
        val rows = supa.select("vibes_profiles", "id=eq.$id&select=*")
        val p = rows.firstOrNull()?.let { json.decodeFromJsonElement<Profile>(it) }
        _me.value = p
        hasProfile.value = p != null
        if (p != null) {
            supa.realtime.start()
            refreshFriends(); refreshRequests(); refreshChats()
        }
    }

    fun clear() {
        _me.value = null; hasProfile.value = null
        _friends.value = emptyList(); _requests.value = emptyList()
        _lastMessages.value = emptyMap(); _unread.value = emptyMap()
    }

    // ------------------------------------------------------------------ profile

    suspend fun createProfile(username: String, displayName: String, emoji: String, color: String) {
        val id = uid ?: throw Supa.SupaException("Non hai effettuato l'accesso")
        val row = buildJsonObject {
            put("id", id); put("username", username.trim().lowercase())
            put("display_name", displayName.trim()); put("avatar_emoji", emoji); put("avatar_color", color)
        }
        supa.insert("vibes_profiles", row, returning = false)
        bootstrap()
    }

    suspend fun updateProfile(
        displayName: String? = null, bio: String? = null, emoji: String? = null,
        color: String? = null, top4: List<SongRef>? = null,
    ) {
        val id = uid ?: return
        val patch = buildJsonObject {
            displayName?.let { put("display_name", it.trim()) }
            bio?.let { put("bio", it.trim().take(160)) }
            emoji?.let { put("avatar_emoji", it) }
            color?.let { put("avatar_color", it) }
            top4?.let { put("top4", json.encodeToJsonElement(ListSerializer(SongRef.serializer()), it.take(4))) }
        }
        supa.update("vibes_profiles", "id=eq.$id", patch)
        _me.update { cur ->
            cur?.copy(
                displayName = displayName?.trim() ?: cur.displayName,
                bio = bio?.trim() ?: cur.bio,
                avatarEmoji = emoji ?: cur.avatarEmoji,
                avatarColor = color ?: cur.avatarColor,
                top4 = top4?.take(4) ?: cur.top4,
            )
        }
    }

    /** Best effort "now playing" on my profile, so friends see what I'm listening to. */
    fun setNowPlaying(song: SongRef?) {
        val id = uid ?: return
        if (_me.value == null) return
        scope.launch {
            runCatching {
                val patch = buildJsonObject {
                    put("now_playing", song?.let { json.encodeToJsonElement(SongRef.serializer(), it) }
                        ?: kotlinx.serialization.json.JsonNull)
                    put("last_seen", java.time.Instant.now().toString())
                }
                supa.update("vibes_profiles", "id=eq.$id", patch)
            }
        }
    }

    suspend fun profile(id: String): Profile? =
        supa.select("vibes_profiles", "id=eq.$id&select=*").firstOrNull()
            ?.let { json.decodeFromJsonElement<Profile>(it) }

    suspend fun searchUsers(query: String): List<Profile> {
        val q = query.trim().lowercase().replace("*", "")
        if (q.length < 2) return emptyList()
        return supa.select("vibes_profiles", "username=ilike.${enc("*$q*")}&select=*&limit=20")
            .map { json.decodeFromJsonElement<Profile>(it) }
            .filter { it.id != uid }
    }

    // ------------------------------------------------------------------ friends

    suspend fun refreshFriends() {
        val id = uid ?: return
        val ids = supa.select("vibes_friendships", "user_id=eq.$id&select=friend_id")
            .mapNotNull { (it as JsonObject).str("friend_id") }
        _friends.value = if (ids.isEmpty()) emptyList() else
            supa.select("vibes_profiles", "id=in.(${ids.joinToString(",")})&select=*")
                .map { json.decodeFromJsonElement<Profile>(it) }
                .sortedBy { it.name.lowercase() }
    }

    suspend fun refreshRequests() {
        val id = uid ?: return
        val reqs = supa.select("vibes_friend_requests", "to_id=eq.$id&status=eq.pending&select=*")
            .map { json.decodeFromJsonElement<FriendRequest>(it) }
        if (reqs.isEmpty()) { _requests.value = emptyList(); return }
        val profiles = supa.select("vibes_profiles", "id=in.(${reqs.joinToString(",") { it.fromId }})&select=*")
            .map { json.decodeFromJsonElement<Profile>(it) }.associateBy { it.id }
        _requests.value = reqs.mapNotNull { r -> profiles[r.fromId]?.let { r to it } }
    }

    suspend fun addByCode(code: String): Profile {
        val res = supa.rpc("vibes_add_friend_by_code", buildJsonObject { put("code", code) })
        refreshFriends()
        return json.decodeFromJsonElement(res!!)
    }

    suspend fun sendRequest(to: String) {
        val id = uid ?: return
        supa.insert("vibes_friend_requests", buildJsonObject { put("from_id", id); put("to_id", to) }, false)
    }

    suspend fun accept(requestId: String) {
        supa.rpc("vibes_accept_request", buildJsonObject { put("request_id", requestId) })
        refreshRequests(); refreshFriends()
    }

    suspend fun decline(requestId: String) {
        supa.delete("vibes_friend_requests", "id=eq.$requestId")
        refreshRequests()
    }

    suspend fun removeFriend(friendId: String) {
        val id = uid ?: return
        supa.delete("vibes_friendships", "or=(and(user_id.eq.$id,friend_id.eq.$friendId),and(user_id.eq.$friendId,friend_id.eq.$id))")
        refreshFriends()
    }

    // ------------------------------------------------------------------ chat

    suspend fun refreshChats() {
        val id = uid ?: return
        val rows = supa.select(
            "vibes_messages",
            "or=(sender_id.eq.$id,receiver_id.eq.$id)&select=*&order=created_at.desc&limit=400"
        ).map { json.decodeFromJsonElement<Message>(it) }
        val last = mutableMapOf<String, Message>()
        val unread = mutableMapOf<String, Int>()
        rows.forEach { m ->
            val other = if (m.senderId == id) m.receiverId else m.senderId
            if (other !in last) last[other] = m
            if (m.receiverId == id && m.readAt == null) unread[other] = (unread[other] ?: 0) + 1
        }
        _lastMessages.value = last
        _unread.value = unread
        // everything that reached this phone is delivered (second grey tick for the sender)
        unread.keys.forEach { from -> runCatching { markDelivered(from) } }
    }

    suspend fun history(friendId: String, limit: Int = 200): List<Message> {
        val id = uid ?: return emptyList()
        return supa.select(
            "vibes_messages",
            "or=(and(sender_id.eq.$id,receiver_id.eq.$friendId),and(sender_id.eq.$friendId,receiver_id.eq.$id))" +
                    "&select=*&order=created_at.desc&limit=$limit"
        ).map { json.decodeFromJsonElement<Message>(it) }.reversed()
    }

    suspend fun send(to: String, kind: String, body: String, payload: JsonObject? = null): Message {
        val id = uid ?: throw Supa.SupaException("Non hai effettuato l'accesso")
        val row = buildJsonObject {
            put("sender_id", id); put("receiver_id", to); put("kind", kind); put("body", body)
            payload?.let { put("payload", it) }
        }
        val saved = json.decodeFromJsonElement<Message>(supa.insert("vibes_messages", row).first())
        _lastMessages.update { it + (to to saved) }
        return saved
    }

    suspend fun sendSong(to: String, song: SongRef, note: String = "") =
        send(to, "track", note, json.encodeToJsonElement(SongRef.serializer(), song) as JsonObject)

    /** Uploads a snapshot of a playlist so friends (or a link) can open and save it. */
    suspend fun publishPlaylist(playlist: SharedPlaylist): SharedPlaylist {
        val id = uid ?: throw Supa.SupaException("Non hai effettuato l'accesso")
        val row = buildJsonObject {
            put("owner_id", id); put("name", playlist.name)
            put("tracks", json.encodeToJsonElement(ListSerializer(SongRef.serializer()), playlist.tracks))
        }
        return json.decodeFromJsonElement(supa.insert("vibes_shared_playlists", row).first())
    }

    suspend fun sendPlaylist(to: String, playlist: SharedPlaylist, note: String = ""): Message {
        val saved = if (playlist.id.isNotBlank()) playlist else publishPlaylist(playlist)
        val payload = buildJsonObject {
            put("playlist_id", saved.id); put("name", saved.name); put("count", saved.tracks.size)
            saved.tracks.firstOrNull()?.cover?.let { put("cover", it) }
        }
        return send(to, "playlist", note, payload)
    }

    suspend fun sharedPlaylist(id: String): SharedPlaylist? =
        supa.select("vibes_shared_playlists", "id=eq.$id&select=*").firstOrNull()
            ?.let { json.decodeFromJsonElement<SharedPlaylist>(it) }

    suspend fun markRead(friendId: String) {
        supa.rpc("vibes_mark_read", buildJsonObject { put("from_user", friendId) })
        _unread.update { it - friendId }
    }

    private suspend fun markDelivered(friendId: String) {
        supa.rpc("vibes_mark_delivered", buildJsonObject { put("from_user", friendId) })
    }

    // ------------------------------------------------------------------ media (temporary cloud copy)

    /** Uploads to vibes-media/<me>/<friend>/<name>; the receiver deletes it after downloading. */
    suspend fun sendMedia(to: String, kind: String, bytes: ByteArray, mime: String, ext: String, durationMs: Long? = null): Message {
        val id = uid ?: throw Supa.SupaException("Non hai effettuato l'accesso")
        val path = "$id/$to/${System.currentTimeMillis()}.$ext"
        supa.upload("vibes-media", path, bytes, mime)
        val payload = buildJsonObject {
            put("path", path); put("mime", mime); put("size", bytes.size)
            durationMs?.let { put("duration", it) }
        }
        return send(to, kind, "", payload)
    }

    suspend fun fetchMedia(path: String): ByteArray = supa.download("vibes-media", path)
    suspend fun deleteMedia(path: String) = supa.removeObject("vibes-media", path)

    // ------------------------------------------------------------------ games

    suspend fun createLobby(game: String, guest: String, state: JsonObject): Lobby {
        val id = uid ?: throw Supa.SupaException("Non hai effettuato l'accesso")
        val row = buildJsonObject {
            put("game", game); put("host_id", id); put("guest_id", guest); put("state", state)
        }
        return json.decodeFromJsonElement(supa.insert("vibes_game_lobbies", row).first())
    }

    suspend fun lobby(id: String): Lobby? =
        supa.select("vibes_game_lobbies", "id=eq.$id&select=*").firstOrNull()
            ?.let { json.decodeFromJsonElement<Lobby>(it) }

    suspend fun updateLobby(id: String, status: String?, patch: JsonObject): Lobby {
        val res = supa.rpc("vibes_lobby_update", buildJsonObject {
            put("lobby_id", id); put("new_status", status?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("patch", patch)
        })
        return json.decodeFromJsonElement(res!!)
    }

    suspend fun claimRound(id: String, round: Int, winner: String, reason: String): Boolean {
        val res = supa.rpc("vibes_claim_round", buildJsonObject {
            put("lobby_id", id); put("round", round); put("winner", winner); put("reason", reason)
        })
        return (res as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    }

    // ------------------------------------------------------------------ realtime

    private suspend fun onChange(c: Supa.Change) {
        val id = uid ?: return
        when (c.table) {
            "vibes_messages" -> {
                val m = c.record?.let { json.decodeFromJsonElement<Message>(it) } ?: return
                val other = if (m.senderId == id) m.receiverId else m.senderId
                _messageChanges.emit(m)
                _lastMessages.update { cur ->
                    val prev = cur[other]
                    if (prev == null || prev.id <= m.id) cur + (other to m) else cur
                }
                if (c.type == "INSERT" && m.receiverId == id) {
                    if (openChat == other) markRead(other)
                    else {
                        _unread.update { it + (other to ((it[other] ?: 0) + 1)) }
                        markDelivered(other)
                        _incoming.emit(m)
                    }
                }
            }
            "vibes_game_lobbies" -> {
                val l = c.record?.let { json.decodeFromJsonElement<Lobby>(it) } ?: return
                _lobbyChanges.emit(l)
                if (c.type == "INSERT" && l.guestId == id) _newChallenges.emit(l)
            }
            "vibes_friend_requests" -> refreshRequests()
            "vibes_friendships" -> refreshFriends()
        }
    }

    fun friendName(id: String): String =
        _friends.value.firstOrNull { it.id == id }?.name ?: "Un amico"
}
