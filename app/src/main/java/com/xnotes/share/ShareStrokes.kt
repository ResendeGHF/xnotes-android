package com.xnotes.share

import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig

/**
 * The companion canvas's stroke dialect: plain JSON polylines in PDF points,
 * deliberately dumber than the native model (no pressure curves, no calligraphy,
 * no effects). The server translates both ways at the boundary:
 *
 * - device → companion: every native [Stroke] on the page becomes a polyline
 *   (pressure is baked out; a highlighter stays a highlighter).
 * - companion → device: each uploaded polyline becomes a real, editable
 *   native stroke appended to the page — never pixels, never flattened.
 *
 * Coordinates are PDF points measured from the page's top-left, y down, so the
 * companion never needs the device's content-px scale (`dpi` rides in
 * `/api/canvas` and both sides convert with it).
 */
object ShareStrokes {
    /** A single uploaded stroke, in PDF points. */
    data class WireStroke(
        val tool: String,
        val color: String,
        val width: Double,
        val points: List<Double>,
    )

    /** Largest single upload the server accepts (points are ~16 bytes of JSON each). */
    const val MAX_POINTS_PER_STROKE = 8192

    /** Largest batch per POST; the save is one sealed rewrite either way. */
    const val MAX_STROKES_PER_POST = 256

    /** Native → wire, or null when the item has nothing drawable. */
    fun toWire(stroke: Stroke, pxToPt: Double): WireStroke? {
        if (stroke.sampleCount <= 0) return null
        val pts = ArrayList<Double>(stroke.sampleCount * 2)
        for (s in stroke.samples) {
            val x = s.x * pxToPt
            val y = s.y * pxToPt
            if (!x.isFinite() || !y.isFinite()) return null
            pts.add(x)
            pts.add(y)
        }
        if (pts.isEmpty()) return null
        val tool = if (stroke.tool == Tool.HIGHLIGHTER) "highlighter" else "pen"
        val c = stroke.config.rgba
        return WireStroke(tool, "#%02x%02x%02x".format(c.r, c.g, c.b), stroke.config.baseWidth * pxToPt, pts)
    }

    /** Wire → native, or null when the upload is malformed (never throws). */
    fun toNative(wire: WireStroke, ptToPx: Double): Stroke? {
        val tool = when (wire.tool) {
            "pen" -> Tool.PEN
            "highlighter" -> Tool.HIGHLIGHTER
            else -> return null
        }
        val rgba = parseColor(wire.color) ?: return null
        if (!wire.width.isFinite() || wire.width <= 0.0 || wire.width > 200.0) return null
        if (wire.points.size < 2 || wire.points.size % 2 != 0) return null
        if (wire.points.size > MAX_POINTS_PER_STROKE * 2) return null
        val samples = ArrayList<Sample>(wire.points.size / 2)
        var i = 0
        while (i < wire.points.size) {
            val x = wire.points[i] * ptToPx
            val y = wire.points[i + 1] * ptToPx
            if (!x.isFinite() || !y.isFinite()) return null
            samples.add(Sample(x, y, 1.0))
            i += 2
        }
        if (samples.size == 1) samples.add(samples[0].copy())
        val widthPx = wire.width * ptToPx
        val config = ToolConfig(baseWidth = widthPx, rgba = rgba, pressureEnabled = false)
        return Stroke(tool, config, samples)
    }

    fun parseColor(s: String): Rgba? {
        val v = s.trim().removePrefix("#")
        if (v.length != 6) return null
        val n = v.toIntOrNull(16) ?: return null
        return Rgba((n shr 16) and 0xFF, (n shr 8) and 0xFF, n and 0xFF)
    }
}
