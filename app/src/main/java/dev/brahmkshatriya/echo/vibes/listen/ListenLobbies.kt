package dev.brahmkshatriya.echo.vibes.listen

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.media3.common.Player
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

@Serializable
data class LobbyState(
    val queue: List<SongRef> = emptyList(),
    val index: Int = 0,
    /** Server time (ms) at which song [index] is at [anchorPos]. */
    @SerialName("anchor_server") val anchorServer: Long = 0,
    @SerialName("anchor_pos") val anchorPos: Long = 0,
    val playing: Boolean = false,
    val seq: Long = 0,
)

@Serializable
data class ListenLobby(
    val id: String = "",
    val host: String = "",
    val name: String = "Lobby",
    val state: LobbyState = LobbyState(),
    val active: Boolean = true,
)

@Serializable
data class LobbyMember(
    @SerialName("lobby_id") val lobbyId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("seen_at") val seenAt: String? = null,
)

@Serializable
data class ListenInvite(
    val id: Long = 0,
    @SerialName("lobby_id") val lobbyId: String,
    @SerialName("from_user") val fromUser: String,
    @SerialName("to_user") val toUser: String,
)

/**
 * The listening lobby ("ascolto in gruppo", same room): one host ("re") controls the music, every
 * member's phone plays the same song at the same instant.
 *
 * How the sync works (target: well under 0.1 s between phones):
 *  - the host never sends "play now": it writes an anchor in SERVER time ("song 3 at 0 ms at
 *    server instant T", with T ~1.5 s in the future so everyone can buffer);
 *  - each phone measures its offset to the server clock with several round trips and keeps the
 *    most precise one (error = half the best round trip, typically < 20 ms);
 *  - every 100 ms each phone compares its player position with where the anchor says it must be
 *    and corrects: speed +-1..4 % for small errors (inaudible), a seek for large ones;
 *  - the delay between "play" and sound coming out is learned per phone and compensated.
 * Each member plays from their own Deezer account; crossfade is paused while in a lobby.
 */
class ListenLobbies(private val supa: Supa, private val context: Context) {
    private val json = Supa.json
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("vibes_lobby", Context.MODE_PRIVATE)

    private val _lobby = MutableStateFlow<ListenLobby?>(null)
    val lobby: StateFlow<ListenLobby?> = _lobby
    private val _members = MutableStateFlow<List<LobbyMember>>(emptyList())
    val members: StateFlow<List<LobbyMember>> = _members
    /** Current measured error vs the group (ms, positive = ahead), null when not playing. */
    private val _syncError = MutableStateFlow<Long?>(null)
    val syncError: StateFlow<Long?> = _syncError
    private val _invites = MutableStateFlow<List<ListenInvite>>(emptyList())
    val invites: StateFlow<List<ListenInvite>> = _invites

    /** Manual extra delay for Bluetooth speakers/headphones (ms), per phone. */
    var calibrationMs: Long
        get() = prefs.getLong("calibration", 0)
        set(v) = prefs.edit { putLong("calibration", v) }
    private var startLatency: Long
        get() = prefs.getLong("start_latency", 120)
        set(v) = prefs.edit { putLong("start_latency", v.coerceIn(0, 600)) }
    private var seekLatency: Long
        get() = prefs.getLong("seek_latency", 150)
        set(v) = prefs.edit { putLong("seek_latency", v.coerceIn(0, 800)) }

    private var engine: MusicEngine? = null
    private var loop: Job? = null
    private var poll: Job? = null
    private var offset: Long? = null
    private var lastClockSync = 0L
    private var savedFade: Int? = null

    val uid get() = supa.userId
    val isHost get() = _lobby.value?.host == uid && uid != null

    // ------------------------------------------------------------------ server clock

    private fun localNow() = System.currentTimeMillis()

    private suspend fun syncClock() = withContext(Dispatchers.IO) {
        val samples = (1..8).mapNotNull {
            val t0 = localNow()
            val s = runCatching { supa.rpc("vibes_now")?.jsonPrimitive?.long }.getOrNull()
            val t1 = localNow()
            s?.let { LobbySync.Sample(t0, it, t1) }
        }
        LobbySync.offset(samples)?.let { offset = it; lastClockSync = SystemClock.elapsedRealtime() }
    }

