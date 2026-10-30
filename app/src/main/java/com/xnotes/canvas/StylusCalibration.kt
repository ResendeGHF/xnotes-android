package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Mature stylus-offset calibration.
 *
 * A pen whose digitizer reports a constant parallax error puts ink a fixed
 * screen distance from the nib. The correction is a single translation in
 * screen/viewport space (dp), applied to every stylus/eraser sample before the
 * viewport→content transform, so it is zoom- and page-independent.
 *
 * Calibration shows N crosshair targets at known viewport positions [Target] and
 * records where the digitizer said the pen touched ([reported]). Each tap gives
 * an offset sample `target − reported`; the estimate is the robust mean after
 * dropping rushed/mis-tapped outliers, plus the residual spread used to grade
 * the run (excellent → poor, retry when poor).
 */
object StylusCalibrator {

    /** One crosshair tap: where the target was and where the pen reported, same units (px). */
    data class Tap(val targetX: Double, val targetY: Double, val reportedX: Double, val reportedY: Double)

    enum class Quality { EXCELLENT, GOOD, FAIR, POOR }

    data class Result(
        /** Correction in dp: add to every future stylus sample (after × density). */
        val offsetXDp: Double,
        val offsetYDp: Double,
        /** RMS residual of the kept taps around the estimate, in dp. */
        val spreadDp: Double,
        val kept: Int,
        val total: Int,
        val quality: Quality,
    )

    /** Largest correction the UI and storage accept, per axis, in dp. Covers ~4 mm of parallax. */
    const val MAX_OFFSET_DP = 20.0

    /** Manual fine-tune range shown in Settings, per axis, in dp. */
    const val TUNE_RANGE_DP = 10.0

    /** Maximum taps the guided run collects (3×3 grid). */
    const val TARGET_COUNT = 9

    /** Minimum taps needed for an estimate. */
    const val MIN_TAPS = 3

    /**
     * Crosshair centres for a guided run, in viewport px. A 3×3 grid pulled
     * [insetFraction] in from each edge so corner targets stay reachable, with
     * the centre first (a stable anchor) then the edges, corners last.
     */
    fun targetsFor(widthPx: Double, heightPx: Double, insetFraction: Double = 0.14): List<Pt> {
        if (widthPx <= 0 || heightPx <= 0) return emptyList()
        val ix = widthPx * insetFraction.coerceIn(0.05, 0.4)
        val iy = heightPx * insetFraction.coerceIn(0.05, 0.4)
        val xs = listOf(ix, widthPx / 2.0, widthPx - ix)
        val ys = listOf(iy, heightPx / 2.0, heightPx - iy)
        // Centre first, then edge centres, then corners: a rushed corner mis-tap
        // reads as an outlier against the stable centre/edge majority.
        return listOf(
            Pt(xs[1], ys[1]),
            Pt(xs[1], ys[0]), Pt(xs[1], ys[2]), Pt(xs[0], ys[1]), Pt(xs[2], ys[1]),
            Pt(xs[0], ys[0]), Pt(xs[2], ys[0]), Pt(xs[0], ys[2]), Pt(xs[2], ys[2]),
        )
    }

    /**
     * Robust mean of `target − reported`, converted to dp.
     *
     * Outliers: with ≥4 taps the median offset is the robust centre; taps
     * farther than [outlierPx] from it are dropped (a mis-tap), then the mean
     * of the kept taps is the estimate. When a majority would be dropped the
     * run is too inconsistent to trim, so everything is kept and the spread
     * grades it POOR. [density] is px-per-dp; [outlierPx] defaults to ~2 mm.
     */
    fun estimate(taps: List<Tap>, density: Double, outlierPx: Double = 28.0): Result? {
        if (taps.size < MIN_TAPS) return null
        val d = density.takeIf { it.isFinite() && it > 0 } ?: 1.0
        val dxs = taps.map { it.targetX - it.reportedX }
        val dys = taps.map { it.targetY - it.reportedY }
        val keptIdx: List<Int> = if (taps.size >= 4) {
            val mx = median(dxs)
            val my = median(dys)
            val inliers = taps.indices.filter {
                hypot(dxs[it] - mx, dys[it] - my) <= outlierPx
            }
            // Keep the trim only when a real majority agrees; otherwise the whole
            // run is suspect and trimming would fake a good spread.
            if (inliers.size >= MIN_TAPS && inliers.size * 2 >= taps.size) inliers else taps.indices.toList()
        } else {
            taps.indices.toList()
        }
        val ox = keptIdx.sumOf { dxs[it] } / keptIdx.size
        val oy = keptIdx.sumOf { dys[it] } / keptIdx.size
        var se = 0.0
        for (i in keptIdx) {
            val rx = dxs[i] - ox
            val ry = dys[i] - oy
            se += rx * rx + ry * ry
        }
        val spreadPx = sqrt(se / keptIdx.size)
        val spreadDp = spreadPx / d
        val quality = when {
            spreadDp <= 0.6 -> Quality.EXCELLENT
            spreadDp <= 1.2 -> Quality.GOOD
            spreadDp <= 2.5 -> Quality.FAIR
            else -> Quality.POOR
        }
        return Result(
            offsetXDp = (ox / d).coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
            offsetYDp = (oy / d).coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP),
            spreadDp = spreadDp,
            kept = keptIdx.size,
            total = taps.size,
            quality = quality,
        )
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
