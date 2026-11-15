package dev.brahmkshatriya.echo.vibes.desktop.shell

import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.Library
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.vibes.VibesGraph
import dev.brahmkshatriya.echo.vibes.backend.SongRef
import dev.brahmkshatriya.echo.vibes.music.MusicEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.awt.BasicStroke
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * The buttons in the taskbar preview of Vibes (hover the icon in the Windows taskbar): previous,
 * play/pause, next and "save to liked songs", like Spotify. Windows' ITaskbarList3 through JNA; the
 * clicks come back to the window as WM_COMMAND, read by a small window-procedure hook.
 */
object TaskbarButtons {
    private const val PREV = 1; private const val PLAY = 2; private const val NEXT = 3; private const val LIKE = 4
    private const val WM_COMMAND = 0x0111
    private const val THBN_CLICKED = 0x1800
    private const val THB_ICON = 0x2; private const val THB_TOOLTIP = 0x4; private const val THB_FLAGS = 0x8
    private const val THBF_ENABLED = 0x0; private const val THBF_DISABLED = 0x1

    // ITaskbarList3 vtable slots
    private const val HR_INIT = 3; private const val ADD_BUTTONS = 15; private const val UPDATE_BUTTONS = 16

    /** COM objects are used from the thread that made them: one thread for all calls. */
    private val com = Executors.newSingleThreadExecutor { r -> Thread(r, "vibes-taskbar").apply { isDaemon = true } }
    private var taskbar: Pointer? = null
    private var hwnd: WinDef.HWND? = null
    private var oldProc: Pointer? = null
    private var proc: WinUser.WindowProc? = null // kept: the GC must never collect the hook
    private val icons = mutableMapOf<String, WinDef.HICON>()
    private var added = false

    @Suppress("unused")
    class ThumbButton : Structure() {
        @JvmField var dwMask = 0
        @JvmField var iId = 0
        @JvmField var iBitmap = 0
        @JvmField var hIcon: WinDef.HICON? = null
        @JvmField var szTip = CharArray(260)
        @JvmField var dwFlags = 0
        override fun getFieldOrder() = listOf("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
        fun set(id: Int, icon: WinDef.HICON?, tip: String, enabled: Boolean) {
            dwMask = THB_ICON or THB_TOOLTIP or THB_FLAGS; iId = id; hIcon = icon
            szTip = CharArray(260).also { tip.toCharArray().copyInto(it, endIndex = minOf(tip.length, 259)) }
            dwFlags = if (enabled) THBF_ENABLED else THBF_DISABLED
        }
    }

    fun install(window: java.awt.Window, engine: MusicEngine) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        hwnd = WinDef.HWND(Native.getWindowPointer(window))
        runCatching { darkTitleBar(hwnd!!) }
        hook(engine)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // add the buttons once the taskbar button exists, then keep them in step with the player
        scope.launch {
            kotlinx.coroutines.delay(1500)
            combine(engine.player.playerState.current, VibesGraph.library.liked) { cur, liked ->
                val song = cur?.let { runCatching { SongRef.from(it.mediaItem.extensionId, it.track) }.getOrNull() }
                Triple(song != null, cur?.isPlaying == true, song?.let { s -> liked.any { it.key == s.key } } == true)
            }.distinctUntilChanged().collect { (has, playing, liked) -> com.execute { runCatching { update(has, playing, liked) }.onFailure { println("taskbar: ${it.message}") } } }
        }
    }

    /** WM_COMMAND with THBN_CLICKED = one of our buttons. Everything else goes to the original procedure. */
    private fun hook(engine: MusicEngine) {
        val h = hwnd ?: return
        val p = WinUser.WindowProc { w, msg, wParam, lParam ->
            if (msg == WM_COMMAND && (wParam.toLong() shr 16 and 0xFFFF).toInt() == THBN_CLICKED) {
                val id = (wParam.toLong() and 0xFFFF).toInt()
                javax.swing.SwingUtilities.invokeLater {
                    when (id) {
                        PREV -> engine.previous()
                        PLAY -> engine.togglePlay()
                        NEXT -> engine.next()
                        LIKE -> engine.player.playerState.current.value?.let { c -> runCatching { VibesGraph.library.toggleLike(SongRef.from(c.mediaItem.extensionId, c.track)) } }
                    }
                }
                WinDef.LRESULT(0)
            } else User32.INSTANCE.CallWindowProc(oldProc, w, msg, wParam, lParam)
        }
        proc = p
        oldProc = Pointer(User32.INSTANCE.GetWindowLongPtr(h, WinUser.GWL_WNDPROC).toLong())
        User32.INSTANCE.SetWindowLongPtr(h, WinUser.GWL_WNDPROC, CallbackReference.getFunctionPointer(p))
    }

    private fun vcall(obj: Pointer, slot: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(obj.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    private fun ensureTaskbar(): Pointer? {
        taskbar?.let { return it }
        Ole32.INSTANCE.CoInitializeEx(null, 0x2)
        val out = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            Guid.CLSID("{56FDF344-FD6D-11d0-958A-006097C9A090}"), null, 1,
            Guid.IID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}"), out,
        )
        if (hr.toInt() != 0) error("ITaskbarList3 non disponibile (0x${Integer.toHexString(hr.toInt())})")
        val tb = out.value
        vcall(tb, HR_INIT)
        taskbar = tb
        return tb
    }

