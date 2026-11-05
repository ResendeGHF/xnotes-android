package com.xnotes.core.stroke

import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.withLock
import kotlin.math.floor
import kotlin.random.Random

/**
 * The pencil's graphite field, shared by every renderer: one fixed-seed
 * random field (fine grains over a shallow bias with medium clumps, plus
 * sparse pressed fibres), thresholded per density into stipple tiles.
 *
 * Pure Kotlin with a pinned seed, so Android, the shared paged path and the
 * Linux companion generate the identical field: the same stroke wears the
 * same tooth everywhere. Pixel storage stays on the platform side (Android
 * Bitmaps, Qt QImages); this file only produces the threshold decisions.
 */
object PencilField {

    /** Tile edge, in tex px. Power of two so GPUs can mipmap it. */
    const val SIZE_PX = 256

    /** Content px one tile spans: fine grains at any zoom. */
    const val TILE_CONTENT_PX = 40.0

    /** Density quantization steps (matches the paged renderers' tile cache). */
    const val DENSITY_STEPS = 20

    fun densityKey(density: Double): Int =
        (density.coerceIn(0.0, 1.0) * DENSITY_STEPS).toInt().coerceIn(0, DENSITY_STEPS)

    /**
     * Stipple mask for graphite [density] in `[0, 1]`: true where the field
     * runs below the density — those pixels carry opaque graphite.
     * Row-major, [SIZE_PX]² entries.
     */
    fun stippleMask(density: Double): BooleanArray {
        val f = field()
        val d = densityKey(density) / DENSITY_STEPS.toDouble()
        return BooleanArray(SIZE_PX * SIZE_PX) { i -> f[i] < d }
    }

    /**
     * The raw field in `[0, 1)`, row-major. Fixed seed: every process
     * generates the identical field.
     */
    fun field(): FloatArray {
        fieldPixels?.let { return it }
        return withLock(fieldLock) {
            fieldPixels?.let { return@withLock it }
            val n = SIZE_PX
            val rnd = Random(0x5EEDBEEFL)
            val lat = DoubleArray(8 * 8) { rnd.nextDouble() }
            val mid = DoubleArray(64 * 64) { rnd.nextDouble() }
            val out = FloatArray(n * n)
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val bias = vnoise(lat, 8, x / n.toDouble() * 8, y / n.toDouble() * 8)
                    val clump = vnoise(mid, 64, x / n.toDouble() * 64, y / n.toDouble() * 64)
                    out[y * n + x] = (rnd.nextDouble() * 0.68 + bias * 0.12 + clump * 0.20).toFloat()
                }
            }
            repeat(48) {
                var fx = rnd.nextDouble() * n
                var fy = rnd.nextDouble() * n
                val ang = rnd.nextDouble() * kotlin.math.PI * 2
                val len = 5 + rnd.nextDouble() * 14
                val dx = kotlin.math.cos(ang)
                val dy = kotlin.math.sin(ang) * 0.6
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
            out
        }
    }

    private var fieldPixels: FloatArray? = null
    private val fieldLock = Lock()

    /** Wrapped value noise over a lattice, output in `[0, 1]`. */
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
}
