package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * One eraser pass over several items publishes a single z-order change rather than one per
 * item (each of which copies the whole item list). Undo still restores every original in order.
 */
class EraseBatchTest {

    private fun line(y: Double): Stroke = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN),
        (0..20).map { Sample(it * 5.0, y, 1.0) }.toMutableList(),
    )

    @Test fun onePassOverThreeStrokesIsOneOrderingAndUndoes() {
        val a = line(0.0)
        val b = line(6.0)
        val c = line(12.0)
        val doc = InfiniteDocument().apply { addAll(listOf(a, b, c)) }
        var orders = 0
        doc.listener = object : InfiniteDocument.Listener {
            override fun onOrderChanged() {
                orders++
            }
        }
        val session = EraseSession(doc)
        session.erase(50.0, 6.0, 20.0, area = false)
        assertTrue(doc.isEmpty)
        assertEquals("one pass, one ordering", 1, orders)
        val cmd = session.buildCommand()!!
        cmd.undo()
        assertEquals(listOf<CanvasItem>(a, b, c), doc.items)
        cmd.redo()
        assertTrue(doc.isEmpty)
    }

    /** A mixed document so strokes, shapes and fills all ride the same chain. */
    private fun mixedDoc(): Pair<InfiniteDocument, List<CanvasItem>> {
        val items = arrayListOf<CanvasItem>(
            line(0.0),
            line(14.0),
            ShapeItem(ShapeKind.RECTANGLE, Pt(10.0, 30.0), Pt(90.0, 70.0), Rgba(1, 2, 3, 255), 3.0),
            line(100.0),
            ShapeItem.fill(
                listOf(listOf(Pt(0.0, 120.0), Pt(60.0, 120.0), Pt(60.0, 160.0), Pt(0.0, 160.0))),
                Rgba(90, 160, 240, 255),
            ),
        )
        return InfiniteDocument().apply { addAll(items) } to items
    }

    @Test fun emptyChainErasesNothing() {
        val (doc, _) = mixedDoc()
        val session = EraseSession(doc)
        assertNull(session.erasePoints(emptyList(), 8.0, area = true))
        assertTrue(session.isEmpty)
    }

    @Test fun chainedPointsMatchSequentialErases() {
        for (area in listOf(false, true)) {
            val rnd = Random(7)
            // A wandering chain like one input event's historical samples plus its newest point.
            val pts = List(9) { Pt(rnd.nextDouble(0.0, 100.0), rnd.nextDouble(-10.0, 170.0)) }

            val (docSeq, origSeq) = mixedDoc()
            val seq = EraseSession(docSeq)
            for (p in pts) seq.erase(p.x, p.y, 8.0, area)

            val (docChain, origChain) = mixedDoc()
            val chain = EraseSession(docChain)
            val dirtyChain = chain.erasePoints(pts, 8.0, area)

            // Same survivors by shape: same count, same kinds and bounds in order.
            assertEquals(origSeq.size, origChain.size)
            assertEquals(
                docSeq.items.map { it::class to it.paintBounds() },
                docChain.items.map { it::class to it.paintBounds() },
            )
            assertEquals(seq.isEmpty, chain.isEmpty)

            // The chain's dirty region is the union of the sequential calls' regions.
            val (docDirty, _) = mixedDoc()
            val probe = EraseSession(docDirty)
            var want: Rect? = null
            for (p in pts) {
                val d = probe.erase(p.x, p.y, 8.0, area)
                want = if (d == null) want else (want?.union(d) ?: d)
            }
            assertEquals(want, dirtyChain)

            val cmdSeq = seq.buildCommand()
            val cmdChain = chain.buildCommand()
            assertEquals(cmdSeq == null, cmdChain == null)
            if (cmdSeq != null && cmdChain != null) {
                cmdSeq.undo()
                cmdChain.undo()
                assertEquals(origSeq, docSeq.items)
                assertEquals(origChain, docChain.items)
                cmdSeq.redo()
                cmdChain.redo()
                assertEquals(docSeq.itemCount, docChain.itemCount)
            }
        }
    }
}
