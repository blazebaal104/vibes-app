package dev.brahmkshatriya.echo.vibes.music

import android.content.Context
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Offline songs. A download is the real (already decrypted) stream of the track, saved in the
 * app's private storage. The player picks the file up before touching the network
 * (StreamableLoader.loadServer / loadTrack), so downloaded songs play with no connection and no data.
 */
object VibesOffline {
    @Serializable
    data class Entry(val song: SongRef, val file: String, val size: Long, val at: Long = System.currentTimeMillis())

    sealed interface State {
        data object Queued : State
        data class Downloading(val progress: Float) : State
        data class Failed(val message: String) : State
    }

    private lateinit var appContext: Context
    /** Set by VibesActivity once the engine exists (downloads need the Deezer extension). */
    @Volatile var engine: MusicEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<SongRef>(Channel.UNLIMITED)
    private var worker: Job? = null
    private val http by lazy { OkHttpClient() }

    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    /** Downloaded songs by SongRef.key ("deezer:123"). */
    val entries: StateFlow<Map<String, Entry>> = _entries
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    /** Songs waiting / downloading / failed. */
    val states: StateFlow<Map<String, State>> = _states

    private val dir get() = File(appContext.filesDir, "offline").apply { mkdirs() }
    private val indexFile get() = File(dir, "index.json")

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _entries.value = runCatching {
            Supa.json.decodeFromString<List<Entry>>(indexFile.readText())
        }.getOrDefault(emptyList()).filter { File(it.file).exists() }.associateBy { it.song.key }
    }

    private fun save() = runCatching {
        indexFile.writeText(Supa.json.encodeToString(_entries.value.values.toList()))
    }

    /** Local file of a downloaded song, used by the player. Safe to call from any thread. */
    fun fileFor(context: Context, extensionId: String, trackId: String): File? {
        init(context)
        return _entries.value["$extensionId:$trackId"]?.file?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
    }

    fun isDownloaded(song: SongRef) = _entries.value.containsKey(song.key)

    fun totalBytes() = _entries.value.values.sumOf { it.size }

    fun download(songs: List<SongRef>) {
        songs.filter { !isDownloaded(it) && _states.value[it.key] !is State.Downloading }.forEach { s ->
            _states.update { it + (s.key to State.Queued) }
            queue.trySend(s)
        }
        if (worker?.isActive != true) worker = scope.launch {
            for (s in queue) {
                if (isDownloaded(s)) { _states.update { it - s.key }; continue }
                runCatching { fetch(s) }
                    .onSuccess { _states.update { it - s.key } }
                    .onFailure { e -> _states.update { it + (s.key to State.Failed(e.message ?: "Errore")) } }
            }
        }
    }

    fun remove(songs: List<SongRef>) {
        val keys = songs.map { it.key }.toSet()
        _entries.value.filterKeys { it in keys }.values.forEach { File(it.file).delete() }
        _entries.update { it - keys }
        _states.update { it - keys }
        save()
    }

    fun removeAll() {
        dir.listFiles()?.forEach { if (it.name != "index.json") it.delete() }
        _entries.value = emptyMap(); save()
    }

    private suspend fun fetch(song: SongRef) {
        _states.update { it + (song.key to State.Downloading(0f)) }
        val tmp = File(dir, "${song.ext}_${song.id}.part")
        val out = File(dir, "${song.ext}_${song.id}.audio")
        val (full, total) = fetchAudio(song, tmp) { p -> _states.update { it + (song.key to State.Downloading(p)) } }
        tmp.renameTo(out)
        // keep the full track (with its Deezer extras) so the player can show it without network
        val stored = song.copy(data = runCatching { full.toJson() }.getOrNull() ?: song.data)
        _entries.update { it + (song.key to Entry(stored, out.absolutePath, total)) }
        save()
    }

    /**
     * Writes the real (decrypted) audio of [song] to [out]: the downloaded copy if there is one,
     * otherwise straight from the extension's stream. Also used by karaoke HD to get the audio
     * to separate. Returns the loaded track and the byte count.
     */
    suspend fun fetchAudio(song: SongRef, out: File, onProgress: (Float) -> Unit = {}): Pair<Track, Long> {
        val eng = this.engine ?: throw IllegalStateException("Apri Vibes per scaricare")
        _entries.value[song.key]?.file?.let(::File)?.takeIf { it.exists() }?.let { local ->
            local.copyTo(out, overwrite = true)
            return song.toTrack() to local.length()
        }
        val ext = eng.extension()
        val (full: Track, media: Streamable.Media.Server) = withTimeout(30_000) {
            val full = ext.getAs<TrackClient, Track> { loadTrack(song.toTrack(), false) }.getOrThrow()
            val server = full.servers.firstOrNull() ?: throw IllegalStateException("Brano non disponibile")
            full to (ext.getAs<TrackClient, Streamable.Media> { loadStreamableMedia(server, false) }.getOrThrow()
                    as? Streamable.Media.Server ?: throw IllegalStateException("Brano non disponibile"))
        }
        val expected = (full.duration ?: song.duration ?: 0L).let { ms -> if (ms > 0) ms / 1000 * 40_000 else 0L } // ~320 kbps
        val input = when (val src = media.sources.firstOrNull()) {
            is Streamable.Source.Raw -> src.streamProvider!!.provide(0, -1).first
            is Streamable.Source.Http -> {
                if (src.decryption != null) throw IllegalStateException("Formato non scaricabile")
                val res = http.newCall(Request.Builder().url(src.request.url).apply {
                    src.request.headers.forEach { (k, v) -> header(k, v) }
                }.build()).execute()
                if (!res.isSuccessful) { res.close(); throw IllegalStateException("Download fallito (${res.code})") }
                res.body.byteStream()
            }
            else -> throw IllegalStateException("Formato non scaricabile")
        }
        var total = 0L
        input.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); total += n
                    if (expected > 0) onProgress((total.toFloat() / expected).coerceIn(0f, 0.99f))
                }
            }
        }
        if (total < 50_000) { out.delete(); throw IllegalStateException("Download incompleto") }
        return full to total
    }
}
