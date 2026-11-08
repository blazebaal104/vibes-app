package android.graphics

import java.awt.AlphaComposite
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** android.graphics.Bitmap on top of a BufferedImage. */
class Bitmap(val image: BufferedImage) {
    val width get() = image.width
    val height get() = image.height
    enum class Config { ARGB_8888, RGB_565 }
    enum class CompressFormat { JPEG, PNG, WEBP }

    fun compress(format: CompressFormat, quality: Int, out: OutputStream): Boolean {
        val type = if (format == CompressFormat.PNG) "png" else "jpg"
        val img = if (type == "jpg" && image.type != BufferedImage.TYPE_INT_RGB) {
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { rgb ->
                rgb.createGraphics().apply { color = java.awt.Color.BLACK; fillRect(0, 0, width, height); drawImage(image, 0, 0, null); dispose() }
            }
        } else image
        if (type == "png") return ImageIO.write(img, "png", out)
        val w = ImageIO.getImageWritersByFormatName("jpg").next()
        val p = w.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality.coerceIn(1, 100) / 100f }
        ImageIO.createImageOutputStream(out).use { ios -> w.output = ios; w.write(null, IIOImage(img, null, null), p) }
        w.dispose()
        return true
    }

    fun recycle() {}
    val isRecycled get() = false
    fun getPixel(x: Int, y: Int) = image.getRGB(x, y)

    companion object {
        @JvmStatic fun createBitmap(w: Int, h: Int, config: Config): Bitmap =
            Bitmap(BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB))

        @JvmStatic fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap =
            Bitmap(copy(src.image.getSubimage(x, y, w, h)))

        @JvmStatic fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix?, filter: Boolean): Bitmap {
            val sub = copy(src.image.getSubimage(x, y, w, h))
            if (m == null || m.isIdentity) return Bitmap(sub)
            val t = m.toAffine()
            val bounds = t.createTransformedShape(java.awt.Rectangle(0, 0, w, h)).bounds
            val fix = AffineTransform.getTranslateInstance(-bounds.x.toDouble(), -bounds.y.toDouble()).apply { concatenate(t) }
            val out = BufferedImage(bounds.width.coerceAtLeast(1), bounds.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(sub, AffineTransformOp(fix, AffineTransformOp.TYPE_BILINEAR), 0, 0); dispose()
            }
            return Bitmap(out)
        }

        @JvmStatic fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            val out = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(src.image, 0, 0, w, h, null); dispose()
            }
            return Bitmap(out)
        }

        private fun copy(img: BufferedImage): BufferedImage =
            BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(img, 0, 0, null); dispose() } }
    }
}

object BitmapFactory {
    class Options { var inJustDecodeBounds = false; var inSampleSize = 1; var outWidth = 0; var outHeight = 0 }
    // Android returns a platform type (null on failure); here a bad image throws, callers wrap it in runCatching
    @JvmStatic fun decodeStream(input: InputStream?): Bitmap = Bitmap(ImageIO.read(input ?: error("no input")) ?: throw IllegalArgumentException("Immagine non supportata"))
    @JvmStatic fun decodeStream(input: InputStream?, pad: Rect?, opts: Options?): Bitmap = decodeStream(input).also { b -> opts?.let { it.outWidth = b.width; it.outHeight = b.height } }
    @JvmStatic fun decodeFile(path: String?): Bitmap = Bitmap(ImageIO.read(java.io.File(path ?: error("no path"))) ?: throw IllegalArgumentException("Immagine non supportata"))
    @JvmStatic fun decodeByteArray(b: ByteArray, off: Int, len: Int): Bitmap = decodeStream(java.io.ByteArrayInputStream(b, off, len))
    @JvmStatic fun decodeByteArray(b: ByteArray, off: Int, len: Int, opts: Options?): Bitmap = decodeByteArray(b, off, len)
}

class Rect(var left: Int = 0, var top: Int = 0, var right: Int = 0, var bottom: Int = 0)
class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f)

class Matrix {
    private var t = AffineTransform()
    val isIdentity get() = t.isIdentity
    fun postScale(sx: Float, sy: Float): Boolean { t.preConcatenate(AffineTransform.getScaleInstance(sx.toDouble(), sy.toDouble())); return true }
    fun postRotate(deg: Float): Boolean { t.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(deg.toDouble()))); return true }
    fun postTranslate(dx: Float, dy: Float): Boolean { t.preConcatenate(AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble())); return true }
    fun setScale(sx: Float, sy: Float) { t = AffineTransform.getScaleInstance(sx.toDouble(), sy.toDouble()) }
    fun reset() { t = AffineTransform() }
    fun toAffine() = AffineTransform(t)
}

