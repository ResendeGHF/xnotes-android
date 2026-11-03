package com.xnotes.core.pal

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The canvas color-inversion export path uses the notes app's algorithm: object colours
 * flip HSL lightness (hue kept, so red stays red), raster pixels go through the luma
 * matrix. Vector ink, fills and text paint inverted (alpha untouched) while placed
 * images forward untouched — the underlying renderer inverts those itself only under
 * "invert images".
 */
class InvertingRendererTest {

    private class ColorTap(val inner: FakeRenderer = FakeRenderer()) : Renderer by inner {
        val rects = mutableListOf<Rgba>()
        val pens = mutableListOf<Pen>()
        val texts = mutableListOf<Rgba>()
        var images = 0

        override fun fillRect(rect: Rect, color: Rgba) {
            rects += color
        }

        override fun strokePolyline(points: List<Pt>, pen: Pen) {
            pens += pen
        }

        override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) {
            texts += color
        }

        override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double, flip: Flip, part: Rect?) {
            images++
        }
    }

    @Test fun inversionFlipsLightnessAndKeepsHue() {
        // Extremes behave like a plain invert.
        assertEquals(Rgba(0, 0, 0, 255), Rgba(255, 255, 255, 255).inverted())
        assertEquals(Rgba(255, 255, 255, 255), Rgba(0, 0, 0, 255).inverted())
        // Greys behave like a plain invert, alpha untouched.
        assertEquals(Rgba(105, 105, 105, 40), Rgba(150, 150, 150, 40).inverted())
        // Saturated hues keep their hue: red stays red instead of turning cyan.
        assertEquals(Rgba(255, 0, 0, 255), Rgba(255, 0, 0, 255).inverted())
        // Mid hues flip lightness but keep their character (hand-checked against the matrix).
        assertEquals(Rgba(205, 105, 55, 255), Rgba(200, 100, 50, 255).inverted())
        assertEquals(Rgba(225, 235, 245, 40), Rgba(10, 20, 30, 40).inverted())
    }

    @Test fun inversionIsItsOwnInverse() {
        for (c in listOf(Rgba(255, 0, 0), Rgba(200, 100, 50), Rgba(10, 20, 30, 40), Rgba(150, 150, 150))) {
            assertEquals(c, c.inverted().inverted())
        }
    }

    @Test fun invertsVectorAndTextButForwardsImages() {
        val tap = ColorTap()
        val r = InvertingRenderer(tap)
        val box = Rect(0.0, 0.0, 10.0, 10.0)
        r.fillRect(box, Rgba(10, 20, 30, 40))
        r.strokePolyline(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0)), Pen(Rgba(1, 2, 3, 4), width = 2.0))
        r.drawText("hi", box, FontSpec(12.0), Rgba(200, 100, 50), TextFlags())
        val before = tap.images
        r.drawImage(ImageData(File.createTempFile("img", null).apply { deleteOnExit() }, 1, 1), box)
        assertEquals(Rgba(225, 235, 245, 40), tap.rects.single())
        assertEquals(Rgba(252, 253, 254, 4), tap.pens.single().color)
        assertEquals(Rgba(205, 105, 55, 255), tap.texts.single())
        assertEquals(before + 1, tap.images)
    }
}
