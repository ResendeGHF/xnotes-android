package com.xnotes.core.text

import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkMathTest {

    private fun hstroke(x0: Double, y0: Double, x1: Double, y1: Double): Stroke =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x0, y0, 1.0), Sample(x1, y1, 1.0)))

    private fun blank(strokes: List<Stroke>): String {
        throw AssertionError("recognition must not be called for $strokes")
    }

    @Test fun plainRowStaysOneRun() {
        val strokes = listOf(hstroke(0.0, 0.0, 0.0, 40.0), hstroke(50.0, 0.0, 50.0, 40.0), hstroke(100.0, 0.0, 100.0, 40.0))
        val node = InkMath.structure(strokes)
        assertTrue(node is InkMath.Node.Run)
        assertEquals("x+1", InkMath.render(node) { "x+1" })
    }

    @Test fun fractionBarSplitsNumeratorAndDenominator() {
        val num = hstroke(30.0, 10.0, 90.0, 30.0)
        val bar = hstroke(0.0, 55.0, 120.0, 55.0)
        val den = hstroke(30.0, 80.0, 90.0, 110.0)
        val node = InkMath.structure(listOf(num, bar, den))
        assertTrue(node is InkMath.Node.Frac)
        val latex = InkMath.render(node) { part -> if (part.any { it === num }) "1" else "2" }
        assertEquals("\\frac{1}{2}", latex)
    }

    @Test fun minusIsNotAFraction() {
        val strokes = listOf(hstroke(0.0, 0.0, 20.0, 40.0), hstroke(30.0, 18.0, 60.0, 22.0), hstroke(70.0, 0.0, 90.0, 40.0))
        val node = InkMath.structure(strokes)
        assertTrue(node !is InkMath.Node.Frac)
        assertEquals("a-b", InkMath.render(node) { "a-b" })
    }

    @Test fun raisedSmallRunIsSuperscript() {
        val base = hstroke(0.0, 0.0, 30.0, 40.0)
        val exp = hstroke(46.0, 2.0, 64.0, 22.0)
        val node = InkMath.structure(listOf(base, exp))
        assertTrue(node is InkMath.Node.Sup)
        assertEquals("{x}^{2}", InkMath.render(node) { part -> if (part.any { it === exp }) "2" else "x" })
    }

    @Test fun loweredSmallRunIsSubscript() {
        val base = hstroke(0.0, 0.0, 30.0, 40.0)
        val sub = hstroke(46.0, 26.0, 64.0, 44.0)
        val node = InkMath.structure(listOf(base, sub))
        assertTrue(node is InkMath.Node.Sub)
        assertEquals("{x}_{2}", InkMath.render(node) { part -> if (part.any { it === sub }) "2" else "x" })
    }

    @Test fun stackedRowsJoinWithSpace() {
        val top = hstroke(0.0, 0.0, 40.0, 40.0)
        val bottom = hstroke(0.0, 100.0, 40.0, 140.0)
        val node = InkMath.structure(listOf(top, bottom))
        assertTrue(node is InkMath.Node.Seq)
        assertEquals("a b", InkMath.render(node) { part -> if (part.any { it === top }) "a" else "b" })
    }

    @Test fun unicodeMathNormalizesBeforeLatex() {
        assertEquals("a*b/c-d<=e", InkMath.normalizeUnicode("a×b÷c−d≤e"))
        assertEquals("x^2+pi", InkMath.normalizeUnicode("x²+π"))
        assertEquals("sqrt x", InkMath.normalizeUnicode("√x"))
        assertEquals("a+b", InkMath.normalizeUnicode("a+b"))
    }

    @Test fun recognizedSuperscriptUnicodeBecomesExponents() {
        val node = InkMath.Node.Run(listOf(hstroke(0.0, 0.0, 40.0, 40.0)))
        assertEquals("x^{2}+1", InkMath.render(node) { "x²+1" })
    }

    @Test fun blankLeavesAreDropped() {
        val num = hstroke(30.0, 10.0, 90.0, 30.0)
        val bar = hstroke(0.0, 55.0, 120.0, 55.0)
        val den = hstroke(30.0, 80.0, 90.0, 110.0)
        val latex = InkMath.render(InkMath.structure(listOf(num, bar, den))) { part ->
            if (part.any { it === num }) "" else "2"
        }
        assertEquals("\\frac{}{2}", latex)
    }

    @Test fun emptyStrokesGiveEmptyLatex() {
        assertEquals("", InkMath.render(InkMath.structure(emptyList()), ::blank))
        val empty = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf())
        assertEquals("", InkMath.render(InkMath.structure(listOf(empty)), ::blank))
    }
}
