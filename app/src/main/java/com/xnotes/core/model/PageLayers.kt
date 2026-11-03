package com.xnotes.core.model

/**
 * A note page's items either side of its typed text. The run of fills at the very bottom of the page
 * paints under the text, like colour on the paper; everything else paints over it in z-order. Every
 * painter of a note page splits its items here, so the screen, thumbnails and exports agree.
 */
object PageLayers {

    /** How many of [items] are the fills at the very bottom, which paint under typed text. */
    fun underText(items: List<CanvasItem>): Int {
        var n = 0
        while (n < items.size && (items[n] as? ShapeItem)?.isFill == true) n++
        return n
    }

    /** Paint [items] through [each] with [text] in its place: after the fills under it, before the rest. */
    inline fun paint(items: List<CanvasItem>, text: () -> Unit, each: (CanvasItem) -> Unit) {
        val n = underText(items)
        val stickies = ArrayList<CanvasItem>()
        for (i in 0 until n) {
            val it = items[i]
            if (it is StickyNoteItem) stickies.add(it) else each(it)
        }
        text()
        for (i in n until items.size) {
            val it = items[i]
            if (it is StickyNoteItem) stickies.add(it) else each(it)
        }
        // Sticky notes always sit in front of page ink, whatever the document order says.
        for (it in stickies) each(it)
    }
}
