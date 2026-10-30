package com.xnotes.canvas

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer

/**
 * Boxes around the PDF link annotations on the pages shown, over the page background so a
 * link under handwriting still reads. Drawn every frame from the held link cache (see
 * PdfSource.cachedLinks): a page whose links have not parsed yet draws nothing until they
 * land and the view repaints. View-only: caches, thumbnails and export never see it.
 */
class LinkOverlay(
    private val state: CanvasState,
    /** Note page index -> its link boxes in page space, or null when unknown yet. */
    private val boxes: (notePage: Int) -> List<Rect>?,
) {
    fun draw(r: Renderer) {
        val visible = state.visibleContentRect()
        val accent = state.palette.accent
        val fill = accent.withAlpha(FILL_ALPHA)
        val pen = Pen(accent, OUTLINE_WIDTH, cosmetic = true)
        for (i in state.drawablePageRange()) {
            val pr = state.pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            val rects = boxes(i) ?: continue
            for (b in rects) {
                if (b.w <= 0.0 || b.h <= 0.0) continue
                val content = state.fromPageSpaceRect(i, b)
                r.fillRect(content, fill)
                r.strokeRect(content, pen)
            }
        }
    }

    private companion object {
        /** Accent at ~15% for the wash inside each box. */
        const val FILL_ALPHA = 38

        /** Cosmetic outline width (device px, not zoom-scaled). */
        const val OUTLINE_WIDTH = 1.0
    }
}
