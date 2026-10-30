package com.xnotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** LaTeX from handwriting recognition or notes must drop straight into the engine's syntax. */
class LatexToExprTest {

    private fun parses(text: String): Expr? = runCatching { ExpressionParser.parse(text) }.getOrNull()

    private fun assertParses(latex: String) {
        val converted = LatexToExpr.convertOrPass(latex)
        assertTrue("'$latex' -> '$converted' should parse", parses(converted) != null)
    }

    private fun assertConverts(latex: String, expected: String) {
        assertEquals(expected, LatexToExpr.convertOrPass(latex))
    }

    @Test
    fun plainTextPassesThrough() {
        assertConverts("x^2+1", "x^2+1")
        assertConverts("sin(x)^2 + cos(x)^2", "sin(x)^2 + cos(x)^2")
        assertConverts("2pi * 3", "2pi * 3")
    }

    @Test
    fun dollarWrappersAreStripped() {
        assertConverts("\$x^2+1\$", "x^2+1")
        assertConverts("\$\$\\frac{a}{b}\$\$", "((a)/(b))")
        assertConverts("value \\(x+1\\) end", "value (x+1) end")
    }

    @Test
    fun markdownEmphasisIsStripped() {
        assertConverts("**x^2+1**", "x^2+1")
        assertConverts("__x^2+1__", "x^2+1")
        assertConverts("`x^2+1`", "x^2+1")
        assertConverts("**\$x^2\$**", "x^2")
    }

    @Test
    fun dollarSegmentIsPicked() {
        assertConverts("the root \$x^2-4\$ ok", "x^2-4")
    }

    @Test
    fun fractionsBecomeDivisions() {
        assertConverts("\\frac{a}{b}", "((a)/(b))")
        assertConverts("\\frac12", "((1)/(2))")
        assertConverts("\\dfrac{x+1}{x-1}", "((x+1)/(x-1))")
    }

    @Test
    fun rootsBecomePowers() {
        assertConverts("\\sqrt{x}", "sqrt((x))")
        assertConverts("\\sqrt[3]{x}", "((x)^(1/(3)))")
    }

    @Test
    fun operatorsAndFunctions() {
        assertConverts("a\\cdot b", "a* b")
        assertConverts("\\sin x", "sin(x)")
        assertConverts("\\sin^2(x)", "(sin(x)^2)")
        assertConverts("\\sin^2 x", "(sin(x)^2)")
        assertConverts("\\log_{2}(x)", "log((x), (2))")
        assertConverts("e^{x}", "e^(x)")
        assertConverts("\\pi r^2", "pi r^2")
    }

    @Test
    fun subscriptsStayNames() {
        assertConverts("x_0", "x_0")
        assertConverts("x_{10}", "x_10")
    }

    @Test
    fun sumsProductsIntegralsLimits() {
        assertConverts("\\sum_{n=0}^{10} n", "sum(n, 0, 10, (n))")
        assertConverts("\\prod_{k=1}^{4} k", "prod(k, 1, 4, (k))")
        assertConverts("\\int_{0}^{1} x^2 dx", "integral((x^2), x, 0, 1)")
    }

    @Test
    fun absAndDelimiters() {
        assertConverts("|x|", "abs((x))")
        assertConverts("\\left|x\\right|", "abs((x))")
        assertConverts("\\left( a + b \\right)", "( a + b )")
    }

    @Test
    fun nestedOperatorBodies() {
        // Operators inside fractions must not leak past their closing brace.
        assertParses("\\frac{\\lim_{x \\to 0} \\frac{\\sin x}{x}}{2}")
        assertParses("\\frac{\\int_{0}^{1} x \\, dx}{2}")
        assertParses("\\frac{\\sum_{n=1}^{3} n}{2}")
        assertConverts("\\int x^2 dx", "integral((x^2), x, 0, 1)")
        assertConverts("\\int_{-\\infty}^{\\infty} e^{-x^2} dx", "integral((e^(-x^2)), x, -inf, inf)")
    }

    @Test
    fun everythingParses() {
        listOf(
            "\\frac{a}{b}", "\\sqrt[3]{x}", "\\sin^2(x)", "\\sum_{n=0}^{10} n",
            "\\int_{0}^{1} x^2 dx", "|x-2|", "\\binom{n}{k}", "x_{10}",
            "\\left(\\frac{a}{b}\\right)^2", "\\log_{2}(x)",
        ).forEach { assertParses(it) }
    }

    @Test
    fun endToEndValues() {
        fun eval(latex: String, vararg vars: Pair<String, Double>): Double {
            val expr = LatexToExpr.convertOrPass(latex)
            return Evaluator.evaluate(ExpressionParser.parse(expr), mapOf(*vars)).re
        }
        assertTrue(abs(eval("\\frac{1}{2} + \\frac{1}{4}") - 0.75) < 1e-12)
        assertTrue(abs(eval("\\sqrt[3]{8}") - 2.0) < 1e-9)
        assertTrue(abs(eval("\\sin^2(x) + \\cos^2(x)", "x" to 1.1) - 1.0) < 1e-9)
        assertTrue(abs(eval("\\sum_{n=1}^{10} n") - 55.0) < 1e-9)
        assertTrue(abs(eval("x^2+y^2-4", "x" to 2.0, "y" to 0.0)) < 1e-9)
        assertTrue(abs(eval("\\lim_{x \\to 0} \\frac{\\sin x}{x}") - 1.0) < 1e-4)
        assertTrue(abs(eval("\\frac{\\sum_{n=1}^{3} n}{2}") - 3.0) < 1e-9)
        assertTrue(abs(eval("\\int_{0}^{1} x^2 dx") - (1.0 / 3.0)) < 1e-4)
        assertTrue(abs(eval("\\int_{-\\infty}^{\\infty} e^{-x^2} dx") - sqrt(PI)) < 1e-4)
    }
}
