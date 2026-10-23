package com.xnotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The calculator, plots and symbolic cards all sit on this engine, so its numerics are pinned here. */
class MathEngineTest {

    private fun eval(text: String, vararg vars: Pair<String, Double>): Complex =
        Evaluator.evaluate(ExpressionParser.parse(text), mapOf(*vars))

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= tolerance * maxOf(1.0, abs(expected)))
    }

    @Test
    fun parserHandlesImplicitMultiplicationAndSums() {
        assertClose(6 * PI, eval("2pi * 3").re)
        assertClose(55.0, eval("sum(k, 1, 10, k)").re)
        assertClose(24.0, eval("prod(k, 1, 4, k)").re)
        assertClose(720.0, eval("6!").re)
        assertClose(-8.0, eval("(-2)^3").re)
    }

    @Test
    fun complexFunctionsStayOnTheRightBranch() {
        val euler = eval("exp(i*pi)")
        assertClose(-1.0, euler.re, 1e-12)
        assertClose(0.0, euler.im, 1e-12)
        assertClose(5.0, eval("abs(3+4i)").re)
        assertClose(0.0, eval("sqrt(-1)").re, 1e-12)
        assertClose(1.0, eval("sqrt(-1)").im, 1e-12)
    }

    @Test
    fun gammaAndZetaMatchKnownValues() {
        assertClose(24.0, eval("gamma(5)").re, 1e-10)
        assertClose(sqrt(PI), eval("gamma(0.5)").re, 1e-10)
        assertClose(PI * PI / 6.0, eval("zeta(2)").re, 1e-9)
        assertClose(1.202056903159594, eval("zeta(3)").re, 1e-9)
        assertClose(2.612375348685488, eval("zeta(1.5)").re, 1e-9)
        assertClose(-1.4603545088095868, eval("zeta(0.5)").re, 1e-8)
        assertClose(-1.0 / 12.0, eval("zeta(-1)").re, 1e-8)
        assertClose(-0.5772156649015329, eval("digamma(1)").re, 1e-9)
        // The recurrence Γ(z+1)=zΓ(z) pins the complex branch.
        val recurrence = eval("gamma(2+3i) / ((1+3i) * gamma(1+3i))")
        assertClose(1.0, recurrence.re, 1e-8)
        assertClose(0.0, recurrence.im, 1e-8)
    }

    @Test
    fun integralFunctionsUseTheirOwnDefinitions() {
        assertClose(0.33740392290096813, eval("ci(1)").re, 1e-9)
        assertClose(0.946083070367183, eval("si(1)").re, 1e-9)
        assertClose(1.0572508753757285, eval("shi(1)").re, 1e-9)
        assertClose(0.8378669409802082, eval("chi(1)").re, 1e-9)
    }

    @Test
    fun quadratureAndOde() {
        val square = ExpressionParser.parse("x^2")
        val integral = Numerical.adaptiveSimpson(
            { x -> Evaluator.real(square, "x", x) }, 0.0, 1.0,
        )
        assertClose(1.0 / 3.0, integral.value, 1e-9)
        val solution = Numerical.solveOde({ _, y -> y }, 0.0, 1.0, 1.0, steps = 200)
        assertClose(exp(1.0), solution.last().y, 1e-6)
        val root = Numerical.newton({ x -> x * x - 2.0 }, 1.0)
        assertTrue(root.converged)
        assertClose(sqrt(2.0), root.root, 1e-9)
    }

    @Test
    fun unitsHandleFactorsAndAffineOffsets() {
        assertClose(1000.0, Units.convert(1.0, "Length", "km", "m")!!)
        assertClose(32.0, Units.convert(0.0, "Temperature", "°C", "°F")!!)
        assertClose(273.15, Units.convert(0.0, "Temperature", "°C", "K")!!)
    }

    @Test
    fun symbolicDifferentiationIntegrationAndRoots() {
        val x = Expr.Var("x")
        val cube = Expr.Bin(BinOp.POW, x, Expr.Num(3.0))
        val derivative = Symbolic.derivative(cube, "x")
        assertClose(12.0, Evaluator.real(derivative, "x", 2.0), 1e-12)
        val integral = Symbolic.integrate(cube, "x")
        assertClose(0.25, Evaluator.real(integral, "x", 1.0), 1e-12)
        val roots = Symbolic.solve(
            Expr.Bin(BinOp.ADD, Expr.Bin(BinOp.POW, x, Expr.Num(2.0)), Expr.Num(1.0)),
            "x",
        )
        assertNotNull(roots)
        assertEquals(2, roots!!.size)
        assertTrue(roots.all { abs(abs(it.im) - 1.0) < 1e-8 && abs(it.re) < 1e-8 })
        val taylor = Symbolic.taylor(Expr.Call("sin", listOf(x)), "x", 0.0, 9)
        assertClose(sin(0.5), Evaluator.real(taylor, "x", 0.5), 1e-9)
        // A variable exponent differentiates through u^v = e^(v ln u).
        val eToX = Symbolic.derivative(ExpressionParser.parse("e^x"), "x")
        assertClose(exp(1.5), Evaluator.real(eToX, "x", 1.5), 1e-9)
        val twoToX = Symbolic.derivative(ExpressionParser.parse("2^x"), "x")
        assertClose(2.0.pow(1.5) * ln(2.0), Evaluator.real(twoToX, "x", 1.5), 1e-9)
        val xToX = Symbolic.derivative(ExpressionParser.parse("x^x"), "x")
        assertClose(2.0.pow(2.0) * (ln(2.0) + 1.0), Evaluator.real(xToX, "x", 2.0), 1e-9)
    }

    @Test
    fun catalogIsSearchableAndComplete() {
        assertTrue(MathCatalog.functions.size > 80)
        assertTrue(MathCatalog.categories.contains("Special functions"))
        assertNotNull(MathCatalog.functionAliases["jn"])
        assertTrue(MathCatalog.constants.any { it.name == "hbar" })
    }
}
