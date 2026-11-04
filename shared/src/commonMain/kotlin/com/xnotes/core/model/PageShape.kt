package com.xnotes.core.model

/** Everything turning or resizing a page changes, held so it can be put back exactly. */
class PageShape private constructor(
    private val width: Double,
    private val height: Double,
    private val margins: PageMargins,
    private val markups: List<TextMarkup>,
    private val rooms: List<Room>,
    private val geometry: List<Pair<CanvasItem, GeometrySnapshot>>,
) {
    fun restore(page: Page) {
        page.width = width
        page.height = height
        page.margins = margins
        page.markups = markups
        page.rooms = rooms
        for ((item, snap) in geometry) item.restoreGeometry(snap)
    }

    companion object {
        fun of(page: Page) = PageShape(
            page.width, page.height, page.margins, page.markups, page.rooms, page.items.map { it to it.snapshotGeometry() },
        )

        /** [page]'s shape without its items, for an edit that puts its items back itself. */
        fun bare(page: Page) = PageShape(page.width, page.height, page.margins, page.markups, page.rooms, emptyList())
    }
}
