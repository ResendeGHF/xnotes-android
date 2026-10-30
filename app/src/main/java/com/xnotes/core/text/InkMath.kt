package com.xnotes.core.text

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Stroke
import kotlin.math.max

/**
 * Handwritten maths to LaTeX from in-project parts only: no downloaded model, no
 * third-party add-on. The ink's own geometry decides the layout (stacked bars read
 * as fractions, raised or lowered runs as scripts), each run is read with the same
 * on-device handwriting recognition as Convert to text, and [PlainMath] turns that
 * text into LaTeX.
 *
 * Pure Kotlin (no Android imports) so the layout rules are pinned by JVM unit tests;
 * recognition itself is injected as [recognize] by the platform layer.
 */
object InkMath {

    /** The layout read off the ink. Leaves ([Run]) hold the strokes one recognition call reads. */
    sealed interface Node {
        data class Run(val strokes: List<Stroke>) : Node
        data class Seq(val parts: List<Node>) : Node
        data class Frac(val num: Node, val den: Node) : Node
        data class Sup(val base: Node, val exp: Node) : Node
        data class Sub(val base: Node, val sub: Node) : Node
    }

    /**
     * Read the layout of [strokes]: a stacked bar with ink on both sides is a
     * fraction (recurse above and below it), otherwise rows of left-to-right runs
     * with raised or lowered runs as scripts. One unmarked run stays one [Run] so
     * its spacing reaches recognition exactly as written.
     */
    fun structure(strokes: List<Stroke>): Node {
        val live = strokes.filter { it.sampleCount > 0 }
        if (live.size <= 1) return Node.Run(live)
        val split = fractionSplit(live)
        if (split != null) return Node.Frac(structure(split.above), structure(split.below))
        val rows = splitRows(live)
        if (rows.size == 1) return structureRow(rows[0])
        return Node.Seq(rows.map { structureRow(it) })
    }

    /**
     * Turn [node] into LaTeX, reading each leaf's strokes with [recognize] (one call
     * per run). Blank leaves are dropped; "" means nothing could be read.
     */
    fun render(node: Node, recognize: (List<Stroke>) -> String): String = when (node) {
        is Node.Run -> latexOfRun(node.strokes, recognize)
        is Node.Seq -> node.parts.map { render(it, recognize) }.filter { it.isNotEmpty() }.joinToString(" ")
        is Node.Frac -> {
            val n = render(node.num, recognize)
            val d = render(node.den, recognize)
            if (n.isEmpty() && d.isEmpty()) "" else "\\frac{$n}{$d}"
        }
        is Node.Sup -> {
            val b = render(node.base, recognize)
            val e = render(node.exp, recognize)
            when {
                e.isEmpty() -> b
                b.isEmpty() -> e
                else -> "{$b}^{$e}"
            }
        }
        is Node.Sub -> {
            val b = render(node.base, recognize)
            val s = render(node.sub, recognize)
            when {
                s.isEmpty() -> b
                b.isEmpty() -> s
                else -> "{$b}_{$s}"
            }
        }
    }

    private fun latexOfRun(strokes: List<Stroke>, recognize: (List<Stroke>) -> String): String {
        if (strokes.isEmpty()) return ""
        val raw = recognize(strokes).trim()
        if (raw.isEmpty()) return ""
        // Already LaTeX (a backslash): hand it back untouched, as PlainMath does.
        if ('\\' in raw) return raw
        return PlainMath.toLatex(normalizeUnicode(raw)).trim()
    }

    /**
     * What handwriting recognition often returns for maths symbols, in the ASCII
     * [PlainMath] reads: "×" to "*", "÷" to "/", "−" to "-", "√" to sqrt, Greek to
     * its name, and superscript/subscript Unicode (ML Kit emits "x²" for x-squared)
     * to "^2" and "_2". Anything unmapped is kept as-is.
     */
    fun normalizeUnicode(s: String): String {
        if (s.none { it.code > 127 }) return s
        val out = StringBuilder()
        for (c in s) out.append(UNICODE[c] ?: c)
        return out.toString()
    }

