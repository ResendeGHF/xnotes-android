package com.xnotes.format

import com.xnotes.core.template.ImageFit
import com.xnotes.core.template.TNode
import com.xnotes.core.template.TemplateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packaged-template surface the companion needs: `kind` (page vs cover) and `image`
 * nodes with their `src`/`fit`/`position`, all validated at read time.
 */
class TemplatePackageTest {

    private fun read(json: String) = TemplateReader.read(json)

    @Test fun kindDefaultsToPageAndReadsCover() {
        assertEquals(TemplateKind.PAGE, read("""{ "xtemplate": 1, "name": "t", "items": [] }""").kind)
        assertEquals(TemplateKind.COVER, read("""{ "xtemplate": 1, "name": "t", "kind": "cover", "items": [] }""").kind)
        assertEquals(TemplateKind.PAGE, read("""{ "xtemplate": 1, "name": "t", "kind": "nope", "items": [] }""").kind)
    }

    @Test fun imageNodeReadsSrcFitAndPosition() {
        val t = read(
            """{ "xtemplate": 1, "name": "t", "items": [
                 { "type": "image", "src": "images/a.png", "x": 1, "y": 2, "w": 3, "h": 4,
                   "fit": "cover", "position": "top right" } ] }""",
        )
        val n = t.root.children.single() as TNode.Image
        assertEquals("images/a.png", n.src)
        assertEquals(ImageFit.COVER, n.fit)
        assertEquals(1.0, n.posX, 0.0)
        assertEquals(0.0, n.posY, 0.0)
    }

    @Test fun imageDefaultsAreContainAndCenter() {
        val t = read("""{ "xtemplate": 1, "name": "t", "items": [ { "type": "image", "src": "a.png" } ] }""")
        val n = t.root.children.single() as TNode.Image
        assertEquals(ImageFit.CONTAIN, n.fit)
        assertEquals(0.5, n.posX, 0.0)
        assertEquals(0.5, n.posY, 0.0)
    }

    @Test fun badImageSourcesAreSkipped() {
        // Absolute paths, escapes and missing/empty sources never become nodes.
        val t = read(
            """{ "xtemplate": 1, "name": "t", "items": [
                 { "type": "image", "src": "../a.png" }, { "type": "image", "src": "/a.png" },
                 { "type": "image" }, { "type": "image", "src": "" } ] }""",
        )
        assertTrue(t.root.children.isEmpty())
    }

    @Test fun zeroAreaImagesAreKeptButDrawNothing() {
        // A zero-area image parses (its area is known only in layout units), then paints nothing.
        val t = read("""{ "xtemplate": 1, "name": "t", "items": [ { "type": "image", "src": "a.png", "w": 0 } ] }""")
        assertEquals(1, t.root.children.size)
        val out = com.xnotes.core.template.TemplateEval().evaluate(
            t,
            com.xnotes.core.template.TemplatePage(
                100.0, 100.0, com.xnotes.core.geometry.Rect(0.0, 0.0, 100.0, 100.0),
                com.xnotes.core.model.Rgba(10, 20, 30), com.xnotes.core.model.Rgba(200, 0, 0, 128),
            ),
        )
        assertTrue(out.layers.isEmpty())
    }

    @Test fun validSrcAllowsOnlyRelativeInPackagePaths() {
        assertTrue(TemplatePackage.validSrc("images/a.png"))
        assertTrue(TemplatePackage.validSrc("a.png"))
        for (bad in listOf("", "/a.png", "../a.png", "a/../../b.png", "/")) {
            assertTrue(bad, !TemplatePackage.validSrc(bad))
        }
    }
}
