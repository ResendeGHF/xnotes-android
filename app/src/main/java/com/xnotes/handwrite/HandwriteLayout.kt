package com.xnotes.handwrite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextMeasurer

/**
 * Blocks ([HBlock]) laid out as placeable rows: glyph lines, math boxes and
 * tables stacked in block coordinates (x from 0, y from 0), each row carrying
 * its own top, height and items. The paginator slides rows onto pages without
 * knowing what they hold. Pure except for the platform text/math backends,
 * which the caller supplies; JVM tests use the fakes.
 */
/** One stacked row: block-relative [top], [height], and the items sitting in block coordinates. */
class FlowRow(val top: Double, var height: Double, val items: List<CanvasItem>)

/**
 * Stack [blocks] at [wrapWidth] in [ink], body text at [basePt]. Returns rows
 * in order; total height is the last row's end.
 */
fun layoutBlocks(
    blocks: List<HBlock>,
    measurer: TextMeasurer,
    ink: Rgba,
    basePt: Double,
    wrapWidth: Double,
): List<FlowRow> {
    val wrap = wrapWidth.coerceAtLeast(40.0)
    val base = basePt.coerceIn(HandwriteGlyphs.MIN_POINT, HandwriteGlyphs.MAX_POINT)
    val baseLh = measurer.lineHeight(FontSpec(base, FontFace.HAND)).coerceAtLeast(1.0)
    val rows = ArrayList<FlowRow>()
    var cursor = 0.0
    for (b in blocks) {
        when (b) {
            is HBlock.Para -> {
                val size = (base * b.sizeMult).coerceIn(8.0, 72.0)
                val font = FontSpec(size, FontFace.HAND)
                val ascent = measurer.metrics(font).ascent.coerceAtLeast(1.0)
                for (seg in b.segments) {
                    when (seg) {
                        is HSeg.Words -> {
                            if (seg.text.isBlank()) continue
                            for (line in HandwriteGlyphs.toLines(seg.text, measurer, font, wrap, ink)) {
                                val shape = line.shape
                                if (shape == null) {
                                    rows.add(FlowRow(cursor, line.advance, emptyList()))
                                } else {
                                    val top = shape.bounds().top
                                    shape.translate(0.0, cursor + ascent - line.rise - top)
                                    rows.add(FlowRow(cursor, line.advance, listOf(shape)))
                                }
                                cursor += line.advance
                            }
                        }
                        is HSeg.Formula -> {
                            if (seg.latex.isBlank()) continue
                            for (row in mathRows(seg.latex, measurer, ink, size, wrap, cursor, baseLh)) {
                                rows.add(row)
                                cursor = row.top + row.height
                            }
                        }
                    }
                }
            }
            is HBlock.Math -> {
                if (b.latex.isBlank()) continue
                for (row in mathRows(b.latex, measurer, ink, base, wrap, cursor, baseLh)) {
                    rows.add(row)
                    cursor = row.top + row.height
                }
            }
            is HBlock.Table -> {
                if (b.rows.isEmpty()) continue
                val row = tableRow(b.rows, measurer, ink, base, wrap, cursor, baseLh)
                if (row != null) {
                    rows.add(row)
                    cursor = row.top + row.height
                }
            }
            HBlock.Gap -> {
                rows.add(FlowRow(cursor, baseLh, emptyList()))
                cursor += baseLh
            }
        }
    }
    return rows
}

/**
 * One equation as an editable math box centred on [wrap], or — when no
 * typesetter is installed or the source will not set — its source as words so
 * nothing is ever silently dropped.
 */
