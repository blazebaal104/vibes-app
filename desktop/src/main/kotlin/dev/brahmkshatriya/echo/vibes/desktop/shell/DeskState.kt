package dev.brahmkshatriya.echo.vibes.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.brahmkshatriya.echo.vibes.ui.Route

/** What the right-hand panel shows. */
enum class RightPanel { None, NowPlaying, Queue, Friends }

/** What takes the centre instead of the page (Spotify: lyrics, full-screen view). */
enum class CenterOverlay { None, Lyrics }

/**
 * Window layout state of the PC app: panel sizes (remembered between runs), which side panels are
 * open, the colour the centre background is following, forward history.
 */
class DeskState {
    private val prefs = java.util.prefs.Preferences.userRoot().node("vibes/desktop")

    var libraryWidth by mutableStateOf(prefs.getFloat("library_w", 300f))
    var rightWidth by mutableStateOf(prefs.getFloat("right_w", 340f))
    /** The right panel is always there (lyrics, details, queue): it can only be reduced to a strip. */
    var right by mutableStateOf(runCatching { RightPanel.valueOf(prefs.get("right", "NowPlaying")) }.getOrDefault(RightPanel.NowPlaying)
        .let { if (it == RightPanel.None) RightPanel.NowPlaying else it })
    var rightCollapsed by mutableStateOf(prefs.getBoolean("right_collapsed", false))
    var overlay by mutableStateOf(CenterOverlay.None)
    var fullPlayer by mutableStateOf(false)
    var karaoke by mutableStateOf(false)
    var miniPlayer by mutableStateOf(false)
    /** Library list: 0 list, 1 compact, 2 grid. */
    var libraryView by mutableStateOf(prefs.getInt("library_view", 0))
    var librarySort by mutableStateOf(prefs.getInt("library_sort", 0))

    /** Colour of what the mouse is on (a cover): the centre background follows it. */
    var hoverColor by mutableStateOf<Color?>(null)
    /** Colour of the page that is open (album / playlist cover). */
    var pageColor by mutableStateOf<Color?>(null)

    /** Set by Main: toggles the window between normal and full screen. */
    var toggleWindowFullscreen: () -> Unit = {}
    var isWindowFullscreen: () -> Boolean = { false }

    val forward = mutableStateListOf<Route>()

    fun save() {
        prefs.putFloat("library_w", libraryWidth); prefs.putFloat("right_w", rightWidth)
        prefs.put("right", right.name); prefs.putInt("library_view", libraryView); prefs.putInt("library_sort", librarySort)
        prefs.putBoolean("right_collapsed", rightCollapsed)
    }

    /** Same tab again = reduce to the strip; another tab (or from the strip) = open it. Never closed. */
    fun toggleRight(p: RightPanel) {
        if (p == RightPanel.None) { rightCollapsed = true; save(); return }
        if (right == p && !rightCollapsed) rightCollapsed = true else { right = p; rightCollapsed = false }
        save()
    }

    fun collapseRight() { rightCollapsed = true; save() }
    fun expandRight() { rightCollapsed = false; if (rightWidth < 280f) rightWidth = 340f; save() }
}

val LocalDesk = staticCompositionLocalOf<DeskState> { error("no desk") }
