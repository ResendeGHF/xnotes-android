package com.xnotes.core.stroke

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.random.Random

/**
 * The pencil's graphite as opaque stipple from one shared random field, in
 * the spirit of Krita's textured brush tips — not a procedural field, and
 * never translucent wash.
 *
 * Every graphite pixel is fully opaque ink or bare paper: tone and pressure
 * answer how DENSE the grains are, never how transparent. Both GL (sampling
 * the field texture and thresholding in-shader) and the paged renderer (one
 * pre-thresholded tile per density level) draw from the same field, so live
 * and committed strokes wear statistically identical tooth. Thresholds nest —
 * pressing harder ADDS grains instead of reshuffling them — and overlaps are
 * last-wins by construction, so folds, caps, seams and tails can never darken.
 *
 * Tile lookups are quantized ([tileForDensity]) and cached; the field texture
 * upload happens once on the GL side.
 */
object PencilTexture {

    /** Tile edge, in tex px. Power of two so GL can mipmap it. */
    const val SIZE_PX = 256

    /** Content px one tile spans: fine grains at any zoom. */
    const val TILE_CONTENT_PX = 40.0

    @Volatile
    private var field: Bitmap? = null

    private val fieldLock = Any()

    /** Raw field pixels in `[0, 1)`, row-major, generated once. */
    private var fieldPixels: FloatArray? = null

    /** Density tile cache: quantized density -> white bitmap with binary alpha. */
    private val tiles = HashMap<Int, Bitmap>()

    /** Density tile shader cache, for the paged renderer. */
    private val shaders = HashMap<Int, android.graphics.BitmapShader>()

    /**
     * The stipple tile for graphite [density] as a repeating shader. Same
     * quantization and cache as [tileForDensity].
     */
    fun tileShader(density: Double): android.graphics.BitmapShader {
        val key = densityKey(density)
        synchronized(fieldLock) {
            shaders[key]?.let { return it }
            val sh = android.graphics.BitmapShader(
                tileForDensity(density),
                android.graphics.Shader.TileMode.REPEAT,
                android.graphics.Shader.TileMode.REPEAT,
            )
            shaders[key] = sh
            return sh
        }
    }

    private fun densityKey(density: Double): Int =
        (density.coerceIn(0.0, 1.0) * 20.0).toInt().coerceIn(0, 20)

    /** The shared field as a GL-ready bitmap (alpha carries the field). */
    fun fieldBitmap(): Bitmap {
        field?.let { return it }
        synchronized(fieldLock) {
            field?.let { return it }
            val px = field()
            val argb = IntArray(px.size)
            for (i in px.indices) {
                argb[i] = ((px[i] * 255.0).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
            }
            val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            bmp.setPixels(argb, 0, SIZE_PX, 0, 0, SIZE_PX, SIZE_PX)
            field = bmp
            return bmp
        }
    }

    /**
     * The stipple tile for graphite [density] in `[0, 1]`: white pixels where
     * the field runs below the density, transparent elsewhere. Quantized to
     * 0.05 steps and cached, so a stroke's handful of bands share bitmaps.
     */
    fun tileForDensity(density: Double): Bitmap {
        val key = densityKey(density)
        synchronized(fieldLock) {
            tiles[key]?.let { return it }
            val px = stipplePixels(density)
            val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, SIZE_PX, 0, 0, SIZE_PX, SIZE_PX)
            tiles[key] = bmp
            return bmp
        }
    }

    /**
     * Raw stipple pixels for [density], row-major white-or-transparent ints.
     * Pure Kotlin with no Android calls, so unit tests pin the thresholds
     * directly.
     */
    fun stipplePixels(density: Double): IntArray {
        val f = field()
        val d = densityKey(density) / 20.0
        return IntArray(SIZE_PX * SIZE_PX) { i ->
            if (f[i] < d) 0xFFFFFFFF.toInt() else 0x00000000
        }
    }

    /**
     * The raw field in `[0, 1)`, row-major. Mostly uniform random (the fine
     * grains) over a shallow bias with medium clumps, so graphite pools
     * slightly the way real pigment does. Fixed seed: every process
     * generates the identical field. Pure Kotlin, unit-testable.
     */
    fun field(): FloatArray {
        fieldPixels?.let { return it }
        synchronized(fieldLock) {
            fieldPixels?.let { return it }
            val n = SIZE_PX
            val rnd = Random(0x5EEDBEEFL)
            val lat = DoubleArray(8 * 8) { rnd.nextDouble() }
            val mid = DoubleArray(64 * 64) { rnd.nextDouble() }
            val out = FloatArray(n * n)
            for (y in 0 until n) {
                for (x in 0 until n) {
                    // Fine uniform grains over a shallow bias with medium
                    // clumps: graphite pools slightly instead of scattering
                    // perfectly evenly.
                    val bias = vnoise(lat, 8, x / n.toDouble() * 8, y / n.toDouble() * 8)
                    val clump = vnoise(mid, 64, x / n.toDouble() * 64, y / n.toDouble() * 64)
                    out[y * n + x] = (rnd.nextDouble() * 0.68 + bias * 0.12 + clump * 0.20).toFloat()
                }
            }
            // A few short fibres pressed darker: sparse low-field streaks the
            // thresholds turn into dash-like grains.
            repeat(48) {
                var fx = rnd.nextDouble() * n
                var fy = rnd.nextDouble() * n
                val ang = rnd.nextDouble() * Math.PI * 2
                val len = 5 + rnd.nextDouble() * 14
                val dx = Math.cos(ang)
                val dy = Math.sin(ang) * 0.6
                var t = 0.0
                while (t < len) {
                    val ix = (((floor(fx).toInt()) % n) + n) % n
                    val iy = (((floor(fy).toInt()) % n) + n) % n
                    val i = iy * n + ix
                    out[i] = (out[i] * 0.4).toFloat()
                    fx += dx
                    fy += dy
                    t += 1.0
                }
            }
            fieldPixels = out
            return out
        }
    }

    /** Wrapped value noise over an 8² lattice, output in `[0, 1]`. */
    private fun vnoise(lat: DoubleArray, cells: Int, x: Double, y: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        fun at(ix: Int, iy: Int): Double {
            val wx = ((ix % cells) + cells) % cells
            val wy = ((iy % cells) + cells) % cells
            return lat[wy * cells + wx]
        }
        val v00 = at(x0, y0)
        val v10 = at(x0 + 1, y0)
        val v01 = at(x0, y0 + 1)
        val v11 = at(x0 + 1, y0 + 1)
        return (v00 * (1 - sx) + v10 * sx) * (1 - sy) + (v01 * (1 - sx) + v11 * sx) * sy
    }

    /** Drop cached bitmaps (GL context loss, memory trim); the field survives. */
    fun clearBitmaps() {
        synchronized(fieldLock) {
            field = null
            tiles.clear()
            shaders.clear()
        }
    }
}
