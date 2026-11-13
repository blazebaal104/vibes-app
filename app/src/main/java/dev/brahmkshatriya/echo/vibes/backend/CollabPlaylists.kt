package dev.brahmkshatriya.echo.vibes.backend

import dev.brahmkshatriya.echo.vibes.music.VibesLibrary
import dev.brahmkshatriya.echo.vibes.music.VibesPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

/** A playlist shared with friends: one copy on the server, every member edits it. */
@Serializable
data class CollabPlaylist(
    val id: String,
    val name: String = "Playlist",
    val emoji: String = "🎵",
    val color: String = "#B84DFF",
    val cover: String? = null,
    val songs: List<SongRef> = emptyList(),
    val version: Long = 0,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("updated_by") val updatedBy: String? = null,
)

/**
 * Shared playlists. A playlist shared with a friend becomes a collaborative one: it lives on the
 * server (vibes_collab_playlists) and each member keeps a local copy in their library. Any change a
 * member makes (songs added/removed/moved, name, emoji, colour, cover) is sent as a small operation;
 * the server applies it atomically and Realtime pushes the new version to everyone's library.
 */
class CollabPlaylists(private val supa: Supa, private val library: VibesLibrary) {
    private val json = Supa.json
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    init {
        library.onCollabOp = { id, op -> scope.launch { runCatching { apply(id, op) } } }
        scope.launch {
            supa.realtime.changes.collect { c ->
                if (c.table != TABLE) return@collect
                runCatching {
                    if (c.type == "DELETE") return@runCatching
                    c.record?.let { library.applyRemote(json.decodeFromJsonElement<CollabPlaylist>(it)) }
                }
            }
        }
    }

    private fun decode(e: kotlinx.serialization.json.JsonElement?): CollabPlaylist? =
        (e as? JsonObject)?.let { runCatching { json.decodeFromJsonElement<CollabPlaylist>(it) }.getOrNull() }
            ?: (e as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { decode(it) }

    private fun songsJson(songs: List<SongRef>) =
        json.encodeToJsonElement(ListSerializer(SongRef.serializer()), songs.map { it.copy(data = null) })

    /** The server copy of [p], creating it the first time the playlist is shared. Returns its id. */
    suspend fun ensure(p: VibesPlaylist): String = mutex.withLock {
        library.playlist(p.id)?.collabId?.let { return@withLock it }
        val id = UUID.randomUUID().toString()
        val r = decode(supa.rpc("vibes_collab_create", buildJsonObject {
            put("p_id", id); put("p_name", p.name); put("p_emoji", p.emoji); put("p_color", p.color)
            put("p_cover", p.cover?.takeIf { it.startsWith("http") })
            put("p_songs", songsJson(p.songs))
        })) ?: error("Condivisione non riuscita")
        library.linkCollab(p.id, r)
        r.id
    }

    suspend fun invite(collabId: String, friendId: String) {
        supa.rpc("vibes_collab_invite", buildJsonObject { put("p", collabId); put("to_user", friendId) })
    }

    /** What a friend sent (readable once you're invited, before saving it). */
    suspend fun get(id: String): CollabPlaylist? =
        supa.select(TABLE, "id=eq.$id&select=*").firstOrNull()?.let { decode(it) }

    /**
     * Save a playlist a friend shared: you become a member and it lands in your library. If you
     * already have it (the shared one, or the same playlist with the same name and songs), that one
     * is replaced by the shared version instead of adding a copy.
     */
    suspend fun join(id: String, from: String?): VibesPlaylist {
        val r = decode(supa.rpc("vibes_collab_join", buildJsonObject { put("p", id) })) ?: error("Playlist non trovata")
        return library.adoptCollab(r, from)
    }

    /** Every shared playlist I'm in, brought up to date (app start, back online…). */
    suspend fun refresh() {
        val me = supa.userId ?: return
        val rows = supa.select(TABLE, "select=*,vibes_collab_members!inner(user_id)&vibes_collab_members.user_id=eq.$me")
        rows.mapNotNull { (it as? JsonObject)?.let { o -> decode(JsonObject(o - "vibes_collab_members")) } }
            .forEach { library.applyRemote(it) }
    }

    suspend fun leave(collabId: String) {
        supa.rpc("vibes_collab_leave", buildJsonObject { put("p", collabId) })
    }

    private suspend fun apply(collabId: String, op: VibesLibrary.CollabOp) {
        val body = when (op) {
            is VibesLibrary.CollabOp.Add -> buildJsonObject { put("t", "add"); put("song", json.encodeToJsonElement(SongRef.serializer(), op.song.copy(data = null))) }
            is VibesLibrary.CollabOp.Remove -> buildJsonObject { put("t", "remove"); put("key", op.key) }
            is VibesLibrary.CollabOp.Songs -> buildJsonObject { put("t", "songs"); put("songs", songsJson(op.songs)) }
            is VibesLibrary.CollabOp.Meta -> buildJsonObject {
                put("t", "meta"); op.name?.let { put("name", it) }; op.emoji?.let { put("emoji", it) }; op.color?.let { put("color", it) }
                if (op.setCover) put("cover", op.cover?.takeIf { it.startsWith("http") })
            }
            VibesLibrary.CollabOp.Leave -> { leave(collabId); return }
        }
        decode(supa.rpc("vibes_collab_apply", buildJsonObject { put("p", collabId); put("op", body) }))?.let { library.applyRemote(it) }
    }

    companion object { const val TABLE = "vibes_collab_playlists" }
}
