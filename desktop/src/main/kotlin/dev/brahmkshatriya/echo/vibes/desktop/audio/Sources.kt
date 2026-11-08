package dev.brahmkshatriya.echo.vibes.desktop.audio

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Where an item's encoded bytes come from. [open] may start anywhere (seeking). */
interface ByteSource {
    /** total size in bytes, or -1 if unknown */
    val length: Long
    fun open(offset: Long): InputStream
    /** a local copy if there is one (needed by containers that must seek, like m4a) */
    val file: File? get() = null
}

class FileSource(override val file: File) : ByteSource {
    override val length get() = file.length()
    override fun open(offset: Long): InputStream = file.inputStream().also { if (offset > 0) it.skipNBytes(offset.coerceAtMost(file.length())) }
}

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
}

class HttpSource(private val url: String, private val headers: Map<String, String> = emptyMap()) : ByteSource {
    @Volatile private var len = -2L
    override val length: Long
        get() {
            if (len == -2L) len = runCatching {
                Http.client.newCall(Request.Builder().url(url).head().apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute()
                    .use { if (it.isSuccessful) it.header("Content-Length")?.toLongOrNull() ?: -1 else -1 }
            }.getOrDefault(-1)
            return len
        }

    override fun open(offset: Long): InputStream {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val res = Http.client.newCall(req).execute()
        if (!res.isSuccessful) { res.close(); throw java.io.IOException("HTTP ${res.code}") }
        if (len < 0) res.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { len = it }
            ?: res.header("Content-Length")?.toLongOrNull()?.takeIf { offset == 0L }?.let { len = it }
        val body = res.body.byteStream()
        // server ignored Range: skip ourselves
        return if (offset > 0 && res.code == 200) body.also { it.skipNBytes(offset) } else body
    }
}

/** Bytes produced by an extension (Deezer: downloaded + decrypted on the fly). */
class ProviderSource(
    override val length: Long,
    private val provider: (offset: Long) -> InputStream,
) : ByteSource {
    override fun open(offset: Long): InputStream = provider(offset)
}

/** A cached local copy of any source (m4a needs random access). */
fun ByteSource.toLocalFile(dir: File): File {
    file?.let { return it }
    val f = File.createTempFile("vibes", ".audio", dir.apply { mkdirs() })
    f.deleteOnExit()
    open(0).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    return f
}

internal fun RandomAccessFile.readFully(n: Int): ByteArray = ByteArray(n).also { readFully(it) }

/**
 * Keeps the first bytes of a stream in memory so format sniffing, the MP3 probe and the decoder
 * don't each make a new network request; the first stream ([live], already opened by whoever
 * resolved the track) is reused for the bytes after the cached head.
 */
class HeadCachedSource(private val inner: ByteSource, live: InputStream?) : ByteSource {
    private var liveStream: InputStream? = live
    private val head: ByteArray by lazy {
        val s = takeLive() ?: inner.open(0)
        val h = s.readNBytes(HEAD)
        synchronized(this) { liveStream = s; livePos = h.size.toLong() }
        h
    }
    private var livePos = 0L

    @Synchronized private fun takeLive(): InputStream? = liveStream.also { liveStream = null }

    override val length get() = inner.length

    override fun open(offset: Long): InputStream {
        val h = head
        if (offset >= h.size) {
            synchronized(this) {
                if (liveStream != null && livePos == offset) return takeLive()!!
            }
            return inner.open(offset)
        }
        val rest = object : InputStream() {
            private var s: InputStream? = null
            private fun src(): InputStream = s ?: run {
                val l = synchronized(this@HeadCachedSource) { if (liveStream != null && livePos == h.size.toLong()) takeLive() else null }
                (l ?: inner.open(h.size.toLong())).also { s = it }
            }
            override fun read() = src().read()
            override fun read(b: ByteArray, off: Int, len: Int) = src().read(b, off, len)
            override fun close() { s?.close() }
        }
        return java.io.SequenceInputStream(java.io.ByteArrayInputStream(h, offset.toInt(), h.size - offset.toInt()), rest)
    }

    companion object { const val HEAD = 192 * 1024 }
}
