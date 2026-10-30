package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmLatexTest {

    @Test fun promptNamesThePageAndStatesTheContract() {
        val p = VlmLatex.promptFor(0, 3)
        assertTrue(p, p.contains("page 1 of 3"))
        assertTrue(p, p.contains("ONLY"))
        assertTrue(p, p.contains("\\[...\\]") || p.contains("\\[ ... \\]"))
        assertTrue(p, p.contains("usepackage").not() || p.contains("no \\usepackage"))
    }

    @Test fun rasterSizeKeepsAspectAndCapsTheLongEdge() {
        val (w, h) = VlmLatex.rasterSize(595f, 842f)
        assertEquals(1600, maxOf(w, h))
        assertEquals(w.toDouble() / h, 595.0 / 842.0, 0.01)
        val (w2, h2) = VlmLatex.rasterSize(100f, 100f)
        assertEquals(1600 to 1600, w2 to h2)
    }

    @Test fun fencesAreStripped() {
        assertEquals(
            "Hello \$x\$",
            VlmLatex.sanitize("```latex\nHello \$x\$\n```"),
        )
        assertEquals("plain", VlmLatex.sanitize("```\nplain\n```"))
    }

    @Test fun echoedPreambleIsDropped() {
        val raw = "\\documentclass{article}\n\\usepackage{amsmath}\n\\begin{document}\nHello\n\\end{document}\njunk"
        assertEquals("Hello", VlmLatex.sanitize(raw))
    }

    @Test fun packagesAreDroppedAndTextCommandRewritten() {
        val raw = "\\usepackage{amsmath}\nSome \\text{words} here"
        assertEquals("Some \\mathrm{words} here", VlmLatex.sanitize(raw))
    }

    @Test fun assembleJoinsPagesAndSkipsBlanks() {
        val doc = VlmLatex.assembleDocument(listOf("Hello", "", "World \$x\$"))
        assertTrue(doc, doc.contains("\\documentclass{article}"))
        assertTrue(doc, doc.contains("\\begin{document}"))
        assertTrue(doc, doc.contains("% Page 1"))
        assertTrue(doc, doc.contains("Hello"))
        assertTrue(doc, doc.contains("\\newpage"))
        assertTrue(doc, doc.contains("World \$x\$"))
        assertTrue(doc.trimEnd().endsWith("\\end{document}"))
    }

    @Test fun assembleEmptyGivesEmpty() {
        assertEquals("", VlmLatex.assembleDocument(emptyList()))
        assertEquals("", VlmLatex.assembleDocument(listOf("", "  ")))
    }
}
