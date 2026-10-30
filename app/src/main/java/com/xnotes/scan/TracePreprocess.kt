package com.xnotes.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.xnotes.platform.ImageDecoder
import com.xnotes.platform.PdfSource
import java.io.File
import kotlin.math.roundToInt

/**
 * Bitmap plumbing for the scan-to-strokes import: staging a picked file, decoding
 * it (or rendering a PDF page) at a bounded working size, and applying the user's
 * geometric + photometric adjustments before [InkVectorizer] runs.
 *
 * Everything here is IO/heavy — call off the main thread. Bitmaps are ARGB_8888.
 */
object TracePreprocess {

    /** Longest side of the working image the vectorizer sees (speed vs fidelity). */
    const val WORK_MAX_SIDE = 2000

    /**
     * Full-res working size, lowered on small-heap devices so a big photo's
     * transient buffers (luminance + blur fields + distance + skeleton) cannot
     * exhaust the heap. The preview size is unaffected.
     */
    fun cappedWorkSide(requested: Int = WORK_MAX_SIDE): Int {
        val cap = if (Runtime.getRuntime().maxMemory() < 200L * 1024 * 1024) 1200 else WORK_MAX_SIDE
        return minOf(requested, cap)
    }

    /** Longest side of the on-screen preview (cheaper than the working image). */
    const val PREVIEW_MAX_SIDE = 900

    /** Source kinds the trace flow accepts. */
    enum class TraceKind { IMAGE, PDF }

