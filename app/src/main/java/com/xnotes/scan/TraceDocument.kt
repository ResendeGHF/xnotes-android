package com.xnotes.scan

import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults

/**
 * Turns traced paths into a note: one page per source page, sized like the
 * source's aspect, holding editable [Stroke]s. The scan/photo background is
 * discarded — only the ink stays.
 */
object TraceDocument {

    /** One source page's trace result, in working-image pixels. */
    data class TracedPage(
        val paths: List<InkVectorizer.InkPath>,
        val workW: Int,
        val workH: Int,
    )

    /**
     * Build a [Document] of [pages], one note page per traced page. Each note page
     * is [pageWidthPx] wide and keeps its source's aspect (clamped to 1:4 either
     * way). Every path becomes an editable pressure-sensitive pen stroke in [ink]
     * — single-point paths become dots — so the note keeps the weight of the
     * original pen; [inkWidthPx] is the typical stroke's width in page px.
     */
    fun build(
        pages: List<TracedPage>,
        dpi: Int,
        pageWidthPx: Double,
        ink: Rgba,
        inkWidthPx: Double,
    ): Document {
        val doc = Document(dpi = dpi)
        // Pressure stays on (the PEN default): each sample's pressure, sampled
        // from the scan's stroke radius, swells and thins the ink like the pen.
        val config = ToolDefaults.configFor(Tool.PEN).copy(
            baseWidth = inkWidthPx.coerceIn(0.5, 40.0),
            rgba = ink,
        )
        for (tp in pages) {
            if (tp.workW <= 0 || tp.workH <= 0) continue
            val ratio = (tp.workH.toDouble() / tp.workW).coerceIn(0.25, 4.0)
            val pw = pageWidthPx
            val ph = pageWidthPx * ratio
            val page = Page(pw, ph)
            val sx = pw / tp.workW
            val sy = ph / tp.workH
            for (path in tp.paths) {
                val pts = path.points
                if (pts.isEmpty()) continue
                val samples = ArrayList<Sample>(pts.size)
                for ((i, p) in pts.withIndex()) {
                    if (!p.x.isFinite() || !p.y.isFinite()) continue
                    val pressure = path.pressures.getOrNull(i)?.coerceIn(0.0, 1.0) ?: 0.5
                    samples.add(Sample(p.x * sx, p.y * sy, pressure))
                }
                // One sample still draws: the engine renders it as a filled dot,
                // which is what an i-dot or a period becomes.
                if (samples.isEmpty()) continue
                val stroke = Stroke(Tool.PEN, config)
                stroke.setSamples(samples)
                stroke.finished = true
                stroke.trimToSize()
                page.items.add(stroke)
            }
            doc.pages.add(page)
        }
        if (doc.pages.isEmpty()) {
            val (w, h) = com.xnotes.core.model.PageSize.A4.pixels(
                com.xnotes.core.model.Orientation.PORTRAIT, dpi,
            )
            doc.pages.add(Page(w, h))
        }
        doc.created = System.currentTimeMillis()
        return doc
    }
}
