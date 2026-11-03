package com.xnotes.canvas

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Outline
import com.xnotes.core.model.Page
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TapeItem
import com.xnotes.core.shapes.ShapePath
import com.xnotes.core.vector.PathFlattener
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A selected item plus the index of the page it lives on. */
data class Selected(val pageIndex: Int, val item: CanvasItem)

/**
 * Pure selection-membership tests (spec 06 §6–7).
 *
 * The band and the lasso share one rule: an item is in when the ring they draw touches what the
 * item draws, or goes all the way round it. What an item draws is its ink, a shape's or a
 * connector's outline, an image's edge or a text box's frame, never a shape's fill or label, so a
 * ring round writing inside a box that stays clear of the box's outline leaves the box out. A
 * dropped colour and a tape draw only fill, so their outer edge stands in for an outline.
 *
 * A locked item is not a member of anything, whichever way the selection was drawn. Putting that
 * here rather than at each call site is what makes it true of the band and the lasso on both
 * canvases at once.
 */
object SelectionMath {

    /**
     * Band selection over a flat item list, for a canvas with no pages. The paged overloads below
     * are the same test with a page-space translation in front of it; sharing the rule is the
     * point, so the two canvases select alike.
     */
    fun bandMembers(items: List<CanvasItem>, band: Rect, whole: Boolean = false): List<CanvasItem> {
        val ring = Ring(corners(band))
        return items.filter { !it.locked && ring.takes(it, whole) }
    }

    /** Lasso selection over a flat item list: the band's rule, with the loop as the ring. */
    fun lassoMembers(items: List<CanvasItem>, polygon: List<Pt>, whole: Boolean = false): List<CanvasItem> {
        if (polygon.size < 3) return emptyList()
        val ring = Ring(polygon)
        return items.filter { !it.locked && ring.takes(it, whole) }
    }

