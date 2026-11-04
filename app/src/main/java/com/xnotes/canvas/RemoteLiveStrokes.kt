package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer

/**
 * The far side's live runs: samples arrive per pointer event and paint at once,
 * keyed by stroke id. The committed op drops the run as soon as its model
 * lands, so a pen-up never double-draws. Painted in the interaction overlay.
 */
class RemoteLiveStrokes(private val state: CanvasState) {
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
    fun update(file: String, page: Int, strokeId: String, width: Double, color: Rgba, samples: List<Pt>, done: Boolean) {
        if (done) {
            runs.remove(strokeId)
            return
        }
        val run = runs.getOrPut(strokeId) { Run(file, page, width, color) }
        if (run.file != file || run.page != page) return
        run.points.addAll(samples)
    }

    /** Drop the run (its model landed). */
    fun finish(strokeId: String) {
        runs.remove(strokeId)
    }

    /** Drop every run of [file] (it closed or resynced). */
    fun clearFile(file: String) {
        runs.entries.removeIf { it.value.file == file }
    }

    fun clear() = runs.clear()

    fun draw(r: Renderer) {
        val showing = showingFile ?: return
        for ((_, run) in runs) {
            if (run.file != showing || run.points.size < 2) continue
            val pr = state.pageRects.getOrNull(run.page) ?: continue
            r.withSave {
                r.translate(pr.left, pr.top)
                state.applyPageTransform(r, state.document.pages[run.page])
                r.strokePolyline(run.points, Pen(run.color, run.width, cosmetic = false))
            }
        }
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