private fun mathRows(
    latex: String,
    measurer: TextMeasurer,
    ink: Rgba,
    size: Double,
    wrap: Double,
    cursor: Double,
    baseLh: Double,
): List<FlowRow> {
    var s = size.coerceIn(8.0, 72.0)
    var box = probeMath(latex, measurer, ink, s, wrap)?.coerceWidth(wrap)
    if (box == null) {
        val out = ArrayList<FlowRow>()
        var at = cursor
        val font = FontSpec(s, FontFace.HAND)
        val ascent = measurer.metrics(font).ascent.coerceAtLeast(1.0)
        for (line in HandwriteGlyphs.toLines(latex, measurer, font, wrap, ink)) {
            val shape = line.shape
            if (shape == null) {
                out.add(FlowRow(at, line.advance, emptyList()))
            } else {
                shape.translate(0.0, at + ascent - line.rise - shape.bounds().top)
                out.add(FlowRow(at, line.advance, listOf(shape)))
            }
            at += line.advance
        }
        return out
    }
    val h = box.mathBox()?.height ?: baseLh
    box.pos = Pt((wrap - box.width) / 2.0, cursor)
    return listOf(FlowRow(cursor, h + 0.25 * baseLh, listOf(box)))
}

/** A probe math box with a usable size, shrinking once to fit [wrap]. */
private fun probeMath(latex: String, measurer: TextMeasurer, ink: Rgba, size: Double, wrap: Double): TextItem? {
    fun probe(s: Double): TextItem? {
        val box = TextItem(Pt(0.0, 0.0), wrap, 0.0, latex, ink, s, FontFace.HAND, measurer, math = true)
        val mb = box.mathBox() ?: return null
        if (mb.width <= 0.0 || mb.height <= 0.0) return null
        return box
    }
    val first = probe(size) ?: return null
    val w = first.mathBox()!!.width
    if (w <= wrap) {
        first.width = w
        return first
    }
    val shrunk = probe((size * wrap / w).coerceAtLeast(8.0)) ?: return first.also { it.width = wrap }
    shrunk.width = minOf(shrunk.mathBox()!!.width, wrap)
    return shrunk
}

private fun TextItem.coerceWidth(wrap: Double): TextItem = also { width = width.coerceAtMost(wrap) }

/**
 * Cell grid as an editable table spanning up to [wrap]. Columns share the
 * width by content; rows are tall enough that each cell's text sets on one
 * line (tables never wrap). Capped at 80 rows × 10 columns.
 */
private fun tableRow(
    grid: List<List<String>>,
    measurer: TextMeasurer,
    ink: Rgba,
    base: Double,
    wrap: Double,
    cursor: Double,
    baseLh: Double,
): FlowRow? {
    val unit = FontSpec(1.0)
    val rows = grid.take(80).map { r -> r.map { it.replace(Regex("""\s+"""), " ").trim() } }
    if (rows.isEmpty()) return null
    val cols = rows.maxOf { it.size }.coerceIn(1, 10)
    val cells = rows.map { r -> List(cols) { i -> r.getOrNull(i).orEmpty() } }
    val lh = measurer.lineHeight(FontSpec(base)).coerceAtLeast(1.0)
    var rowH = maxOf(lh * 1.7, 26.0)
    var size = rowH * 0.3
    val colUnits = DoubleArray(cols) { c -> cells.maxOf { r -> measurer.advances(r[c], unit).sum() } }
    val pads = 12.0 * cols
    val unitSum = colUnits.sum()
    if (unitSum > 0.0) {
        val fit = (wrap - pads) / unitSum
        if (fit < size) {
            size = fit.coerceAtLeast(7.0)
            rowH = size / 0.3
        }
    }
    val colW = DoubleArray(cols) { c -> colUnits[c] * size + 12.0 }
    val totalW = colW.sum()
    val (tableW, fractions) = if (totalW <= 0.0) {
        wrap to List(cols) { 1.0 / cols }
    } else {
        minOf(totalW, wrap) to colW.map { it / totalW }
    }
    val totalH = rowH * cells.size
    val table = TableItem.create(
        com.xnotes.core.geometry.Rect(0.0, cursor, tableW, totalH),
        cols, cells.size, ink, 2.0,
    )
    table.colFractions.clear()
    table.colFractions.addAll(fractions)
    val rowFractions = List(cells.size) { 1.0 / cells.size }
    table.rowFractions.clear()
    table.rowFractions.addAll(rowFractions)
    table.setCells(cells)
    return FlowRow(cursor, totalH + 0.25 * baseLh, listOf(table))
}
