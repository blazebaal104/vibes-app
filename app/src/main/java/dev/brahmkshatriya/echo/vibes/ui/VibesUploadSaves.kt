package dev.brahmkshatriya.echo.vibes.ui

import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.Upload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Songs published on Vibes that you saved (the bookmark on every Vibes song). They show up in your
 * Library, and the artist sees how many people saved each song in Studio > Statistiche.
 */
object VibesUploadSaves {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ids = MutableStateFlow<List<String>>(emptyList())
    val ids = _ids.asStateFlow()
    @Volatile private var loadedFor: String? = null

    fun refresh() {
        val me = VibesGraph.social.uid ?: return
        loadedFor = me
        scope.launch { runCatching { VibesGraph.social.mySaves() }.onSuccess { _ids.value = it } }
    }

    fun ensure() { if (loadedFor != VibesGraph.social.uid) refresh() }

    fun toggle(u: Upload, on: Boolean) {
        _ids.value = if (on) listOf(u.id) + (_ids.value - u.id) else _ids.value - u.id
        scope.launch { runCatching { VibesGraph.social.save(u.id, on) }.onFailure { refresh() } }
    }
}
