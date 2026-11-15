package dev.brahmkshatriya.echo.vibes.backend

import dev.brahmkshatriya.echo.vibes.music.LibrarySyncDoc
import dev.brahmkshatriya.echo.vibes.music.VibesLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * The library follows the account: playlists made in Vibes, liked songs, saved albums, followed
 * artists and folders are the same on the phone and on the PC.
 *
 * One row per user on the server holds the library plus, for every item, when it was last changed
 * and when it was removed. Each device merges its own library with the server's item by item (the
 * newest change wins, a removal newer than the item removes it everywhere) and uploads the result.
 * So editing on two devices at once never loses anything, and nothing comes back after a delete.
 */
class LibrarySync(private val supa: Supa, private val library: VibesLibrary) {
    private val json = Supa.json
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    init {
        // a change made here goes up shortly after (several quick edits = one upload)
        library.onSyncedChange = {
            pending?.cancel()
            pending = scope.launch { delay(1500); runCatching { sync() } }
        }
    }

    suspend fun sync() = mutex.withLock {
        val me = supa.userId ?: return@withLock
        val row = supa.select("vibes_library_sync", "user_id=eq.$me&select=doc").firstOrNull() as? JsonObject
        val remote = row?.get("doc")?.let { runCatching { json.decodeFromJsonElement<LibrarySyncDoc>(it) }.getOrNull() }
        val local = library.syncDoc()
        val merged = if (remote == null) local else merge(local, remote)
        if (merged != local) library.applySync(merged)
        if (merged != remote) supa.upsert("vibes_library_sync", buildJsonObject {
            put("user_id", me)
            put("doc", json.encodeToJsonElement(merged))
            put("updated_at", java.time.Instant.now().toString())
        })
    }

    companion object {
        fun merge(a: LibrarySyncDoc, b: LibrarySyncDoc): LibrarySyncDoc {
            val stamps = (a.stamps.keys + b.stamps.keys).associateWith { maxOf(a.stamps[it] ?: 0, b.stamps[it] ?: 0) }
            val removed = (a.removed.keys + b.removed.keys).associateWith { maxOf(a.removed[it] ?: 0, b.removed[it] ?: 0) }
            fun <T> pick(prefix: String, la: List<T>, lb: List<T>, key: (T) -> String): List<T> {
                val ma = la.associateBy(key); val mb = lb.associateBy(key)
                val order = la.map(key) + lb.map(key).filter { it !in ma }
                return order.mapNotNull { k ->
                    val sa = a.stamps["$prefix$k"] ?: 0; val sb = b.stamps["$prefix$k"] ?: 0
                    val (item, at) = when {
                        ma[k] == null -> mb[k] to sb
                        mb[k] == null -> ma[k] to sa
                        sb > sa -> mb[k] to sb
                        else -> ma[k] to sa
                    }
                    val gone = removed["$prefix$k"]
                    if (gone != null && gone >= at) null else item
                }
            }
            return LibrarySyncDoc(
                playlists = pick("pl:", a.playlists, b.playlists) { it.id },
                liked = pick("like:", a.liked, b.liked) { it.key }.sortedByDescending { stamps["like:${it.key}"] ?: 0 },
                albums = pick("al:", a.albums, b.albums) { it.id.toString() },
                folders = pick("fo:", a.folders, b.folders) { it.id },
                artists = pick("ar:", a.artists, b.artists) { it.id.toString() },
                stamps = stamps,
                // removals are kept for 90 days: enough for every device to see them
                removed = removed.filterValues { it > System.currentTimeMillis() - 90L * 86_400_000 },
            )
        }
    }
}
