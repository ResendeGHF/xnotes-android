package com.xnotes.core.template

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.format.TemplateReader
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Smoke test: the bundled "lines" template lays out to full-width horizontal strokes. */
class TemplateLinesSmokeTest {

    @Test fun linesLayoutSpansPageWidth() {
        // Unit tests run with the module dir as cwd; standalone runs from the repo root.
        val asset = listOf(
            File("src/main/assets/templates/lines.xtemplate"),
            File("app/src/main/assets/templates/lines.xtemplate"),
        ).first { it.isFile }
        val text = asset.readText()
        val t = TemplateReader.read(text)
        val eval = TemplateEval()
        val cache = TemplateLayoutCache(eval)
        val ink = Rgba(150, 150, 150, 255)
        val accent = Rgba(214, 72, 72, 255)
        val page = TemplatePage(210.0, 297.0, Rect(0.0, 0.0, 210.0, 297.0), ink, accent, TemplateValues(emptyMap(), emptyMap()))
        val out = cache.layout(t, page)
        var strokes = 0
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        for (layer in out.layers) {
            for (p in layer.prims) {
                val b = p.bounds
                if (!b.left.isFinite() || !b.top.isFinite() || !b.right.isFinite() || !b.bottom.isFinite()) continue
                strokes++
                if (b.left < minX) minX = b.left
                if (b.right > maxX) maxX = b.right
            }
        }
        println("lines layers=${out.layers.size} prims=$strokes x=[$minX,$maxX]")
        assertTrue("expected horizontal ruling prims, got $strokes", strokes > 5)
        assertTrue("ruling should span the page width, got [$minX,$maxX]", minX <= 1.0 && maxX >= 209.0)
    }
}
