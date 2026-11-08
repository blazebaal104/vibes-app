package android.net

import java.io.File
import java.net.URI
import java.net.URLDecoder

/** A link or a file on this PC. */
class Uri private constructor(private val s: String) : Comparable<Uri> {
    private val u: URI? = runCatching { URI(s.replace(" ", "%20").replace("\\", "/")) }.getOrNull()
    val scheme: String? get() = u?.scheme?.takeIf { it.length > 1 } ?: if (isFile) "file" else null
    val host: String? get() = u?.host
    val authority: String? get() = u?.authority
    val port: Int get() = u?.port ?: -1
    private val isFile get() = s.startsWith("file:") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(s) || s.startsWith("/")
    val path: String?
        get() = when {
            s.startsWith("file:") -> runCatching { File(URI(s)).path }.getOrElse { s.removePrefix("file://") }
            isFile -> s
            else -> u?.path
        }
    val encodedPath: String? get() = u?.rawPath
    val query: String? get() = u?.query
    val fragment: String? get() = u?.fragment
    val pathSegments: List<String> get() = path?.split('/', '\\')?.filter { it.isNotEmpty() } ?: emptyList()
    val lastPathSegment: String? get() = pathSegments.lastOrNull()
    fun getQueryParameter(key: String): String? = u?.rawQuery?.split('&')?.firstNotNullOfOrNull { kv ->
        val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (URLDecoder.decode(k, "UTF-8") == key) URLDecoder.decode(v, "UTF-8") else null
    }
    val queryParameterNames: Set<String> get() = u?.rawQuery?.split('&')?.map { it.substringBefore('=') }?.toSet() ?: emptySet()
    fun buildUpon() = Builder(s)
    override fun toString() = s
    override fun equals(other: Any?) = other is Uri && other.s == s
    override fun hashCode() = s.hashCode()
    override fun compareTo(other: Uri) = s.compareTo(other.s)

    class Builder(private var base: String = "") {
        private val params = mutableListOf<Pair<String, String>>()
        fun scheme(v: String) = apply { base = "$v://" }
        fun authority(v: String) = apply { base += v }
        fun path(v: String) = apply { base += v }
        fun appendPath(v: String) = apply { base = base.trimEnd('/') + "/" + encode(v) }
        fun appendQueryParameter(k: String, v: String) = apply { params += k to v }
        fun build(): Uri = parse(if (params.isEmpty()) base else base + (if ('?' in base) "&" else "?") +
            params.joinToString("&") { "${encode(it.first)}=${encode(it.second)}" })
    }

    companion object {
        val EMPTY = Uri("")
        fun parse(s: String): Uri = Uri(s)
        fun fromFile(f: File): Uri = Uri(f.toURI().toString())
        fun encode(s: String?): String = java.net.URLEncoder.encode(s ?: "", "UTF-8").replace("+", "%20")
        fun decode(s: String?): String = URLDecoder.decode(s ?: "", "UTF-8")
    }
}

fun String.toUri(): Uri = Uri.parse(this)
fun File.toUri(): Uri = Uri.fromFile(this)
