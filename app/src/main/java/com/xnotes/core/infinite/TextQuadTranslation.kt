package com.xnotes.core.infinite

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On a side-handle resize, move the cached text to the box's new anchor without scaling
 * the glyph bitmap. A worker can update line breaks without scaling the texture.
 */
internal fun fixedSizeTextLift(origin: Pt, boxTransform: Affine): LiftTransform {
    val next = boxTransform.apply(origin)
    return LiftTransform.shift(next.x - origin.x, next.y - origin.y)
}

/**
 * For centred text, changing width without changing any line break only translates the glyphs
 * relative to the box centre. Its existing texture is still valid.
 */
internal fun textWrapChanged(previousEnds: IntArray?, nextEnds: IntArray): Boolean =
    previousEnds == null || !previousEnds.contentEquals(nextEnds)

/**
 * A native text bitmap can be reused only if the layout quad was translated rigidly.
 * Resizing, turning or shearing changes the glyph mapping and must request a new raster.
 */
internal fun isRigidTextQuadTranslation(previous: DoubleArray, next: DoubleArray): Boolean {
    if (previous.size != 8 || next.size != 8) return false
    val dx = next[0] - previous[0]
    val dy = next[1] - previous[1]
    if (!dx.isFinite() || !dy.isFinite()) return false
    for (i in 0 until 8 step 2) {
        if (abs((next[i] - previous[i]) - dx) > 1e-6 ||
            abs((next[i + 1] - previous[i + 1]) - dy) > 1e-6) return false
    }
    return true
}

/** A rotation or translation changes only the placement of a text quad, never its glyph layout.
 *  Compare its two edge lengths and their mutual angle; scaling/shearing must rerasterize. */
internal fun isRigidTextQuadMotion(previous: DoubleArray, next: DoubleArray): Boolean {
    if (previous.size != 8 || next.size != 8 ||
        previous.any { !it.isFinite() } || next.any { !it.isFinite() }) return false
    fun near(a: Double, b: Double): Boolean =
        abs(a - b) <= 1e-6 * max(1.0, max(abs(a), abs(b)))
    fun frame(c: DoubleArray): DoubleArray {
        val ux = c[2] - c[0]
        val uy = c[3] - c[1]
        val vx = c[4] - c[0]
        val vy = c[5] - c[1]
        return doubleArrayOf(ux * ux + uy * uy, vx * vx + vy * vy,
            ux * vx + uy * vy, ux * vy - uy * vx)
    }
    fun isParallelogram(c: DoubleArray): Boolean =
        near(c[6], c[2] + c[4] - c[0]) && near(c[7], c[3] + c[5] - c[1])
    val before = frame(previous)
    val after = frame(next)
    return isParallelogram(previous) && isParallelogram(next) &&
        before.indices.all { near(before[it], after[it]) }
}

/** Keep narrow or tall text legible: its texture can be skinny *and* thousands of pixels long.
 *  The previous hard 1024/2048-height limits squashed a 5324px label to 19%/38% resolution.
 *  Bound total bytes, respect the device's actual GL texture limit, and give unusually narrow
 *  previews a larger (still bounded) allowance so they are not upscaled fivefold. */
internal fun textBitmapScale(
    width: Double,
    height: Double,
    glMaxSide: Double,
    preview: Boolean,
): Double {
    if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return 1.0
    val longSide = max(width, height)
    val shortSide = min(width, height)
    val extremeAspect = longSide > 2048.0 && longSide > shortSide * 8.0
    val budgetPixels = if (!preview) 2_000_000.0
        else if (extremeAspect) 1_000_000.0 else 350_000.0
    val edge = min(8192.0, glMaxSide.coerceAtLeast(2048.0))
    return minOf(1.0, edge / width, edge / height,
        sqrt(budgetPixels / (width * height)))
}