object Color {
    const val BLACK = -0x1000000
    const val WHITE = -0x1
    const val TRANSPARENT = 0
    const val RED = -0x10000
    const val GREEN = -0xff0100
    const val BLUE = -0xffff01
    const val GRAY = -0x777778
    @JvmStatic fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    @JvmStatic fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)
    @JvmStatic fun alpha(c: Int) = c ushr 24
    @JvmStatic fun red(c: Int) = (c shr 16) and 0xFF
    @JvmStatic fun green(c: Int) = (c shr 8) and 0xFF
    @JvmStatic fun blue(c: Int) = c and 0xFF
    @JvmStatic fun parseColor(s: String): Int {
        require(s.startsWith("#")) { "Unknown color" }
        val h = s.substring(1)
        val v = h.toLong(16)
        return when (h.length) {
            6 -> (0xFF000000L or v).toInt()
            8 -> v.toInt()
            3 -> { val r = (v shr 8) and 0xF; val g = (v shr 4) and 0xF; val b = v and 0xF
                (0xFF000000L or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)).toInt() }
            else -> throw IllegalArgumentException("Unknown color")
        }
    }
}

open class Shader { enum class TileMode { CLAMP, REPEAT, MIRROR } }
class LinearGradient(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val colors: IntArray, val positions: FloatArray?, val tile: Shader.TileMode) : Shader()

class Typeface private constructor(val bold: Boolean) {
    companion object {
        const val NORMAL = 0
        const val BOLD = 1
        @JvmField val DEFAULT = Typeface(false)
        @JvmField val DEFAULT_BOLD = Typeface(true)
        @JvmStatic fun create(family: Typeface?, style: Int) = Typeface(style and BOLD != 0)
    }
}

class Paint(flags: Int = 0) {
    var color: Int = Color.BLACK
    var textSize: Float = 12f
    var typeface: Typeface? = Typeface.DEFAULT
    var shader: Shader? = null
    var isAntiAlias = flags and ANTI_ALIAS_FLAG != 0
    var alpha: Int
        get() = color ushr 24
        set(v) { color = (color and 0xFFFFFF) or (v.coerceIn(0, 255) shl 24) }
    fun measureText(s: String): Float {
        val g = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        g.font = awtFont(); return g.fontMetrics.stringWidth(s).toFloat().also { g.dispose() }
    }
    fun awtFont() = Font("Segoe UI", if (typeface?.bold == true) Font.BOLD else Font.PLAIN, textSize.toInt().coerceAtLeast(1))
    companion object { const val ANTI_ALIAS_FLAG = 1 }
}

class Canvas(private val bmp: Bitmap) {
    private val g = bmp.image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    private fun awt(c: Int) = java.awt.Color(c, true)

    fun drawColor(c: Int) {
        g.composite = AlphaComposite.SrcOver; g.color = awt(c); g.fillRect(0, 0, bmp.width, bmp.height)
    }

    fun drawPaint(p: Paint) {
        val s = p.shader
        if (s is LinearGradient && s.colors.size >= 2) {
            val fr = s.positions ?: FloatArray(s.colors.size) { it / (s.colors.size - 1f) }
            g.paint = java.awt.LinearGradientPaint(s.x0, s.y0, s.x1.coerceAtLeast(s.x0 + 0.01f), s.y1, fr, s.colors.map(::awt).toTypedArray())
        } else g.color = awt(p.color)
        g.fillRect(0, 0, bmp.width, bmp.height)
    }

    fun drawText(text: String, x: Float, y: Float, p: Paint) {
        g.font = p.awtFont(); g.color = awt(p.color); g.drawString(text, x, y)
    }

    fun drawBitmap(b: Bitmap, x: Float, y: Float, p: Paint?) { g.drawImage(b.image, x.toInt(), y.toInt(), null) }
    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) { g.color = awt(p.color); g.fillRect(l.toInt(), t.toInt(), (r - l).toInt(), (b - t).toInt()) }
    fun drawCircle(cx: Float, cy: Float, r: Float, p: Paint) { g.color = awt(p.color); g.fillOval((cx - r).toInt(), (cy - r).toInt(), (2 * r).toInt(), (2 * r).toInt()) }
}
