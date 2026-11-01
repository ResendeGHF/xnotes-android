package com.xnotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Orthogonal polynomials, spherical harmonics and complex part-selectors. */
class MathSpecialTest {

    private fun eval(text: String, vararg vars: Pair<String, Double>): Complex =
        Evaluator.evaluate(ExpressionParser.parse(text), mapOf(*vars))

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= tolerance * maxOf(1.0, abs(expected)))
    }

    private fun assertThrowsEval(text: String) {
        try {
            eval(text)
            throw AssertionError("expected EvalException for: $text")
        } catch (e: EvalException) {
            // Expected.
        }
    }

    // ------------------------------------------------- polynomials

    @Test fun legendreValues() {
        assertClose(1.0, Special.legendreP(0, 0.3))
        assertClose(0.3, Special.legendreP(1, 0.3))
        assertClose(-0.125, Special.legendreP(2, 0.5))
        assertClose(1.0, Special.legendreP(5, 1.0))
        assertClose(-0.5, Special.legendreP(2, 0.0))
        assertClose(0.0, Special.legendreP(3, 0.0), 1e-12)
    }

    @Test fun legendreEngineEntryPoints() {
        assertClose(-0.125, eval("legendre(2, 0.5)").re)
        assertClose(-0.125, eval("legendreP(2, 0.5)").re)
        // A classic linear combination, the way multipoles are built in a plotter.
        assertClose(7.0, Evaluator.real(ExpressionParser.parse("2*legendre(1,x)+legendre(0,x)"), "x", 3.0))
        assertClose(1.375, eval("sum(k, 0, 2, legendre(k, 0.5))").re)
    }

    @Test fun legendreRejectsNonIntegerDegrees() {
        assertThrowsEval("legendre(2.5, 0.5)")
        assertThrowsEval("legendre(-1, 0.5)")
    }

    @Test fun associatedLegendreValues() {
        // P_1^1(x) = -sqrt(1-x^2).
        assertClose(-sqrt(0.75), Special.associatedLegendreP(1, 1, 0.5))
        // P_2^1(x) = -3x sqrt(1-x^2).
        assertClose(-3.0 * 0.5 * sqrt(0.75), Special.associatedLegendreP(2, 1, 0.5))
        // |m| > l vanishes.
        assertClose(0.0, Special.associatedLegendreP(2, 3, 0.5), 0.0)
        // Negative m through the Condon-Shortley relation.
        val pos = Special.associatedLegendreP(3, 2, 0.25)
        val neg = Special.associatedLegendreP(3, -2, 0.25)
        assertClose(pos * (1.0 / 120.0), neg, 1e-12)
        // Engine spellings agree.
        assertClose(pos, eval("assocLegendre(3, 2, 0.25)").re, 1e-12)
        assertClose(pos, eval("associatedLegendre(3, 2, 0.25)").re, 1e-12)
    }

    @Test fun laguerreValues() {
        assertClose(1.0, Special.laguerre(0, 1.7))
        assertClose(0.3, Special.laguerre(1, 0.7))
        assertClose(-0.5, Special.laguerre(2, 1.0))
        assertClose(3.0, Special.laguerre(1, 2.0, 0.0))
    }

    @Test fun laguerreEngineEntryPoints() {
        assertClose(-0.5, eval("laguerre(2, 1)").re)
        assertClose(2.5, eval("genLaguerre(1, 2, 0.5)").re)
        assertClose(2.5, eval("laguerrel(1, 2, 0.5)").re)
        assertThrowsEval("laguerre(1.5, 0.5)")
    }

    @Test fun hermiteValues() {
        assertClose(1.0, Special.hermite(0, 9.0))
        assertClose(2.0, Special.hermite(1, 1.0))
        assertClose(2.0, Special.hermite(2, 1.0))
        assertClose(2.0, eval("hermite(2, 1)").re)
        assertThrowsEval("hermite(-2, 0.5)")
    }

    @Test fun chebyshevValues() {
        assertClose(1.0, Special.chebyshevT(0, 0.3))
        assertClose(-1.0, Special.chebyshevT(3, 0.5))
        assertClose(1.0, Special.chebyshevU(1, 0.5))
        assertClose(-1.0, Special.chebyshevU(2, 0.0))
        assertClose(-1.0, eval("chebyshevT(3, 0.5)").re)
        assertClose(-1.0, eval("chebyshevU(2, 0.0)").re)
        assertClose(-1.0, eval("chebT(3, 0.5)").re)
        assertClose(1.0, eval("chebU(1, 0.5)").re)
    }

    // ------------------------------------------------- complex parts

    @Test fun complexParts() {
        assertClose(2.0, eval("re(2+3*i)").re)
        assertClose(3.0, eval("im(2+3*i)").re)
        assertClose(PI / 4, eval("arg(1+i)").re, 1e-12)
        assertClose(-3.0, eval("conj(2+3*i)").im)
        assertClose(2.0, eval("conj(2+3*i)").re)
        assertClose(-2.0, eval("real(-2)").re)
        assertClose(3.0, eval("imag(3*i)").re)
        assertClose(0.0, eval("phase(5)").re, 1e-15)
    }

    // ------------------------------------------------- spherical harmonics

    @Test fun spherharmGroundState() {
        val y00 = Special.sphHarm(0, 0, 1.0, 2.0)
        assertClose(1.0 / sqrt(4.0 * PI), y00.re)
        assertClose(0.0, y00.im, 0.0)
    }

    @Test fun spherharmDipole() {
        // Y_1^0(0) = sqrt(3/4pi).
        val up = Special.sphHarm(1, 0, 0.0, 0.0)
        assertClose(sqrt(3.0 / (4.0 * PI)), up.re)
        assertClose(0.0, up.im, 1e-15)
        // Node at the equator.
        assertClose(0.0, Special.sphHarm(1, 0, PI / 2, 1.0).re, 1e-15)
        // Y_1^1(pi/2, 0) = -sqrt(3/8pi), real.
        val eq = Special.sphHarm(1, 1, PI / 2, 0.0)
        assertClose(-sqrt(3.0 / (8.0 * PI)), eq.re)
        assertClose(0.0, eq.im, 1e-12)
    }

    @Test fun spherharmConjugation() {
        // Y_2^(-1) = -conj(Y_2^1).
        val a = Special.sphHarm(2, 1, 1.1, 0.7)
        val b = Special.sphHarm(2, -1, 1.1, 0.7)
        assertClose(-a.re, b.re, 1e-12)
        assertClose(a.im, b.im, 1e-12)
    }

    @Test fun spherharmOutOfRangeIsZero() {
        assertEquals(Complex.ZERO, Special.sphHarm(2, 3, 1.0, 0.0))
        assertEquals(0.0, Special.sphHarmReal(2, 3, 1.0, 0.0), 0.0)
    }

    @Test fun realHarmonicsMatchTextbookForms() {
        // R_2^0 = Y_2^0; R_2^1 = sqrt(2) Re Y_2^1; R_2^-1 = sqrt(2) Im Y_2^1.
        val th = 1.0; val ph = 0.6
        assertClose(Special.sphHarm(2, 0, th, ph).re, Special.sphHarmReal(2, 0, th, ph), 1e-14)
        assertClose(sqrt(2.0) * Special.sphHarm(2, 1, th, ph).re, Special.sphHarmReal(2, 1, th, ph), 1e-14)
        assertClose(sqrt(2.0) * Special.sphHarm(2, 1, th, ph).im, Special.sphHarmReal(2, -1, th, ph), 1e-14)
    }

    @Test fun sphericalHarmonicsPlotAsRadii() {
        // The exact expression a 3D spherical plot evaluates per (th, ph) sample.
        val v = eval("sphreal(3, 2, th, ph)", "th" to 1.0, "ph" to 0.5)
        assertTrue(v.isFinite)
        assertClose(Special.sphHarmReal(3, 2, 1.0, 0.5), v.re, 1e-12)
        // A linear combination (multipole-style) evaluates too.
        val combo = eval("2*sphreal(1, 0, th, ph) - 0.5*sphreal(2, 0, th, ph)", "th" to 0.7, "ph" to 0.0)
        assertTrue(combo.isFinite)
        assertClose(
            2 * Special.sphHarmReal(1, 0, 0.7, 0.0) - 0.5 * Special.sphHarmReal(2, 0, 0.7, 0.0),
            combo.re, 1e-12,
        )
        // Complex parts are reachable for colouring or projections.
        val re = eval("re(spherharm(1, 1, th, ph))", "th" to (PI / 2), "ph" to 0.0)
        assertClose(-sqrt(3.0 / (8.0 * PI)), re.re, 1e-9)
        val im = eval("im(spherharm(1, 1, th, ph))", "th" to (PI / 2), "ph" to (PI / 2))
        assertClose(-sqrt(3.0 / (8.0 * PI)), im.re, 1e-9)
    }

    @Test fun sphericalEngineValidates() {
        assertThrowsEval("spherharm(1.5, 0, 0, 0)")
        assertThrowsEval("spherharm(-1, 0, 0, 0)")
        assertThrowsEval("sphreal(2, 0.5, 0, 0)")
        // |m| > l is the zero function, not an error (matches associatedLegendreP).
        assertClose(0.0, eval("sphreal(2, 5, 1, 1)").re, 0.0)
    }
}
