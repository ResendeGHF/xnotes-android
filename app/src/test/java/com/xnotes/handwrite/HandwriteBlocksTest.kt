package com.xnotes.handwrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the Markdown/LaTeX block parsers. */
class HandwriteBlocksTest {

    @Test
    fun `markdown headings lists and quotes become paragraphs`() {
        val blocks = parseMarkdown("# Title\n\n- one\n- two\n\n> quoted\n")
        val paras = blocks.filterIsInstance<HBlock.Para>()
        assertEquals(3, paras.size)
        assertEquals(1.5, paras[0].sizeMult, 1e-9)
        assertTrue((paras[0].segments[0] as HSeg.Words).text.contains("Title"))
        assertTrue((paras[1].segments[0] as HSeg.Words).text.startsWith("• "))
        assertTrue((paras[2].segments[0] as HSeg.Words).text.startsWith("quoted"))
    }

    @Test
    fun `markdown pipe table becomes a table`() {
        val blocks = parseMarkdown("| a | b |\n|---|---|\n| c | d |\n| e | f |\n")
        val tables = blocks.filterIsInstance<HBlock.Table>()
        assertEquals(1, tables.size)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f")), tables[0].rows)
    }

    @Test
    fun `markdown display and inline math survive`() {
        val blocks = parseMarkdown("Before \$x^2\$ after.\n\n\$\$\\frac{1}{2}\$\$\n")
        val paras = blocks.filterIsInstance<HBlock.Para>()
        assertEquals(1, paras.size)
        val segs = paras[0].segments
        assertEquals(3, segs.size)
        assertTrue(segs[0] is HSeg.Words && segs[2] is HSeg.Words)
        assertEquals("x^2", (segs[1] as HSeg.Formula).latex)
        val maths = blocks.filterIsInstance<HBlock.Math>()
        assertEquals(1, maths.size)
        assertEquals("\\frac{1}{2}", maths[0].latex)
    }

    @Test
    fun `markdown fences become plain paragraphs`() {
        val blocks = parseMarkdown("```\ncode()\n```\n")
        val paras = blocks.filterIsInstance<HBlock.Para>()
        assertEquals(1, paras.size)
        assertTrue((paras[0].segments[0] as HSeg.Words).text.contains("code()"))
    }

    @Test
    fun `latex sections equations and lists survive`() {
        val src = "\\section{Intro} Hello \\textbf{world}.\n" +
            "\\begin{equation}\nx^2 \\label{a}\n\\end{equation}\n" +
            "\\begin{itemize}\n\\item one\n\\item two\n\\end{itemize}\n"
        val blocks = parseLatex(src)
        val paras = blocks.filterIsInstance<HBlock.Para>()
        assertTrue(paras.any { it.sizeMult == 1.5 && (it.segments[0] as HSeg.Words).text.contains("Intro") })
        assertTrue(paras.any { (it.segments[0] as HSeg.Words).text.contains("Hello world") })
        val maths = blocks.filterIsInstance<HBlock.Math>()
        assertEquals(1, maths.size)
        assertEquals("x^2", maths[0].latex)
        assertTrue(paras.any { (it.segments[0] as HSeg.Words).text.startsWith("• one") })
    }

    @Test
    fun `latex tabular becomes a table and inline math splits`() {
        val src = "Value \$\\alpha\$ here.\n\\begin{tabular}{cc}\na & b \\\\\n\\hline\nc & d\n\\end{tabular}\n"
        val blocks = parseLatex(src)
        val tables = blocks.filterIsInstance<HBlock.Table>()
        assertEquals(1, tables.size)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), tables[0].rows)
        val paras = blocks.filterIsInstance<HBlock.Para>()
        val first = paras[0].segments
        assertTrue(first.any { it is HSeg.Formula && (it as HSeg.Formula).latex == "\\alpha" })
    }

    @Test
    fun `latex preamble and comments are dropped`() {
        val blocks = parseLatex("\\documentclass{article}\n% a comment\n\\begin{document}\nHi.\n\\end{document}\nTrailing")
        val words = blocks.filterIsInstance<HBlock.Para>()
            .flatMap { it.segments }.filterIsInstance<HSeg.Words>().joinToString(" ") { it.text }
        assertTrue(words.contains("Hi."))
        assertFalse(words.contains("comment") || words.contains("documentclass") || words.contains("Trailing"))
    }

    @Test
    fun `latex starred environments close correctly`() {
        val blocks = parseLatex("\\begin{align*}\na &= b\n\\end{align*}\nAfter.\n")
        val maths = blocks.filterIsInstance<HBlock.Math>()
        assertEquals(1, maths.size)
        assertTrue(maths[0].latex.contains("a &= b"))
        assertTrue(blocks.filterIsInstance<HBlock.Para>().any {
            (it.segments[0] as HSeg.Words).text.contains("After.")
        })
    }

    @Test
    fun `plain blocks keep dollars literal and preview round-trips`() {
        val blocks = plainBlocks("price \$5\$ and \$x\$")
        assertEquals(1, blocks.size)
        val seg = (blocks[0] as HBlock.Para).segments[0] as HSeg.Words
        assertTrue(seg.text.contains("\$5\$"))
        assertTrue(blocksPreview(blocks).contains("\$5\$"))
        assertTrue(blocksHaveContent(blocks))
        assertFalse(blocksHaveContent(listOf(HBlock.Gap)))
    }
}
