package dev.brahmkshatriya.echo.vibes.desktop

import java.awt.SystemTray
import java.awt.TrayIcon

/** Windows notifications through the system tray icon (they also land in the Action Center). */
object DesktopTray {
    private var icon: TrayIcon? = null
    /** opens a vibes:// link inside the app (set by Main) */
    var onOpen: (String) -> Unit = {}
    /** brings the window to the front (set by Main) */
    var onShow: () -> Unit = {}
    @Volatile private var lastLink: String? = null

    fun install(image: java.awt.Image) {
        if (!SystemTray.isSupported() || icon != null) return
        runCatching {
            val menu = java.awt.PopupMenu().apply {
                add(java.awt.MenuItem("Apri Vibes").apply { addActionListener { onShow() } })
                add(java.awt.MenuItem("Esci").apply { addActionListener { kotlin.system.exitProcess(0) } })
            }
            icon = TrayIcon(image, "Vibes", menu).apply {
                isImageAutoSize = true
                addActionListener { onShow(); lastLink?.let { l -> lastLink = null; onOpen(l) } }
            }
            SystemTray.getSystemTray().add(icon)
        }
    }

    fun notify(title: String, text: String, link: String? = null) {
        lastLink = link
        icon?.displayMessage(title, text, TrayIcon.MessageType.NONE)
    }
}
