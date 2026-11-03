package com.xnotes.core.infinite

import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayShiftTest {

    private val accent = Rgba(0, 255, 0, 255)

    @Test fun zeroShiftReturnsTheSameList() {
        val box = Obb(Pt(100.0, 200.0), 60.0, 40.0, 0.0)
        val parts = OverlayTessellator.selection(box, 2.0, accent, 0.5)
        assertTrue(parts.isNotEmpty())
        assertSame(parts, OverlayTessellator.translated(parts, 0.0, 0.0))
    }

    @Test fun translatedPartsMatchAFreshBuild() {
        val box = Obb(Pt(100.0, 200.0), 60.0, 40.0, 0.3)
        val parts = OverlayTessellator.selection(box, 2.0, accent, 0.5)
        assertTrue(parts.isNotEmpty())
        val moved = Obb(Pt(140.0, 260.0), 60.0, 40.0, 0.3)
        val shifted = OverlayTessellator.translated(parts, 40.0, 60.0)
        val fresh = OverlayTessellator.selection(moved, 2.0, accent, 0.5)
        assertEquals(fresh.size, shifted.size)
        for (i in fresh.indices) {
            val a = fresh[i].mesh.positions
            val b = shifted[i].mesh.positions
            assertEquals("part $i vertex count", a.size, b.size)
            for (k in a.indices) assertEquals("part $i position $k", a[k], b[k], 1e-6)
            assertEquals(fresh[i].mesh.indices.toList(), shifted[i].mesh.indices.toList())
        }
    }

    @Test fun translatedBoundsMatchAShiftedRect() {
        val box = Obb(Pt(0.0, 0.0), 60.0, 40.0, 0.0)
        val parts = OverlayTessellator.selection(box, 1.0, accent, 0.5)
        val shifted = OverlayTessellator.translated(parts, 25.0, -10.0)
        val b = OverlayTessellator.selectionBounds(box, 1.0)
        // Every shifted vertex lies inside the shifted cover quad.
        val moved = b.translate(25.0, -10.0)
        for (part in shifted) {
            val pos = part.mesh.positions
            for (i in pos.indices step 2) {
                assertTrue(moved.contains(Pt(pos[i], pos[i + 1])))
            }
        }
    }
}
