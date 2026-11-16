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

    /** Ids of people I follow / who follow me. Friends (chat, challenges) = mutual follows. */
    private val _following = MutableStateFlow<Set<String>>(emptySet())
    val following = _following.asStateFlow()
    private val _followers = MutableStateFlow<Set<String>>(emptySet())
    val followers = _followers.asStateFlow()
    /** Profiles of everyone in following ∪ followers. */
    private val _people = MutableStateFlow<Map<String, Profile>>(emptyMap())
    val people = _people.asStateFlow()
    private val _stories = MutableStateFlow<List<StoryGroup>>(emptyList())
    val stories = _stories.asStateFlow()

    /** Latest message per friend id, for the chat list. */
    private val _lastMessages = MutableStateFlow<Map<String, Message>>(emptyMap())
    val lastMessages = _lastMessages.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread = _unread.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    /** New messages addressed to me (for notifications). */
    val incoming = _incoming.asSharedFlow()
    /** Someone invited me to a listening lobby (INSERT on vibes_listen_invites). */
    private val _lobbyInvites = MutableSharedFlow<dev.brahmkshatriya.echo.vibes.listen.ListenInvite>(extraBufferCapacity = 8)
    val lobbyInvites = _lobbyInvites.asSharedFlow()

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

    /** Accounts with moderation powers (vibes_admins). */
    val admins = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val isAdmin: Boolean get() = uid != null && uid in admins.value

    // ------------------------------------------------------------------ moderation (admins only; the
    // database rules enforce it, these calls just fail for everybody else)

    suspend fun adminPatch(userId: String, patch: kotlinx.serialization.json.JsonObject) {
        supa.update("vibes_profiles", "id=eq.$userId", patch)
        refreshPerson(userId)
    }

    /** Re-reads one profile after a moderation change so every screen shows the new data. */
    suspend fun refreshPerson(userId: String): Profile? {
        val p = profile(userId) ?: return null
        _people.update { m -> if (userId in m) m + (userId to p) else m }
        return p
    }

    suspend fun adminRemoveAvatar(userId: String) {
        adminPatch(userId, buildJsonObject { put("avatar_url", kotlinx.serialization.json.JsonNull) })
    }

    suspend fun adminSetAvatar(userId: String, jpeg: ByteArray) {
        val url = uploadPublicImage(jpeg, "admin_$userId")
        adminPatch(userId, buildJsonObject { put("avatar_url", url) })
    }

    suspend fun adminBan(userId: String, reason: String) {
        supa.function("vibes-admin", buildJsonObject { put("action", "ban"); put("user_id", userId); put("reason", reason) })
        refreshPerson(userId)
    }

    suspend fun adminUnban(userId: String) {
        supa.function("vibes-admin", buildJsonObject { put("action", "unban"); put("user_id", userId) })
        refreshPerson(userId)
    }

    suspend fun adminDeleteStories(userId: String) = supa.delete("vibes_stories", "user_id=eq.$userId")
    suspend fun adminDeleteUploads(userId: String) = supa.delete("vibes_uploads", "user_id=eq.$userId")

    suspend fun bootstrap() {
        val id = uid ?: return
        val rows = supa.select("vibes_profiles", "id=eq.$id&select=*")
        val p = rows.firstOrNull()?.let { json.decodeFromJsonElement<Profile>(it) }
        _me.value = p
        hasProfile.value = p != null
        runCatching {
            admins.value = supa.select("vibes_admins", "select=user_id").mapNotNull {
                (it as? kotlinx.serialization.json.JsonObject)?.get("user_id")?.let { v -> (v as kotlinx.serialization.json.JsonPrimitive).content }
            }.toSet()
        }
        if (p != null) {
            supa.realtime.start()
            refreshFriends(); refreshRequests(); refreshChats()
            runCatching { refreshFollows() }
            runCatching { refreshStories() }
        }
    }

    fun clear() {
        _me.value = null; hasProfile.value = null
        _friends.value = emptyList(); _requests.value = emptyList()
        _following.value = emptySet(); _followers.value = emptySet(); _people.value = emptyMap(); _stories.value = emptyList()
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

    /** Uploads a square profile photo (already compressed) and saves its public URL. */
    suspend fun uploadAvatar(jpeg: ByteArray) {
        val id = uid ?: return
        val path = "$id/avatar_${System.currentTimeMillis()}.jpg"
        supa.upload("vibes-avatars", path, jpeg, "image/jpeg")
        val url = "${Supa.URL}/storage/v1/object/public/vibes-avatars/$path"
        supa.update("vibes_profiles", "id=eq.$id", buildJsonObject { put("avatar_url", url) })
        _me.update { it?.copy(avatarUrl = url) }
    }

    /** Uploads a public image (playlist covers…) and returns its URL. */
    suspend fun uploadPublicImage(jpeg: ByteArray, name: String): String {
        val id = uid ?: error("Non sei connesso")
        val path = "$id/${name}_${System.currentTimeMillis()}.jpg"
        supa.upload("vibes-avatars", path, jpeg, "image/jpeg")
        return "${Supa.URL}/storage/v1/object/public/vibes-avatars/$path"
    }

    /** Profile background photo: everyone opening your profile sees it (blurred, like glass). */
    suspend fun uploadBanner(jpeg: ByteArray) {
        val id = uid ?: return
        val path = "$id/banner_${System.currentTimeMillis()}.jpg"
        supa.upload("vibes-avatars", path, jpeg, "image/jpeg")
        val url = "${Supa.URL}/storage/v1/object/public/vibes-avatars/$path"
        supa.update("vibes_profiles", "id=eq.$id", buildJsonObject { put("banner_url", url) })
        _me.update { it?.copy(bannerUrl = url) }
    }

    suspend fun removeBanner() {
        val id = uid ?: return
        supa.update("vibes_profiles", "id=eq.$id", buildJsonObject { put("banner_url", kotlinx.serialization.json.JsonNull) })
        _me.update { it?.copy(bannerUrl = null) }
    }

    suspend fun removeAvatar() {
        val id = uid ?: return
        supa.update("vibes_profiles", "id=eq.$id", buildJsonObject { put("avatar_url", kotlinx.serialization.json.JsonNull) })
        _me.update { it?.copy(avatarUrl = null) }
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

    /** Writes some profile columns and mirrors them locally. */
    suspend fun patchProfile(patch: kotlinx.serialization.json.JsonObject, local: (Profile) -> Profile) {
        val id = uid ?: return
        supa.update("vibes_profiles", "id=eq.$id", patch)
        _me.update { it?.let(local) }
    }

    suspend fun setFavArtist(a: FavArtist?) = patchProfile(buildJsonObject {
        put("fav_artist", a?.let { json.encodeToJsonElement(FavArtist.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
    }) { it.copy(favArtist = a) }

    suspend fun setFavSong(s: SongRef?) = patchProfile(buildJsonObject {
        put("fav_song", s?.let { json.encodeToJsonElement(SongRef.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
    }) { it.copy(favSong = s) }

    suspend fun setFavPlaylist(p: dev.brahmkshatriya.echo.vibes.music.VibesPlaylist?) {
        val fav = p?.let {
            val shared = publishPlaylist(SharedPlaylist(name = it.name, tracks = it.songs))
            FavPlaylist(shared.id, it.name, it.cover ?: it.songs.firstOrNull()?.cover, it.songs.size, it.emoji, it.color)
        }
        patchProfile(buildJsonObject {
            put("fav_playlist", fav?.let { json.encodeToJsonElement(FavPlaylist.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
        }) { it.copy(favPlaylist = fav) }
    }

    suspend fun setPrivacy(p: Privacy) = patchProfile(buildJsonObject {
        put("privacy", json.encodeToJsonElement(Privacy.serializer(), p))
        if (!p.nowPlaying) put("now_playing", kotlinx.serialization.json.JsonNull)
    }) { it.copy(privacy = p) }

    /** Publishes this week's summary + all-time records (what friends can see). */
    fun publishStats(week: WeekSummary?, records: Records?) {
        if (uid == null || _me.value == null) return
        scope.launch {
            runCatching {
                patchProfile(buildJsonObject {
                    put("stats_week", week?.let { json.encodeToJsonElement(WeekSummary.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
                    put("records", records?.let { json.encodeToJsonElement(Records.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
                }) { it.copy(statsWeek = week, records = records) }
            }
        }
    }

    private var lastLive: String? = null

    /** Real-time playback state for friends (called by the player service). */
    fun publishLive(l: LiveListen?) {
        val id = uid ?: return
        val me = _me.value
        val allowed = me?.privacy?.nowPlaying != false && !dev.brahmkshatriya.echo.vibes.VibesGraph.prefs.privateSession
        val value = if (allowed) l else null
        val encoded = value?.let { json.encodeToString(LiveListen.serializer(), it) }
        if (encoded == null && lastLive == null) return
        lastLive = encoded
        scope.launch {
            runCatching {
                supa.update("vibes_profiles", "id=eq.$id", buildJsonObject {
                    put("live", value?.let { json.encodeToJsonElement(LiveListen.serializer(), it) } ?: kotlinx.serialization.json.JsonNull)
                })
            }
        }
    }

    /** Just the live state of someone (polled by their profile screen / listen-along). */
    suspend fun live(userId: String): LiveListen? =
        supa.select("vibes_profiles", "id=eq.$userId&select=live").firstOrNull()
            ?.let { (it as JsonObject)["live"] }?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.let { json.decodeFromJsonElement(LiveListen.serializer(), it) }

    /** Best effort "now playing" on my profile, so friends see what I'm listening to. */
    fun setNowPlaying(song: SongRef?) {
        val id = uid ?: return
        if (_me.value == null) return
        if (_me.value?.privacy?.nowPlaying == false && song != null) return
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

    // ------------------------------------------------------------------ follow

    suspend fun refreshFollows() {
        val id = uid ?: return
        val ing = supa.select("vibes_follows", "follower_id=eq.$id&select=followee_id").mapNotNull { (it as JsonObject).str("followee_id") }.toSet()
        val ers = supa.select("vibes_follows", "followee_id=eq.$id&select=follower_id").mapNotNull { (it as JsonObject).str("follower_id") }.toSet()
        val ids = ing + ers
        _people.value = if (ids.isEmpty()) emptyMap() else
            supa.select("vibes_profiles", "id=in.(${ids.joinToString(",")})&select=*")
                .map { json.decodeFromJsonElement<Profile>(it) }.associateBy { it.id }
        _following.value = ing
        _followers.value = ers
    }

    suspend fun follow(other: String) {
        val id = uid ?: return
        _following.update { it + other }
        supa.insertIgnore("vibes_follows", buildJsonObject { put("follower_id", id); put("followee_id", other) })
        refreshFollows(); refreshFriends()
    }

    suspend fun unfollow(other: String) {
        val id = uid ?: return
        _following.update { it - other }
        supa.delete("vibes_follows", "follower_id=eq.$id&followee_id=eq.$other")
        refreshFollows(); refreshFriends()
    }

    /** Removes someone from MY followers. */
    suspend fun removeFollower(other: String) {
        val id = uid ?: return
        supa.delete("vibes_follows", "follower_id=eq.$other&followee_id=eq.$id")
        refreshFollows(); refreshFriends()
    }

    suspend fun followByCode(code: String): Profile {
        val res = supa.rpc("vibes_follow_by_code", buildJsonObject { put("code", code) })
        refreshFollows(); refreshFriends()
        return json.decodeFromJsonElement(res!!)
    }

    /** (followers, following) of anybody. */
    suspend fun followLists(userId: String): Pair<List<Profile>, List<Profile>> {
        val ers = supa.select("vibes_follows", "followee_id=eq.$userId&select=follower_id").mapNotNull { (it as JsonObject).str("follower_id") }
        val ing = supa.select("vibes_follows", "follower_id=eq.$userId&select=followee_id").mapNotNull { (it as JsonObject).str("followee_id") }
        val all = (ers + ing).toSet()
        val map = if (all.isEmpty()) emptyMap() else supa.select("vibes_profiles", "id=in.(${all.joinToString(",")})&select=*")
            .map { json.decodeFromJsonElement<Profile>(it) }.associateBy { it.id }
        return ers.mapNotNull { map[it] } to ing.mapNotNull { map[it] }
    }

    // ------------------------------------------------------------------ own music & samples

    suspend fun uploadAsset(file: java.io.File, ext: String, mime: String): String {
        val id = uid ?: error("Non sei connesso")
        val path = "$id/${java.util.UUID.randomUUID()}.$ext"
        supa.uploadFile("vibes-uploads", path, file, mime)
        return "${Supa.URL}/storage/v1/object/public/vibes-uploads/$path"
    }

    /** Publishes the songs and returns them as saved (with their ids). */
    suspend fun publishUploads(list: List<Upload>): List<Upload> {
        val id = uid ?: error("Non sei connesso")
        return supa.insert("vibes_uploads", kotlinx.serialization.json.JsonArray(list.map { u ->
            buildJsonObject {
                put("user_id", id); put("title", u.title); put("artist", u.artist)
                u.album?.let { put("album", it) }; u.albumId?.let { put("album_id", it) }
                put("track_no", u.trackNo); u.cover?.let { put("cover", it) }; put("url", u.url)
                put("duration_ms", u.durationMs); put("genre", u.genre)
                put("description", u.description); put("visibility", u.visibility); put("explicit", u.explicit)
                put("tags", kotlinx.serialization.json.JsonArray(u.tags.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
        }), true).map { json.decodeFromJsonElement<Upload>(it) }
    }

    /** A play of a song published on Vibes, sent only after a third of it was really heard. */
    fun countUploadPlay(uploadId: String, listenedMs: Long) {
        scope.launch {
            runCatching { supa.rpc("vibes_upload_play", buildJsonObject { put("item", uploadId); put("listened_ms", listenedMs) }) }
        }
    }

    /** Songs published on Vibes matching a search (title, artist, album, genre). */
    suspend fun searchUploads(q: String, limit: Int = 20): List<Upload> {
        if (q.isBlank()) return emptyList()
        val t = enc("*${q.trim().replace("*", "").replace(",", " ").replace("(", " ").replace(")", " ")}*")
        return supa.select("vibes_uploads", "or=(title.ilike.$t,artist.ilike.$t,album.ilike.$t,genre.ilike.$t)&select=*&order=plays.desc&limit=$limit")
            .map { json.decodeFromJsonElement<Upload>(it) }
    }

    suspend fun uploadsByIds(ids: Collection<String>): List<Upload> =
        if (ids.isEmpty()) emptyList() else
            supa.select("vibes_uploads", "id=in.(${ids.joinToString(",")})&select=*").map { json.decodeFromJsonElement<Upload>(it) }

    // ------------------------------------------------------------------ albums (ordered lists of published songs)

    suspend fun publishAlbum(a: VibesAlbum): VibesAlbum {
        val id = uid ?: error("Non sei connesso")
        return supa.insert("vibes_albums", buildJsonObject {
            put("user_id", id); put("title", a.title); put("artist", a.artist); a.cover?.let { put("cover", it) }
            put("genre", a.genre); put("description", a.description); put("visibility", a.visibility)
            a.id.takeIf { it.isNotBlank() }?.let { put("id", it) }
            put("tracks", kotlinx.serialization.json.JsonArray(a.tracks.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }, true).first().let { json.decodeFromJsonElement<VibesAlbum>(it) }
    }

    suspend fun albumsOf(userId: String): List<VibesAlbum> =
        supa.select("vibes_albums", "user_id=eq.$userId&select=*&order=created_at.desc").map { json.decodeFromJsonElement<VibesAlbum>(it) }

    suspend fun latestAlbums(limit: Int = 12): List<VibesAlbum> =
        supa.select("vibes_albums", "select=*&order=created_at.desc&limit=$limit").map { json.decodeFromJsonElement<VibesAlbum>(it) }

    suspend fun deleteAlbum(id: String) = supa.delete("vibes_albums", "id=eq.$id")

    suspend fun albumById(id: String): VibesAlbum? =
        supa.select("vibes_albums", "id=eq.$id&select=*&limit=1").firstOrNull()?.let { json.decodeFromJsonElement<VibesAlbum>(it) }

    // ------------------------------------------------------------------ saves + studio analytics

    /** Who started following me after [sinceIso] (oldest first): follower id + when. */
    suspend fun newFollowers(sinceIso: String): List<Pair<String, String>> {
        val me = uid ?: return emptyList()
        return supa.select("vibes_follows", "followee_id=eq.$me&created_at=gt.${enc(sinceIso)}&select=follower_id,created_at&order=created_at.asc&limit=20")
            .mapNotNull { r -> (r as? JsonObject)?.let { o -> o.str("follower_id")?.let { it to (o.str("created_at") ?: sinceIso) } } }
    }

    suspend fun mySaves(): List<String> {
        val id = uid ?: return emptyList()
        return supa.select("vibes_upload_saves", "user_id=eq.$id&select=upload_id&order=created_at.desc").mapNotNull { (it as JsonObject).str("upload_id") }
    }

    suspend fun save(uploadId: String, on: Boolean) {
        val id = uid ?: return
        if (on) supa.insertIgnore("vibes_upload_saves", buildJsonObject { put("upload_id", uploadId); put("user_id", id) })
        else supa.delete("vibes_upload_saves", "upload_id=eq.$uploadId&user_id=eq.$id")
    }

    suspend fun studioStats(days: Int = 28): StudioStats? =
        supa.rpc("vibes_studio_stats", buildJsonObject { put("ndays", days) })?.let { json.decodeFromJsonElement<StudioStats>(it) }

    suspend fun uploadsOf(userId: String): List<Upload> =
        supa.select("vibes_uploads", "user_id=eq.$userId&select=*&order=created_at.desc,track_no.asc")
            .map { json.decodeFromJsonElement<Upload>(it) }

    suspend fun latestUploads(limit: Int = 30): List<Upload> =
        supa.select("vibes_uploads", "select=*&order=created_at.desc&limit=$limit").map { json.decodeFromJsonElement<Upload>(it) }

    suspend fun deleteUpload(id: String) = supa.delete("vibes_uploads", "id=eq.$id")

    suspend fun uploadById(id: String): Upload? =
        supa.select("vibes_uploads", "id=eq.$id&select=*&limit=1").firstOrNull()?.let { json.decodeFromJsonElement<Upload>(it) }

    suspend fun publishSample(s: Sample) {
        val id = uid ?: error("Non sei connesso")
        supa.insert("vibes_samples", buildJsonObject {
            put("user_id", id); put("name", s.name); put("kind", s.kind); put("genre", s.genre)
            s.bpm?.let { put("bpm", it) }; s.key?.let { put("musical_key", it) }
            put("tags", kotlinx.serialization.json.JsonArray(s.tags.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("url", s.url); put("duration_ms", s.durationMs)
        }, false)
    }

    /** Sample search: name/tags/genre text, kind, BPM range. */
    suspend fun searchSamples(q: String, kind: String?, genre: String?, bpmMin: Int?, bpmMax: Int?): List<Sample> {
        val f = mutableListOf("select=*", "order=created_at.desc", "limit=100")
        if (q.isNotBlank()) {
            val t = enc("*${q.trim().replace("*", "").replace(",", " ")}*")
            f += "or=(name.ilike.$t,genre.ilike.$t,musical_key.ilike.$t)"
        }
        kind?.let { f += "kind=eq.$it" }
        genre?.let { f += "genre=ilike.${enc(it)}" }
        bpmMin?.let { f += "bpm=gte.$it" }
        bpmMax?.let { f += "bpm=lte.$it" }
        return supa.select("vibes_samples", f.joinToString("&")).map { json.decodeFromJsonElement<Sample>(it) }
    }

    /** Uploads I liked. */
    suspend fun myLikes(): Set<String> {
        val id = uid ?: return emptySet()
        return supa.select("vibes_upload_likes", "user_id=eq.$id&select=upload_id").mapNotNull { (it as JsonObject).str("upload_id") }.toSet()
    }

    suspend fun like(uploadId: String, on: Boolean) {
        val id = uid ?: return
        if (on) supa.insertIgnore("vibes_upload_likes", buildJsonObject { put("upload_id", uploadId); put("user_id", id) })
        else supa.delete("vibes_upload_likes", "upload_id=eq.$uploadId&user_id=eq.$id")
    }

    suspend fun trendingUploads(limit: Int = 20): List<Upload> =
        supa.select("vibes_uploads", "select=*&order=plays.desc,likes.desc&limit=$limit").map { json.decodeFromJsonElement<Upload>(it) }

    suspend fun uploadsFrom(userIds: Collection<String>, limit: Int = 30): List<Upload> =
        if (userIds.isEmpty()) emptyList() else
            supa.select("vibes_uploads", "user_id=in.(${userIds.joinToString(",")})&select=*&order=created_at.desc&limit=$limit")
                .map { json.decodeFromJsonElement<Upload>(it) }

    fun count(kind: String, item: String) {
        scope.launch { runCatching { supa.rpc("vibes_count", buildJsonObject { put("kind", kind); put("item", item) }) } }
    }

    // ------------------------------------------------------------------ game scores

    suspend fun submitScore(game: String, score: Int, difficulty: String = "") {
        val id = uid ?: return
        supa.insert("vibes_game_scores", buildJsonObject {
            put("user_id", id); put("game", game); put("score", score); put("difficulty", difficulty)
        }, false)
    }

    /** Best score of me and each friend for a game, highest first. */
    suspend fun leaderboard(game: String): List<Pair<Profile, Int>> {
        val id = uid ?: return emptyList()
        val people = _friends.value + listOfNotNull(_me.value)
        val ids = (people.map { it.id } + id).distinct()
        val rows = supa.select("vibes_game_scores", "game=eq.$game&user_id=in.(${ids.joinToString(",")})&select=user_id,score&order=score.desc&limit=500")
        val best = mutableMapOf<String, Int>()
        rows.forEach { r -> val o = r as JsonObject; val u = o.str("user_id") ?: return@forEach; val sc = o.int("score") ?: 0
            if ((best[u] ?: -1) < sc) best[u] = sc }
        val byId = people.associateBy { it.id }
        return best.entries.mapNotNull { (u, sc) -> byId[u]?.let { it to sc } }.sortedByDescending { it.second }
    }

    // ------------------------------------------------------------------ stories

    /** Active stories of me + people I follow, grouped per person (mine first, then unseen first). */
    suspend fun refreshStories() {
        val id = uid ?: return
        val now = java.time.Instant.now().toString()
        val authors = _following.value + id
        val rows = supa.select("vibes_stories",
            "user_id=in.(${authors.joinToString(",")})&expires_at=gt.${enc(now)}&select=*&order=created_at.asc")
            .map { json.decodeFromJsonElement<Story>(it) }
        if (rows.isEmpty()) { _stories.value = emptyList(); return }
        val seen = supa.select("vibes_story_views", "viewer_id=eq.$id&story_id=in.(${rows.joinToString(",") { it.id }})&select=story_id")
            .mapNotNull { (it as JsonObject).str("story_id") }.toSet()
        val profiles = (_people.value + listOfNotNull(_me.value).associateBy { it.id })
        _stories.value = rows.groupBy { it.userId }.mapNotNull { (u, list) -> profiles[u]?.let { StoryGroup(it, list, seen) } }
            .sortedWith(compareBy<StoryGroup>({ it.user.id != id }, { it.allSeen }, { -(it.stories.lastOrNull()?.createdAt?.hashCode() ?: 0) }))
    }

    /** Uploads a story photo/video and returns its public URL. */
    suspend fun uploadStoryFile(file: java.io.File, video: Boolean): String {
        val id = uid ?: error("Non sei connesso")
        val path = "$id/${java.util.UUID.randomUUID()}.${if (video) "mp4" else "jpg"}"
        supa.uploadFile("vibes-stories", path, file, if (video) "video/mp4" else "image/jpeg")
        return "${Supa.URL}/storage/v1/object/public/vibes-stories/$path"
    }

    suspend fun postStory(song: SongRef, caption: String, style: StoryStyle, media: List<StoryMedia> = emptyList()): Story {
        val id = uid ?: error("Non sei connesso")
        val row = supa.insert("vibes_stories", buildJsonObject {
            put("user_id", id)
            put("song", json.encodeToJsonElement(SongRef.serializer(), song))
            put("caption", caption)
            put("style", json.encodeToJsonElement(StoryStyle.serializer(), style))
            put("media", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(StoryMedia.serializer()), media))
        })
        refreshStories()
        return json.decodeFromJsonElement(row.first())
    }

    suspend fun deleteStory(storyId: String) {
        supa.delete("vibes_stories", "id=eq.$storyId")
        refreshStories()
    }

    suspend fun markStorySeen(storyId: String) {
        val id = uid ?: return
        runCatching { supa.insertIgnore("vibes_story_views", buildJsonObject { put("story_id", storyId); put("viewer_id", id) }) }
        _stories.update { groups -> groups.map { g -> if (g.stories.any { it.id == storyId }) g.copy(seen = g.seen + storyId) else g } }
    }

    /** Who saw one of my stories. */
    suspend fun storyViewers(storyId: String): List<Profile> {
        val ids = supa.select("vibes_story_views", "story_id=eq.$storyId&select=viewer_id&order=viewed_at.desc")
            .mapNotNull { (it as JsonObject).str("viewer_id") }
        if (ids.isEmpty()) return emptyList()
        val map = supa.select("vibes_profiles", "id=in.(${ids.joinToString(",")})&select=*")
            .map { json.decodeFromJsonElement<Profile>(it) }.associateBy { it.id }
        return ids.mapNotNull { map[it] }
    }

    /** All my stories (also expired ones) between two instants: for the monthly recap. */
    suspend fun myStories(fromIso: String, toIso: String): List<Story> {
        val id = uid ?: return emptyList()
        return supa.select("vibes_stories",
            "user_id=eq.$id&created_at=gte.${enc(fromIso)}&created_at=lt.${enc(toIso)}&select=*&order=created_at.asc")
            .map { json.decodeFromJsonElement<Story>(it) }
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

    suspend fun sendPlaylist(to: String, playlist: SharedPlaylist, note: String = "", collabId: String? = null): Message {
        val saved = if (playlist.id.isNotBlank()) playlist else publishPlaylist(playlist)
        val payload = buildJsonObject {
            put("playlist_id", saved.id); put("name", saved.name); put("count", saved.tracks.size)
            collabId?.let { put("collab_id", it) }
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
            "vibes_listen_invites" -> if (c.type == "INSERT") c.record?.let {
                _lobbyInvites.emit(json.decodeFromJsonElement<dev.brahmkshatriya.echo.vibes.listen.ListenInvite>(it))
            }
            "vibes_friend_requests" -> refreshRequests()
            "vibes_friendships" -> refreshFriends()
            "vibes_follows" -> { refreshFollows(); refreshFriends() }
        }
    }

    fun friendName(id: String): String =
        _friends.value.firstOrNull { it.id == id }?.name ?: "Un amico"
}
