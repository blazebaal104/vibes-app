package dev.brahmkshatriya.echo.vibes.notify

import android.content.Context
import dev.brahmkshatriya.echo.vibes.VibesGraph
import kotlinx.coroutines.delay

/**
 * "X ha iniziato a seguirti": checks every minute (while Vibes runs) for new followers and hands
 * each one to [show] with their name. The first check only sets the starting point, so installing
 * or updating never floods you with old follows.
 */
object FollowWatch {
    suspend fun run(context: Context, show: (followerId: String, name: String) -> Unit) {
        val sp = context.getSharedPreferences("vibes_follow_watch", Context.MODE_PRIVATE)
        while (true) {
            runCatching {
                val me = VibesGraph.social.uid
                if (me != null) {
                    val key = "since_$me"
                    val since = sp.getString(key, null)
                    if (since == null) sp.edit().putString(key, java.time.Instant.now().toString()).apply()
                    else {
                        val list = VibesGraph.social.newFollowers(since)
                        list.lastOrNull()?.let { sp.edit().putString(key, it.second).apply() }
                        val prefs = VibesGraph.prefs.value
                        if (prefs.notifyEnabled && prefs.notifyFollowers) list.forEach { (id, _) ->
                            val name = runCatching { VibesGraph.social.profile(id)?.name }.getOrNull() ?: "Qualcuno"
                            show(id, name)
                        }
                    }
                }
            }
            delay(60_000)
        }
    }
}