    fun serverNow(): Long? = offset?.let { localNow() + it }

    // ------------------------------------------------------------------ lobby API

    suspend fun create(name: String): ListenLobby {
        val me = uid ?: error("Non sei connesso")
        val row = supa.insert("vibes_listen_lobbies", buildJsonObject { put("host", me); put("name", name) }).first()
        val l = json.decodeFromJsonElement<ListenLobby>(row)
        supa.insert("vibes_listen_members", buildJsonObject { put("lobby_id", l.id); put("user_id", me) }, returning = false)
        return l
    }

    suspend fun join(lobbyId: String, engine: MusicEngine) {
        val me = uid ?: error("Non sei connesso")
        supa.upsert("vibes_listen_members", buildJsonObject { put("lobby_id", lobbyId); put("user_id", me) })
        runCatching { supa.delete("vibes_listen_invites", "lobby_id=eq.$lobbyId&to_user=eq.$me") }
        refreshInvites()
        enter(lobbyId, engine)
    }

    suspend fun invite(friendIds: List<String>) {
        val l = _lobby.value ?: return
        val me = uid ?: return
        friendIds.forEach { f ->
            runCatching {
                supa.insertIgnore("vibes_listen_invites", buildJsonObject { put("lobby_id", l.id); put("from_user", me); put("to_user", f) })
            }
        }
    }

    suspend fun refreshInvites() {
        val me = uid ?: return
        _invites.value = runCatching {
            supa.select("vibes_listen_invites", "to_user=eq.$me&select=*&order=created_at.desc&limit=10")
                .map { json.decodeFromJsonElement<ListenInvite>(it) }
        }.getOrDefault(emptyList())
    }

    suspend fun lobbyName(id: String): String? = runCatching {
        supa.select("vibes_listen_lobbies", "id=eq.$id&select=name").firstOrNull()?.let { (it as JsonObject)["name"]?.jsonPrimitive?.content }
    }.getOrNull()

    suspend fun transfer(to: String) {
        val l = _lobby.value ?: return
        supa.rpc("vibes_lobby_transfer", buildJsonObject { put("l", l.id); put("to_user", to) })
        refresh()
    }

    suspend fun kick(userId: String) {
        val l = _lobby.value ?: return
        supa.delete("vibes_listen_members", "lobby_id=eq.${l.id}&user_id=eq.$userId")
        refresh()
    }

    fun leave() {
        val l = _lobby.value
        val me = uid
        val others = _members.value.filter { it.userId != me }
        stopLoops()
        _lobby.value = null; _members.value = emptyList(); _syncError.value = null
        restorePlayer()
        if (l != null && me != null) scope.launch(Dispatchers.IO) {
            runCatching {
                // the last host leaving hands the crown to someone else (or closes the lobby)
                if (l.host == me && others.isNotEmpty() || l.host == me) {
                    val heir = supa.select("vibes_listen_members", "lobby_id=eq.${l.id}&user_id=neq.$me&select=user_id&limit=1").firstOrNull()
                        ?.let { (it as JsonObject)["user_id"]?.jsonPrimitive?.content }
                    if (heir != null) supa.rpc("vibes_lobby_transfer", buildJsonObject { put("l", l.id); put("to_user", heir) })
                    else supa.update("vibes_listen_lobbies", "id=eq.${l.id}", buildJsonObject { put("active", false) })
                }
                supa.delete("vibes_listen_members", "lobby_id=eq.${l.id}&user_id=eq.$me")
            }
        }
    }

    // ------------------------------------------------------------------ host controls

    private suspend fun writeState(s: LobbyState) {
        val l = _lobby.value ?: return
        val next = s.copy(seq = l.state.seq + 1)
        _lobby.value = l.copy(state = next) // apply locally right away
        supa.update("vibes_listen_lobbies", "id=eq.${l.id}", buildJsonObject {
            put("state", json.encodeToJsonElement(next)); put("updated_at", java.time.Instant.now().toString())
        })
    }

