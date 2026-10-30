package com.xnotes.platform

import java.io.File
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min

/**
 * Links a Math Studio animation filmstrip back to the setup that rendered it. The exported PNG
 * carries the animation spec in an `iTXt` chunk (`xnotes-math-anim`), so the bytes themselves
 * remember the tab, expressions and `t` range: the canvas shows a play badge on such images and
 * a tap on the badge reopens Math Studio with the animation restored. No model or codec change
 * is needed — cropping or re-encoding drops the chunk and the badge along with it.
 */
object MathAnimPng {

    const val KEYWORD = "xnotes-math-anim"

    /** Embeds UTF-8 [json] into [png] right after IHDR; returns [png] unchanged when it is not a PNG. */
    fun embed(png: ByteArray, json: String): ByteArray {
        if (!isPng(png)) return png
        val text = json.toByteArray(Charsets.UTF_8)
        val kw = KEYWORD.toByteArray(Charsets.US_ASCII)
        // iTXt payload: keyword NUL, compression flag 0, method 0, lang NUL, translated NUL, text.
        val data = ByteArray(kw.size + 1 + 4 + text.size)
        var o = 0
        kw.copyInto(data, o); o += kw.size
        data[o++] = 0 // NUL after keyword
        data[o++] = 0 // compression flag: uncompressed
        data[o++] = 0 // compression method
        data[o++] = 0 // empty language tag
        data[o++] = 0 // empty translated keyword
        text.copyInto(data, o)
        val chunk = ByteArray(4 + 4 + data.size + 4)
        putInt(chunk, 0, data.size)
        "iTXt".toByteArray(Charsets.US_ASCII).copyInto(chunk, 4)
        data.copyInto(chunk, 8)
        val crc = CRC32()
        crc.update(chunk, 4, 4 + data.size)
        putInt(chunk, 8 + data.size, crc.value.toInt())
        // Splice right after the 8-byte signature + IHDR chunk.
        var pos = 8
        val ihdrLen = getInt(png, pos)
        pos += 4 + 4 + ihdrLen + 4
        val out = ByteArray(png.size + chunk.size)
        png.copyInto(out, 0, 0, pos)
        chunk.copyInto(out, pos)
        png.copyInto(out, pos + chunk.size, pos, png.size)
        return out
    }

    /** The embedded spec JSON, or null when [file] is not one of ours. */
    fun readSpec(file: File): String? = runCatching { readSpec(file.readBytes()) }.getOrNull()

    fun readSpec(png: ByteArray): String? {
        if (!isPng(png)) return null
        var pos = 8
        // Our chunk sits right after IHDR, but scan a bounded prefix anyway.
        val end = min(png.size, 262_144)
        while (pos + 8 <= png.size && pos < end) {
            val len = getInt(png, pos)
            if (len < 0 || pos + 12 + len > png.size) return null
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            if (type == "iTXt") {
                extract(png, pos + 8, len)?.let { return it }
            }
            if (type == "IEND") return null
            pos += 12 + len
        }
        return null
    }

    private fun extract(png: ByteArray, at: Int, len: Int): String? {
        var p = at
        val stop = at + len
        while (p < stop && png[p] != 0.toByte()) p++
        if (p >= stop) return null
        if (String(png, at, p - at, Charsets.US_ASCII) != KEYWORD) return null
        // keyword NUL, compression flag, method, lang NUL, translated NUL, then UTF-8 text.
        p++ // NUL
        if (p + 1 >= stop || png[p] != 0.toByte()) return null // only uncompressed supported
        p += 2
        while (p < stop && png[p] != 0.toByte()) p++
        if (p >= stop) return null
        p++
        while (p < stop && png[p] != 0.toByte()) p++
        if (p >= stop) return null
        p++
        if (p > stop) return null
        return String(png, p, stop - p, Charsets.UTF_8)
    }

    private fun isPng(png: ByteArray): Boolean {
        if (png.size < 8 + 12 + 4) return false
        val sig = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (!png.copyOf(8).contentEquals(sig)) return false
        // First chunk must be IHDR so the splice point below is valid.
        return String(png, 12, 4, Charsets.US_ASCII) == "IHDR"
    }

    private fun getInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    // --- badge bookkeeping (renderer + hit test share this geometry) ---

    private data class Entry(val mtime: Long, val spec: Boolean)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > 128
    }

    /** Whether the image file carries an animation spec (cached by path + mtime). */
    @Synchronized
    fun hasSpec(path: String): Boolean {
        val mtime = runCatching { File(path).lastModified() }.getOrDefault(0L)
        cache[path]?.takeIf { it.mtime == mtime }?.let { return it.spec }
        val spec = readSpec(File(path)) != null
        cache[path] = Entry(mtime, spec)
        return spec
    }

    /**
     * Badge circle (centre x/y + radius, in the image's own content units) for a destination of
     * [w]×[h] units drawn at [pxPerUnit] device pixels per unit. Bottom-right corner, at least
     * 16 device pixels in radius so it stays tappable when zoomed out.
     */
    fun badge(destW: Double, destH: Double, pxPerUnit: Double): Triple<Double, Double, Double> {
        val r = max(0.10 * min(destW, destH), 16.0 / pxPerUnit.coerceAtLeast(1e-9))
        return Triple(destW - r * 1.4, destH - r * 1.4, r)
    }

    fun hitsBadge(destW: Double, destH: Double, pxPerUnit: Double, x: Double, y: Double): Boolean {
        val (bx, by, r) = badge(destW, destH, pxPerUnit)
        val dx = x - bx; val dy = y - by
        val touch = r + 6.0 / pxPerUnit.coerceAtLeast(1e-9)
        return dx * dx + dy * dy <= touch * touch
    }
}
