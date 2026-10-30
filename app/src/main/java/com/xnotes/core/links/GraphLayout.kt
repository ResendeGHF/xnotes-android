package com.xnotes.core.links

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/**
 * Layout math for the link graph screen: reachability plus the layered-tree and
 * force-directed positions the screen draws. Pure Kotlin, so it unit tests
 * without Android.
 */
object GraphLayout {

    /**
     * Files reachable from [root] following [edges] (BFS, root first). Without a
     * root, either nothing ([showAll] off) or the linked subgraph: every file that
     * links to something plus everything those links point at — isolated notes
     * with no links at all stay hidden.
     */
    fun visibleFrom(
        root: String?,
        files: Collection<String>,
        edges: Map<String, List<String>>,
        showAll: Boolean = false,
    ): List<String> {
        if (root == null) {
            if (!showAll) return emptyList()
            val known = files.toSet()
            val linked = LinkedHashSet<String>()
            for ((source, targets) in edges) {
                if (source !in known) continue
                var hits = false
                for (to in targets) {
                    if (to in known) {
                        linked.add(to)
                        hits = true
                    }
                }
                if (hits) linked.add(source)
            }
            return linked.sorted()
        }
        if (root !in files) return files.sorted()
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        visited.add(root)
        queue.add(root)
        while (queue.isNotEmpty()) {
            val from = queue.removeFirst()
            for (to in edges[from].orEmpty()) {
                if (to in files && visited.add(to)) queue.add(to)
            }
        }
        return visited.sorted()
    }

