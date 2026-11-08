package android.util

object Log {
    private fun p(level: String, tag: String?, msg: String?, t: Throwable? = null): Int {
        System.err.println("$level/$tag: $msg"); t?.printStackTrace(); return 0
    }
    @JvmStatic fun d(tag: String?, msg: String?) = p("D", tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, t: Throwable?) = p("D", tag, msg, t)
    @JvmStatic fun i(tag: String?, msg: String?) = p("I", tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, t: Throwable?) = p("I", tag, msg, t)
    @JvmStatic fun v(tag: String?, msg: String?) = 0
    @JvmStatic fun w(tag: String?, msg: String?) = p("W", tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, t: Throwable?) = p("W", tag, msg, t)
    @JvmStatic fun w(tag: String?, t: Throwable?) = p("W", tag, null, t)
    @JvmStatic fun e(tag: String?, msg: String?) = p("E", tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, t: Throwable?) = p("E", tag, msg, t)
    @JvmStatic fun getStackTraceString(t: Throwable?): String = t?.stackTraceToString() ?: ""
}