    private fun update(has: Boolean, playing: Boolean, liked: Boolean) {
        val tb = ensureTaskbar() ?: return
        val h = hwnd ?: return
        val arr = ThumbButton().toArray(4).map { it as ThumbButton }
        arr[0].set(PREV, icon("prev"), "Precedente", has)
        arr[1].set(PLAY, icon(if (playing) "pause" else "play"), if (playing) "Pausa" else "Riproduci", has)
        arr[2].set(NEXT, icon("next"), "Successivo", has)
        arr[3].set(LIKE, icon(if (liked) "liked" else "like"), if (liked) "Rimuovi da Brani che ti piacciono" else "Salva in Brani che ti piacciono", has)
        arr.forEach { it.write() }
        val hr = vcall(tb, if (added) UPDATE_BUTTONS else ADD_BUTTONS, h, 4, arr[0].pointer)
        if (hr == 0) added = true
    }

    // ---------------------------------------------------------------- icons (drawn, then loaded as .ico)

    private fun icon(name: String): WinDef.HICON? = icons.getOrPut(name) {
        val size = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXSMICON).takeIf { it > 0 } ?: 16
        val file = File(System.getProperty("java.io.tmpdir"), "vibes-tb-$name-$size.ico")
        file.writeBytes(ico(draw(name, size * 2)))
        val h = User32.INSTANCE.LoadImage(null, file.absolutePath, WinUser.IMAGE_ICON, size, size, WinUser.LR_LOADFROMFILE)
            ?: return null
        WinDef.HICON(h.pointer)
    }

    private fun draw(name: String, s: Int): BufferedImage {
        val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color.WHITE
        val f = s / 32f
        fun tri(x0: Float, flip: Boolean) {
            val p = Path2D.Float()
            if (!flip) { p.moveTo(x0 * f, 7 * f); p.lineTo((x0 + 14) * f, 16 * f); p.lineTo(x0 * f, 25 * f) }
            else { p.moveTo((x0 + 14) * f, 7 * f); p.lineTo(x0 * f, 16 * f); p.lineTo((x0 + 14) * f, 25 * f) }
            p.closePath(); g.fill(p)
        }
        when (name) {
            "play" -> tri(10f, false)
            "pause" -> { g.fillRoundRect((9 * f).toInt(), (7 * f).toInt(), (5 * f).toInt(), (18 * f).toInt(), 2, 2); g.fillRoundRect((18 * f).toInt(), (7 * f).toInt(), (5 * f).toInt(), (18 * f).toInt(), 2, 2) }
            "next" -> { tri(7f, false); g.fillRect((22 * f).toInt(), (7 * f).toInt(), (3 * f).toInt(), (18 * f).toInt()) }
            "prev" -> { tri(11f, true); g.fillRect((7 * f).toInt(), (7 * f).toInt(), (3 * f).toInt(), (18 * f).toInt()) }
            "like", "liked" -> {
                val p = Path2D.Float()
                p.moveTo(16 * f, 27 * f)
                p.curveTo(2 * f, 18 * f, 3 * f, 6 * f, 11 * f, 6 * f)
                p.curveTo(14 * f, 6 * f, 16 * f, 9 * f, 16 * f, 10 * f)
                p.curveTo(16 * f, 9 * f, 18 * f, 6 * f, 21 * f, 6 * f)
                p.curveTo(29 * f, 6 * f, 30 * f, 18 * f, 16 * f, 27 * f)
                p.closePath()
                if (name == "liked") { g.color = java.awt.Color(0xB8, 0x4D, 0xFF); g.fill(p) }
                else { g.stroke = BasicStroke(2.6f * f); g.draw(p) }
            }
        }
        g.dispose()
        return img
    }

    /** A one-image .ico with the PNG inside (Windows Vista and later read these). */
    private fun ico(img: BufferedImage): ByteArray {
        val png = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        val b = java.nio.ByteBuffer.allocate(6 + 16 + png.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putShort(0); b.putShort(1); b.putShort(1)
        b.put((if (img.width >= 256) 0 else img.width).toByte()); b.put((if (img.height >= 256) 0 else img.height).toByte())
        b.put(0); b.put(0); b.putShort(1); b.putShort(32); b.putInt(png.size); b.putInt(22)
        b.put(png)
        return b.array()
    }

    @Suppress("unused") private val keep = Memory(1)

    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attr: Int, value: IntByReference, size: Int): Int
    }

    /** Black title bar like the rest of the window (Windows 10 dark mode attribute, Windows 11 caption colour). */
    private fun darkTitleBar(h: WinDef.HWND) {
        val dwm = Native.load("dwmapi", Dwm::class.java)
        dwm.DwmSetWindowAttribute(h, 20, IntByReference(1), 4)
        dwm.DwmSetWindowAttribute(h, 35, IntByReference(0x000000), 4)
    }
}