    private val UNICODE: Map<Char, String> = buildMap {
        put('×', "*")
        put('÷', "/")
        put('−', "-")
        put('–', "-")
        put('—', "-")
        put('·', "*")
        put('⋅', "*")
        put('√', "sqrt ")
        put('π', "pi")
        put('∞', "inf")
        put('∂', "partial ")
        put('∫', "int ")
        put('∑', "sum ")
        put('∏', "prod ")
        put('≤', "<=")
        put('≥', ">=")
        put('≠', "!=")
        put('±', "+-")
        put('∓', "-+")
        put('→', "->")
        put('←', "<-")
        put('⇒', "=>")
        put('⇔', "<=>")
        put('…', "...")
        put('′', "'")
        for ((u, name) in listOf(
            'α' to "alpha", 'β' to "beta", 'γ' to "gamma", 'δ' to "delta",
            'ε' to "epsilon", 'ζ' to "zeta", 'η' to "eta", 'θ' to "theta",
            'λ' to "lambda", 'μ' to "mu", 'ν' to "nu", 'ξ' to "xi",
            'ρ' to "rho", 'σ' to "sigma", 'τ' to "tau", 'φ' to "phi",
            'χ' to "chi", 'ψ' to "psi", 'ω' to "omega",
            'Δ' to "Delta", 'Θ' to "Theta", 'Λ' to "Lambda", 'Σ' to "Sigma",
            'Φ' to "Phi", 'Ω' to "Omega",
        )) put(u, name)
        for ((u, d) in listOf(
            '⁰' to "0", '¹' to "1", '²' to "2", '³' to "3", '⁴' to "4",
            '⁵' to "5", '⁶' to "6", '⁷' to "7", '⁸' to "8", '⁹' to "9",
        )) put(u, "^$d")
        put('⁺', "^+")
        put('⁻', "^-")
        put('ⁿ', "^n")
        for ((u, d) in listOf(
            '₀' to "0", '₁' to "1", '₂' to "2", '₃' to "3", '₄' to "4",
            '₅' to "5", '₆' to "6", '₇' to "7", '₈' to "8", '₉' to "9",
        )) put(u, "_$d")
        for ((u, name) in listOf(
            'ᵃ' to "a", 'ᵇ' to "b", 'ᶜ' to "c", 'ᵈ' to "d", 'ᵉ' to "e",
            'ᶠ' to "f", 'ᵍ' to "g", 'ʰ' to "h", 'ⁱ' to "i", 'ʲ' to "j",
            'ᵏ' to "k", 'ˡ' to "l", 'ᵐ' to "m", 'ⁿ' to "n", 'ᵒ' to "o",
            'ᵖ' to "p", 'ʳ' to "r", 'ˢ' to "s", 'ᵗ' to "t", 'ᵘ' to "u",
            'ᵛ' to "v", 'ʷ' to "w", 'ˣ' to "x", 'ʸ' to "y", 'ᶻ' to "z",
        )) put(u, "^$name")
    }

    private data class Split(val above: List<Stroke>, val below: List<Stroke>)

    /**
     * A fraction bar: one long thin stroke with ink strictly above and below it,
     * both overlapping its span. A minus has nothing above or below, and an
     * underline has nothing below, so neither qualifies.
     */
    private fun fractionSplit(strokes: List<Stroke>): Split? {
        val boxes = strokes.associateWith { it.bounds() }
        val mh = medianHeight(boxes.values.toList())
        val bars = strokes.filter { s ->
            val b = boxes.getValue(s)
            b.w >= 2.2 * mh && b.h <= 0.45 * mh
        }.sortedByDescending { boxes.getValue(it).w }
        for (bar in bars) {
            val b = boxes.getValue(bar)
            val above = strokes.filter { it != bar && boxes.getValue(it).bottom <= b.top + 0.1 * mh }
            val below = strokes.filter { it != bar && boxes.getValue(it).top >= b.bottom - 0.1 * mh }
            if (above.isEmpty() || below.isEmpty()) continue
            if (xOverlap(unionOf(above.map { boxes.getValue(it) }), b) < 0.4 * b.w) continue
            if (xOverlap(unionOf(below.map { boxes.getValue(it) }), b) < 0.4 * b.w) continue
            return Split(above, below)
        }
        return null
    }

    /** Rows split where the vertical gap between successive strokes clears half a glyph. */
    private fun splitRows(strokes: List<Stroke>): List<List<Stroke>> {
        val boxes = strokes.associateWith { it.bounds() }
        val mh = medianHeight(boxes.values.toList())
        val ordered = strokes.sortedBy { boxes.getValue(it).top }
        val rows = ArrayList<MutableList<Stroke>>()
        for (s in ordered) {
            val top = boxes.getValue(s).top
            val row = rows.lastOrNull()
            val bottom = row?.maxOf { boxes.getValue(it).bottom } ?: Double.NEGATIVE_INFINITY
            if (row == null || top - bottom > 0.55 * mh) rows.add(mutableListOf(s)) else row.add(s)
        }
        return rows
    }

