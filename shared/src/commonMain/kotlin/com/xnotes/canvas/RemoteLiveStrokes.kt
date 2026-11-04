package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.sync.WireSample

/**
 * The far side's live runs: samples arrive per pointer event and paint at once,
 * keyed by stroke id. The committed op drops the run as soon as its model
 * lands, so a pen-up never double-draws. Painted in the interaction overlay,
 * over ink. Only runs for [showingFile] draw (set to the open file's sync id).
 */
open class RemoteLiveStrokes(private val state: CanvasState) {
    /** One run in flight, in note-page content coordinates. */
    class Run(
        val file: String,
        val page: Int,
        val tool: String,
        val width: Double,
        val color: Rgba,
        val points: ArrayList<Pt> = ArrayList(),
    )

    /** The open file's sync id; runs for other files never draw. */
    var showingFile: String? = null

    private val runs = LinkedHashMap<String, Run>()

    /** Append [samples] (page-local) to [strokeId]'s run, starting it first. */
    open fun update(
        file: String,
        page: Int,
        strokeId: String,
        tool: String,
        width: Double,
        color: String,
        samples: List<WireSample>,
        done: Boolean,
    ) {
        if (done) {
            runs.remove(strokeId)
            return
        }
        val run = runs.getOrPut(strokeId) {
            Run(file, page, tool, width, parseColor(color) ?: Rgba(20, 20, 20, 255))
        }
        if (run.file != file || run.page != page) return
        for (s in samples) run.points += Pt(s.x, s.y)
    }

    /** Drop the run (its model landed). */
    open fun finish(strokeId: String) {
        runs.remove(strokeId)
    }

    /** Drop every run of [file] (it closed or resynced). */
    open fun clearFile(file: String) {
        runs.keys.filter { runs[it]?.file == file }.forEach { runs.remove(it) }
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

    /** Parse `#rrggbb` (or `#rrggbbaa`); null when malformed. */
    companion object {
        fun parseColor(s: String): Rgba? {
            val h = s.removePrefix("#")
            if (h.length != 6 && h.length != 8) return null
            val v = h.toLongOrNull(16) ?: return null
            return if (h.length == 6) {
                Rgba((v shr 16).toInt() and 0xFF, (v shr 8).toInt() and 0xFF, v.toInt() and 0xFF, 255)
            } else {
                Rgba((v shr 24).toInt() and 0xFF, (v shr 16).toInt() and 0xFF, (v shr 8).toInt() and 0xFF, v.toInt() and 0xFF)
            }
        }
    }
}
