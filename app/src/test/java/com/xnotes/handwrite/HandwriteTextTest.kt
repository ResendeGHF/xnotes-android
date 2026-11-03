package com.xnotes.handwrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the text cleanup behind text-to-handwriting. */
class HandwriteTextTest {

    @Test
    fun `markdown headings emphasis and links strip to words`() {
        val md = "# Title\n\nSome **bold** and *italic* text with a [link](https://x.test).\n"
        val out = HandwriteText.sanitizeMarkdown(md)
        assertTrue(out.contains("Title"))
        assertTrue(out.contains("bold"))
        assertTrue(out.contains("link"))
        assertTrue(!out.contains("#") && !out.contains("**") && !out.contains("https://"))
    }

    @Test
    fun `markdown lists quotes fences and tables strip to words`() {
        val md = "> quoted\n\n- one\n- [ ] two\n\n```\ncode()\n```\n\n| a | b |\n|---|---|\n| c | d |\n"
        val out = HandwriteText.sanitizeMarkdown(md)
        for (w in listOf("quoted", "one", "two", "code()", "a", "b", "c", "d")) {
            assertTrue("missing '$w' in: $out", out.contains(w))
        }
        assertTrue(!out.contains(">") && !out.contains("```") && !out.contains("|"))
    }

    @Test
    fun `latex commands and environments strip to words`() {
        val tex = "\\section{Hello} World \\textbf{bold} \\begin{itemize}\n\\item one\n\\end{itemize}\n"
        val out = HandwriteText.sanitizeLatex(tex)
        for (w in listOf("Hello", "World", "bold", "one")) {
            assertTrue("missing '$w' in: $out", out.contains(w))
        }
        assertTrue(!out.contains("\\") && !out.contains("{") && !out.contains("begin"))
    }

    @Test
    fun `latex comments math and line breaks become text`() {
        val tex = "Price 5\\% off % a comment\n\$x^2\$ \\\\ next"
        val out = HandwriteText.sanitizeLatex(tex)
        assertTrue("in: $out", out.contains("Price 5% off"))
        assertTrue("in: $out", out.contains("x^2"))
        assertTrue("in: $out", out.contains("next"))
        assertTrue("in: $out", !out.contains("a comment"))
    }

    @Test
    fun `normalize trims caps blank runs and truncates`() {
        val out = HandwriteText.normalize("  hi  \n\n\n\n\nbye\r\n")
        assertEquals("hi\n\n\nbye", out)
        val long = HandwriteText.normalize("a".repeat(HandwriteText.MAX_CHARS + 100))
        assertEquals(HandwriteText.MAX_CHARS, long.length)
        assertEquals("", HandwriteText.normalize("   \n  "))
    }

    @Test
    fun `plainFromFile dispatches on extension`() {
        assertTrue(HandwriteText.plainFromFile("n.md", "# T\n").contains("T"))
        assertTrue(HandwriteText.plainFromFile("n.TEX", "\\emph{E}\n").contains("E"))
        assertEquals("raw *kept*", HandwriteText.plainFromFile("n.txt", "raw *kept*"))
        assertEquals("raw *kept*", HandwriteText.plainFromFile("noext", "raw *kept*"))
    }
}