    /**
     * The BFS spanning tree from [root] over [visible]: parent -> children, in visit
     * order. Cycles collapse (a file already seen is not re-added), so the result
     * always draws as a tree.
     */
    fun treeEdges(root: String, visible: Set<String>, edges: Map<String, List<String>>): List<Pair<String, String>> {
        if (root !in visible) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        val visited = HashSet<String>()
        visited.add(root)
        val queue = ArrayDeque<String>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val from = queue.removeFirst()
            for (to in edges[from].orEmpty()) {
                if (to in visible && visited.add(to)) {
                    out.add(from to to)
                    queue.add(to)
                }
            }
        }
        return out
    }

    /**
     * Layered tree positions in a [width] x [height] box: depth sets y, siblings
     * spread across x in visit order, each level centered. [siblingGap] is in
     * the same units as the box.
     */
    fun treePositions(
        root: String,
        tree: List<Pair<String, String>>,
        width: Double,
        height: Double,
        siblingGap: Double = 140.0,
    ): Map<String, Pt> {
        val children = HashMap<String, MutableList<String>>()
        val inTree = HashSet<String>()
        inTree.add(root)
        for ((from, to) in tree) {
            children.getOrPut(from) { ArrayList() }.add(to)
            inTree.add(to)
        }
        val depth = HashMap<String, Int>()
        val order = ArrayList<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(root to 0)
        depth[root] = 0
        while (queue.isNotEmpty()) {
            val (node, d) = queue.removeFirst()
            order.add(node)
            for (c in children[node].orEmpty()) {
                depth[c] = d + 1
                queue.add(c to d + 1)
            }
        }
        val maxDepth = depth.values.maxOrNull() ?: 0
        val byLevel = order.groupBy { depth[it] ?: 0 }.toSortedMap()
        val out = HashMap<String, Pt>()
        if (maxDepth == 0) {
            out[root] = Pt(width / 2.0, height / 2.0)
            return out
        }
        val yFor = { d: Int -> (d + 0.5) * height / (maxDepth + 1.0) }
        for ((d, level) in byLevel) {
            val span = (level.size - 1) * siblingGap
            var x = width / 2.0 - span / 2.0
            for (node in level) {
                out[node] = Pt(x, yFor(d))
                x += siblingGap
            }
        }
        return out
    }

    /**
     * Fruchterman-Reingold force-directed positions in a [width] x [height] box.
     * Deterministic for [seed]; [iterations] scales down with node count.
     */
    fun forcePositions(
        nodes: List<String>,
        edges: List<Pair<String, String>>,
        width: Double,
        height: Double,
        seed: Long = 0L,
        iterations: Int = -1,
    ): Map<String, Pt> {
        if (nodes.isEmpty()) return emptyMap()
        if (nodes.size == 1) return mapOf(nodes[0] to Pt(width / 2.0, height / 2.0))
        val rnd = kotlin.random.Random(seed)
        val pos = HashMap<String, Pt>(nodes.size * 2)
        for (n in nodes) pos[n] = Pt(rnd.nextDouble() * width, rnd.nextDouble() * height)
        val area = width * height
        val k = kotlin.math.sqrt(area / nodes.size)
        val iters = if (iterations >= 0) iterations else (650 - (nodes.size * 0.4).toInt()).coerceIn(80, 700)
        var temp = minOf(width, height) / 10.0
        val cool = temp / (iters + 1.0)
        val indexOf = nodes.withIndex().associate { (i, n) -> n to i }
        val adjacency = edges.mapNotNull { (a, b) ->
            val i = indexOf[a]
            val j = indexOf[b]
            if (i == null || j == null || i == j) null else i to j
        }
        repeat(iters) {
            val disp = Array(nodes.size) { Pt(0.0, 0.0) }
            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    val dx = pos[nodes[i]]!!.x - pos[nodes[j]]!!.x
                    val dy = pos[nodes[i]]!!.y - pos[nodes[j]]!!.y
                    var dist = kotlin.math.hypot(dx, dy)
                    if (dist < 0.01) dist = 0.01
                    val force = k * k / dist
                    val fx = dx / dist * force
                    val fy = dy / dist * force
                    disp[i] = Pt(disp[i].x + fx, disp[i].y + fy)
                    disp[j] = Pt(disp[j].x - fx, disp[j].y - fy)
                }
            }
            for ((i, j) in adjacency) {
                val dx = pos[nodes[i]]!!.x - pos[nodes[j]]!!.x
                val dy = pos[nodes[i]]!!.y - pos[nodes[j]]!!.y
                var dist = kotlin.math.hypot(dx, dy)
                if (dist < 0.01) dist = 0.01
                val force = dist * dist / k
                val fx = dx / dist * force
                val fy = dy / dist * force
                disp[i] = Pt(disp[i].x - fx, disp[i].y - fy)
                disp[j] = Pt(disp[j].x + fx, disp[j].y + fy)
            }
            for (i in nodes.indices) {
                var dx = disp[i].x
                var dy = disp[i].y
                val len = kotlin.math.hypot(dx, dy)
                if (len > 0.0) {
                    val capped = minOf(len, temp)
                    dx = dx / len * capped
                    dy = dy / len * capped
                }
                val p = pos[nodes[i]]!!
                pos[nodes[i]] = Pt(
                    (p.x + dx).coerceIn(0.0, width),
                    (p.y + dy).coerceIn(0.0, height),
                )
            }
            temp = maxOf(0.0, temp - cool)
        }
        return pos
    }

    /**
     * Pill rect for a node label centred on [center]: the text box plus padding,
     * clamped to [minW]..[maxW] and exactly [h] tall, so the label the screen
     * measures is the pill the graph draws.
     */
    fun nodeRect(
        center: Pt,
        textW: Double,
        textH: Double,
        padH: Double = 14.0,
        minW: Double = 48.0,
        h: Double = 44.0,
        maxW: Double = 228.0,
    ): Rect {
        val w = (textW + padH * 2.0).coerceIn(minW, maxW)
        return Rect(center.x - w / 2.0, center.y - h / 2.0, w, h)
    }

    /**
     * Where the ray from [from] to [to] exits [target] (the arrow tip): the far
     * crossing in (0, 1] so lines stop at the pill instead of piercing it or
     * stopping short. Null when the ray never crosses (parallel miss).
     */
    fun edgeTip(from: Pt, to: Pt, target: Rect): Pt? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        var tmin = 0.0
        var tmax = 1.0
        if (kotlin.math.abs(dx) < 1e-9) {
            if (from.x < target.left || from.x > target.right) return null
        } else {
            var t1 = (target.left - from.x) / dx
            var t2 = (target.right - from.x) / dx
            if (t1 > t2) {
                val t = t1; t1 = t2; t2 = t
            }
            tmin = maxOf(tmin, t1)
            tmax = minOf(tmax, t2)
        }
        if (kotlin.math.abs(dy) < 1e-9) {
            if (from.y < target.top || from.y > target.bottom) return null
        } else {
            var t1 = (target.top - from.y) / dy
            var t2 = (target.bottom - from.y) / dy
            if (t1 > t2) {
                val t = t1; t1 = t2; t2 = t
            }
            tmin = maxOf(tmin, t1)
            tmax = minOf(tmax, t2)
        }
        if (tmin > tmax) return null
        val t = if (tmin > 0.0) tmin else tmax
        if (t < 0.0 || t > 1.0) return null
        return Pt(from.x + dx * t, from.y + dy * t)
    }

    /** [tip] pulled back along [dir] (tip-to-source) by [len], for arrow bases. */
    fun pullBack(tip: Pt, dir: Pt, len: Double): Pt {
        val l = kotlin.math.hypot(dir.x, dir.y)
        if (l < 1e-9) return tip
        return Pt(tip.x + dir.x / l * len, tip.y + dir.y / l * len)
    }
}