    /**
     * Band selection across pages. [toPage] maps a content-space point into page i's own space (a
     * plain page-rect translation, plus the display rotation when the view is rotated), where the
     * items' geometry lives.
     */
    fun bandMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        band: Rect,
        whole: Boolean = false,
        toPage: (Int, Pt) -> Pt = { i, p -> Pt(p.x - pageRects[i].left, p.y - pageRects[i].top) },
    ): List<Selected> = members(pages, pageRects, corners(band), whole, toPage)

    /** Lasso selection across pages: the band's rule, with the loop as the ring. */
    fun lassoMembers(
        pages: List<Page>,
        pageRects: List<Rect>,
        polygon: List<Pt>,
        whole: Boolean = false,
        toPage: (Int, Pt) -> Pt = { i, p -> Pt(p.x - pageRects[i].left, p.y - pageRects[i].top) },
    ): List<Selected> {
        if (polygon.size < 3) return emptyList()
        return members(pages, pageRects, polygon, whole, toPage)
    }

    private fun members(pages: List<Page>, pageRects: List<Rect>, ring: List<Pt>, whole: Boolean, toPage: (Int, Pt) -> Pt): List<Selected> {
        val reach = Rect.bounding(ring)
        val out = ArrayList<Selected>()
        for (i in pages.indices) {
            val pr = pageRects.getOrNull(i) ?: continue
            // Skip pages out of reach before touching their items; the slack covers ink painted past the edge.
            if (!pr.outset(OFF_PAGE_SLACK).intersects(reach)) continue
            val local = Ring(ring.map { toPage(i, it) })
            for (item in pages[i].items) {
                if (!item.locked && local.takes(item, whole)) out.add(Selected(i, item))
            }
        }
        return out
    }

    /** How far past its page edge an item may reach and still be reachable by a band that misses
     *  the page rect. Covers neon bloom and ink drawn over the margin. */
    const val OFF_PAGE_SLACK = 64.0

    private fun corners(r: Rect): List<Pt> = listOf(r.topLeft, Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom))

    /** One run of what an item draws, joined back to its start when [closed]. */
    private class Line(val pts: List<Pt>, val closed: Boolean)

    /** What an item draws, as lines, and how far its ink spreads either side of them. */
    private class Drawn(val lines: List<Line>, val reach: Double)

    private fun drawnOf(item: CanvasItem): Drawn = when (item) {
        is Stroke -> Drawn(
            if (item.cuts.isEmpty()) listOf(Line(List(item.sampleCount) { Pt(item.xAt(it), item.yAt(it)) }, false))
            else item.visibleRuns().map { Line(it, false) },
            item.config.baseWidth / 2.0,
        )
        is ShapeItem -> if (item.isFill) fillEdge(item) else outlines(item.paths(), item.strokeWidth)
        is ConnectorItem -> outlines(item.paths(), item.strokeWidth)
        is ImageItem -> Drawn(listOf(Line(item.corners(), true)), 0.0)
        // Tape is all body, like a dropped colour, so its edge stands in for an outline.
        is TapeItem -> Drawn(item.shape.outline.map { Line(it.pts, it.closed) }, 0.0)
        else -> Drawn(listOf(Line(corners(item.bounds()), true)), 0.0)
    }

    /**
     * A dropped colour's outer edge: the rings that bound it, never its holes, whose edges sit under
     * lines drawn inside it. So a ring round writing inside a filled area leaves the fill out.
     */
    private fun fillEdge(fill: ShapeItem): Drawn {
        val rings = fill.fillRings(Outline.HIT_FLATTEN)
        val outer = rings.filter { ring -> rings.count { it !== ring && Geometry.pointInPolygon(it, ring[0]) } % 2 == 0 }
        return Drawn(outer.map { Line(it, true) }, 0.0)
    }

    private fun outlines(paths: List<ShapePath>, width: Double) = Drawn(
        paths.flatMap { path -> path.contours.map { Line(PathFlattener.flatten(it, Outline.HIT_FLATTEN), it.closed) } },
        width / 2.0,
    )

    /** A band or a lasso as the closed ring it draws, inside by the even-odd rule. */
    private class Ring(pts: List<Pt>) {
        val n: Int = pts.size
        private val xs = DoubleArray(n) { pts[it].x }
        private val ys = DoubleArray(n) { pts[it].y }
        private val box = Rect.bounding(pts)

        // Per-edge bounding boxes, so per-item and per-segment rejects cost four compares.
        private val eMinX = DoubleArray(n)
        private val eMaxX = DoubleArray(n)
        private val eMinY = DoubleArray(n)
        private val eMaxY = DoubleArray(n)

        // Uniform grid over the ring's own box, indexing edge bounding boxes. Built once per
        // selection (not per item): a large lasso over many strokes previously paid O(P) per
        // item just to find the handful of edges near it; now it pays O(cells touched).
        private val cols: Int
        private val rows: Int
        private val cellW: Double
        private val cellH: Double
        private val cells: Array<IntArray>?

        // Deduplication stamps plus a reusable candidate buffer: collecting the edges near an
        // item allocates nothing per item.
        private val stamp = IntArray(n)
        private var cur = 1
        private val buf = IntArray(n)

        init {
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = xs[i]
                val ay = ys[i]
                val bx = xs[j]
                val by = ys[j]
                eMinX[i] = min(ax, bx)
                eMaxX[i] = max(ax, bx)
                eMinY[i] = min(ay, by)
                eMaxY[i] = max(ay, by)
            }
            if (n < GRID_MIN_EDGES || box.w <= 0.0 || box.h <= 0.0 ||
                !box.x.isFinite() || !box.y.isFinite() || !box.w.isFinite() || !box.h.isFinite()
            ) {
                cols = 0
                rows = 0
                cellW = 0.0
                cellH = 0.0
                cells = null
            } else {
                // Square-ish cells: split the long side more often.
                val target = sqrt(n.toDouble()).coerceIn(4.0, 64.0)
                val aspect = box.w / box.h
                var c = (target * sqrt(aspect)).toInt().coerceIn(1, 64)
                var r = (target / sqrt(aspect)).toInt().coerceIn(1, 64)
                while (c * r > MAX_GRID_CELLS) {
                    if (c >= r) c /= 2 else r /= 2
                    if (c < 1 || r < 1) break
                }
                c = c.coerceAtLeast(1)
                r = r.coerceAtLeast(1)
                cols = c
                rows = r
                cellW = box.w / cols
                cellH = box.h / rows
                val tmp = Array(cols * rows) { mutableListOf<Int>() }
                for (i in 0 until n) {
                    val x0 = cellX(eMinX[i])
                    val x1 = cellX(eMaxX[i])
                    val y0 = cellY(eMinY[i])
                    val y1 = cellY(eMaxY[i])
                    for (cy in y0..y1) for (cx in x0..x1) tmp[cy * cols + cx].add(i)
                }
                cells = Array(tmp.size) { tmp[it].toIntArray() }
            }
        }

        private fun cellX(v: Double): Int {
            if (!v.isFinite() || cellW <= 0.0) return 0
            return ((v - box.left) / cellW).toInt().coerceIn(0, cols - 1)
        }

        private fun cellY(v: Double): Int {
            if (!v.isFinite() || cellH <= 0.0) return 0
            return ((v - box.top) / cellH).toInt().coerceIn(0, rows - 1)
        }

        private fun nextStamp(): Int {
            if (cur == Int.MAX_VALUE) {
                stamp.fill(0)
                cur = 1
            } else {
                cur++
            }
            return cur
        }

        /** Even-odd containment, over packed edges rather than boxed points. */
        fun pointInside(px: Double, py: Double): Boolean {
            if (px < box.left || px > box.right || py < box.top || py > box.bottom) return false
            val grid = cells
            if (grid == null) {
                var inside = false
                for (i in 0 until n) {
                    val ax = xs[i]
                    val ay = ys[i]
                    val bx = xs[(i + 1) % n]
                    val by = ys[(i + 1) % n]
                    val crosses = (ay > py) != (by > py) &&
                        px < (bx - ax) * (py - ay) / (by - ay) + ax
                    if (crosses) inside = !inside
                }
                return inside
            }
            // Only edges in the point's own row can cross its ray: every crossing edge spans
            // that row, so it was filed there. Extras in the row simply fail the crossing test.
            val row = cellY(py)
            val mark = nextStamp()
            var inside = false
            for (cx in 0 until cols) {
                val bucket = grid[row * cols + cx]
                for (k in bucket.indices) {
                    val i = bucket[k]
                    if (stamp[i] == mark) continue
                    stamp[i] = mark
                    val ax = xs[i]
                    val ay = ys[i]
                    val bx = xs[(i + 1) % n]
                    val by = ys[(i + 1) % n]
                    val crosses = (ay > py) != (by > py) &&
                        px < (bx - ax) * (py - ay) / (by - ay) + ax
                    if (crosses) inside = !inside
                }
            }
            return inside
        }

        /**
         * Edges whose bounding box meets [q], collected into [buf]; returns how many.
         * Exactly the old per-item `near` set, but via the grid when there is one.
         */
        private fun collectInto(qLeft: Double, qTop: Double, qRight: Double, qBottom: Double): Int {
            val grid = cells
            if (grid == null) {
                var count = 0
                for (i in 0 until n) {
                    if (eMinX[i] <= qRight && eMaxX[i] >= qLeft && eMinY[i] <= qBottom && eMaxY[i] >= qTop) {
                        buf[count++] = i
                    }
                }
                return count
            }
            val mark = nextStamp()
            var count = 0
            val x0 = cellX(qLeft)
            val x1 = cellX(qRight)
            val y0 = cellY(qTop)
            val y1 = cellY(qBottom)
            for (cy in y0..y1) for (cx in x0..x1) {
                val bucket = grid[cy * cols + cx]
                for (k in bucket.indices) {
                    val i = bucket[k]
                    if (stamp[i] == mark) continue
                    stamp[i] = mark
                    if (eMinX[i] <= qRight && eMaxX[i] >= qLeft && eMinY[i] <= qBottom && eMaxY[i] >= qTop) {
                        buf[count++] = i
                    }
                }
            }
            return count
        }

        /** [whole]: everything [item] draws lies inside the ring; otherwise [meets]. */
        fun takes(item: CanvasItem, whole: Boolean): Boolean = if (whole) holds(item) else meets(item)

        private fun holds(item: CanvasItem): Boolean {
            // Strokes take the allocation-free path; everything else keeps its drawn lines but
            // tests containment against the packed ring.
            if (item is Stroke && item.cuts.isEmpty()) return holdsStroke(item)
            val drawn = drawnOf(item)
            val area = boundsOf(drawn) ?: return false
            if (!area.intersects(box)) return false
            return drawn.lines.all { line -> line.pts.all { pointInside(it.x, it.y) } }
        }

        private fun holdsStroke(s: Stroke): Boolean {
            val count = s.sampleCount
            if (count == 0) return false
            var minX = s.xAt(0)
            var maxX = minX
            var minY = s.yAt(0)
            var maxY = minY
            for (i in 1 until count) {
                val x = s.xAt(i)
                val y = s.yAt(i)
                if (x < minX) minX = x else if (x > maxX) maxX = x
                if (y < minY) minY = y else if (y > maxY) maxY = y
            }
            if (maxX < box.left || minX > box.right || maxY < box.top || minY > box.bottom) return false
            for (i in 0 until count) if (!pointInside(s.xAt(i), s.yAt(i))) return false
            return true
        }

        /** True when some of what [item] draws lies inside the ring or within its ink's reach of the ring's line. */
        fun meets(item: CanvasItem): Boolean {
            if (item is Stroke && item.cuts.isEmpty()) return meetsStroke(item)
            val drawn = drawnOf(item)
            val reach = drawn.reach
            val area = boundsOf(drawn)?.outset(reach) ?: return false
            if (!area.intersects(box)) return false
            // A line starting inside is in; one starting outside must cross or touch an edge near the item.
            for (line in drawn.lines) if (line.pts.isNotEmpty() && pointInside(line.pts[0].x, line.pts[0].y)) return true
            val count = collectInto(area.left, area.top, area.right, area.bottom)
            if (count == 0) return false
            for (line in drawn.lines) {
                val v = line.pts
                if (v.size == 1) {
                    val p = v[0]
                    for (k in 0 until count) {
                        val e = buf[k]
                        if (distPointSeg(p.x, p.y, xs[e], ys[e], xs[(e + 1) % n], ys[(e + 1) % n]) <= reach) return true
                    }
                    continue
                }
                val segments = if (line.closed) v.size else v.size - 1
                for (k in 0 until segments) {
                    val p = v[k]
                    val q = v[(k + 1) % v.size]
                    val sMinX = min(p.x, q.x)
                    val sMaxX = max(p.x, q.x)
                    val sMinY = min(p.y, q.y)
                    val sMaxY = max(p.y, q.y)
                    for (c in 0 until count) {
                        val e = buf[c]
                        if (eMaxX[e] < sMinX - reach || eMinX[e] > sMaxX + reach ||
                            eMaxY[e] < sMinY - reach || eMinY[e] > sMaxY + reach
                        ) continue
                        if (distSegSeg(p.x, p.y, q.x, q.y, xs[e], ys[e], xs[(e + 1) % n], ys[(e + 1) % n]) <= reach) return true
                    }
                }
            }
            return false
        }

        private fun meetsStroke(s: Stroke): Boolean {
            val count = s.sampleCount
            if (count == 0) return false
            val reach = s.config.baseWidth / 2.0
            var minX = s.xAt(0)
            var maxX = minX
            var minY = s.yAt(0)
            var maxY = minY
            for (i in 1 until count) {
                val x = s.xAt(i)
                val y = s.yAt(i)
                if (x < minX) minX = x else if (x > maxX) maxX = x
                if (y < minY) minY = y else if (y > maxY) maxY = y
            }
            if (maxX + reach < box.left || minX - reach > box.right ||
                maxY + reach < box.top || minY - reach > box.bottom
            ) return false
            if (pointInside(s.xAt(0), s.yAt(0))) return true
            val edges = collectInto(minX - reach, minY - reach, maxX + reach, maxY + reach)
            if (edges == 0) return false
            if (count == 1) {
                val px = s.xAt(0)
                val py = s.yAt(0)
                for (k in 0 until edges) {
                    val e = buf[k]
                    if (distPointSeg(px, py, xs[e], ys[e], xs[(e + 1) % n], ys[(e + 1) % n]) <= reach) return true
                }
                return false
            }
            for (k in 0 until count - 1) {
                val px = s.xAt(k)
                val py = s.yAt(k)
                val qx = s.xAt(k + 1)
                val qy = s.yAt(k + 1)
                val sMinX = min(px, qx)
                val sMaxX = max(px, qx)
                val sMinY = min(py, qy)
                val sMaxY = max(py, qy)
                for (c in 0 until edges) {
                    val e = buf[c]
                    if (eMaxX[e] < sMinX - reach || eMinX[e] > sMaxX + reach ||
                        eMaxY[e] < sMinY - reach || eMinY[e] > sMaxY + reach
                    ) continue
                    if (distSegSeg(px, py, qx, qy, xs[e], ys[e], xs[(e + 1) % n], ys[(e + 1) % n]) <= reach) return true
                }
            }
            return false
        }

        private fun boundsOf(drawn: Drawn): Rect? {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (line in drawn.lines) for (p in line.pts) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return if (minX > maxX) null else Rect(minX, minY, maxX - minX, maxY - minY)
        }

        companion object {
            /** Below this many edges the grid costs more than it saves; scan directly. */
            private const val GRID_MIN_EDGES = 64

            /** Hard cap on grid cells, so a huge lasso cannot blow the per-selection work. */
            private const val MAX_GRID_CELLS = 4096
        }
    }

    // --- primitive distance predicates: bit-for-bit the Geometry versions, without Pt boxing ---

    private fun distPointSeg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        if (lenSq < 1e-12) {
            val dx = px - ax
            val dy = py - ay
            return sqrt(dx * dx + dy * dy)
        }
        var t = ((px - ax) * abx + (py - ay) * aby) / lenSq
        t = t.coerceIn(0.0, 1.0)
        val dx = px - (ax + t * abx)
        val dy = py - (ay + t * aby)
        return sqrt(dx * dx + dy * dy)
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)

    private fun distSegSeg(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, dx: Double, dy: Double,
    ): Double {
        val abc = cross(ax, ay, bx, by, cx, cy)
        val abd = cross(ax, ay, bx, by, dx, dy)
        val cda = cross(cx, cy, dx, dy, ax, ay)
        val cdb = cross(cx, cy, dx, dy, bx, by)
        if (((abc > 0.0 && abd < 0.0) || (abc < 0.0 && abd > 0.0)) &&
            ((cda > 0.0 && cdb < 0.0) || (cda < 0.0 && cdb > 0.0))
        ) return 0.0
        return min(
            min(distPointSeg(ax, ay, cx, cy, dx, dy), distPointSeg(bx, by, cx, cy, dx, dy)),
            min(distPointSeg(cx, cy, ax, ay, bx, by), distPointSeg(dx, dy, ax, ay, bx, by)),
        )
    }
}