    /**
     * One row, left to right: strokes that touch (within a fifth of a glyph) are one
     * symbol; a run that is smaller and rides above the baseline is an exponent,
     * below it an index. Everything unmarked stays a single run.
     */
    private fun structureRow(strokes: List<Stroke>): Node {
        val boxes = strokes.associateWith { it.bounds() }
        val mh = medianHeight(boxes.values.toList())
        val clusters = clusterSymbols(strokes, boxes, mh).sortedBy { c -> c.minOf { boxes.getValue(it).left } }
        if (clusters.size == 1) return Node.Run(clusters[0])
        val heights = clusters.map { c -> c.maxOf { max(boxes.getValue(it).h, 2.0) } }
        val fullH = medianDouble(heights)
        val baseLine = clusters.filterIndexed { i, _ -> heights[i] >= 0.7 * fullH }
            .map { c -> c.sumOf { boxes.getValue(it).centerY } / c.size }
            .let { if (it.isEmpty()) clusters.map { c -> c.sumOf { boxes.getValue(it).centerY } / c.size }.median() else it.median() }
        val parts = ArrayList<Node>()
        var pending = ArrayList<Stroke>()
        fun flushBase() {
            if (pending.isNotEmpty()) {
                parts.add(Node.Run(pending.toList()))
                pending = ArrayList()
            }
        }
        var i = 0
        while (i < clusters.size) {
            val kind = if (i == 0) Kind.BASE else scriptKind(clusters[i], boxes, fullH, baseLine)
            if (kind == Kind.BASE) {
                pending.addAll(clusters[i])
                i++
                continue
            }
            var j = i
            while (j < clusters.size && scriptKind(clusters[j], boxes, fullH, baseLine) == kind) j++
            val script = clusters.subList(i, j).flatten()
            if (pending.isEmpty() && parts.isEmpty()) {
                // A leading script belongs to nothing: read it as written.
                pending.addAll(script)
            } else {
                val base = if (pending.isNotEmpty()) {
                    Node.Run(pending.toList()).also { pending = ArrayList() }
                } else {
                    parts.removeLast()
                }
                val node = if (kind == Kind.SUP) Node.Sup(base, Node.Run(script)) else Node.Sub(base, Node.Run(script))
                parts.add(node)
            }
            i = j
        }
        flushBase()
        return if (parts.size == 1) parts[0] else Node.Seq(parts)
    }

    private enum class Kind { BASE, SUP, SUB }

    private fun scriptKind(cluster: List<Stroke>, boxes: Map<Stroke, Rect>, fullH: Double, baseLine: Double): Kind {
        val h = cluster.maxOf { max(boxes.getValue(it).h, 2.0) }
        if (h >= 0.78 * fullH) return Kind.BASE
        val cy = cluster.sumOf { boxes.getValue(it).centerY } / cluster.size
        if (cy < baseLine - 0.15 * fullH && baseLine - cy < 1.2 * fullH) return Kind.SUP
        if (cy > baseLine + 0.15 * fullH && cy - baseLine < 1.2 * fullH) return Kind.SUB
        return Kind.BASE
    }

    /** Single-linkage over boxes grown by a fifth of a glyph: "i" dots and "=" bars join. */
    private fun clusterSymbols(strokes: List<Stroke>, boxes: Map<Stroke, Rect>, mh: Double): List<List<Stroke>> {
        val parent = IntArray(strokes.size) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            parent[find(a)] = find(b)
        }
        val grown = strokes.map { boxes.getValue(it).outset(0.2 * mh) }
        for (a in strokes.indices) for (b in a + 1 until strokes.size) {
            if (grown[a].intersects(grown[b])) union(a, b)
        }
        return strokes.indices.groupBy { find(it) }.values.map { g -> g.map { strokes[it] } }
    }

    private fun medianHeight(boxes: List<Rect>): Double =
        boxes.map { max(it.h, 2.0) }.median()

    private fun List<Double>.median(): Double = medianDouble(this)

    private fun medianDouble(v: List<Double>): Double {
        if (v.isEmpty()) return 2.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun unionOf(boxes: List<Rect>): Rect = boxes.reduce { a, b -> a.union(b) }

    private fun xOverlap(a: Rect, b: Rect): Double =
        max(0.0, minOf(a.right, b.right) - maxOf(a.left, b.left))
}
