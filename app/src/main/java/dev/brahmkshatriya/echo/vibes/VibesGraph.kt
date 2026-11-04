package dev.brahmkshatriya.echo.vibes

import android.content.Context
import dev.brahmkshatriya.echo.vibes.backend.Social
import dev.brahmkshatriya.echo.vibes.backend.Supa
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.VibesLibrary

/** Process-wide Vibes singletons (backend, social, library). */
object VibesGraph {
    @Volatile
    private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx == null) ctx = context.applicationContext
    }

    private val app get() = ctx ?: error("VibesGraph.init not called")

    val supa by lazy { Supa(app) }
    val social by lazy { Social(app, supa) }
    val library by lazy { VibesLibrary(app) }
    val deezer by lazy { DeezerPublic(supa.http) }
    val recommender by lazy { dev.brahmkshatriya.echo.vibes.music.Recommender(deezer, library) }
    val importer by lazy { dev.brahmkshatriya.echo.vibes.music.Importer(deezer, library) }
    val picker by lazy { dev.brahmkshatriya.echo.vibes.games.TrackPicker(deezer, library) }
}
