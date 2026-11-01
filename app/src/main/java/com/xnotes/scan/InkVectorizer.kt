package com.xnotes.scan

import com.xnotes.core.geometry.Pt
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Scanned handwriting (a photo/scan of paper, or a PDF page render) turned into
 * editable strokes, with no extra native dependency.
 *
 * Pipeline (all on plain arrays, so it unit-tests on the JVM):
 *  1. [autoEnhance] (optional, on by default) — flatten uneven lighting with a
 *     wide box blur, then stretch the 2nd..98th percentile to full range, so
 *     phone photos with shadows and faint pencil trace like flatbed scans.
 *  2. Threshold — Sauvola local thresholding by default ([sauvolaBinarize]),
 *     which adapts to remaining light gradients; a manual global level, or Otsu
 *     for light-on-dark sources, when asked.
 *  3. [despeckle] — drop ink blobs smaller than [TraceOptions.denoisePx].
 *  4. [removeRulings] (optional, on by default) — erase full-span ruled-paper
 *     lines plus broken line fragments on detected ruling bands, which would
 *     otherwise cross every text stroke and shatter it at the junctions;
 *     short underlines, fraction bars and diagram axes are left alone.
 *  5. [closeMask] (optional, on by default; off for printed text) — 3×3
 *     closing bridges 1px binarization gaps so letters thin to one connected
 *     centreline. Skipped for print, where the same welding would fuse
 *     neighbouring glyphs into unreadable scribbles.
 *  6. [distanceTransform] — chamfer distance to the background: the local stroke
 *     radius, later mapped to per-sample pressure so the ink keeps its weight.
 *  7. [thin] — Zhang-Suen skeletonization down to 1px centrelines.
 *  8. [pruneSpurs] (optional, on by default) — trim short terminal side
 *     branches (edge-noise spurs) so they never become stub strokes.
 *  9. [trace] — walk the skeleton into ordered polylines. Junctions are found
 *     topologically (separate 8-neighbourhood arms, not raw neighbour counts),
 *     so staircase pixels on curves read as through-pixels and handwriting
 *     comes out as long continuous strokes instead of 2–3px fragments.
 *  10. [simplify] — Ramer-Douglas-Peucker per polyline plus short-path filtering.
 *
 * Coordinates throughout are in working-image pixels; callers scale them onto
 * the note page (see [TraceDocument]).
 */
object InkVectorizer {

    /** Tunables for one trace run; the UI exposes the marked ones. */
    data class TraceOptions(
        /** Manual global threshold 0..255, or null for auto. UI. */
        val threshold: Int? = null,
        /**
         * Auto mode uses Sauvola local thresholding (adapts to shadows and
         * vignetting); false falls back to a global Otsu level. Ignored unless
         * [threshold] is null, and forced off for [invert] sources, where the
         * dark background has no usable local statistics.
         */
        val adaptive: Boolean = true,
        /** Light ink on a dark source (whiteboard photo negative). UI. */
        val invert: Boolean = false,
        /**
         * Grow the strong ink into faint connected strokes (hysteresis): a looser
         * weak level keeps pale letter parts that touch solid ink, while isolated
         * faint noise still drops. The text-friendly default.
         */
        val hysteresis: Boolean = true,
        /** How much looser the weak level is than the strong one, in 0..255 steps. */
        val weakExtra: Int = 18,
        /** Drop ink blobs smaller than this many pixels. UI (0 = off). */
        val denoisePx: Int = 4,
        /**
         * Printed (typeset) text instead of handwriting. UI. Only the gap
         * closing changes: handwriting needs [closeMask] to bridge faint pen
         * lifts, but on print the same closing welds neighbouring glyphs into
         * unreadable scribbles, so it is skipped and each letter traces
         * separately. Hysteresis, ruling removal and spur pruning stay on.
         */
        val printedText: Boolean = false,
        /**
         * Erase ruled-paper lines before thinning: ink runs spanning at least
         * this fraction of the page width (or height) with a cross-thickness of
         * at most [rulingMaxThick] px are paper, not handwriting, as are broken
         * line fragments on detected ruling bands. Short underlines, fraction
         * bars and diagram axes survive; full-bleed notebook lines do not. UI.
         * When false, every long line is kept as ink.
         */
        val removeRulings: Boolean = true,
        /** Span fraction (of page width/height) a straight run needs to count as ruling. */
        val rulingMinSpan: Double = 0.5,
        /** Thickest (px) a ruling line may be across its direction. */
        val rulingMaxThick: Int = 4,
        /** Bridge 1px binarization gaps with a 3×3 closing before thinning. */
        val closeGaps: Boolean = true,
        /**
         * Trim terminal skeleton side-branches (edge-noise spurs) up to this
         * long, in working px. Only endpoint→junction branches are cut — dots,
         * accents and isolated dashes never end at a junction, so they survive.
         * 0 disables.
         */
        val spurPrunePx: Int = 8,
        /** RDP simplification tolerance, in working px. */
        val simplifyEps: Double = 0.8,
        /** Drop paths shorter than this, in working px (dots are always kept). */
        val minPathLen: Double = 3.0,
        /** Keep at most this many paths (dots first, then longest first). */
        val maxStrokes: Int = 12000,
        /**
         * Hard budget on traced samples across the page (dots first, then longest
         * paths). Bounds note size and render cost on pathological inputs, so a
         * noisy photo can never produce a note that crashes the app to open.
         * Sized for dense printed pages (thousands of small glyphs).
         */
        val maxTotalSamples: Int = 150_000,
        /**
         * Bail out (no ink) when the strong mask covers more than this fraction
         * of the page: the threshold is certainly wrong (a photo of a dark desk,
         * a maxed-out slider), and thinning a near-solid block would hang.
         */
        val maxInkFraction: Double = 0.5,
    )

