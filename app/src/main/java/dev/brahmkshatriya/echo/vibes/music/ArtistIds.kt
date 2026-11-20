package dev.brahmkshatriya.echo.vibes.music

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.backend.Supa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Who made a song: a Deezer artist (by its id), or the Vibes user who published it. */
@Serializable
data class ArtistRef(
    val id: Long = 0,
    val name: String,
    val picture: String? = null,
    /** Songs published on Vibes: the profile of the user who uploaded it. */
    val userId: String? = null,
) {
    /** Same artist = same key, whatever the name says (two artists called the same stay apart). */
    val key: String get() = when {
        userId != null -> "u:$userId"
        id > 0 -> "dz:$id"
        else -> "n:" + name.lowercase()
    }
}

/**
 * The artists of a song by ID, never by name. Many artists share a name (and Vibes users may use the
 * name of a real artist), so searching "the artist called X" picked the wrong one in the stats and on
 * the artist links. Here:
 *  - a song published on Vibes → the user who uploaded it;
 *  - a Deezer song → its contributors from Deezer by the track id (main artist first, then the feats);
 *  - anything else → first matched to its Deezer track, then the same.
 * Answers are kept on the device and in a table on the server shared by everyone, so each track is
 * asked to Deezer once for all users.
 */
object ArtistIds {
    private val cache = ConcurrentHashMap<String, List<ArtistRef>>()
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val serializer = MapSerializer(String.serializer(), ListSerializer(ArtistRef.serializer()))

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "artist_ids.json")
        file = f
        runCatching { cache.putAll(Supa.json.decodeFromString(serializer, f.readText())) }
    }

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            runCatching {
                val f = file ?: return@launch
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(Supa.json.encodeToString(serializer, HashMap(cache)))
                tmp.renameTo(f)
            }
        }
    }

    /** Already known (no network). */
    fun cached(song: SongRef): List<ArtistRef>? = cache[song.key]

    /** Names as written on the song (fallback when nothing better is known). */
    fun names(song: SongRef) = song.artist.split(", ").map { it.trim() }.filter { it.isNotBlank() }

    suspend fun of(song: SongRef): List<ArtistRef> {
        cache[song.key]?.let { return it }
        val found = runCatching { resolve(song) }.getOrNull().orEmpty()
        if (found.isEmpty()) return names(song).map { ArtistRef(name = it) }
        cache[song.key] = found
        save()
        return found
    }

    /** The artist at [index] of the song's "A, B, C" artist line (matched by name when possible). */
    suspend fun at(song: SongRef, index: Int): ArtistRef? {
        val all = of(song)
        val name = names(song).getOrNull(index)
        return all.firstOrNull { name != null && it.name.equals(name, true) } ?: all.getOrNull(index) ?: all.firstOrNull()
    }

    private suspend fun resolve(song: SongRef): List<ArtistRef> {
        val graph = dev.brahmkshatriya.echo.vibes.VibesGraph
        if (VibesUploads.isUpload(song.id)) {
            val up = graph.social.uploadById(song.id.removePrefix(VibesUploads.PREFIX)) ?: return emptyList()
            val p = runCatching { graph.social.profile(up.userId) }.getOrNull()
            return listOf(ArtistRef(name = up.artist.ifBlank { p?.displayName?.ifBlank { null } ?: p?.username ?: "" },
                picture = p?.avatarUrl, userId = up.userId))
        }
        val trackId = song.id.takeIf { song.ext == MusicEngine.DEEZER && it.isNotEmpty() && it.all(Char::isDigit) }
            ?: graph.deezer.match(song.title, song.artist)?.id?.toString()
            ?: return emptyList()
        // shared cache on the server
        runCatching {
            val row = graph.supa.select("vibes_track_artists", "track_id=eq.$trackId&select=artists").firstOrNull() as? JsonObject
            row?.get("artists")?.let { Supa.json.decodeFromJsonElement(ListSerializer(ArtistRef.serializer()), it) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val list = graph.deezer.trackArtists(trackId.toLong()).map { ArtistRef(it.id, it.name, it.picture) }
        if (list.isNotEmpty()) runCatching {
            graph.supa.insertIgnore("vibes_track_artists", buildJsonObject {
                put("track_id", trackId)
                put("artists", Supa.json.encodeToJsonElement(ListSerializer(ArtistRef.serializer()), list))
            })
        }
        return list
    }
}
