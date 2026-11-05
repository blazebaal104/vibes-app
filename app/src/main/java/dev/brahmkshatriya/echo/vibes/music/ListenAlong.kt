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
                    browser?.pause()
                } else {
                    goneSince = 0
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
            if (abs(b.currentPosition - target) > 1800) b.seekTo(target + if (b.bufferedPosition < target) startLatency / 2 else 0)
            if (!b.isPlaying) b.play()
        } else {
            if (b.isPlaying) b.pause()
            if (abs(b.currentPosition - live.position) > 1500) b.seekTo(live.position)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        _session.value = null
        expectedKey = null
    }
}