    /** Host: start [songs] from [index] for everyone, 1.5 s from now (time to buffer). */
    suspend fun play(songs: List<SongRef>, index: Int = 0) {
        if (!isHost) return
        if (offset == null) syncClock()
        val now = serverNow() ?: return
        writeState(LobbyState(songs, index.coerceIn(0, songs.size - 1), now + 1500, 0, true))
    }

    private fun current(now: Long): LobbySync.Position? {
        val s = _lobby.value?.state ?: return null
        if (s.queue.isEmpty()) return null
        return LobbySync.expected(s.index, s.anchorPos, s.anchorServer, s.playing, now, s.queue.map { it.duration })
    }

    suspend fun togglePlay() {
        if (!isHost) return
        val s = _lobby.value?.state ?: return
        val now = serverNow() ?: return
        val p = current(now) ?: return
        writeState(
            if (s.playing) s.copy(index = p.index, anchorPos = p.posMs.coerceAtLeast(0), anchorServer = now, playing = false)
            else s.copy(index = p.index, anchorPos = p.posMs.coerceAtLeast(0), anchorServer = now + 800, playing = true)
        )
    }

    suspend fun skip(delta: Int) {
        if (!isHost) return
        val s = _lobby.value?.state ?: return
        val now = serverNow() ?: return
        val p = current(now) ?: return
        val i = if (delta < 0 && p.posMs > 3000) p.index else (p.index + delta).coerceIn(0, s.queue.size - 1)
        writeState(s.copy(index = i, anchorPos = 0, anchorServer = now + 1200, playing = true))
    }

    suspend fun seek(ms: Long) {
        if (!isHost) return
        val s = _lobby.value?.state ?: return
        val now = serverNow() ?: return
        val p = current(now) ?: return
        writeState(s.copy(index = p.index, anchorPos = ms.coerceAtLeast(0), anchorServer = now + 600))
    }

    // ------------------------------------------------------------------ follow the group

    private suspend fun refresh() {
        val id = _lobby.value?.id ?: return
        runCatching {
            supa.select("vibes_listen_lobbies", "id=eq.$id&select=*").firstOrNull()?.let {
                val l = json.decodeFromJsonElement<ListenLobby>(it)
                // keep my own newer local write until the server has it
                val cur = _lobby.value
                _lobby.value = if (cur != null && cur.state.seq > l.state.seq) l.copy(state = cur.state) else l
            }
            _members.value = supa.select("vibes_listen_members", "lobby_id=eq.$id&select=lobby_id,user_id,seen_at&order=joined_at.asc")
                .map { json.decodeFromJsonElement<LobbyMember>(it) }
        }
    }

    suspend fun enter(lobbyId: String, engine: MusicEngine) {
        this.engine = engine
        if (_lobby.value?.id != lobbyId) {
            stopLoops()
            _lobby.value = ListenLobby(id = lobbyId)
        }
        // crossfade swaps players at song changes: off while in the lobby, restored on leave
        val sp = context.getSettings()
        if (savedFade == null) {
            savedFade = sp.getInt("true_crossfade_seconds", dev.brahmkshatriya.echo.playback.PlayerService.VIBES_DEFAULT_FADE)
            sp.edit { putInt("true_crossfade_seconds", 0) }
        }
        refresh()
        syncClock()
        poll = scope.launch {
            var tick = 0
            while (isActive) {
                refresh()
                if (tick++ % 15 == 0) uid?.let { me ->
                    runCatching { supa.update("vibes_listen_members", "lobby_id=eq.$lobbyId&user_id=eq.$me", buildJsonObject { put("seen_at", java.time.Instant.now().toString()) }) }
                }
                if (SystemClock.elapsedRealtime() - lastClockSync > 30_000) syncClock()
                delay(600)
            }
        }
        loop = scope.launch { followLoop() }
    }

    private fun stopLoops() { loop?.cancel(); poll?.cancel(); loop = null; poll = null }

    private fun restorePlayer() {
        val e = engine ?: return
        runCatching { e.player.browser.value?.setPlaybackSpeed(1f) }
        savedFade?.let { f -> context.getSettings().edit { putInt("true_crossfade_seconds", f) } }
        savedFade = null
    }

