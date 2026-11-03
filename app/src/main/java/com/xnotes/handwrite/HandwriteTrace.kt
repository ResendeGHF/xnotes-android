package com.xnotes.handwrite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.scan.InkVectorizer

/**
 * Scanned pages (no text layer) traced to editable pen strokes: the same
 * [InkVectorizer] centreline pipeline the scan tracer uses, mapped onto pen
 * strokes like [com.xnotes.scan.TraceDocument] (`smoothScale = 0`, so short
 * fragments render exactly as traced). Pure and JVM-testable.
 */
object HandwriteTrace {

    /** One rendered PDF page's trace result, in render pixels. */
    class ScanPage(val paths: List<InkVectorizer.InkPath>, val w: Int, val h: Int)

    /**
     * Build strokes from [page]'s paths, scaled by [scale] and offset by
     * [origin] (both in page px). Non-finite points are skipped; a path left
     * with no usable sample is dropped. Returns the strokes in path order.
     */
    fun toStrokes(
        page: ScanPage,
        ink: Rgba,
        inkWidthPx: Double,
        origin: Pt = Pt.ZERO,
        scale: Double = 1.0,
    ): List<Stroke> {
        if (page.paths.isEmpty() || !scale.isFinite() || scale <= 0.0) return emptyList()
        val config = ToolDefaults.configFor(Tool.PEN).copy(
            baseWidth = inkWidthPx.coerceIn(0.5, 40.0),
            rgba = ink,
        )
        val out = ArrayList<Stroke>(page.paths.size)
        for (path in page.paths) {
            val pts = path.points
            if (pts.isEmpty()) continue
            val samples = ArrayList<Sample>(pts.size)
            for ((i, p) in pts.withIndex()) {
                if (!p.x.isFinite() || !p.y.isFinite()) continue
                val pressure = path.pressures.getOrNull(i)?.coerceIn(0.0, 1.0)
                    ?.takeIf { it.isFinite() } ?: 0.5
                samples.add(Sample(origin.x + p.x * scale, origin.y + p.y * scale, pressure))
            }
            // One sample still draws: the engine renders it as a filled dot,
            // which is what an i-dot or a period becomes.
            if (samples.isEmpty()) continue
            val stroke = Stroke(Tool.PEN, config, smoothScale = 0.0)
            stroke.setSamples(samples)
            stroke.finished = true
            stroke.trimToSize()
            out.add(stroke)
        }
        return out
    }
}