    fun classify(name: String, mime: String?): TraceKind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "pdf" || mime == "application/pdf") return TraceKind.PDF
        val imageExt = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
        if (ext in imageExt || mime?.startsWith("image/") == true) return TraceKind.IMAGE
        return null
    }

    /** Copy [bytes] (an image read from a content URI) into a temp file in [dir]. IO. */
    fun stageBytes(bytes: ByteArray, suffix: String, dir: File): File? = runCatching {
        val f = File.createTempFile("trace-", suffix, dir)
        f.writeBytes(bytes)
        f
    }.getOrNull()

    /** Decode [file] (any raster the platform reads, EXIF-turn applied) bounded by [maxSide]. IO. */
    fun decodeBounded(file: File, maxSide: Int = WORK_MAX_SIDE): Bitmap? {
        // EXIF turn first: a phone photo of paper usually needs it.
        val turn = ImageDecoder.exifTurn(file.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        // Swap for the sample-size computation when a quarter turn applies.
        val (ew, eh) = if (turn % 180 == 0) sw to sh else sh to sw
        var sample = 1
        while (maxOf(ew / sample, eh / sample) > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        if (turn != 0) {
            val m = Matrix().apply { postRotate(turn.toFloat()) }
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }
        if (maxOf(bmp.width, bmp.height) > maxSide) {
            val s = maxSide.toDouble() / maxOf(bmp.width, bmp.height)
            val tw = (bmp.width * s).roundToInt().coerceAtLeast(1)
            val th = (bmp.height * s).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    /** Render PDF [pageIndex] of [pdfFile] into a bitmap bounded by [maxSide]. IO. */
    fun renderPdfPage(pdfFile: File, pageIndex: Int, maxSide: Int = WORK_MAX_SIDE): Bitmap? {
        val src = PdfSource.open(pdfFile)
        try {
            if (src.openError != null || pageIndex !in 0 until src.pageCount) return null
            val (wPts, hPts) = src.pageSizePoints(pageIndex) ?: return null
            if (wPts <= 0f || hPts <= 0f) return null
            val s = maxSide.toDouble() / maxOf(wPts, hPts)
            val wPx = (wPts * s).roundToInt().coerceAtLeast(1)
            val hPx = (hPts * s).roundToInt().coerceAtLeast(1)
            val surf = src.renderPage(pageIndex, wPx, hPx) ?: return null
            return surf.bitmap
        } finally {
            src.close()
        }
    }

    /** Page count of the PDF at [pdfFile], 0 when it does not open. IO. */
    fun pdfPageCount(pdfFile: File): Int {
        val src = PdfSource.open(pdfFile)
        try {
            return if (src.openError != null) 0 else src.pageCount
        } finally {
            src.close()
        }
    }

    /**
     * Geometric adjustment: keep the fractional [crop] (0..1 in each axis), then turn
     * [quarterTurns] × 90° clockwise plus [fineDegrees] (-15..15). Returns a new bitmap.
     */
    fun adjustGeometry(src: Bitmap, crop: CropFrac, quarterTurns: Int, fineDegrees: Float): Bitmap {
        val l = (crop.left * src.width).roundToInt().coerceIn(0, src.width - 1)
        val t = (crop.top * src.height).roundToInt().coerceIn(0, src.height - 1)
        val r = (crop.right * src.width).roundToInt().coerceIn(l + 1, src.width)
        val b = (crop.bottom * src.height).roundToInt().coerceIn(t + 1, src.height)
        var bmp = Bitmap.createBitmap(src, l, t, r - l, b - t)
        val total = (quarterTurns * 90f + fineDegrees)
        if (total != 0f) {
            val m = Matrix().apply { postRotate(((total % 360f) + 360f) % 360f, bmp.width / 2f, bmp.height / 2f) }
            // A fine angle's corners leave the frame: render onto a grown canvas.
            val grown = if (fineDegrees != 0f) {
                val rad = Math.toRadians(kotlin.math.abs(fineDegrees).toDouble())
                val gw = (bmp.width * kotlin.math.abs(kotlin.math.cos(rad)) + bmp.height * kotlin.math.abs(kotlin.math.sin(rad))).roundToInt()
                val gh = (bmp.width * kotlin.math.abs(kotlin.math.sin(rad)) + bmp.height * kotlin.math.abs(kotlin.math.cos(rad))).roundToInt()
                gw to gh
            } else null
            val target = if (grown != null) {
                val (gw, gh) = grown
                val out = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
                out.eraseColor(0xFFFFFFFF.toInt())
                val c = Canvas(out)
                c.translate((gw - bmp.width) / 2f, (gh - bmp.height) / 2f)
                c.translate(bmp.width / 2f, bmp.height / 2f)
                c.rotate(((total % 360f) + 360f) % 360f)
                c.translate(-bmp.width / 2f, -bmp.height / 2f)
                c.drawBitmap(bmp, 0f, 0f, null)
                bmp.recycle()
                out
            } else {
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (out !== bmp) bmp.recycle()
                out
            }
            bmp = target
        }
        return bmp
    }

    /** Fractional crop rect in 0..1 (what the preview sliders edit). */
    data class CropFrac(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        companion object {
            val FULL = CropFrac(0f, 0f, 1f, 1f)
        }
    }

    /**
     * Photometric adjustment for the trace input: [brightness] -100..100,
     * [contrast] 0..200 (% of normal), then the result is what [toLuminance]
     * thresholds. Returns a new bitmap.
     */
    fun adjustPhoto(src: Bitmap, brightness: Int, contrast: Int): Bitmap {
        if (brightness == 0 && contrast == 100) return src
        val c = contrast / 100f
        val b = brightness * 2.55f
        // Contrast about the midpoint, then brightness shift.
        val m = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, b + 128f * (1f - c),
                0f, c, 0f, 0f, b + 128f * (1f - c),
                0f, 0f, c, 0f, b + 128f * (1f - c),
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        return out
    }

    /** Row-major 0..255 luminance of [bmp] (Rec. 709), the vectorizer's input. */
    fun toLuminance(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = ((0.2126 * r + 0.7152 * g + 0.0722 * b) + 0.5).toInt().coerceIn(0, 255)
        }
        return lum
    }

    /** A black-on-white preview of an ink mask (what the tracer sees). */
    fun maskPreview(mask: BooleanArray, w: Int, h: Int): Bitmap {
        require(mask.size == w * h)
        val px = IntArray(w * h)
        for (i in px.indices) px[i] = if (mask[i]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
