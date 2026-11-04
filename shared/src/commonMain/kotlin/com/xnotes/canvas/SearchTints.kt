package com.xnotes.canvas

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Labelled
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pdf.PageText
import com.xnotes.core.pdf.TextQuads
import com.xnotes.core.platform.identityMap
import com.xnotes.core.search.SearchHit
import com.xnotes.core.search.SearchResults
import com.xnotes.core.search.SearchTarget
import com.xnotes.core.text.FlowFrame
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange

/**
 * The search's matches tinted on the pages shown, over ink so a match under handwriting still
 * shows: every match faint, the current one stronger and outlined. A match's boxes are worked out
 * the first time its page shows and kept: a PDF match's from its glyphs, a flow match's from its
 * laid-out lines, a text box match's from the box's layout.
 */
class SearchTints(
    private val state: CanvasState,
    private val texts: PdfTextSource,
    private val frame: () -> FlowFrame?,
    private val onViewChanged: () -> Unit,
    private val requestRender: () -> Unit,
) {
    /** A match's boxes: for PDF and flow text, page-space rects on [pages]; for a text box, rects from its corner at [width]. */
    private class Boxes(val pages: IntArray, val rects: Array<Rect>, val width: Double = 0.0)

    private var results: SearchResults? = null
    private var current: SearchHit? = null
    private val boxes = identityMap<SearchHit, Boxes>()

    /** The flow layout the cached boxes were worked out against. */
    private var boxFrame: FlowFrame? = null

    /** Pages whose PDF text is being read for their matches, and those it could not be read for. */
    private val reading = HashSet<Int>()
    private val unreadable = HashSet<Int>()

    /** A match waiting on its page's text to be brought into view. */
    private var revealing: SearchHit? = null



    fun show(results: SearchResults?, current: SearchHit?) {
        if (results == null || results.query != this.results?.query) {
            boxes.clear()
            reading.clear()
            unreadable.clear()
            revealing = null
        }
        this.results = results
        this.current = current
        requestRender()
    }

    fun draw(r: Renderer) {
        val res = results ?: return
        if (res.count == 0) return
        val f = frame()
        if (f !== boxFrame) {
            boxes.clear()
            boxFrame = f
        }
        val visible = state.visiblePageRange() ?: return
        val accent = state.palette.accent
        val faint = accent.withAlpha(FAINT_ALPHA)
        for (page in visible) {
            for (hit in res.hitsOn(page)) if (hit !== current) paint(r, hit, faint, null)
        }
        current?.let { paint(r, it, accent.withAlpha(CURRENT_ALPHA), Pen(accent, state.devicePxPerDp)) }
    }

    /**
     * Brings [hit] into view: its page shown, then the match on screen. A PDF match whose page text
     * is still being read goes to its page at once and onto the match when the text lands.
     */
    fun reveal(hit: SearchHit) {
        val shown = state.visiblePageRange()
        val bounds = boundsOf(hit)
        if (bounds != null) {
            revealing = null
            if (hit.page !in state.drawablePageRange()) state.goToPage(hit.page)
            scrollIntoView(bounds)
        } else {
            revealing = hit.takeIf { it.target == SearchTarget.Pdf && it.page !in unreadable }
            if (shown == null || hit.page !in shown) state.goToPage(hit.page)
        }
        onViewChanged()
        requestRender()
    }

    private fun paint(r: Renderer, hit: SearchHit, color: Rgba, outline: Pen?) {
        val b = boxesOf(hit) ?: return
        val target = hit.target
        if (target is SearchTarget.Label) {
            // A label turns with its shape, so its pieces are drawn as turned quads.
            for (k in b.rects.indices) {
                val quad = labelQuad(hit, target.item, b.rects[k]) ?: continue
                r.fillPolygon(quad, color)
                if (outline != null) r.strokePolygon(quad, outline)
            }
            return
        }
        for (k in b.rects.indices) {
            val rect = contentRect(hit, b, k) ?: continue
            r.fillRect(rect, color)
            if (outline != null) r.strokeRect(rect, outline)
        }
    }

    private fun boundsOf(hit: SearchHit): Rect? {
        val b = boxesOf(hit) ?: return null
        val target = hit.target
        if (target is SearchTarget.Label) {
            val corners = b.rects.mapNotNull { labelQuad(hit, target.item, it) }.flatten()
            return if (corners.isEmpty()) null else Rect.bounding(corners)
        }
        var acc: Rect? = null
        for (k in b.rects.indices) contentRect(hit, b, k)?.let { acc = acc?.union(it) ?: it }
        return acc
    }

    private fun contentRect(hit: SearchHit, b: Boxes, k: Int): Rect? {
        val target = hit.target
        if (target is SearchTarget.Box) {
            if (state.pageRects.getOrNull(hit.page) == null) return null
            val rel = b.rects[k]
            return state.fromPageSpaceRect(hit.page, Rect(target.item.pos.x + rel.left, target.item.pos.y + rel.top, rel.w, rel.h))
        }
        val page = b.pages[k]
        if (state.pageRects.getOrNull(page) == null) return null
        return state.fromPageSpaceRect(page, b.rects[k])
    }

    private fun boxesOf(hit: SearchHit): Boxes? {
        val target = hit.target
        boxes[hit]?.let { b ->
            val fresh = when (target) {
                is SearchTarget.Box -> b.width == target.item.width
                is SearchTarget.Label -> b.width == target.item.labelFrame()?.w
                else -> true
            }
            if (fresh) return b
        }
        val b = when (target) {
            SearchTarget.Pdf -> {
                val text = texts.peek(hit.page) ?: return readPage(hit.page)
                pdfBoxes(hit, text)
            }
            is SearchTarget.Flow -> flowBoxes(hit, target.para)
            is SearchTarget.Box -> textBoxBoxes(hit, target.item)
            is SearchTarget.Label -> labelBoxes(hit, target.item) ?: return null
        }
        boxes[hit] = b
        return b
    }

    /** Reads [page]'s PDF text, working out every match on it once it lands; null meanwhile. */
    private fun readPage(page: Int): Boxes? {
        if (page in unreadable || !reading.add(page)) return null
        val asked = results?.query
        texts.request(page) { text ->
            reading.remove(page)
            val res = results
            if (res == null || res.query != asked) return@request
            // Every match on the page at once, while its text is at hand: the cache keeps only a few pages.
            if (text == null) unreadable += page
            else for (hit in res.hitsOn(page)) if (hit.target == SearchTarget.Pdf) boxes[hit] = pdfBoxes(hit, text)
            revealing?.takeIf { it.page == page }?.let(::reveal)
            requestRender()
        }
        return null
    }

    private fun pdfBoxes(hit: SearchHit, text: PageText): Boxes {
        val quads = TextQuads.of(text, hit.sourceStart, hit.sourceEnd)
        val s = state.document.dpi / 72.0
        // Rooms do not exist on this canvas: text lies on the page as it is.
        val rects = quads.map { q ->
            Rect(q.left * s, q.top * s, (q.right - q.left) * s, (q.bottom - q.top) * s)
        }
        return Boxes(IntArray(rects.size) { hit.page }, rects.toTypedArray())
    }

    private fun flowBoxes(hit: SearchHit, para: Int): Boxes {
        val rects = frame()?.selectionRects(FlowRange(FlowPos(para, hit.sourceStart), FlowPos(para, hit.sourceEnd))).orEmpty()
        return Boxes(IntArray(rects.size) { rects[it].first }, Array(rects.size) { rects[it].second })
    }

    /** A text box match tints the whole box: the shared measurer lays out no lines to split it by. */
    private fun textBoxBoxes(hit: SearchHit, item: TextItem): Boxes {
        val box = Rect(0.0, 0.0, item.width, item.height.coerceAtLeast(1.0))
        return Boxes(IntArray(0), arrayOf(box), item.width)
    }

    /** A label match tints the whole label frame: the shared measurer lays out no lines to split it by. */
    private fun labelBoxes(hit: SearchHit, item: Labelled): Boxes? {
        val frame = item.labelFrame() ?: return null
        return Boxes(IntArray(0), arrayOf(Rect(0.0, 0.0, frame.w, frame.h.coerceAtLeast(1.0))), frame.w)
    }

    /** [rel], a piece of a label match from its frame's corner, as the turned quad it covers in content space. */
    private fun labelQuad(hit: SearchHit, item: Labelled, rel: Rect): List<Pt>? {
        if (state.pageRects.getOrNull(hit.page) == null) return null
        val frame = item.labelFrame() ?: return null
        val r = Rect(frame.left + rel.left, frame.top + rel.top, rel.w, rel.h)
        return listOf(r.topLeft, Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom))
            .map { state.fromPageSpace(hit.page, item.labelToPage(it)) }
    }

    /** Scrolls the least that centres [c] on whichever axis it is off screen. */
    private fun scrollIntoView(c: Rect) {
        val margin = REVEAL_MARGIN_DP * state.devicePxPerDp
        val tl = state.contentToViewport(Pt(c.left, c.top))
        val br = state.contentToViewport(Pt(c.right, c.bottom))
        var dx = 0.0
        if (br.x - tl.x > state.viewportW - 2 * margin) dx = (tl.x + br.x) / 2 - state.viewportW / 2
        else if (tl.x < margin) dx = tl.x - margin
        else if (br.x > state.viewportW - margin) dx = br.x - (state.viewportW - margin)
        var dy = 0.0
        if (br.y - tl.y > state.viewportH - 2 * margin) dy = (tl.y + br.y) / 2 - state.viewportH / 2
        else if (tl.y < margin) dy = tl.y - margin
        else if (br.y > state.viewportH - margin) dy = br.y - (state.viewportH - margin)
        if (dx != 0.0 || dy != 0.0) state.scrollBy(-dx, -dy)
    }

    private companion object {
        /** Accent at 25% for every match, 45% for the current one. */
        const val FAINT_ALPHA = 64
        const val CURRENT_ALPHA = 115

        /** Room kept between a match brought into view and the edges. */
        const val REVEAL_MARGIN_DP = 48.0
    }
}
