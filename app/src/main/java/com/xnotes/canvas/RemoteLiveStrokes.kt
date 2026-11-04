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
        val width: Double,
        val color: Rgba,
        val points: ArrayList<Pt> = ArrayList(),
    )

    /** The open file's sync id; runs for other files never draw. */
    var showingFile: String? = null

    private val runs = LinkedHashMap<String, Run>()

    /** Append [samples] (page-local) to [strokeId]'s run, starting it first. */
    open fun update(file: String, page: Int, strokeId: String, width: Double, color: Rgba, samples: List<Pt>, done: Boolean) {
        if (done) {
            runs.remove(strokeId)
            return
        }
        val run = runs.getOrPut(strokeId) { Run(file, page, width, color) }
        if (run.file != file || run.page != page) return
        run.points.addAll(samples)
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
            // NOTE: the caller (drawOverlay) already translated to content space
            // (origin + zoom); page coords only need the page offset + transform.
            r.withSave {
                r.translate(pr.left, pr.top)
                state.applyPageTransform(r, state.document.pages[run.page])
                r.strokePolyline(run.points, Pen(run.color, run.width, cosmetic = false))
            }
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
