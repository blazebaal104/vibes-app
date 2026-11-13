package dev.brahmkshatriya.echo.vibes.music

import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.LiveListen
import dev.brahmkshatriya.echo.vibes.backend.Profile
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * "Ascolta insieme": follows a friend's player 1:1 — same song, same second, same next songs,
 * pauses when they pause. Stops if you pick something else yourself or they stop listening.
 */
class ListenAlong(private val engine: MusicEngine) {
    data class Session(val friend: Profile, val live: LiveListen?)

    private val _session = MutableStateFlow<Session?>(null)
    val session = _session.asStateFlow()
    private val scope = MainScope()
    private var job: Job? = null
    private var expectedKey: String? = null
    /** How long a song takes to start on this phone (learned): we jump that far ahead so we land in sync. */
    private var startLatency = 1800L
    private var switchedAt = 0L

    private val browser get() = engine.player.browser.value

    /**
     * Pause pressed by the user anywhere (mini player, notification, lock screen, headphones): stop
     * following and stay paused right there; play then simply resumes from that point. To catch up
     * with the friend again you go back to their profile. Our own pauses/plays (mirroring the friend)
     * are marked with [own] so they don't count as the user's.
     */
    private var ownUntil = 0L
    private fun own() { ownUntil = android.os.SystemClock.elapsedRealtime() + 1500 }
    private var listening: androidx.media3.common.Player? = null
    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady || _session.value == null) return
            if (android.os.SystemClock.elapsedRealtime() < ownUntil) return
            // a phone call or another app taking the audio is not "the user paused"
            if (reason != androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
                reason != androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) return
            stop()
        }
    }
    private fun attach() {
        val b = browser ?: return
        if (listening === b) return
        listening?.removeListener(listener)
        b.addListener(listener); listening = b
    }
    private fun currentKey(): String? = engine.player.playerState.current.value?.let { "${it.mediaItem.extensionId}:${it.track.id}" }

    fun start(friend: Profile) {
        stop()
        _session.value = Session(friend, null)
        expectedKey = null
        job = scope.launch {
            var goneSince = 0L
            while (isActive) {
                val live = runCatching { VibesGraph.social.live(friend.id) }.getOrNull()?.takeIf { it.isFresh }
                _session.value = Session(friend, live)
                if (live == null) {
                    if (goneSince == 0L) goneSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - goneSince > 120_000) { stop(); return@launch }
                    if (browser?.playWhenReady == true) { own(); browser?.pause() }
                } else {
                    goneSince = 0
                    attach()
                    sync(live)
                }
                delay(1200)
            }
        }
    }

    private fun sync(live: LiveListen) {
        val b = browser ?: return
        val mine = currentKey()
        // I chose another song myself -> leave the session
        if (expectedKey != null && mine != null && mine != expectedKey && mine != live.song.key &&
            live.queue.none { it.key == mine }) { stop(); return }
        val target = live.positionNow() + 300 // network delay of the poll
        if (mine != live.song.key) {
            own()
            // they moved to the song that is next in OUR queue (we mirror theirs): just skip, it's
            // already preloaded, so it starts instantly
            val nextIdx = b.currentMediaItemIndex + 1
            val nextKey = if (nextIdx < b.mediaItemCount) runCatching {
                b.getMediaItemAt(nextIdx).let { "${it.extensionId}:${it.track.id}" }
            }.getOrNull() else null
            if (nextKey == live.song.key) {
                b.seekTo(nextIdx, target)
            } else {
                val songs = listOf(live.song) + live.queue
                engine.player.setQueue(live.song.ext, songs.map { it.toTrack() }, 0, null)
                // land where they will be once our song has actually started
                b.seekTo(target + startLatency)
            }
            expectedKey = live.song.key
            switchedAt = System.currentTimeMillis()
            if (live.playing) b.play() else b.pause()
            return
        }
        if (switchedAt != 0L && b.isPlaying) {
            // learn how long starting took on this phone/network (smoothed)
            val took = (System.currentTimeMillis() - switchedAt).coerceIn(300, 8000)
            startLatency = (startLatency * 2 + took) / 3
            switchedAt = 0
        }
        expectedKey = live.song.key
        if (live.playing) {
            // small drift is fine (a seek costs a re-buffer): only correct a real gap
            if (abs(b.currentPosition - target) > 1800) { own(); b.seekTo(target + if (b.bufferedPosition < target) startLatency / 2 else 0) }
            if (!b.playWhenReady) { own(); b.play() }
        } else {
            if (b.playWhenReady) { own(); b.pause() }
            if (abs(b.currentPosition - live.position) > 1500) { own(); b.seekTo(live.position) }
        }
    }

    fun stop() {
        listening?.removeListener(listener); listening = null
        job?.cancel(); job = null
        _session.value = null
        expectedKey = null
    }
}
