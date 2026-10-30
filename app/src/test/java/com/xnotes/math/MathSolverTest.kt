package com.xnotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Improper integrals, infinite constants and the call forms the Solver screen relies on. */
class MathSolverTest {

    private fun eval(text: String, vararg vars: Pair<String, Double>): Complex =
        Evaluator.evaluate(ExpressionParser.parse(text), mapOf(*vars))

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= tolerance * maxOf(1.0, abs(expected)))
    }

    @Test
    fun inftyConstantMatchesInf() {
        assertTrue(eval("infty").re.isInfinite())
        assertEquals(eval("inf").re, eval("infty").re, 0.0)
    }

    @Test
    fun gaussianIntegralOverTheWholeLine() {
        assertClose(sqrt(PI), eval("integral(e^(-x^2),x,-inf,inf)").re, 1e-6)
        assertClose(sqrt(PI), eval("integral(e^(-x^2),x,-infty,infty)").re, 1e-6)
    }

    @Test
    fun semiInfiniteIntegrals() {
        assertClose(1.0, eval("integral(1/x^2,x,1,inf)").re, 1e-6)
        assertClose(1.0, eval("integral(e^x,x,-inf,0)").re, 1e-6)
        assertClose(PI / 2.0, eval("integral(1/(1+x^2),x,0,inf)").re, 1e-6)
    }

    @Test
    fun finiteIntegralsStillWork() {
        assertClose(1.0 / 3.0, eval("integral(x^2,x,0,1)").re, 1e-9)
        assertClose(1.0, eval("integral(1/x,x,1,e)").re, 1e-6)
    }

    @Test
    fun limitAndDerivativeCallForms() {
        assertClose(1.0, eval("limit(sin(x)/x,x,0)").re, 1e-4)
        assertTrue(abs(eval("limit(1/x,x,inf)").re) < 1e-6)
        assertClose(3.0, eval("derivative(x^3,x,1)").re, 1e-6)
        assertClose(6.0, eval("derivative(x^3,x,1,2)").re, 1e-4)
    }

    @Test
    fun sumsAndProductsInSolverSyntax() {
        assertClose(55.0, eval("sum(n,1,10,n)").re)
        assertClose(24.0, eval("prod(n,1,4,n)").re)
    }

    @Test
    fun symbolicSolveFindsPolynomialRoots() {
        val roots = Symbolic.solve(ExpressionParser.parse("x^2-3x+2"), "x")
        assertNotNull(roots)
        val sorted = roots!!.map { it.re }.sorted()
        assertClose(1.0, sorted[0], 1e-9)
        assertClose(2.0, sorted[1], 1e-9)
    }

    @Test
    fun numericFallbackSolvesTranscendental() {
        val f: (Double) -> Double = { x -> kotlin.math.cos(x) - x }
        val r = Numerical.newton(f, 0.5)
        assertTrue(r.converged)
        assertClose(0.7390851332, r.root, 1e-6)
    }

    @Test
    fun odeSolverMatchesExponential() {
        val pts = Numerical.solveOde({ _, y -> y }, 0.0, 1.0, 1.0, steps = 200)
        assertTrue(pts.isNotEmpty())
        assertClose(exp(1.0), pts.last().y, 1e-4)
    }
}
