package dev.brahmkshatriya.echo.vibes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.utils.PermsUtils.checkAppPermissions
import dev.brahmkshatriya.echo.utils.PermsUtils.setupPermissionRequests
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import dev.brahmkshatriya.echo.vibes.notify.VibesNotifier
import dev.brahmkshatriya.echo.vibes.ui.VibesApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Vibes' only activity. The whole UI is Compose (dev.brahmkshatriya.echo.vibes.ui); from Gladix
 * we keep the engine only: extension loader (Deezer), PlayerViewModel and the playback service.
 */
class VibesActivity : ComponentActivity() {

    private val playerViewModel by viewModel<PlayerViewModel>()
    private val extensionLoader by inject<ExtensionLoader>()
    private val app by inject<App>()

    /** Deep links (vibes://...) and notification taps, consumed by the Compose UI. */
    val pendingLink = MutableStateFlow<android.net.Uri?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VibesGraph.init(this)
        enableEdgeToEdge(
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        checkAppPermissions { extensionLoader.setPermGranted() }
        setupPermissionRequests(app)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val engine = MusicEngine(extensionLoader, playerViewModel)
        VibesNotifier.start(applicationContext)
        handleIntent(intent)

        // Every track that starts playing: play counts (games), recents, and "now playing" for friends.
        lifecycleScope.launch {
            playerViewModel.playerState.current.filterNotNull()
                .distinctUntilChangedBy { it.mediaItem.mediaId }
                .collect { cur ->
                    val track = cur.track
                    val ext = cur.mediaItem.extensionId
                    val song = SongRef.from(ext, track)
                    VibesGraph.library.recordPlay(song)
                    VibesGraph.social.setNowPlaying(song)
                }
        }

        setContent { VibesApp(engine, pendingLink) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "vibes") pendingLink.value = data
    }
}
