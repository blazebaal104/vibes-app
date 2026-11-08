package android.os

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

object Build {
    const val MODEL = "Windows PC"
    const val MANUFACTURER = "PC"
    const val BRAND = "PC"
    const val DEVICE = "pc"
    const val PRODUCT = "pc"
    const val HARDWARE = "pc"
    val SUPPORTED_ABIS: Array<String> = arrayOf("windows")
    object VERSION {
        /** Behaves like a recent Android: blur, modern APIs paths… */
        const val SDK_INT = 35
        const val RELEASE = "Windows"
    }
    object VERSION_CODES {
        const val M = 23; const val N = 24; const val O = 26; const val P = 28; const val Q = 29; const val R = 30
        const val S = 31; const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34; const val VANILLA_ICE_CREAM = 35
    }
}

class Bundle {
    private val m = HashMap<String, Any?>()
    fun putString(k: String, v: String?) { m[k] = v }
    fun getString(k: String): String? = m[k] as? String
    fun putInt(k: String, v: Int) { m[k] = v }
    fun getInt(k: String, d: Int = 0) = m[k] as? Int ?: d
    fun putBoolean(k: String, v: Boolean) { m[k] = v }
    fun getBoolean(k: String, d: Boolean = false) = m[k] as? Boolean ?: d
    fun putLong(k: String, v: Long) { m[k] = v }
    fun getLong(k: String, d: Long = 0) = m[k] as? Long ?: d
    fun containsKey(k: String) = m.containsKey(k)
}

class Looper private constructor() {
    companion object {
        private val main = Looper()
        @JvmStatic fun getMainLooper(): Looper = main
        @JvmStatic fun myLooper(): Looper? = if (javax.swing.SwingUtilities.isEventDispatchThread()) main else null
    }
    val thread: Thread get() = Thread.currentThread()
}

/** Runs callbacks on the UI thread (Swing/Compose), like a main-looper Handler. */
open class Handler(looper: Looper = Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val jobs = HashMap<Runnable, MutableList<Job>>()
    fun post(r: Runnable): Boolean = postDelayed(r, 0)
    fun postDelayed(r: Runnable, ms: Long): Boolean {
        lateinit var job: Job
        job = scope.launch {
            if (ms > 0) delay(ms)
            synchronized(jobs) { jobs[r]?.remove(job) }
            r.run()
        }
        synchronized(jobs) { jobs.getOrPut(r) { mutableListOf() } += job }
        return true
    }
    fun postDelayed(ms: Long, r: () -> Unit) = postDelayed(Runnable(r), ms)
    fun removeCallbacks(r: Runnable) { synchronized(jobs) { jobs.remove(r)?.forEach { it.cancel() } } }
    fun removeCallbacksAndMessages(token: Any?) { synchronized(jobs) { jobs.values.flatten().forEach { it.cancel() }; jobs.clear() } }
}

object SystemClock {
    private val t0 = System.nanoTime()
    @JvmStatic fun elapsedRealtime(): Long = (System.nanoTime() - t0) / 1_000_000
    @JvmStatic fun uptimeMillis(): Long = elapsedRealtime()
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - t0
    @JvmStatic fun sleep(ms: Long) = Thread.sleep(ms)
}

object Environment {
    const val DIRECTORY_DOWNLOADS = "Downloads"
    const val DIRECTORY_MUSIC = "Music"
    const val DIRECTORY_PICTURES = "Pictures"
    @JvmStatic fun getExternalStoragePublicDirectory(type: String): java.io.File =
        java.io.File(System.getProperty("user.home"), type).apply { mkdirs() }
}

class PowerManager {
    class WakeLock { fun acquire(ms: Long = 0) {}; fun release() {}; val isHeld get() = false; fun setReferenceCounted(b: Boolean) {} }
    fun newWakeLock(flags: Int, tag: String) = WakeLock()
    companion object { const val PARTIAL_WAKE_LOCK = 1 }
}

interface Parcelable