    private fun currentKey(e: MusicEngine): String? =
        e.player.playerState.current.value?.let { "${it.mediaItem.extensionId}:${it.track.id}" }

    private suspend fun followLoop() {
        val errs = ArrayDeque<Long>()
        var lastLoadAt = 0L
        var lastFixAt = 0L
        var startedAt = 0L
        while (true) {
            delay(100)
            val e = engine ?: continue
            val l = _lobby.value ?: continue
            val s = l.state
            val now = serverNow() ?: continue
            if (s.queue.isEmpty()) continue
            val exp = LobbySync.expected(s.index, s.anchorPos, s.anchorServer, s.playing, now + calibrationMs, s.queue.map { it.duration })
            val want = s.queue[exp.index]
            val browser = e.player.browser.value ?: continue
            if (exp.ended) { if (browser.isPlaying) e.player.setPlaying(false); _syncError.value = null; continue }

            // 1. the right song loaded (with the rest of the queue after it, so the next one preloads)
            if (currentKey(e) != want.key) {
                if (SystemClock.elapsedRealtime() - lastLoadAt > 2500) {
                    e.player.setQueue(want.ext, s.queue.drop(exp.index).map { it.toTrack() }, 0, null)
                    e.player.setPlaying(false)
                    errs.clear()
                    lastLoadAt = SystemClock.elapsedRealtime()
                }
                continue
            }

            // 2. paused group: hold the exact position
            if (!s.playing) {
                if (browser.isPlaying) e.player.setPlaying(false)
                if (kotlin.math.abs(e.position() - exp.posMs) > 300) e.seekTo(exp.posMs.coerceAtLeast(0))
                _syncError.value = null
                continue
            }
            // 3. start scheduled in the future: sit at 0 ready, press play exactly on time
            if (exp.posMs < 0) {
                if (e.position() > 500) e.seekTo(0)
                if (browser.isPlaying) e.player.setPlaying(false)
                if (-exp.posMs <= startLatency + 100 && browser.playbackState == Player.STATE_READY) {
                    delay((-exp.posMs - startLatency).coerceAtLeast(0))
                    e.player.setPlaying(true); startedAt = SystemClock.elapsedRealtime(); errs.clear()
                }
                continue
            }
            // 4. playing: join late / resume after buffering
            if (!browser.isPlaying) {
                if (browser.playbackState == Player.STATE_READY || browser.playbackState == Player.STATE_IDLE) {
                    e.seekTo(exp.posMs + startLatency + seekLatency / 2)
                    e.player.setPlaying(true); startedAt = SystemClock.elapsedRealtime(); errs.clear()
                }
                continue
            }
            // 5. measure and correct (skip the first 700 ms after a start: positions settle)
            if (SystemClock.elapsedRealtime() - startedAt < 700) continue
            val err = e.position() - exp.posMs
            errs.addLast(err); if (errs.size > 5) errs.removeFirst()
            if (errs.size < 3) continue
            val med = LobbySync.median(errs.toList())
            _syncError.value = med
            // learn this phone's start delay from the first measurement after a start
            if (SystemClock.elapsedRealtime() - startedAt < 2000 && kotlin.math.abs(med) < 400) {
                startLatency = (startLatency - med / 2)
            }
            if (SystemClock.elapsedRealtime() - lastFixAt < 400) continue
            when (val f = LobbySync.fix(med, exp.posMs, seekLatency)) {
                LobbySync.Fix.None -> browser.setPlaybackSpeed(1f)
                is LobbySync.Fix.Speed -> browser.setPlaybackSpeed(f.speed)
                is LobbySync.Fix.Seek -> {
                    browser.setPlaybackSpeed(1f)
                    e.seekTo(f.toMs)
                    startedAt = SystemClock.elapsedRealtime()
                    // the error after this seek tells how long a seek takes on this phone
                    scope.launch {
                        delay(1500)
                        val after = e.position() - (current(serverNow() ?: return@launch)?.posMs ?: return@launch)
                        if (kotlin.math.abs(after) < 400) seekLatency = seekLatency - after / 2
                    }
                }
            }
            errs.clear()
            lastFixAt = SystemClock.elapsedRealtime()
        }
    }
}
