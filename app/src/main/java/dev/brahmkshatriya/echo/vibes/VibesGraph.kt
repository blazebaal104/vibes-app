package dev.brahmkshatriya.echo.vibes

import android.content.Context
import kotlinx.coroutines.launch
import dev.brahmkshatriya.echo.vibes.backend.Social
import dev.brahmkshatriya.echo.vibes.backend.Supa
import dev.brahmkshatriya.echo.vibes.music.DeezerPublic
import dev.brahmkshatriya.echo.vibes.music.VibesLibrary

/** Process-wide Vibes singletons (backend, social, library). */
object VibesGraph {
    @Volatile
    private var ctx: Context? = null

    fun init(context: Context) {
        dev.brahmkshatriya.echo.vibes.music.VibesOffline.init(context)
        dev.brahmkshatriya.echo.vibes.karaoke.KaraokeHD.init(context)
        dev.brahmkshatriya.echo.vibes.music.VibesUploads.init(context)
        dev.brahmkshatriya.echo.vibes.music.ArtistIds.init(context)
        if (ctx == null) ctx = context.applicationContext
        dev.brahmkshatriya.echo.vibes.diag.ErrorReporter.install(context.applicationContext)
    }

    private val app get() = ctx ?: error("VibesGraph.init not called")
    /** The app's private files folder (small state files). */
    val filesDir: java.io.File get() = app.filesDir

    val supa by lazy { Supa(app) }
    val social by lazy { Social(app, supa) }
    val lobbies by lazy { dev.brahmkshatriya.echo.vibes.listen.ListenLobbies(supa, app) }
    val library by lazy { VibesLibrary(app) }
    val collab by lazy { dev.brahmkshatriya.echo.vibes.backend.CollabPlaylists(supa, library) }
    val deezer by lazy { DeezerPublic(supa.http) }
    /** Playlists, likes, albums... the same on every device of the account. */
    /** "Segnala un problema": reports and ideas, answered by the admins. */
    val feedback by lazy { dev.brahmkshatriya.echo.vibes.backend.FeedbackApi(supa) }
    val librarySync by lazy { dev.brahmkshatriya.echo.vibes.backend.LibrarySync(supa, library) }
    val recommender by lazy { dev.brahmkshatriya.echo.vibes.music.Recommender(deezer, library) }
    val matcher by lazy { dev.brahmkshatriya.echo.vibes.music.SongMatcher(deezer, supa) }
    val importer by lazy { dev.brahmkshatriya.echo.vibes.music.Importer(deezer, { library }, supa.http, matcher) }
    val picker by lazy { dev.brahmkshatriya.echo.vibes.games.TrackPicker(deezer, library) }
    val prefs by lazy { VibesPrefs(app) }
    val studio by lazy { dev.brahmkshatriya.echo.vibes.music.StudioPlayer(app) }
    val webSamples by lazy { dev.brahmkshatriya.echo.vibes.music.WebSamples(supa.http).also { it.init(app) } }
    val search by lazy { dev.brahmkshatriya.echo.vibes.music.VibesSearch(deezer) }
    val listens by lazy { dev.brahmkshatriya.echo.vibes.music.ListenLog(app, deezer) }
    /** Listening history of the account: phone + PC add up. */
    val listenSync by lazy { dev.brahmkshatriya.echo.vibes.backend.ListenSync(supa, listens, app.filesDir) }
    /** Settings + Studio favourite samples: the same on phone and PC. */
    val userState by lazy { dev.brahmkshatriya.echo.vibes.backend.UserStateSync(supa, prefs, app) }

    /** App opened / back in front: pull what the other device changed, right away. */
    fun syncNow() {
        scope.launch {
            runCatching { userState.sync() }
            runCatching { librarySync.sync() }
            runCatching { listenSync.sync() }
        }
    }

    /** App going to the background or closing: send everything still waiting. */
    fun flushSync() = syncNow()

    /** The app is closing (PC): send what is waiting, at most ~3 seconds. */
    fun flushBlocking() {
        runCatching {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    runCatching { userState.sync() }
                    runCatching { librarySync.sync() }
                    runCatching { listenSync.sync() }
                }
            }
        }
    }

    val updater by lazy { dev.brahmkshatriya.echo.vibes.update.Updater(app, supa.http) }

    /** Set when a Vibes playlist starts playing (stats: most played playlists). */
    @Volatile
    var playContext: dev.brahmkshatriya.echo.vibes.music.PlayContext? = null

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var lastPublish = 0L

    /** After each listen: refresh what friends see (this week + records), at most every 2 minutes. */
    fun onListened(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 120_000) return
        lastPublish = now
        scope.launch {
            runCatching {
                val me = social.me.value ?: return@launch
                val week = if (me.privacy.weekStats) listens.summary(listens.thisWeek) else null
                val rec = if (me.privacy.records) listens.records() else null
                social.publishStats(week, rec)
            }
        }
    }
}
