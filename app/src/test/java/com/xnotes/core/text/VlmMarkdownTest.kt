package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmMarkdownTest {

    @Test fun promptNamesThePageAndForbidsFences() {
        val p = VlmMarkdown.promptFor(0, 3)
        assertTrue(p, p.contains("page 1 of 3"))
        assertTrue(p, p.contains("ONLY"))
        assertTrue(p, p.contains("no code fences"))
        assertTrue(p, p.contains("$") && p.contains("inline"))
    }

    @Test fun sanitizeDropsFencesButKeepsMarkdown() {
        assertEquals(
            "# Title\n\n- a\n- b",
            VlmMarkdown.sanitize("```markdown\n# Title\n\n- a\n- b\n```"),
        )
        assertEquals("# Title", VlmMarkdown.sanitize("  # Title  "))
        assertEquals("| a | b |\n|---|---|\n| 1 | 2 |", VlmMarkdown.sanitize("| a | b |\n|---|---|\n| 1 | 2 |"))
    }

    @Test fun assembleSkipsEmptyPagesAndMarksTheRest() {
        val doc = VlmMarkdown.assembleDocument(listOf("# Hello", "", "World \$x\$"))
        assertTrue(doc, doc.contains("<!-- Page 1 -->"))
        assertTrue(doc, doc.contains("# Hello"))
        assertTrue(doc, doc.contains("<!-- Page 3 -->"))
        assertTrue(doc, doc.contains("World \$x\$"))
    }

    @Test fun assembleEmptyGivesEmptyString() {
        assertEquals("", VlmMarkdown.assembleDocument(emptyList()))
        assertEquals("", VlmMarkdown.assembleDocument(listOf("", "   ")))
    }
}