    /**
     * A traced path in working-image pixels. [pressures] parallels [points]
     * (0..1, from the local stroke radius); single-point paths are dots.
     */
    data class InkPath(
        val points: List<Pt>,
        val pressures: List<Double> = emptyList(),
        val lengthPx: Double,
    )

    // --- photometric auto treatment ---

    /**
     * Separable sliding-window box blur of [src] with radius [r] (edge-extended).
     * O(w*h) time, two float buffers; the workhorse behind both the background
     * estimate and the Sauvola statistics.
     */
    fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r <= 0) return src.copyOf()
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val win = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var acc = 0.0
            for (x in -r..r) acc += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = (acc / win).toFloat()
                acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0.0
            for (y in -r..r) acc += tmp[x + y.coerceIn(0, h - 1) * w]
            for (y in 0 until h) {
                out[y * w + x] = (acc / win).toFloat()
                acc += tmp[x + (y + r + 1).coerceAtMost(h - 1) * w] - tmp[x + (y - r).coerceAtLeast(0) * w]
            }
        }
        return out
    }

    /**
     * Level uneven lighting and boost faint ink: subtract a wide-blur background
     * estimate (re-centred on mid-grey), then stretch the 2nd..98th percentiles
     * to the full range. Returns new 0..255 luminance. Nearly-flat inputs skip
     * the stretch and come back only levelled.
     */
    fun autoEnhance(lum: IntArray, w: Int, h: Int): IntArray {
        require(lum.size == w * h) { "luminance size ${lum.size} != ${w}x$h" }
        val f = FloatArray(lum.size) { lum[it].toFloat() }
        val bg = boxBlur(f, w, h, (minOf(w, h) / 8).coerceIn(16, 120))
        val flat = FloatArray(f.size) { (f[it] - bg[it] + 128f).coerceIn(0f, 255f) }
        val hist = IntArray(256)
        var sampled = 0
        var i = 0
        while (i < flat.size) {
            hist[flat[i].toInt().coerceIn(0, 255)]++
            sampled++
            i += 4
        }
        val loTarget = (sampled * 0.02).toInt()
        val hiTarget = sampled - (sampled * 0.02).toInt()
        var acc = 0
        var lo = 0
        while (lo < 255) {
            acc += hist[lo]
            if (acc >= loTarget) break
            lo++
        }
        acc = 0
        var hi = 255
        while (hi > 0) {
            acc += hist[hi]
            if (acc >= sampled - hiTarget + 1) break
            hi--
        }
        if (hi - lo < 8) return IntArray(flat.size) { flat[it].toInt() }
        val gain = 255.0 / (hi - lo)
        return IntArray(flat.size) { ((flat[it] - lo) * gain).toInt().coerceIn(0, 255) }
    }

    /** Sauvola gain: lower keeps faint pencil, higher rejects paper texture. */
    const val SAUVOLA_K = 0.15

    /** Normaliser for the Sauvola standard-deviation term. */
    const val SAUVOLA_R = 128.0

    /** Local window radius for Sauvola: a few handwriting line-heights across. */
    fun sauvolaWindow(w: Int, h: Int): Int = (minOf(w, h) / 36).coerceIn(10, 45)

    /**
     * Sauvola local thresholding: each pixel's level follows its neighbourhood's
     * mean and spread, so a shadowed corner and a bright corner trace alike.
     * Dark-on-light only; light-on-dark sources use a global level instead.
     * A smaller [k] is looser (keeps fainter marks); [SAUVOLA_K] is the strong
     * level, a fraction of it the hysteresis weak level.
     */
    fun sauvolaBinarize(lum: IntArray, w: Int, h: Int, k: Double = SAUVOLA_K): BooleanArray {
        require(lum.size == w * h) { "luminance size ${lum.size} != ${w}x$h" }
        val r = sauvolaWindow(w, h)
        val f = FloatArray(lum.size) { lum[it].toFloat() }
        val mean = boxBlur(f, w, h, r)
        for (i in f.indices) f[i] = f[i] * f[i]
        val meanSq = boxBlur(f, w, h, r)
        val out = BooleanArray(lum.size)
        for (i in lum.indices) {
            val m = mean[i].toDouble()
            val v = maxOf(meanSq[i].toDouble() - m * m, 0.0)
            out[i] = lum[i] < m * (1.0 + k * (sqrt(v) / SAUVOLA_R - 1.0))
        }
        return out
    }

    /**
     * Hysteresis growth: every weak pixel connected to a strong pixel becomes
     * ink. Faint letter parts that touch solid strokes survive; isolated faint
     * noise does not. The seed loop is O(seeds), the flood O(kept pixels).
     */
    fun growFromSeeds(strong: BooleanArray, weak: BooleanArray, w: Int, h: Int): BooleanArray {
        require(strong.size == w * h && weak.size == w * h)
        val out = strong.copyOf()
        val queue = ArrayDeque<Int>()
        for (i in strong.indices) if (strong[i]) queue.add(i)
        while (queue.isNotEmpty()) {
            val p = queue.removeLast()
            val x = p % w
            val y = p / w
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    if (nx < 0 || nx >= w) continue
                    val q = ny * w + nx
                    if (!out[q] && weak[q]) {
                        out[q] = true
                        queue.add(q)
                    }
                }
            }
        }
        return out
    }

    // --- threshold ---

    /**
     * Threshold [lum] for [options]: the manual global level, Sauvola local
     * thresholding for auto (dark-on-light), or a global Otsu level for auto on
     * light-on-dark sources. With [TraceOptions.hysteresis] the strong mask
     * grows into faint connected strokes through a looser weak level, which is
     * what keeps pale letter parts. The first stage of [inkMask], which the
     * preview and [vectorize] share so the black-on-white on screen is exactly
     * what gets traced.
     */
    fun applyThreshold(lum: IntArray, w: Int, h: Int, options: TraceOptions): BooleanArray {
        val strong = when {
            options.threshold != null -> binarize(lum, options.threshold, options.invert)
            options.adaptive && !options.invert -> sauvolaBinarize(lum, w, h)
            else -> binarize(lum, otsuThreshold(lum).coerceIn(1, 254), options.invert)
        }
        if (!options.hysteresis) return strong
        val weak = when {
            options.threshold != null -> {
                val t = (options.threshold + if (options.invert) -options.weakExtra else options.weakExtra)
                    .coerceIn(1, 254)
                binarize(lum, t, options.invert)
            }
            options.adaptive && !options.invert -> sauvolaBinarize(lum, w, h, SAUVOLA_K * 0.4)
            else -> {
                val t = (otsuThreshold(lum) + if (options.invert) -options.weakExtra else options.weakExtra)
                    .coerceIn(1, 254)
                binarize(lum, t, options.invert)
            }
        }
        return growFromSeeds(strong, weak, w, h)
    }

    /** Otsu's threshold for [lum] (0..255 per pixel): the level splitting the
     *  histogram into two classes with maximal between-class variance. On a
     *  plateau of equally good levels (two sharp peaks), returns its middle. */
    fun otsuThreshold(lum: IntArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0, 255)]++
        val total = lum.size.toDouble()
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0.0
        var best = -1.0
        var first = 127
        var last = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                first = t
                last = t
            } else if (between == best) {
                last = t
            }
        }
        return (first + last) / 2
    }

    /** Luminance threshold: true = ink. Dark-on-light by default, flipped by [invert]. */
    fun binarize(lum: IntArray, threshold: Int, invert: Boolean): BooleanArray {
        val out = BooleanArray(lum.size)
        if (!invert) {
            for (i in lum.indices) out[i] = lum[i] < threshold
        } else {
            for (i in lum.indices) out[i] = lum[i] > threshold
        }
        return out
    }

    // --- denoise ---

    /**
     * Drop 8-connected ink blobs with fewer than [minSize] pixels (sensor dust,
     * JPEG speckle, paper grain). Returns a new array.
     */
    fun despeckle(ink: BooleanArray, w: Int, h: Int, minSize: Int): BooleanArray {
        if (minSize <= 1) return ink
        val out = ink.copyOf()
        val seen = BooleanArray(ink.size)
        val stack = IntArray(4096)
        val pixels = ArrayList<Int>(256)
        for (s in ink.indices) {
            if (!out[s] || seen[s]) continue
            pixels.clear()
            var top = 0
            stack[top++] = s
            seen[s] = true
            // A blob bigger than the record (or the stack) is real ink: keep it whole.
            var big = false
            while (top > 0) {
                val p = stack[--top]
                if (!big) {
                    pixels.add(p)
                    if (pixels.size > 65536) big = true
                }
                if (big) continue // drain: everything reachable is already marked seen
                val x = p % w
                val y = p / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val q = ny * w + nx
                        if (out[q] && !seen[q]) {
                            seen[q] = true
                            if (top < stack.size) stack[top++] = q
                            else big = true
                        }
                    }
                }
            }
            if (!big && pixels.size < minSize) {
                for (p in pixels) out[p] = false
            }
        }
        return out
    }

    // --- ruling lines + gap closing ---

    /**
     * Erase ruled-paper lines: horizontal ink runs spanning at least
     * [spanFrac] of the page width (and vertical runs spanning the height)
     * whose cross-thickness is at most [maxThick] px. Pixels where the ink is
     * taller (horizontal lines) or wider (vertical lines) than [maxThick] are
     * text crossing the line, and are kept; a later [closeMask] bridges the
     * 1px notches this leaves in descenders. Short underlines and axes never
     * reach the span, so they survive.
     *
     * A second pass catches *broken* line fragments: rows whose long
     * (≥ 20px) runs cover ≥ 30% of the width form ruling bands (evenly spaced
     * ones on notebook paper), and thin run pixels inside those bands are
     * erased. Fragments of ruled lines that touch text stay connected to it
     * and are kept as text — only isolated dashes vanish — and anything
     * between the bands (fraction bars, minus signs, underlines drawn between
     * the lines) is never touched. Returns a new array.
     */
    fun removeRulings(
        ink: BooleanArray,
        w: Int,
        h: Int,
        spanFrac: Double = 0.5,
        maxThick: Int = 4,
    ): BooleanArray {
        if (w < 8 || h < 8) return ink
        val out = ink.copyOf()
        val thick = maxThick.coerceAtLeast(1)
        val minHSpan = (w * spanFrac).toInt().coerceAtLeast(8)
        val minVSpan = (h * spanFrac).toInt().coerceAtLeast(8)
        // Vertical run length at each ink pixel (for the horizontal-line test).
        val vert = IntArray(ink.size)
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!ink[y * w + x]) {
                    y++
                    continue
                }
                var y2 = y
                while (y2 < h && ink[y2 * w + x]) y2++
                for (yy in y until y2) vert[yy * w + x] = y2 - y
                y = y2
            }
        }
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!ink[y * w + x]) {
                    x++
                    continue
                }
                var x2 = x
                while (x2 < w && ink[y * w + x2]) x2++
                if (x2 - x >= minHSpan) {
                    for (xx in x until x2) {
                        if (vert[y * w + xx] <= thick) out[y * w + xx] = false
                    }
                }
                x = x2
            }
        }
        // Horizontal run length at each ink pixel (for the vertical-line test).
        val horiz = IntArray(ink.size)
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!ink[y * w + x]) {
                    x++
                    continue
                }
                var x2 = x
                while (x2 < w && ink[y * w + x2]) x2++
                for (xx in x until x2) horiz[y * w + xx] = x2 - x
                x = x2
            }
        }
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!ink[y * w + x]) {
                    y++
                    continue
                }
                var y2 = y
                while (y2 < h && ink[y2 * w + x]) y2++
                if (y2 - y >= minVSpan) {
                    for (yy in y until y2) {
                        if (horiz[yy * w + x] <= thick) out[yy * w + x] = false
                    }
                }
                y = y2
            }
        }
        // Broken-line pass: ruling bands are rows whose long runs cover much
        // of the width (faint scans break lines into short dashes that the
        // full-span pass above cannot see). Thin run pixels inside those bands
        // go; text crossings (tall runs) stay, as does everything between bands.
        val inBand = BooleanArray(h)
        var y = 0
        while (y < h) {
            var cover = 0
            var x = 0
            while (x < w) {
                if (!ink[y * w + x]) {
                    x++
                    continue
                }
                var x2 = x
                while (x2 < w && ink[y * w + x2]) x2++
                if (x2 - x >= 20) cover += x2 - x
                x = x2
            }
            if (cover * 1.0 / w >= 0.30) {
                var y2 = y
                // The band lasts while rows stay covered (recomputed per row).
                while (y2 < h) {
                    var c2 = 0
                    var xx = 0
                    while (xx < w) {
                        if (!ink[y2 * w + xx]) {
                            xx++
                            continue
                        }
                        var e2 = xx
                        while (e2 < w && ink[y2 * w + e2]) e2++
                        if (e2 - xx >= 20) c2 += e2 - xx
                        xx = e2
                    }
                    if (c2 * 1.0 / w < 0.30 || y2 - y >= 9) break
                    y2++
                }
                for (yy in y until y2) inBand[yy] = true
                y = y2
            } else {
                y++
            }
        }
        for (yy in 0 until h) {
            if (!inBand[yy]) continue
            var x = 0
            while (x < w) {
                if (!ink[yy * w + x]) {
                    x++
                    continue
                }
                var x2 = x
                while (x2 < w && ink[yy * w + x2]) x2++
                if (x2 - x >= 20) {
                    for (xx in x until x2) {
                        if (vert[yy * w + xx] <= thick) out[yy * w + xx] = false
                    }
                }
                x = x2
            }
        }
        return out
    }

    /**
     * 3×3 binary closing (dilation then erosion): bridges 1px binarization gaps
     * along faint strokes so letters thin to one connected centreline. Returns
     * a new array. Out-of-bounds neighbours count as background (the page edge
     * never grows ink, and erosion clears the 1px border), so every index is
     * bounds-checked.
     */
    fun closeMask(ink: BooleanArray, w: Int, h: Int): BooleanArray {
        val dil = BooleanArray(ink.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!ink[y * w + x]) continue
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        dil[ny * w + nx] = true
                    }
                }
            }
        }
        val out = BooleanArray(ink.size) { true }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var all = true
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) {
                        all = false
                        break
                    }
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w || !dil[ny * w + nx]) {
                            all = false
                            break
                        }
                    }
                    if (!all) break
                }
                out[y * w + x] = all
            }
        }
        return out
    }

    /**
     * The final ink mask [vectorize] thins: threshold → denoise → optional
     * ruling removal → optional gap closing (skipped for
     * [TraceOptions.printedText], where closing would weld neighbouring glyphs
     * together). The trace preview renders this, so the black-on-white on
     * screen is exactly what gets traced.
     */
    fun inkMask(lum: IntArray, w: Int, h: Int, options: TraceOptions): BooleanArray {
        val grown = applyThreshold(lum, w, h, options)
        val clean = if (options.denoisePx > 0) despeckle(grown, w, h, options.denoisePx) else grown
        val unruled = if (options.removeRulings) {
            removeRulings(clean, w, h, options.rulingMinSpan, options.rulingMaxThick)
        } else {
            clean
        }
        return if (options.closeGaps && !options.printedText) closeMask(unruled, w, h) else unruled
    }

    // --- stroke radius ---

    /**
     * Chamfer 3-4 distance transform of [ink]: each foreground pixel's approx
     * Euclidean distance to the background, i.e. the local stroke radius in px.
     * The skeleton's radii become per-sample pressures, so the traced ink keeps
     * the weight of the pen that laid it down.
     */
    fun distanceTransform(ink: BooleanArray, w: Int, h: Int): FloatArray {
        val d = FloatArray(w * h) { if (ink[it]) Float.MAX_VALUE else 0f }
        val diag = 1.41421356f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = y * w + x
                var v = d[p]
                if (v == 0f) continue
                if (x > 0) v = minOf(v, d[p - 1] + 1f)
                if (y > 0) {
                    v = minOf(v, d[p - w] + 1f)
                    if (x > 0) v = minOf(v, d[p - w - 1] + diag)
                    if (x < w - 1) v = minOf(v, d[p - w + 1] + diag)
                }
                d[p] = v
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val p = y * w + x
                var v = d[p]
                if (v == 0f) continue
                if (x < w - 1) v = minOf(v, d[p + 1] + 1f)
                if (y < h - 1) {
                    v = minOf(v, d[p + w] + 1f)
                    if (x < w - 1) v = minOf(v, d[p + w + 1] + diag)
                    if (x > 0) v = minOf(v, d[p + w - 1] + diag)
                }
                d[p] = v
            }
        }
        return d
    }

    // --- thinning (Zhang-Suen) ---

    /**
     * Skeletonize [ink] (true = foreground) to 1px centrelines. Returns a new array.
     * Runs at most [maxPasses] two-step passes (each pass deletes border pixels).
     */
    fun thin(ink: BooleanArray, w: Int, h: Int, maxPasses: Int = 100): BooleanArray {
        val cur = ink.copyOf()
        val mark = BooleanArray(cur.size)

        // Zhang-Suen deletion test at [p] for sub-step [step].
        fun killAt(p: Int, step: Int): Boolean {
            // p2..p9 clockwise from north.
            val p2 = if (cur[p - w]) 1 else 0
            val p3 = if (cur[p - w + 1]) 1 else 0
            val p4 = if (cur[p + 1]) 1 else 0
            val p5 = if (cur[p + w + 1]) 1 else 0
            val p6 = if (cur[p + w]) 1 else 0
            val p7 = if (cur[p + w - 1]) 1 else 0
            val p8 = if (cur[p - 1]) 1 else 0
            val p9 = if (cur[p - w - 1]) 1 else 0
            val b = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
            if (b < 2 || b > 6) return false
            // 0->1 transitions in p2,p3,...,p9,p2.
            var a = 0
            if (p2 == 0 && p3 == 1) a++
            if (p3 == 0 && p4 == 1) a++
            if (p4 == 0 && p5 == 1) a++
            if (p5 == 0 && p6 == 1) a++
            if (p6 == 0 && p7 == 1) a++
            if (p7 == 0 && p8 == 1) a++
            if (p8 == 0 && p9 == 1) a++
            if (p9 == 0 && p2 == 1) a++
            if (a != 1) return false
            return if (step == 0) {
                p2 * p4 * p6 == 0 && p4 * p6 * p8 == 0
            } else {
                p2 * p4 * p8 == 0 && p2 * p6 * p8 == 0
            }
        }

        var pass = 0
        while (pass < maxPasses) {
            var changed = false
            for (step in 0..1) {
                mark.fill(false)
                var any = false
                for (y in 1 until h - 1) {
                    val row = y * w
                    for (x in 1 until w - 1) {
                        val p = row + x
                        if (cur[p] && killAt(p, step)) {
                            mark[p] = true
                            any = true
                        }
                    }
                }
                if (any) {
                    for (i in cur.indices) if (mark[i]) cur[i] = false
                    changed = true
                }
            }
            pass++
            if (!changed) break
        }
        return cur
    }

    // --- tracing ---

    private val DX8 = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY8 = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    private fun neighborCount(skel: BooleanArray, w: Int, h: Int, p: Int): Int {
        val x = p % w
        val y = p / w
        var n = 0
        for (k in 0 until 8) {
            val nx = x + DX8[k]
            val ny = y + DY8[k]
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            if (skel[ny * w + nx]) n++
        }
        return n
    }

    /**
     * Topological junction measure: the number of separate ink arms leaving
     * [p], i.e. 0→1 transitions clockwise round its 8-neighbourhood ([DX8]/[DY8]
     * are already circular). A raw neighbour count calls every staircase pixel
     * on a curve a junction (its two steps touch diagonally *and* sideways);
     * arms count them as one continuous curve, so only true branches —
     * crossings, loop joins, real spurs — split a path. On dense handwriting
     * this is ~9× fewer junctions, which is what turns 2–3px fragments back
     * into readable strokes.
     */
    fun skeletonArms(skel: BooleanArray, w: Int, h: Int, p: Int): Int {
        val x = p % w
        val y = p / w
        var prev = 0
        // Clockwise ring, seeded from the last neighbour so the wrap counts.
        run {
            val nx = x + DX8[7]
            val ny = y + DY8[7]
            if (nx in 0 until w && ny in 0 until h && skel[ny * w + nx]) prev = 1
        }
        var arms = 0
        for (k in 0 until 8) {
            val nx = x + DX8[k]
            val ny = y + DY8[k]
            val cur = if (nx in 0 until w && ny in 0 until h && skel[ny * w + nx]) 1 else 0
            if (cur == 1 && prev == 0) arms++
            prev = cur
        }
        return arms
    }

    /**
     * Trim short terminal side-branches (thinning spurs from ragged stroke
     * edges): endpoint→junction walks of at most [maxLen] px are erased, the
     * junction pixel itself kept. Only branches ending at a junction are cut —
     * isolated dots, accents, dashes and pen tips end at endpoints or nowhere,
     * so they always survive. Runs two passes (cutting one spur can expose the
     * next) and returns a new array.
     */
    fun pruneSpurs(skel: BooleanArray, w: Int, h: Int, maxLen: Int): BooleanArray {
        if (maxLen <= 0) return skel
        var cur = skel.copyOf()
        repeat(2) {
            val counts = IntArray(cur.size)
            val arms = IntArray(cur.size)
            for (p in cur.indices) {
                if (!cur[p]) continue
                counts[p] = neighborCount(cur, w, h, p)
                arms[p] = skeletonArms(cur, w, h, p)
            }
            val cut = BooleanArray(cur.size)
            var any = false
            for (s in cur.indices) {
                if (!cur[s] || cut[s] || counts[s] != 1) continue
                // Walk the branch through through-pixels (never through junctions).
                val branch = ArrayList<Int>()
                var prev = -1
                var p = s
                while (true) {
                    branch.add(p)
                    if (arms[p] >= 3) break // reached the junction: spur
                    if (p != s && counts[p] == 1) break // isolated run, no junction
                    if (arms[p] > 2) break
                    val x = p % w
                    val y = p / w
                    var next = -1
                    for (k in 0 until 8) {
                        val nx = x + DX8[k]
                        val ny = y + DY8[k]
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val q = ny * w + nx
                        if (q == prev || !cur[q]) continue
                        next = q
                        break
                    }
                    if (next < 0) break
                    prev = p
                    p = next
                    if (branch.size > maxLen + 1) break
                }
                // A spur ends at a junction and fits the budget (junction kept).
                if (branch.size >= 2 && branch.size - 1 <= maxLen && arms[branch.last()] >= 3) {
                    for (i in 0 until branch.size - 1) {
                        if (!cut[branch[i]]) {
                            cut[branch[i]] = true
                            any = true
                        }
                    }
                }
            }
            if (!any) return cur
            for (i in cur.indices) if (cut[i]) cur[i] = false
        }
        return cur
    }

    /**
     * Walk the skeleton into ordered polylines (working-pixel [Pt]s), splitting
     * at topological junctions ([skeletonArms] ≥ 3) so every path is a plain
     * stroke without branches. Endpoints are raw degree-1 pixels; staircase
     * pixels on curves (two touching neighbours) read as through-pixels, which
     * keeps curves whole. Closed loops come out as first-point-repeated rings;
     * isolated pixels as single-point dot paths. Steps favour the straightest
     * unvisited neighbour, so curves hold together instead of zigzagging
     * through the skeleton's stair-steps.
     */
    fun trace(skel: BooleanArray, w: Int, h: Int): List<List<Pt>> {
        val visited = BooleanArray(skel.size)
        val out = ArrayList<List<Pt>>()
        val counts = IntArray(skel.size)
        val arms = IntArray(skel.size)
        for (p in skel.indices) {
            if (!skel[p]) continue
            counts[p] = neighborCount(skel, w, h, p)
            arms[p] = skeletonArms(skel, w, h, p)
        }
        // A path stops at endpoints (one neighbour), true junctions (3+ arms)
        // and isolated dots — never at a staircase curve pixel.
        fun isStop(p: Int): Boolean = counts[p] == 1 || arms[p] >= 3 || counts[p] == 0

        fun walkFrom(start: Int, prev: Int): ArrayList<Pt> {
            val path = ArrayList<Pt>()
            var cur = start
            var back = prev
            var dx = 0.0
            var dy = 0.0
            var hasDir = prev >= 0
            if (hasDir) {
                dx = (cur % w - prev % w).toDouble()
                dy = (cur / w - prev / w).toDouble()
            }
            while (true) {
                visited[cur] = true
                val cx = cur % w
                val cy = cur / w
                path.add(Pt(cx.toDouble(), cy.toDouble()))
                var best = -1
                var bestScore = Double.NEGATIVE_INFINITY
                for (k in 0 until 8) {
                    val nx = cx + DX8[k]
                    val ny = cy + DY8[k]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (q == back || !skel[q] || visited[q]) continue
                    // Straightest continuation wins; the first step takes any neighbour.
                    val score = if (!hasDir) 0.0 else dx * (nx - cx) + dy * (ny - cy)
                    if (score > bestScore) {
                        bestScore = score
                        best = q
                    }
                }
                if (best < 0) break
                dx = (best % w - cx).toDouble()
                dy = (best / w - cy).toDouble()
                hasDir = true
                back = cur
                cur = best
                if (isStop(cur)) {
                    visited[cur] = true
                    path.add(Pt((cur % w).toDouble(), (cur / w).toDouble()))
                    break
                }
            }
            return path
        }

        // 1) Endpoint-started paths.
        for (p in skel.indices) {
            if (!skel[p] || visited[p] || counts[p] != 1) continue
            out.add(walkFrom(p, -1))
        }
        // 2) Junction-started remainders (spurs the endpoint pass stopped short of).
        for (p in skel.indices) {
            if (!skel[p] || visited[p]) continue
            if (arms[p] < 3) continue
            val x = p % w
            val y = p / w
            for (k in 0 until 8) {
                val nx = x + DX8[k]
                val ny = y + DY8[k]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val q = ny * w + nx
                if (!skel[q] || visited[q]) continue
                val path = ArrayList<Pt>()
                path.add(Pt(x.toDouble(), y.toDouble()))
                visited[p] = true
                val rest = walkFrom(q, p)
                path.addAll(rest)
                out.add(path)
            }
        }
        // 3) Whatever is left: closed loops (all degree-2) and isolated dots.
        for (p in skel.indices) {
            if (!skel[p] || visited[p]) continue
            val path = walkFrom(p, -1)
            if (path.size == 1) {
                out.add(path)
            } else if (path.size >= 2) {
                // Close genuine rings; leave plain stubs open.
                val a = path.first()
                val b = path.last()
                if (path.size > 3 && abs(a.x - b.x) <= 1.5 && abs(a.y - b.y) <= 1.5) path.add(a)
                out.add(path)
            }
        }
        return out
    }

    // --- simplify ---

    /** Ramer-Douglas-Peucker; keeps the endpoints, drops what [eps] covers. */
    fun simplify(points: List<Pt>, eps: Double): List<Pt> {
        if (points.size <= 2 || eps <= 0.0) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = points[a].x
            val ay = points[a].y
            val bx = points[b].x
            val by = points[b].y
            val dx = bx - ax
            val dy = by - ay
            val len = kotlin.math.hypot(dx, dy)
            var far = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = if (len < 1e-9) {
                    points[i].distanceTo(points[a])
                } else {
                    abs(dy * points[i].x - dx * points[i].y + bx * ay - by * ax) / len
                }
                if (d > far) {
                    far = d
                    idx = i
                }
            }
            if (far > eps) {
                keep[idx] = true
                stack.add(a to idx)
                stack.add(idx to b)
            }
        }
        val out = ArrayList<Pt>(points.size)
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    fun pathLength(points: List<Pt>): Double {
        var L = 0.0
        for (i in 1 until points.size) L += points[i].distanceTo(points[i - 1])
        return L
    }

    /**
     * Full run over [lum] (row-major 0..255, [w]×[h]): threshold (Sauvola local
     * by default, global manual/Otsu otherwise, with hysteresis growth) →
     * denoise → ruling removal → gap closing → radius field → skeleton →
     * spur pruning → trace → simplify → filter/cap. Returns longest-first
     * paths in source pixels, each carrying per-point pressures from the
     * stroke radius; single-point dot paths come first so the cap never eats
     * the i-dots.
     */
    fun vectorize(lum: IntArray, w: Int, h: Int, options: TraceOptions = TraceOptions()): List<InkPath> {
        require(lum.size == w * h) { "luminance size ${lum.size} != ${w}x$h" }
        val mask = inkMask(lum, w, h, options)
        // Degenerate input guard: more ink than paper means the threshold is
        // certainly wrong (a photo of a dark desk, a maxed-out slider). Bailing
        // here also keeps the thinner from grinding through a near-solid block.
        var inkCount = 0
        for (b in mask) if (b) inkCount++
        if (inkCount > options.maxInkFraction * mask.size) return emptyList()
        val dist = distanceTransform(mask, w, h)
        val skel = pruneSpurs(thin(mask, w, h), w, h, options.spurPrunePx)
        val paths = trace(skel, w, h)
        // Reference radius: the median over skeleton pixels (robust to blobs).
        var refR = 2.0
        run {
            var count = 0
            for (i in skel.indices) if (skel[i]) count++
            if (count > 0) {
                val all = FloatArray(count)
                var k = 0
                for (i in skel.indices) if (skel[i]) all[k++] = dist[i]
                all.sort()
                refR = all[count / 2].toDouble().coerceAtLeast(1.0)
            }
        }
        fun pressureAt(x: Int, y: Int): Double =
            (dist[y * w + x].toDouble() / refR * 0.75).coerceIn(0.08, 1.0)

        val dots = ArrayList<InkPath>()
        val rest = ArrayList<InkPath>(paths.size)
        for (raw in paths) {
            if (raw.isEmpty()) continue
            if (raw.size == 1) {
                // An isolated mark (an i-dot, a period, a surviving speck): a dot stroke.
                val p = raw[0]
                val x = p.x.roundToInt().coerceIn(0, w - 1)
                val y = p.y.roundToInt().coerceIn(0, h - 1)
                dots.add(InkPath(raw, listOf(pressureAt(x, y)), 0.0))
                continue
            }
            val simp = simplify(raw, options.simplifyEps)
            if (simp.size < 2) continue
            val len = pathLength(simp)
            if (len < options.minPathLen) continue
            val pr = ArrayList<Double>(simp.size)
            for (p in simp) {
                val x = p.x.roundToInt().coerceIn(0, w - 1)
                val y = p.y.roundToInt().coerceIn(0, h - 1)
                pr.add(pressureAt(x, y))
            }
            rest.add(InkPath(simp, pr, len))
        }
        rest.sortByDescending { it.lengthPx }
        // Hard sample budget against pathological inputs: dots first (details),
        // then longest paths, packing smaller ones while they still fit. Caps the
        // note size and render cost no matter how noisy the photo is.
        val budget = options.maxTotalSamples.coerceAtLeast(0)
        val out = ArrayList<InkPath>(dots.size + rest.size)
        var total = 0
        for (d in dots) {
            if (total + d.points.size > budget) break
            out.add(d)
            total += d.points.size
        }
        for (p in rest) {
            if (out.size >= options.maxStrokes) break
            if (total + p.points.size > budget) continue
            out.add(p)
            total += p.points.size
        }
        return out
    }
}
