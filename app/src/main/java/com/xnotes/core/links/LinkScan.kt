package com.xnotes.core.links

import com.xnotes.core.model.Document
import com.xnotes.core.model.NoteLinkItem
import com.xnotes.core.model.Rgba

/**
 * One outgoing note link found by scanning a document: the 0-based [sourcePage]
 * holding the button, and what the button points at. [targetUri] is "" for a
 * link within the same document. [colorArgb] packs [Rgba] as `0xAARRGGBB`.
 */
data class ParsedLink(
    val sourcePage: Int,
    val targetUri: String,
    val targetName: String,
    val targetPages: List<Int>,
    val label: String,
    val colorArgb: Int,
)

/** Read the note links out of an in-memory document (a save, a scan), in z-order per page. */
object NoteLinkScan {
    fun of(doc: Document): List<ParsedLink> {
        val out = ArrayList<ParsedLink>()
        for ((pageIndex, page) in doc.pages.withIndex()) {
            for (item in page.items) {
                if (item !is NoteLinkItem) continue
                if (item.pages.isEmpty()) continue
                out.add(
                    ParsedLink(
                        sourcePage = pageIndex,
                        targetUri = item.targetUri,
                        targetName = item.targetName,
                        targetPages = item.pages.toList(),
                        label = item.label(),
                        colorArgb = item.color.toArgb(),
                    ),
                )
            }
        }
        return out
    }
}
