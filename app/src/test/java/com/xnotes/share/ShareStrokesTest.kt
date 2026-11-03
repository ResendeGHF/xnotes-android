package com.xnotes.share

import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShareStrokesTest {

    private fun pen(samples: List<Sample> = listOf(Sample(100.0, 200.0, 1.0), Sample(300.0, 400.0, 1.0))) =
        Stroke(Tool.PEN, ToolConfig(baseWidth = 6.0, rgba = Rgba(255, 0, 0), pressureEnabled = false), samples)

    @Test
    fun roundTripPreservesGeometry() {
        val pxToPt = 72.0 / 160.0
        val wire = ShareStrokes.toWire(pen(), pxToPt)!!
        assertEquals("pen", wire.tool)
        assertEquals("#ff0000", wire.color)
        assertTrue(abs(wire.width - 6.0 * pxToPt) < 1e-9)
        val back = ShareStrokes.toNative(wire, 160.0 / 72.0)!!
        assertEquals(Tool.PEN, back.tool)
        assertEquals(6.0, back.config.baseWidth, 1e-9)
        assertEquals(2, back.sampleCount)
        assertTrue(abs(back.samples[0].x - 100.0) < 1e-6)
        assertTrue(abs(back.samples[1].y - 400.0) < 1e-6)
    }

    @Test
    fun highlighterSurvives() {
        val stroke = Stroke(
            Tool.HIGHLIGHTER,
            ToolConfig(baseWidth = 24.0, rgba = Rgba(255, 255, 0), pressureEnabled = false),
            listOf(Sample(0.0, 0.0, 1.0), Sample(50.0, 0.0, 1.0)),
        )
        val wire = ShareStrokes.toWire(stroke, 0.5)!!
        assertEquals("highlighter", wire.tool)
        val back = ShareStrokes.toNative(wire, 2.0)!!
        assertEquals(Tool.HIGHLIGHTER, back.tool)
        assertEquals(24.0, back.config.baseWidth, 1e-9)
    }

    @Test
    fun rejectsGarbage() {
        val good = ShareStrokes.WireStroke("pen", "#112233", 2.0, listOf(1.0, 2.0, 3.0, 4.0))
        assertNull(ShareStrokes.toNative(good.copy(tool = "spray"), 1.0))
        assertNull(ShareStrokes.toNative(good.copy(color = "red"), 1.0))
        assertNull(ShareStrokes.toNative(good.copy(color = "#12345"), 1.0))
        assertNull(ShareStrokes.toNative(good.copy(width = 0.0), 1.0))
        assertNull(ShareStrokes.toNative(good.copy(width = Double.NaN), 1.0))
        assertNull(ShareStrokes.toNative(good.copy(points = listOf(1.0)), 1.0))
        assertNull(ShareStrokes.toNative(good.copy(points = listOf(1.0, Double.NaN)), 1.0))
        assertNotNull(ShareStrokes.toNative(good.copy(points = listOf(5.0, 6.0)), 1.0))
    }

    @Test
    fun emptyStrokeHasNoWire() {
        assertNull(ShareStrokes.toWire(pen(emptyList()), 1.0))
    }
}
