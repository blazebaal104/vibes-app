package dev.brahmkshatriya.echo.vibes.karaoke

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Karaoke HD (AI vocal removal) for PC. Same API as the phone. Until the PC engine is ready it
 * reports that HD is not available yet: the quick karaoke (real-time filter) works.
 */
object KaraokeHD {
    const val RATE = 44100

    sealed interface Phase {
        data object Idle : Phase
        data object NeedsModel : Phase
        data class DownloadingModel(val progress: Float) : Phase
        data class Fetching(val progress: Float) : Phase
        data class Separating(val progress: Float) : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    data class Background(val title: String?, val progress: Float, val queued: Int, val doneInBatch: Int, val batch: Int)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase
    val background: StateFlow<Background?> = MutableStateFlow(null)
    val active: StateFlow<Boolean> = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = MutableStateFlow(false)
    val cached: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    @Volatile var nextInQueue: SongRef? = null
    @Volatile var externalPositionMs: Long = 0
    @Volatile var onEnded: (() -> Unit)? = null

    private const val SOON = "Il karaoke HD su PC arriva con il prossimo aggiornamento: intanto usa quello rapido"

    fun statusLine(): Triple<String, String, Float> = Triple("Karaoke HD", SOON, 0f)
    fun init(context: Context) {}
    fun hasModel() = true
    fun open(song: SongRef, positionMs: Long) { _phase.value = Phase.Failed(SOON) }
    fun retry(song: SongRef, positionMs: Long) = open(song, positionMs)
    fun close(): Long? { _phase.value = Phase.Idle; return null }
    fun preparePlaylist(songs: List<SongRef>) {}
    fun readyAtMs(ms: Long): Boolean = false
    fun downloadModel(song: SongRef, positionMs: Long) = open(song, positionMs)
    fun positionSamples(): Int = 0
    fun positionMs(): Long = 0
    fun play(ms: Long): Boolean = false
    fun pause() {}
    fun resume() {}
    fun toggle() {}
    fun seekTo(ms: Long): Boolean = false
    fun stopPlayback() {}
    fun currentTitle(): String? = null
    fun cacheBytes(): Long = 0
    fun clearCache() {}
}
