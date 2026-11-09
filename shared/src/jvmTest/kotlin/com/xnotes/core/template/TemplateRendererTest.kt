package com.xnotes.core.template

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared template renderer: notes carrying custom templates (embedded JSON or packages)
 * paint through the same engine on every host, while built-ins and none keep their existing
 * ruling path untouched.
 */
class TemplateRendererTest {

    private val json = """{ "xtemplate": 1, "name": "Test Ruled",
        "items": [ { "type": "hline", "y": 10 } ] }"""

    private fun renderer() = TemplateRenderer(pictures = TemplatePictures(null) { null })

    private fun docWith(key: String?, json: String? = null): Document {
        val d = Document()
        d.style = PageStyle(template = key)
        if (key != null && json != null) d.templates = mapOf(key to json)
        d.pages.add(Page(200.0, 200.0))
        return d
    }

    private fun cover() = Rect(0.0, 0.0, 200.0, 200.0)

    @Test fun customTemplatePaintsThroughTheRenderer() {
        val r = TemplateRenderer(pictures = TemplatePictures(null) { null })
        val doc = docWith("mytemplate", json)
        val fake = FakeRenderer()
        assertTrue(r.paint(fake, doc, doc.pages[0], cover(), cover()))
        assertTrue("expected stroke calls, got ${fake.ops}", fake.segments.isNotEmpty())
    }

    @Test fun unknownKeyPaintsNothing() {
        val r = renderer()
        val doc = docWith("nosuchtemplate")
        val fake = FakeRenderer()
        assertFalse(r.paint(fake, doc, doc.pages[0], cover(), cover()))
        assertTrue(fake.ops.isEmpty())
        assertTrue(fake.segments.isEmpty())
    }

    @Test fun builtInRulingsAndNoneStayOnTheRulingPath() {
        val r = renderer()
        val fake = FakeRenderer()
        for (key in listOf("none", "lines", "dots", "grid")) {
            assertFalse(key, r.paint(fake, docWith(key), docWith(key).pages[0], cover(), cover()))
        }
        assertTrue(fake.ops.isEmpty())
    }

    @Test fun templateResolvesNoteEmbeddedFirst() {
        val r = renderer()
        val doc = docWith("mytemplate", json)
        assertEquals("Test Ruled", r.template(doc, "mytemplate")?.name)
        assertEquals(null, r.template(doc, "other"))
    }

    @Test fun entriesListsLibraryAndNoteTemplates() {
        val r = renderer()
        r.library = mapOf("libkey" to LibraryTemplate(json, null))
        val doc = docWith("notekey", json)
        val keys = r.entries(doc).map { it.key }.toSet()
        assertTrue(keys.contains("libkey"))
        assertTrue(keys.contains("notekey"))
        assertEquals("Test Ruled", r.entries(doc).first { it.key == "notekey" }.name)
    }
}
