package com.xnotes.ui

import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.math.ExpressionParser
import com.xnotes.math.Numbers
import com.xnotes.math.Symbolic
import com.xnotes.math.toFracLatex
import com.xnotes.math.toFracText
import com.xnotes.math.toText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/** Fractions in symbolic output, differential stripping and the symbolic/numeric method switch. */
class MathSolverOpsTest {

    @Test fun fractionBasics() {
        assertEquals("1/3", Numbers.formatFraction(1.0 / 3.0))
        assertEquals("1/2", Numbers.formatFraction(0.5))
        assertEquals("-1/6", Numbers.formatFraction(-1.0 / 6.0))
        assertEquals("5/2", Numbers.formatFraction(2.5))
        assertEquals("2", Numbers.formatFraction(2.0))
        assertEquals("0", Numbers.formatFraction(0.0))
        // A typed decimal is not a computed rational: it stays decimal.
        assertEquals("0.333", Numbers.formatFraction(0.333))
        // Transcendentals never match a small denominator.
        assertTrue(!Numbers.formatFraction(PI).contains("/"))
        assertEquals("NaN", Numbers.formatFraction(Double.NaN))
    }

    @Test fun fractionLatex() {
        assertEquals("\\frac{1}{3}", Numbers.formatFractionLatex(1.0 / 3.0))
        assertEquals("-\\frac{1}{2}", Numbers.formatFractionLatex(-0.5))
        assertEquals("2", Numbers.formatFractionLatex(2.0))
    }

    @Test fun taylorUsesFractions() {
        val r = Symbolic.taylor(ExpressionParser.parse("sin(x)"), "x", 0.0, 3)
        assertTrue("got ${r.toFracText()}", r.toFracText().contains("1/6"))
        assertTrue("got ${r.toFracLatex()}", r.toFracLatex().contains("\\frac{1}{6}"))
        // Plain rendering is untouched.
        assertTrue(r.toText().contains("0.166"))
    }

    @Test fun stripDifferential() {
        assertEquals("x^2", stripTrailingDifferential("x^2 dx", "x"))
        assertEquals("x^2", stripTrailingDifferential("x^2 dx ", "x"))
        assertEquals("(x+1)", stripTrailingDifferential("(x+1)(dx)", "x"))
        assertEquals("f(x)", stripTrailingDifferential("f(x) dx", "x"))
        // Exponents, explicit products, other variables and calls keep their meaning.
        assertEquals("x^dx", stripTrailingDifferential("x^dx", "x"))
        assertEquals("2*dx", stripTrailingDifferential("2*dx", "x"))
        assertEquals("x dy", stripTrailingDifferential("x dy", "x"))
        assertEquals("sin(dx)", stripTrailingDifferential("sin(dx)", "x"))
        assertEquals("(x + dx)", stripTrailingDifferential("(x + dx)", "x"))
        assertEquals("x*(dx)", stripTrailingDifferential("x*(dx)", "x"))
        assertEquals("dx", stripTrailingDifferential("dx", "x"))
        assertEquals("x", stripTrailingDifferential("x", "x"))
    }

    private fun solver(expr: String, op: SolverOp): SolverState {
        val s = SolverState()
        s.input = TextFieldValue(expr)
        s.op = op
        return s
    }

    @Test fun definiteSymbolicGivesExactFraction() {
        val s = solver("x^2", SolverOp.DEFINITE)
        s.method = SolverMethod.SYMBOLIC
        runSolver(s)
        assertNull(s.error)
        assertEquals("1/3", s.result)
    }

    @Test fun definiteStripsTrailingDifferential() {
        val s = solver("x^2 dx", SolverOp.DEFINITE)
        s.from = "0"; s.to = "1"
        runSolver(s)
        assertNull(s.error)
        assertTrue(abs(s.result.toDouble() - 1.0 / 3.0) < 1e-9)
    }

    @Test fun definiteSymbolicRefusesImproperBounds() {
        val s = solver("e^(-x^2)", SolverOp.DEFINITE)
        s.method = SolverMethod.SYMBOLIC
        s.from = "-inf"; s.to = "inf"
        runSolver(s)
        assertTrue((s.error ?: "").contains("numeric", ignoreCase = true))
    }

    @Test fun solveNumericOnlySkipsSymbolic() {
        val s = solver("x^2-2", SolverOp.SOLVE)
        s.method = SolverMethod.NUMERIC
        s.from = "1"; s.to = "2"
        runSolver(s)
        assertNull(s.error)
        assertTrue(abs(s.result.toDouble() - sqrt(2.0)) < 1e-9)
    }

    @Test fun solveSymbolicOnlyRejectsTranscendental() {
        val s = solver("sin(x)", SolverOp.SOLVE)
        s.method = SolverMethod.SYMBOLIC
        runSolver(s)
        assertTrue((s.error ?: "").contains("Numeric"))
    }

    @Test fun solveRootsUseFractions() {
        val s = solver("2x-1", SolverOp.SOLVE)
        runSolver(s)
        assertNull(s.error)
        assertEquals("1/2", s.result)
    }

    @Test fun limitSymbolicPath() {
        val s = solver("sin(x)/x", SolverOp.LIMIT)
        s.method = SolverMethod.SYMBOLIC
        runSolver(s)
        assertNull(s.error)
        assertEquals("1", s.result)
    }

    @Test fun keyboardFocusRoutesToSmallFields() {
        val s = SolverState()
        s.focusKey = "at"
        s.at = "0"
        s.typeMath("5")
        assertEquals("05", s.at)
        s.eraseBefore()
        assertEquals("0", s.at)
        // Caret moves are expression-only.
        s.input = TextFieldValue("12")
        s.focusKey = "at"
        s.moveCaret(-1)
        assertEquals("12", s.input.text)
        // Main field keeps template behaviour.
        s.focusKey = "input"
        s.input = TextFieldValue("x", androidx.compose.ui.text.TextRange(0, 1))
        s.typeMath("^{#}")
        assertTrue(s.input.text.contains("^{x}"))
    }
}
