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

    /** Host: start [songs] from [index] for everyone, 1.5 s from now (time to load the song). */
    suspend fun play(songs: List<SongRef>, index: Int = 0) {
        if (!isHost) return
        if (offset == null) syncClock()
        val now = serverNow() ?: return
        writeState(LobbyState(songs, index.coerceIn(0, songs.size - 1), now + 1500, 0, true))
    }

    /** Where the group is now (song index + position), from the last anchor. */
    private fun current(now: Long): LobbySync.Position? {
        val s = _lobby.value?.state ?: return null
        if (s.queue.isEmpty()) return null
        return LobbySync.expected(s.index, s.anchorPos, s.anchorServer, s.playing, maxOf(now, s.anchorServer), s.queue.map { it.duration })
    }

    /** The host's REAL player position (the host is the master clock, never the anchor maths). */
    private fun hostPosition(): Long? = engine?.position()

    /**
     * Host play/pause. Pause: everyone stops on the exact position the host's player is at.
     * Play: everyone (host included) starts from that position at the same server instant,
     * 0.6 s from now (enough for the others to get it and be ready, paused on that spot).
     */
    suspend fun togglePlay() {
        if (!isHost) return
        val s = _lobby.value?.state ?: return
        val now = serverNow() ?: return
        val p = current(now) ?: return
        if (s.playing) {
            engine?.player?.setPlaying(false) // the host stops at once
            val at = hostPosition() ?: p.posMs
            writeState(s.copy(index = p.index, anchorPos = at.coerceAtLeast(0), anchorServer = now, playing = false))
        } else {
            writeState(s.copy(index = p.index, anchorPos = s.anchorPos.coerceAtLeast(0), anchorServer = now + 600, playing = true))
        }
    }

    suspend fun skip(delta: Int) {
        if (!isHost) return
        val s = _lobby.value?.state ?: return
        val now = serverNow() ?: return
        val p = current(now) ?: return
        val i = if (delta < 0 && p.posMs > 3000) p.index else (p.index + delta).coerceIn(0, s.queue.size - 1)
        writeState(s.copy(index = i, anchorPos = 0, anchorServer = now + 1500, playing = true))
    }

    suspend fun seek(ms: Long) {
        if (!isHost) return
        val s = _lobby.value?.state ?: return
        val now = serverNow() ?: return
        val p = current(now) ?: return
        writeState(s.copy(index = p.index, anchorPos = ms.coerceAtLeast(0), anchorServer = now + 600))
    }

    /** Host: its player stalled (buffering): re-anchor the group on where the host really is. */
    private suspend fun reanchor(pos: Long) {
        val s = _lobby.value?.state ?: return
        val now = serverNow() ?: return
        writeState(s.copy(anchorPos = pos, anchorServer = now))
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
                delay(250) // host actions reach everyone within ~0.3 s
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

    /**
     * Same loop for everyone, two roles:
     *  - before a scheduled start: sit PAUSED exactly on the start position (seeking while paused is
     *    silent), then press play at the start instant minus this phone's learned start delay;
     *  - after the start: the HOST never corrects itself (it is the clock). A MEMBER measures for
     *    2 s and, only if it is off by more than 40 ms, does ONE gentle catch-up (2.5 % faster or
     *    slower for the needed seconds, then back to normal) or ONE seek if off by more than
     *    0.5 s. Then it leaves the audio alone: no constant tiny changes, no clicks.
     */
    private suspend fun followLoop() {
        var lastLoadAt = 0L
        var startedAt = 0L          // elapsedRealtime of our last start (or seek)
        var startedSeq = -1L        // anchor we started for
        var corrected = false       // already did the one correction for this anchor
        var speedUntil = 0L         // a catch-up at modified speed ends here
        var learned = false
        val errs = ArrayDeque<Long>()
        while (true) {
            delay(50)
            val e = engine ?: continue
            val l = _lobby.value ?: continue
            val s = l.state
            val now = serverNow() ?: continue
            if (s.queue.isEmpty()) continue
            val browser = e.player.browser.value ?: continue
            val host = l.host == uid
            val nowCal = now + if (host) 0 else calibrationMs
            val p = LobbySync.expected(s.index, s.anchorPos, s.anchorServer, s.playing, maxOf(nowCal, s.anchorServer), s.queue.map { it.duration })
            if (p.ended) { if (browser.isPlaying) e.player.setPlaying(false); _syncError.value = null; continue }
            val want = s.queue[p.index]

            // the right song loaded (with the rest of the queue, so the next one preloads)
            if (currentKey(e) != want.key) {
                if (SystemClock.elapsedRealtime() - lastLoadAt > 2500) {
                    e.player.setQueue(want.ext, s.queue.drop(p.index).map { it.toTrack() }, 0, null)
                    e.player.setPlaying(false)
                    lastLoadAt = SystemClock.elapsedRealtime()
                    startedSeq = -1
                }
                continue
            }
            if (s.seq != startedSeq) {
                // a new host action: forget previous measurements and corrections
                errs.clear(); corrected = false; learned = false
                if (speedUntil > 0) { browser.setPlaybackSpeed(1f); speedUntil = 0 }
            }

            // paused group: everyone stopped on the host's exact position
            if (!s.playing) {
                if (browser.isPlaying) e.player.setPlaying(false)
                if (kotlin.math.abs(e.position() - s.anchorPos) > 30) e.seekTo(s.anchorPos)
                startedSeq = s.seq
                _syncError.value = null
                continue
            }

            val wait = s.anchorServer - nowCal
            // scheduled start: wait paused on the start position, press play right on time
            if (wait > 0 && s.seq != startedSeq) {
                if (browser.isPlaying) e.player.setPlaying(false)
                if (kotlin.math.abs(e.position() - p.posMs) > 30) e.seekTo(p.posMs)
                if (wait <= startLatency + 60 && browser.playbackState == Player.STATE_READY) {
                    delay((wait - startLatency).coerceAtLeast(0))
                    e.player.setPlaying(true)
                    startedAt = SystemClock.elapsedRealtime(); startedSeq = s.seq
                }
                continue
            }
            // started late (joined, slow load or missed the instant): jump in once, then play
            if (s.seq != startedSeq || !browser.isPlaying) {
                if (browser.playbackState == Player.STATE_READY || browser.playbackState == Player.STATE_IDLE) {
                    if (!browser.isPlaying) {
                        e.seekTo(p.posMs + startLatency + seekLatency)
                        e.player.setPlaying(true)
                    }
                    startedAt = SystemClock.elapsedRealtime(); startedSeq = s.seq
                }
                continue
            }

            // end of a catch-up at modified speed
            if (speedUntil > 0 && SystemClock.elapsedRealtime() >= speedUntil) { browser.setPlaybackSpeed(1f); speedUntil = 0; errs.clear() }
            // measure only once things settled (1.5 s after a start) and not during a catch-up
            if (SystemClock.elapsedRealtime() - startedAt < 1500 || speedUntil > 0) continue
            errs.addLast(e.position() - p.posMs); if (errs.size > 12) errs.removeFirst()
            if (errs.size < 12) continue
            val med = LobbySync.median(errs.toList())
            _syncError.value = if (host) 0 else med

            // learn how long "play" takes to become sound on this phone (first measurement only)
            if (!learned) {
                learned = true
                if (kotlin.math.abs(med) < 400) startLatency = startLatency - med / 2
            }
            if (host) {
                // the host is the clock: if its player stalled (buffering), move the anchor, never the audio
                if (kotlin.math.abs(med) > 150) { reanchor(e.position()); errs.clear() }
                continue
            }
            if (corrected) continue
            when {
                kotlin.math.abs(med) <= 40 -> {}
                kotlin.math.abs(med) <= 500 -> {
                    // one smooth catch-up: 2.5 % faster/slower for exactly the time needed
                    val speed = if (med > 0) 0.975f else 1.025f
                    browser.setPlaybackSpeed(speed)
                    speedUntil = SystemClock.elapsedRealtime() + (kotlin.math.abs(med) / 0.025).toLong()
                    corrected = true
                }
                else -> {
                    e.seekTo(p.posMs + seekLatency)
                    startedAt = SystemClock.elapsedRealtime()
                    corrected = true
                    scope.launch {
                        delay(2000)
                        val pos2 = current(serverNow() ?: return@launch)?.posMs ?: return@launch
                        val after = e.position() - pos2
                        if (kotlin.math.abs(after) < 400) seekLatency = seekLatency - after / 2
                    }
                }
            }
            errs.clear()
        }
    }
}
