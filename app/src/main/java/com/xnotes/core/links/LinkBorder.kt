package com.xnotes.core.links

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer

/**
 * Colored frame drawn around pages that a link-aware export appended, tinted with
 * the [com.xnotes.core.model.NoteLinkItem.color] of the link they came from, so the
 * reader always sees which content belongs to which link.
 */
object LinkBorder {
    /** Page-space border width, in content px. */
    const val WIDTH = 6.0

    fun paint(page: Page, r: Renderer) {
        val c = page.linkBorder ?: return
        if (page.width <= 0.0 || page.height <= 0.0) return
        r.strokeRect(Rect(0.0, 0.0, page.width, page.height), Pen(c, width = WIDTH, cosmetic = false))
    }
}
