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
 *  4. [distanceTransform] — chamfer distance to the background: the local stroke
 *     radius, later mapped to per-sample pressure so the ink keeps its weight.
 *  5. [thin] — Zhang-Suen skeletonization down to 1px centrelines.
 *  6. [trace] — walk the skeleton into ordered polylines, splitting at
 *     junctions; isolated pixels survive as dot paths (i-dots, periods).
 *  7. [simplify] — Ramer-Douglas-Peucker per polyline plus short-path filtering.
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
        val denoisePx: Int = 8,
        /** RDP simplification tolerance, in working px. */
        val simplifyEps: Double = 0.8,
        /** Drop paths shorter than this, in working px (dots are always kept). */
        val minPathLen: Double = 3.0,
        /** Keep at most this many paths (dots first, then longest first). */
        val maxStrokes: Int = 6000,
        /**
         * Hard budget on traced samples across the page (dots first, then longest
         * paths). Bounds note size and render cost on pathological inputs, so a
         * noisy photo can never produce a note that crashes the app to open.
         */
        val maxTotalSamples: Int = 80_000,
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
     * The ink mask [vectorize] thresholds with for [options]: the manual global
     * level, Sauvola local thresholding for auto (dark-on-light), or a global
     * Otsu level for auto on light-on-dark sources. With [TraceOptions.hysteresis]
     * the strong mask grows into faint connected strokes through a looser weak
     * level, which is what keeps pale letter parts. Shared with the preview so
     * the black-on-white edges on screen are exactly what gets traced.
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
     * Walk the skeleton into ordered polylines (working-pixel [Pt]s), splitting at
     * junctions so every path is a plain stroke without branches. Closed loops come
     * out as first-point-repeated rings; isolated pixels as single-point dot paths.
     * Steps favour the straightest unvisited neighbour, so curves hold together
     * instead of zigzagging through the skeleton's stair-steps.
     */
    fun trace(skel: BooleanArray, w: Int, h: Int): List<List<Pt>> {
        val visited = BooleanArray(skel.size)
        val out = ArrayList<List<Pt>>()
        val counts = IntArray(skel.size)
        for (p in skel.indices) if (skel[p]) counts[p] = neighborCount(skel, w, h, p)

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
                if (counts[cur] != 2) {
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
            if (counts[p] < 3) continue
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
     * by default, global manual/Otsu otherwise) → denoise → radius field →
     * skeleton → trace → simplify → filter/cap. Returns longest-first paths in
     * source pixels, each carrying per-point pressures from the stroke radius;
     * single-point dot paths come first so the cap never eats the i-dots.
     */
    fun vectorize(lum: IntArray, w: Int, h: Int, options: TraceOptions = TraceOptions()): List<InkPath> {
        require(lum.size == w * h) { "luminance size ${lum.size} != ${w}x$h" }
        val grown = applyThreshold(lum, w, h, options)
        // Degenerate input guard: more ink than paper means the threshold is
        // certainly wrong (a photo of a dark desk, a maxed-out slider). Bailing
        // here also keeps the thinner from grinding through a near-solid block.
        var inkCount = 0
        for (b in grown) if (b) inkCount++
        if (inkCount > options.maxInkFraction * grown.size) return emptyList()
        val clean = if (options.denoisePx > 0) despeckle(grown, w, h, options.denoisePx) else grown
        val dist = distanceTransform(clean, w, h)
        val skel = thin(clean, w, h)
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
