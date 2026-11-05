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
        val pressures: ArrayList<Double> = ArrayList(),
        val style: com.xnotes.sync.WireStrokeStyle? = null,
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
        style: com.xnotes.sync.WireStrokeStyle? = null,
    ) {
        if (done) {
            runs.remove(strokeId)
            return
        }
        val run = runs.getOrPut(strokeId) {
            Run(file, page, tool, width, parseColor(color) ?: Rgba(20, 20, 20, 255), style = style)
        }
        if (run.file != file || run.page != page) return
        for (s in samples) {
            run.points += Pt(s.x, s.y)
            run.pressures += s.p
        }
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
            // Render through the real Stroke painter so pencil grain, taper,
            // highlighter and dashed rhythm match the commit exactly.
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
            val tool = com.xnotes.core.tools.Tool.fromId(run.tool) ?: com.xnotes.core.tools.Tool.PEN
            val base = com.xnotes.core.tools.ToolDefaults.configFor(tool)
                .copy(baseWidth = run.width, rgba = run.color)
            val st = run.style
            val cfg = if (st == null) base else base.copy(
                pressureMinFactor = st.pmin ?: base.pressureMinFactor,
                directionStrength = st.dir ?: base.directionStrength,
                speedStrength = st.speed ?: base.speedStrength,
                taperEnabled = st.taper ?: base.taperEnabled,
                taperMinFactor = st.taperMin ?: base.taperMinFactor,
                neon = st.neon ?: base.neon,
                neonStrength = st.neonS ?: base.neonStrength,
                dashLength = st.dashL ?: base.dashLength,
                dashGap = st.dashG ?: base.dashGap,
                highlighterAlpha = st.hlAlpha ?: base.highlighterAlpha,
                highlighterInverse = st.hlInv ?: base.highlighterInverse,
            )
            val n = run.points.size
            val xs = DoubleArray(n) { run.points[it].x }
            val ys = DoubleArray(n) { run.points[it].y }
            val ps = DoubleArray(n) { run.pressures.getOrElse(it) { 1.0 } }
            val ts = DoubleArray(n) { 0.0 }
            val s = com.xnotes.core.model.Stroke(tool, cfg, emptyList(), 1.0, false, 1.0)
            s.setSamples(xs, ys, ps, ts, n)
            s
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Union of all runs' bounds in viewport px (for partial damage repaints):
     * page-space point bounds → content → viewport, padded for width/AA.
     * A live room drag adds its band, so room previews never force full frames.
     */
    fun dirtyViewportRect(): com.xnotes.core.geometry.Rect? {
        val showing = showingFile ?: return null
        var out: com.xnotes.core.geometry.Rect? = null
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
            out = unionPageRect(run.page, l - pad, t - pad, rr - l + pad * 2, b - t + pad * 2, out)
        }
        state.roomView?.takeIf { it.live }?.let { v ->
            val pi = state.document.pages.indexOf(v.page)
            if (pi >= 0 && state.pageRects.getOrNull(pi) != null) {
                val f = state.footprint(v.page)
                val lo = minOf(v.split, v.resume) + minOf(0.0, v.shift) - 2.0
                val hi = maxOf(v.split, v.resume) + maxOf(0.0, v.shift) + 2.0
                out = if (v.sideways) {
                    unionPageRect(pi, lo, 0.0, hi - lo, f.h, out)
                } else {
                    unionPageRect(pi, 0.0, lo, f.w, hi - lo, out)
                }
            }
        }
        return out
    }

    /** [out] ∪ page-space rect ([x],[y],[w],[h]) on [page] mapped to viewport px. */
    private fun unionPageRect(
        page: Int,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        out: com.xnotes.core.geometry.Rect?,
    ): com.xnotes.core.geometry.Rect? {
        val content = state.fromPageSpaceRect(page, com.xnotes.core.geometry.Rect(x, y, w, h))
        val corners = listOf(
            com.xnotes.core.geometry.Pt(content.left, content.top),
            com.xnotes.core.geometry.Pt(content.right, content.top),
            com.xnotes.core.geometry.Pt(content.left, content.bottom),
            com.xnotes.core.geometry.Pt(content.right, content.bottom),
        ).map { state.contentToViewport(it) }
        var vl = Double.MAX_VALUE
        var vt = Double.MAX_VALUE
        var vr = -Double.MAX_VALUE
        var vb = -Double.MAX_VALUE
        for (c in corners) {
            if (c.x < vl) vl = c.x
            if (c.y < vt) vt = c.y
            if (c.x > vr) vr = c.x
            if (c.y > vb) vb = c.y
        }
        val padV = 3.0
        val r = com.xnotes.core.geometry.Rect(vl - padV, vt - padV, vr - vl + padV * 2, vb - vt + padV * 2)
        return if (out == null) r else out.union(r)
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
