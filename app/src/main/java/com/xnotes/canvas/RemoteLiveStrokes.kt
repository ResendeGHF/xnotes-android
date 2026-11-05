package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer

/**
 * The far side's live runs: samples arrive per pointer event and paint at once,
 * keyed by stroke id. The committed op drops the run as soon as its model
 * lands, so a pen-up never double-draws. Painted in the interaction overlay.
 */
open class RemoteLiveStrokes(private val state: CanvasState) {
    class Run(
        val file: String,
        val page: Int,
        val tool: com.xnotes.core.tools.Tool,
        val config: com.xnotes.core.tools.ToolConfig,
        val points: ArrayList<Pt> = ArrayList(),
        val pressures: ArrayList<Double> = ArrayList(),
    ) {
        val width: Double get() = config.baseWidth
        val color: Rgba get() = config.rgba
    }

    /** The open file's sync id; runs for other files never draw. */
    var showingFile: String? = null

    private val runs = LinkedHashMap<String, Run>()

    /** Append [samples] (page-local) to [strokeId]'s run, starting it first. */
    open fun update(file: String, page: Int, strokeId: String, width: Double, color: Rgba, samples: List<Pt>, done: Boolean) {
        updateFull(file, page, strokeId, com.xnotes.core.tools.Tool.PEN,
            com.xnotes.core.tools.ToolConfig(baseWidth = width, rgba = color),
            samples, List(samples.size) { 1.0 }, done)
    }

    /**
     * Full-style live update: tool + config travel with the run so pencil,
     * taper, highlighter and dashed pens preview exactly as they will commit.
     * Old callers without style funnel through [update].
     */
    open fun updateFull(
        file: String,
        page: Int,
        strokeId: String,
        tool: com.xnotes.core.tools.Tool,
        config: com.xnotes.core.tools.ToolConfig,
        samples: List<Pt>,
        pressures: List<Double>,
        done: Boolean,
    ) {
        if (done) {
            runs.remove(strokeId)
            return
        }
        val run = runs.getOrPut(strokeId) { Run(file, page, tool, config) }
        if (run.file != file || run.page != page) return
        run.points.addAll(samples)
        run.pressures.addAll(pressures.ifEmpty { List(samples.size) { 1.0 } })
    }

    /** Drop the run (its model landed). */
    open fun finish(strokeId: String) {
        runs.remove(strokeId)
    }

    /** Drop every run of [file] (it closed or resynced). */
    open fun clearFile(file: String) {
        runs.entries.removeIf { it.value.file == file }
    }

    open fun clear() = runs.clear()

    open fun draw(r: Renderer) {
        val showing = showingFile ?: return
        for ((_, run) in runs) {
            if (run.file != showing || run.points.size < 2) continue
            val pr = state.pageRects.getOrNull(run.page) ?: continue
            // Render through the real Stroke painter so pencil grain, taper,
            // highlighter multiply and dashed rhythm match the commit exactly.
            val stroke = runToStroke(run) ?: continue
            r.withSave {
                r.translate(pr.left, pr.top)
                state.applyPageTransform(r, state.document.pages[run.page])
                stroke.paint(r)
            }
        }
    }

    private fun runToStroke(run: Run): com.xnotes.core.model.Stroke? {
        return try {
            val n = run.points.size
            val xs = DoubleArray(n) { run.points[it].x }
            val ys = DoubleArray(n) { run.points[it].y }
            val ps = DoubleArray(n) { run.pressures.getOrElse(it) { 1.0 } }
            val ts = DoubleArray(n) { 0.0 }
            val s = com.xnotes.core.model.Stroke(run.tool, run.config, emptyList(), 1.0, false, 1.0)
            s.setSamples(xs, ys, ps, ts, n)
            s
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Union of all runs' bounds in viewport px (for partial-damage repaints):
     * page-space point bounds → content → viewport, padded for width/AA.
     */
    open fun dirtyViewportRect(): Rect? {
        val showing = showingFile ?: return null
        var out: Rect? = null
        for ((_, run) in runs) {
            if (run.file != showing || run.points.size < 2) continue
            if (state.pageRects.getOrNull(run.page) == null) continue
            var l = Double.MAX_VALUE
            var t = Double.MAX_VALUE
            var rr = -Double.MAX_VALUE
            var b = -Double.MAX_VALUE
            for (p in run.points) {
                if (p.x < l) l = p.x
                if (p.y < t) t = p.y
                if (p.x > rr) rr = p.x
                if (p.y > b) b = p.y
            }
            val pad = run.width / 2.0 + 2.0
            val content = state.fromPageSpaceRect(run.page, Rect(l - pad, t - pad, rr - l + pad * 2, b - t + pad * 2))
            val corners = listOf(
                Pt(content.left, content.top),
                Pt(content.right, content.top),
                Pt(content.left, content.bottom),
                Pt(content.right, content.bottom),
            ).map { state.contentToViewport(it) }
            out = Rect.bounding(corners).outset(3.0).let { if (out == null) it else out.union(it) }
        }
        // A live room drag (either side's): the band it cuts, so previews stay partial.
        state.roomView?.takeIf { it.live }?.let { v ->
            val pi = state.document.pages.indexOf(v.page)
            if (pi >= 0 && state.pageRects.getOrNull(pi) != null) {
                val f = state.footprint(v.page)
                val lo = minOf(v.split, v.resume) + minOf(0.0, v.shift) - 2.0
                val hi = maxOf(v.split, v.resume) + maxOf(0.0, v.shift) + 2.0
                val band = if (v.sideways) Rect(lo, 0.0, hi - lo, f.h) else Rect(0.0, lo, f.w, hi - lo)
                val content = state.fromPageSpaceRect(pi, band)
                val corners = listOf(
                    Pt(content.left, content.top),
                    Pt(content.right, content.top),
                    Pt(content.left, content.bottom),
                    Pt(content.right, content.bottom),
                ).map { state.contentToViewport(it) }
                val added = Rect.bounding(corners).outset(3.0)
                out = if (out == null) added else out.union(added)
            }
        }
        return out
    }

    companion object {
        /** Parse `#rrggbb` (or `#rrggbbaa`); null when malformed. */
        fun parseColor(s: String): Rgba? {
            val h = s.removePrefix("#")
            if (h.length != 6 && h.length != 8) return null
            val v = h.toLongOrNull(16) ?: return null
            return if (h.length == 6) {
                Rgba(v.shr(16).toInt() and 0xFF, v.shr(8).toInt() and 0xFF, v.toInt() and 0xFF, 255)
            } else {
                Rgba(v.shr(24).toInt() and 0xFF, v.shr(16).toInt() and 0xFF, v.shr(8).toInt() and 0xFF, v.toInt() and 0xFF)
            }
        }
    }
}
