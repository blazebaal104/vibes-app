package dev.brahmkshatriya.echo.vibes.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference

/**
 * Playing music behind a game without getting in its way (Windows; does nothing elsewhere).
 *
 *  - The audio thread joins the Windows multimedia scheduler ("Pro Audio" task, MMCSS) like the
 *    players and DAWs do: the system gives it its slices of CPU on time even when a game takes all
 *    the cores, so the sound never clicks or stops, whatever priority the rest of Vibes has.
 *  - When Vibes is not the window in front, the rest of the process (interface, network, garbage
 *    collector) drops to "below normal" priority: the game always comes first. Back in front, normal.
 */
object WinPerf {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private interface Avrt : Library {
        fun AvSetMmThreadCharacteristicsW(task: WString, index: IntByReference): Pointer?
        fun AvSetMmThreadPriority(handle: Pointer, priority: Int): Boolean
    }

    private interface K32 : Library {
        fun GetCurrentProcess(): Pointer
        fun SetPriorityClass(process: Pointer, priorityClass: Int): Boolean
    }

    private val k32: K32? by lazy { if (isWindows) runCatching { Native.load("kernel32", K32::class.java) }.getOrNull() else null }

    private val avrt: Avrt? by lazy { if (isWindows) runCatching { Native.load("Avrt", Avrt::class.java) }.getOrNull() else null }

    /** Call from the audio thread itself, once. */
    fun registerAudioThread() {
        if (!isWindows) return
        runCatching {
            val a = avrt ?: return
            val h = a.AvSetMmThreadCharacteristicsW(WString("Pro Audio"), IntByReference(0))
            if (h != null) a.AvSetMmThreadPriority(h, 1 /* AVRT_PRIORITY_HIGH */)
            println("vibes-audio: MMCSS ${if (h != null) "on" else "unavailable"}")
        }.onFailure { println("vibes-audio: MMCSS ${it.message}") }
    }

    @Volatile private var lastBackground: Boolean? = null

    /** true = another app is in front (or Vibes is minimized): let it have the PC. */
    fun background(on: Boolean) {
        if (!isWindows || lastBackground == on) return
        lastBackground = on
        runCatching {
            val k = k32 ?: return
            k.SetPriorityClass(k.GetCurrentProcess(), if (on) BELOW_NORMAL else NORMAL)
        }
    }

    private const val NORMAL = 0x20
    private const val BELOW_NORMAL = 0x4000
}
