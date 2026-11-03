package com.xnotes.core.stroke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pencil's graphite: one shared random field, thresholded into opaque
 * stipple tiles — statistically identical tooth live and committed.
 */
class PencilTextureTest {

    @Test fun tilePhaseMatchesBothSamplers() {
        // One tile spans TILE_CONTENT_PX content px on both sides: the GL
        // cover samples vPage / TILE and the bitmap shader scales geometry
        // coordinates by 1 / TILE.
        assertEquals(256, PencilTexture.SIZE_PX)
        assertEquals(40.0, PencilTexture.TILE_CONTENT_PX, 0.0)
    }

    @Test fun fieldIsDeterministicAndFullRange() {
        val a = PencilTexture.field()
        assertEquals(PencilTexture.SIZE_PX * PencilTexture.SIZE_PX, a.size)
        assertEquals(a.toList(), PencilTexture.field().toList())
        for (v in a) assertTrue(v in 0.0f..1.0f)
        // Mostly bright with real dark structure for the thresholds to bite.
        assertTrue(a.any { it < 0.3f })
        assertTrue(a.average() in 0.45..0.9)
    }

    @Test fun tilesThresholdTheSameField() {
        val lo = PencilTexture.stipplePixels(0.3).toList()
        val hi = PencilTexture.stipplePixels(0.7).toList()
        // Binary: opaque graphite or bare paper, nothing between.
        for (p in lo) assertTrue(p == 0x00000000 || p == 0xFFFFFFFF.toInt())
        for (p in hi) assertTrue(p == 0x00000000 || p == 0xFFFFFFFF.toInt())
        // Nested: pressing harder adds grains instead of reshuffling them.
        val loSet = lo.mapIndexedNotNull { i, p -> if (p != 0) i else null }.toSet()
        val hiSet = hi.mapIndexedNotNull { i, p -> if (p != 0) i else null }.toSet()
        assertTrue(loSet.all { it in hiSet })
        // Densities land near their thresholds.
        assertTrue(loSet.size.toDouble() / lo.size in 0.15..0.45)
        assertTrue(hiSet.size.toDouble() / hi.size in 0.55..0.85)
    }

    @Test fun tileDensitiesStayGraphite() {
        // Even the sparsest band body is substantial; rims dissolve lower.
        assertTrue(PencilTexture.stipplePixels(0.0).all { it == 0 })
        assertTrue(PencilTexture.stipplePixels(1.0).all { it != 0 })
    }
}
