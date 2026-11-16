package dev.brahmkshatriya.echo.vibes.backend

import dev.brahmkshatriya.echo.vibes.music.Listen
import dev.brahmkshatriya.echo.vibes.music.ListenLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * Listening time follows the account: every listen heard on the phone or on the PC goes up to the
 * account, and each device brings down the others'. So minutes, top songs, top artists and records
 * are the total of all devices, not of the one in your hand.
 *
 * Rows are keyed by (user, start time, song) so the same listen is never counted twice.
 */
class ListenSync(private val supa: Supa, private val log: ListenLog, private val dir: File) {
    private val json = Supa.json
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    private data class State(val user: String = "", val pushedAt: Long = 0, val pulledId: Long = 0)

    private val file get() = File(dir, "vibes_listen_sync.json")
    private fun load() = runCatching { json.decodeFromString(State.serializer(), file.readText()) }.getOrDefault(State())
    private fun store(s: State) = runCatching { file.writeText(json.encodeToString(State.serializer(), s)) }

    init {
        log.onCleared = {
            scope.launch {
                runCatching {
                    val me = supa.userId ?: return@launch
                    supa.delete("vibes_listens", "user_id=eq.$me")
                    // after a wipe the rows from before must not come back: skip past them
                    store(State(me, System.currentTimeMillis(), lastRemoteId(me)))
                }
            }
        }
    }

    private suspend fun lastRemoteId(me: String): Long =
        (supa.select("vibes_listens", "user_id=eq.$me&select=id&order=id.desc&limit=1").firstOrNull() as? JsonObject)
            ?.get("id")?.jsonPrimitive?.long ?: 0L

    suspend fun sync() = mutex.withLock {
        val me = supa.userId ?: return@withLock
        var st = load().let { if (it.user != me) State(me) else it }

        // up: what this device heard since the last upload (a little overlap: duplicates are ignored)
        val mine = log.all().filter { it.at > st.pushedAt - 3_600_000 }
        mine.chunked(400).forEach { part ->
            supa.insertIgnore("vibes_listens", JsonArray(part.map { l ->
                buildJsonObject {
                    put("user_id", me); put("at", l.at); put("song_key", l.song.key); put("ms", l.ms)
                    put("song", json.encodeToJsonElement(l.song.copy(data = null)))
                    l.ctx?.let { put("ctx", it) }; l.ctxId?.let { put("ctx_id", it) }; l.ctxCover?.let { put("ctx_cover", it) }
                }
            }))
        }
        if (mine.isNotEmpty()) st = st.copy(pushedAt = maxOf(st.pushedAt, mine.maxOf { it.at }))
        store(st)

        // down: what the other devices heard
        while (true) {
            val rows = supa.select("vibes_listens",
                "user_id=eq.$me&id=gt.${st.pulledId}&select=id,at,ms,song,ctx,ctx_id,ctx_cover&order=id.asc&limit=1000")
            if (rows.isEmpty()) break
            val list = rows.mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                runCatching {
                    Listen(
                        song = json.decodeFromJsonElement<SongRef>(o["song"]!!),
                        at = o["at"]!!.jsonPrimitive.long,
                        ms = o["ms"]!!.jsonPrimitive.long,
                        ctx = o.str("ctx"), ctxId = o.str("ctx_id"), ctxCover = o.str("ctx_cover"),
                    )
                }.getOrNull()
            }
            log.merge(list)
            st = st.copy(pulledId = rows.maxOf { (it as JsonObject)["id"]!!.jsonPrimitive.long })
            store(st)
            if (rows.size < 1000) break
        }
    }
}
