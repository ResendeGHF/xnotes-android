package com.xnotes.handwrite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.shapes.ShapeKind

/**
 * Text laid out as solid handwriting: one filled [ShapeKind.GLYPHS] shape per
 * visual line, built from the font's vector outlines (the same seam shape
 * labels use in `ShapeLabel.asGlyphs`).
 *
 * Solid glyphs — not thinned centrelines — are what make the result read like
 * handwritten notes: a skeleton of small print shatters into fragments, while
 * outlines stay exactly the letters at any zoom. Pure and JVM-testable (the
 * JVM tests use `FakeTextMeasurer`).
 */
object HandwriteGlyphs {

    /** One visual line: [shape] in block coordinates (null for a blank-paragraph
    spacer), [rise] how far its ink reaches above its baseline, and [advance]
    how far the pen cursor moves down past it. */
    class HandLine(val shape: ShapeItem?, var rise: Double, var advance: Double)

    /**
     * Set [text] in [font] wrapped at [wrapWidth] in [ink]. Blank paragraphs
     * become spacers so paragraph breaks survive (the outliner drops
     * whitespace-only lines). Empty when there is nothing to set.
     */
    fun toLines(
        text: String,
        measurer: TextMeasurer,
        font: FontSpec,
        wrapWidth: Double,
        ink: Rgba,
        flags: TextFlags = TextFlags(),
    ): List<HandLine> {
        if (text.isBlank()) return emptyList()
        val wrap = wrapWidth.coerceAtLeast(40.0)
        val lineHeight = measurer.lineHeight(font).coerceAtLeast(1.0)
        val out = ArrayList<HandLine>()
        for (para in text.split('\n')) {
            if (para.isBlank()) {
                out.add(HandLine(null, 0.0, lineHeight))
                continue
            }
            var any = false
            for (line in measurer.outlineLines(para, font, wrap, flags)) {
                if (line.contours.isEmpty()) continue
                val local = FreePath.map(line.contours) { Pt(it.x + line.origin.x, it.y + line.origin.y) }
                val lb = FreePath.bounds(local) ?: continue
                if (lb.w <= 0.0 || lb.h <= 0.0) continue
                val shape = ShapeItem(
                    ShapeKind.GLYPHS,
                    Pt(lb.left, lb.top),
                    Pt(lb.right, lb.bottom),
                    strokeRgba = ink,
                    strokeWidth = GLYPH_PEN,
                    fillRgba = ink,
                    path = FreePath.normalize(local, lb),
                )
                out.add(HandLine(shape, line.origin.y - lb.top, lineHeight))
                any = true
            }
            // A paragraph the outliner cannot set still moves the cursor on.
            if (!any) out.add(HandLine(null, 0.0, lineHeight))
        }
        return out
    }

    /** Ink width of the widest line, for centring the block on the page. */
    fun blockWidth(lines: List<HandLine>): Double {
        var w = 0.0
        for (l in lines) {
            val s = l.shape ?: continue
            val b = s.bounds()
            if (b.w > w) w = b.w
        }
        return w
    }

    /** Drawn text is never stroked; its width only pads its bounds. */
    private const val GLYPH_PEN = 0.5

    /** Point-size range the dialog offers. */
    const val MIN_POINT = 12.0
    const val MAX_POINT = 32.0
}
