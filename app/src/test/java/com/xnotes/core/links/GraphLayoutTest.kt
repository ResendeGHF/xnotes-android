package com.xnotes.core.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLayoutTest {

    private val files = setOf("a", "b", "c", "d", "lone")
    private val edges = mapOf(
        "a" to listOf("b", "c"),
        "b" to listOf("c", "a"), // cycle back to a
        "c" to listOf("d"),
    )

    @Test fun visibleFromRootFollowsEdges() {
        val visible = GraphLayout.visibleFrom("a", files, edges)
        assertEquals(listOf("a", "b", "c", "d"), visible)
    }

    @Test fun visibleFromUnknownRootShowsAll() {
        assertEquals(files.sorted(), GraphLayout.visibleFrom("gone", files, edges))
    }

    @Test fun visibleWithoutRootIsEmptyUnlessShowAll() {
        assertTrue(GraphLayout.visibleFrom(null, files, edges).isEmpty())
        // Show-all is the linked subgraph only: linkers plus their targets, never isolates.
        assertEquals(listOf("a", "b", "c", "d"), GraphLayout.visibleFrom(null, files, edges, showAll = true))
    }

    @Test fun showAllKeepsSelfLinkedNotes() {
        val only = mapOf("lone" to listOf("lone"))
        assertEquals(listOf("lone"), GraphLayout.visibleFrom(null, setOf("lone"), only, showAll = true))
        assertEquals(emptyList<String>(), GraphLayout.visibleFrom(null, setOf("lone"), emptyMap(), showAll = true))
    }

    @Test fun treeEdgesCollapseCycles() {
        val tree = GraphLayout.treeEdges("a", setOf("a", "b", "c", "d"), edges)
        // b -> a and b -> c are dropped: both targets are already seen via a.
        assertEquals(listOf("a" to "b", "a" to "c", "c" to "d"), tree)
        // Every non-root node has exactly one parent.
        val parents = tree.groupBy({ it.second }, { it.first })
        for ((child, ps) in parents) {
            assertEquals("child $child", 1, ps.size)
        }
        // All three connected nodes are covered.
        assertEquals(setOf("b", "c", "d"), parents.keys)
    }

    @Test fun treePositionsLayerByDepthAndCenterLevels() {
        val tree = GraphLayout.treeEdges("a", setOf("a", "b", "c", "d"), edges)
        val pos = GraphLayout.treePositions("a", tree, 1000.0, 700.0)
        assertEquals(4, pos.size)
        val ya = pos.getValue("a").y
        val yb = pos.getValue("b").y
        val yc = pos.getValue("c").y
        val yd = pos.getValue("d").y
        // Depth grows downward: a above its children, d deepest.
        assertTrue(ya < yb)
        assertTrue(yb <= yc)
        assertTrue(yc < yd)
        // Levels are centered: b and c share a level symmetric about the middle.
        val mid = (pos.getValue("b").x + pos.getValue("c").x) / 2.0
        assertEquals(500.0, mid, 1.0)
    }

    @Test fun forcePositionsStayInBoxAndDeterministic() {
        val nodes = listOf("a", "b", "c", "d")
        val pairs = listOf("a" to "b", "b" to "c", "c" to "d")
        val first = GraphLayout.forcePositions(nodes, pairs, 1000.0, 700.0, seed = 7L)
        val second = GraphLayout.forcePositions(nodes, pairs, 1000.0, 700.0, seed = 7L)
        assertEquals(first, second)
        for ((_, pt) in first) {
            assertTrue(pt.x in 0.0..1000.0)
            assertTrue(pt.y in 0.0..700.0)
        }
        // Connected nodes end up reasonably close (not flung to opposite corners).
        val a = first.getValue("a")
        val b = first.getValue("b")
        val dist = kotlin.math.hypot(a.x - b.x, a.y - b.y)
        assertTrue("a-b dist $dist", dist < 900.0)
    }

    @Test fun singleNodeSitsInTheMiddle() {
        val pos = GraphLayout.forcePositions(listOf("only"), emptyList(), 1000.0, 700.0)
        assertEquals(500.0, pos.getValue("only").x, 1e-9)
        assertEquals(350.0, pos.getValue("only").y, 1e-9)
    }

    @Test fun nodeRectCentersAndClamps() {
        val center = com.xnotes.core.geometry.Pt(100.0, 100.0)
        val r = GraphLayout.nodeRect(center, textW = 80.0, textH = 20.0)
        assertEquals(100.0, r.centerX, 1e-9)
        assertEquals(100.0, r.centerY, 1e-9)
        assertEquals(108.0, r.w, 1e-9)
        assertEquals(44.0, r.h, 1e-9)
        assertEquals(48.0, GraphLayout.nodeRect(center, textW = 4.0, textH = 10.0).w, 1e-9)
        assertEquals(228.0, GraphLayout.nodeRect(center, textW = 900.0, textH = 10.0).w, 1e-9)
    }

    @Test fun edgeTipLandsOnTheTargetBorder() {
        val target = com.xnotes.core.geometry.Rect(90.0, 90.0, 20.0, 20.0)
        val tip = GraphLayout.edgeTip(
            com.xnotes.core.geometry.Pt(0.0, 100.0),
            com.xnotes.core.geometry.Pt(100.0, 100.0),
            target,
        )!!
        assertEquals(90.0, tip.x, 1e-9)
        assertEquals(100.0, tip.y, 1e-9)
        val out = GraphLayout.edgeTip(
            com.xnotes.core.geometry.Pt(100.0, 100.0),
            com.xnotes.core.geometry.Pt(200.0, 100.0),
            target,
        )!!
        assertEquals(110.0, out.x, 1e-9)
        assertEquals(
            null,
            GraphLayout.edgeTip(
                com.xnotes.core.geometry.Pt(0.0, 0.0),
                com.xnotes.core.geometry.Pt(50.0, 0.0),
                target,
            ),
        )
    }

    @Test fun pullBackMovesAlongTheDirection() {
        val tip = com.xnotes.core.geometry.Pt(90.0, 100.0)
        val back = GraphLayout.pullBack(tip, com.xnotes.core.geometry.Pt(-10.0, 0.0), 5.0)
        assertEquals(85.0, back.x, 1e-9)
        assertEquals(100.0, back.y, 1e-9)
    }
}
