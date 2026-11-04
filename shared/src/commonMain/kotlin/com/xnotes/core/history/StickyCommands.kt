package com.xnotes.core.history

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.StickyNoteItem
import com.xnotes.core.model.Stroke

/** Retype a sticky note's text. */
class EditStickyText(
    private val item: StickyNoteItem,
    private val before: String,
    private val after: String,
) : Command {
    override fun redo() {
        item.text = after
    }

    override fun undo() {
        item.text = before
    }

    override fun touched(locate: (CanvasItem) -> Page?) = pagedSticky(item, locate)
}

/** Recolor sticky paper, one entry per note. */
class RecolorSticky(private val entries: List<Entry>) : Command {
    class Entry(val item: StickyNoteItem, val before: Rgba, val after: Rgba)

    override fun redo() {
        for (e in entries) e.item.color = e.after
    }

    override fun undo() {
        for (e in entries) e.item.color = e.before
    }

    override fun touched(locate: (CanvasItem) -> Page?) = entries.mapNotNull { locate(it.item)?.let { p -> p to it.item } }

    companion object {
        fun of(items: List<StickyNoteItem>, color: Rgba): List<Entry> =
            items.filter { it.color != color }.map { Entry(it, it.color, color) }
    }
}

/** Collapse or expand sticky notes. */
class SetStickyCollapsed(private val entries: List<Entry>) : Command {
    class Entry(
        val item: StickyNoteItem,
        val before: Boolean,
        val after: Boolean,
        val rectBefore: Rect? = null,
        val rectAfter: Rect? = null,
    )

    constructor(item: StickyNoteItem, collapsed: Boolean) : this(listOf(Entry(item, item.collapsed, collapsed)))

    override fun redo() {
        for (e in entries) {
            e.item.collapsed = e.after
            e.rectAfter?.let { e.item.rect = it }
        }
    }

    override fun undo() {
        for (e in entries) {
            e.item.collapsed = e.before
            e.rectBefore?.let { e.item.rect = it }
        }
    }

    override fun touched(locate: (CanvasItem) -> Page?) = entries.mapNotNull { locate(it.item)?.let { p -> p to it.item } }
}

/**
 * Replace a sticky note's note-local ink. Snapshots are deep copies taken at
 * command creation; redo/undo restores from copies so later edits never mutate
 * a stored side.
 */
class EditStickyInk(
    private val item: StickyNoteItem,
    before: List<Stroke>,
    after: List<Stroke>,
) : Command {
    private val before: List<Stroke> = before.map { Stroke(it) }
    private val after: List<Stroke> = after.map { Stroke(it) }

    override fun redo() = restore(after)

    override fun undo() = restore(before)

    private fun restore(side: List<Stroke>) {
        item.ink.clear()
        for (s in side) item.ink.add(Stroke(s))
    }

    override fun touched(locate: (CanvasItem) -> Page?) = pagedSticky(item, locate)
}

private fun pagedSticky(
    item: StickyNoteItem,
    locate: (CanvasItem) -> Page?,
): List<Pair<Page, CanvasItem>> = locate(item)?.let { listOf(it to item) } ?: emptyList()
