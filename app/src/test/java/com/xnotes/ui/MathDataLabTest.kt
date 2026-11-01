package com.xnotes.ui

import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TableItem
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.math.Evaluator
import com.xnotes.math.ExpressionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Table cells, CSV/Markdown/LaTeX export, number parsing, RNG engine, Monte Carlo, data fits. */
class MathDataLabTest {

    // ------------------------------------------------ table model

    private fun table(cols: Int = 3, rows: Int = 2): TableItem =
        TableItem.create(Rect(0.0, 0.0, 300.0, 200.0), cols, rows, Rgba(0, 0, 0, 255))

    @Test fun cellsDefaultBlankAndRoundTrip() {
        val t = table()
        assertEquals("", t.cell(0, 0))
        t.setCell(0, 0, "1.5")
        t.setCell(2, 1, "1/2")
        assertEquals("1.5", t.cell(0, 0))
        assertEquals("1/2", t.cell(2, 1))
        assertTrue(t.hasCells())
        assertEquals(listOf(listOf("1.5", "", ""), listOf("", "", "1/2")), t.cellGrid())
    }

    @Test fun structuralEditsKeepCellsAligned() {
        val t = table()
        t.setCell(0, 0, "a"); t.setCell(1, 0, "b"); t.setCell(2, 0, "c")
        t.setCell(0, 1, "d"); t.setCell(1, 1, "e"); t.setCell(2, 1, "f")
        t.insertColumn(1)
        assertEquals(listOf(listOf("a", "", "b", "c"), listOf("d", "", "e", "f")), t.cellGrid())
        t.removeColumn()
        assertEquals(listOf(listOf("a", "", "b"), listOf("d", "", "e")), t.cellGrid())
        t.insertRow(1)
        assertEquals(3, t.rows)
        assertEquals(listOf("", "", ""), t.cellGrid()[1])
        t.removeRows(0..0)
        assertEquals(listOf(listOf("", "", ""), listOf("d", "", "e")), t.cellGrid())
        // Out-of-range writes are ignored.
        t.setCell(9, 9, "z")
        assertEquals("", t.cell(9, 9))
    }

    @Test fun snapshotRestoresCells() {
        val t = table()
        t.setCell(1, 1, "7")
        val snap = t.snapshotGeometry()
        t.setCell(1, 1, "changed")
        t.removeColumn()
        t.restoreGeometry(snap)
        assertEquals(3, t.cols)
        assertEquals("7", t.cell(1, 1))
    }

    // ------------------------------------------------ export + parsing

    @Test fun csvRoundTripWithQuoting() {
        val grid = listOf(listOf("a", "b,c", "d\"e"), listOf("1", "2", "3"))
        val csv = tableGridToCsv(grid)
        assertTrue(csv.contains("\"b,c\""))
        assertTrue(csv.contains("\"d\"\"e\""))
        val back = parseTableCsv(csv)
        assertEquals(grid, back)
    }

    @Test fun markdownAndLatexShapes() {
        val grid = listOf(listOf("x", "y"), listOf("1", "2"))
        val md = tableGridToMarkdown(grid)
        assertTrue(md.startsWith("| x | y |"))
        assertTrue(md.contains("| --- | --- |"))
        val tex = tableGridToLatex(grid)
        assertTrue(tex.contains("\\begin{tabular}"))
        assertTrue(tex.contains("x & y"))
    }

    @Test fun parseTableNumberForms() {
        assertEquals(0.5, parseTableNumber("1/2")!!, 0.0)
        assertEquals(1.5, parseTableNumber("1 1/2")!!, 0.0)
        assertEquals(0.5, parseTableNumber("50%")!!, 0.0)
        assertEquals(1.5, parseTableNumber("1,5")!!, 0.0)
        assertEquals(1000.0, parseTableNumber("1,000")!!, 0.0)
        assertEquals(2.5, parseTableNumber(" 2.5 ")!!, 0.0)
        assertNull(parseTableNumber("abc"))
        assertNull(parseTableNumber("1/0"))
        assertNull(parseTableNumber(""))
    }

    // ------------------------------------------------ engine RNG

    private fun evalReal(text: String): Double =
        Evaluator.evaluate(ExpressionParser.parse(text)).realValue()

    @Test fun randStaysInRange() {
        repeat(200) {
            val v = evalReal("rand()")
            assertTrue(v >= 0.0 && v < 1.0)
        }
    }

    @Test fun randintBounds() {
        repeat(200) {
            val v = evalReal("randint(1, 6)").toInt()
            assertTrue(v in 1..6)
        }
    }

    @Test fun randnRoughlyStandardNormal() {
        var sum = 0.0
        var sum2 = 0.0
        val n = 2000
        repeat(n) {
            val v = evalReal("randn()")
            assertTrue(v.isFinite())
            sum += v; sum2 += v * v
        }
        val mean = sum / n
        val sd = sqrt(sum2 / n - mean * mean)
        assertTrue(abs(mean) < 0.15)
        assertTrue(abs(sd - 1.0) < 0.15)
    }

    @Test fun randseedRepeatsSequence() {
        evalReal("randseed(42)")
        val a = evalReal("rand()")
        evalReal("randseed(42)")
        val b = evalReal("rand()")
        assertEquals(a, b, 0.0)
    }

    // ------------------------------------------------ Monte Carlo solver

    private fun solver(expr: String, op: SolverOp): SolverState {
        val s = SolverState()
        s.input = TextFieldValue(expr)
        s.op = op
        return s
    }

    @Test fun monteCarloIntegrates() {
        val s = solver("x^2", SolverOp.MONTECARLO)
        s.from = "0"; s.to = "1"
        s.samples = "200000"; s.seed = "7"
        runSolver(s)
        assertNull(s.error)
        val estimate = s.result.substringBefore("±").trim().toDouble()
        assertTrue(abs(estimate - 1.0 / 3.0) < 0.01)
        assertTrue(s.detail?.contains("200000") == true)
        assertTrue(s.odePoints.size > 10)
    }

    @Test fun monteCarloRejectsBadBounds() {
        val s = solver("x", SolverOp.MONTECARLO)
        s.from = "1"; s.to = "0"
        runSolver(s)
        assertTrue((s.error ?: "").contains("lower bound"))
    }

    // ------------------------------------------------ fits + helpers

    private fun linspace(lo: Double, hi: Double, n: Int) = DoubleArray(n) { lo + (hi - lo) * it / (n - 1) }

    @Test fun linearFitExact() {
        val xs = linspace(0.0, 10.0, 21)
        val ys = DoubleArray(xs.size) { 1.0 + 2.0 * xs[it] }
        val w = DoubleArray(xs.size) { 1.0 }
        val r = fitSeries(xs, ys, w, DataFit.LINEAR, 1)
        assertNotNull(r)
        assertTrue(abs(r!!.predict(0.0) - 1.0) < 1e-6)
        assertTrue(abs(r.predict(5.0) - 11.0) < 1e-6)
        assertTrue(r.rSquared > 0.999999)
        assertTrue(r.slope != null && abs(r.slope!!.invoke(3.0) - 2.0) < 1e-6)
    }

    @Test fun polynomialFitQuadratic() {
        val xs = linspace(-2.0, 2.0, 17)
        val ys = DoubleArray(xs.size) { 1.0 + xs[it] * xs[it] }
        val w = DoubleArray(xs.size) { 1.0 }
        val r = fitSeries(xs, ys, w, DataFit.POLYNOMIAL, 2)
        assertNotNull(r)
        assertTrue(abs(r!!.predict(0.0) - 1.0) < 1e-4)
        assertTrue(abs(r.predict(2.0) - 5.0) < 1e-4)
    }

    @Test fun exponentialFit() {
        val xs = linspace(0.0, 2.0, 21)
        val ys = DoubleArray(xs.size) { 2.0 * kotlin.math.exp(0.5 * xs[it]) }
        val w = DoubleArray(xs.size) { 1.0 }
        val r = fitSeries(xs, ys, w, DataFit.EXPONENTIAL, 1)
        assertNotNull(r)
        assertTrue(abs(r!!.predict(1.0) - 2.0 * kotlin.math.exp(0.5)) / (2.0 * kotlin.math.exp(0.5)) < 1e-6)
    }

    @Test fun powerAndLogFits() {
        val xs = linspace(1.0, 10.0, 20)
        val pw = fitSeries(xs, DoubleArray(xs.size) { 3.0 * xs[it].pow(2.0) }, DoubleArray(xs.size) { 1.0 }, DataFit.POWER, 1)
        assertNotNull(pw)
        assertTrue(abs(pw!!.predict(4.0) - 48.0) < 1e-3)
        val lg = fitSeries(xs, DoubleArray(xs.size) { 1.0 + 2.0 * ln(xs[it]) }, DoubleArray(xs.size) { 1.0 }, DataFit.LOGARITHMIC, 1)
        assertNotNull(lg)
        assertTrue(abs(lg!!.predict(kotlin.math.E) - 3.0) < 1e-6)
    }

    @Test fun gaussianFitFindsPeak() {
        val xs = linspace(-3.0, 3.0, 61)
        val ys = DoubleArray(xs.size) { 5.0 * kotlin.math.exp(-((xs[it] - 0.5) * (xs[it] - 0.5)) / (2 * 0.8 * 0.8)) }
        val w = DoubleArray(xs.size) { 1.0 }
        val r = fitSeries(xs, ys, w, DataFit.GAUSSIAN, 1)
        assertNotNull(r)
        // Peak near x = 0.5 with height near 5.
        var bestX = 0.0; var bestY = Double.NEGATIVE_INFINITY
        for (i in 0..200) {
            val x = -3.0 + 6.0 * i / 200
            val y = r!!.predict(x)
            if (y > bestY) { bestY = y; bestX = x }
        }
        assertTrue(abs(bestX - 0.5) < 0.1)
        assertTrue(abs(bestY - 5.0) < 0.5)
    }

    @Test fun splineGoesThroughKnots() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val ys = doubleArrayOf(0.0, 1.0, 0.0, 1.0)
        val sp = DataSpline.of(xs, ys)
        assertNotNull(sp)
        for (i in xs.indices) assertTrue(abs(sp!!.eval(xs[i]) - ys[i]) < 1e-9)
    }

    @Test fun smoothersBehave() {
        val ys = doubleArrayOf(1.0, 100.0, 2.0, 3.0, 4.0)
        val ma = smoothSeries(ys, DataSmooth.MOVING_AVERAGE, 3)
        assertNotNull(ma)
        assertTrue(ma!![1] < 100.0)
        val med = smoothSeries(ys, DataSmooth.MEDIAN, 3)
        assertNotNull(med)
        assertEquals(2.0, med!![1], 0.0)
        assertNull(smoothSeries(ys, DataSmooth.NONE, 3))
    }

    @Test fun scaleTransforms() {
        assertEquals(3.0, dataTransform(1000.0, DataAxisScale.LOG10, 4.0), 1e-12)
        assertEquals(1000.0, dataBackTransform(3.0, DataAxisScale.LOG10, 4.0), 1e-9)
        assertEquals(16.0, dataTransform(2.0, DataAxisScale.POWER, 4.0), 1e-12)
        assertTrue(dataTransform(-1.0, DataAxisScale.LOG10, 2.0).isNaN())
    }

    @Test fun dataImportPairsAndUncertainty() {
        val st = DataState()
        val n = st.importCsv("0,0\n1,1\n2,4\n3,9", DataImportMode.PAIRS, "T")
        assertEquals(1, n)
        assertEquals(4, st.series[0].x.size)
        val st2 = DataState()
        val m = st2.importCsv("0 0 0.1 0.2\n1 1 0.1 0.2\n2 4 0.1 0.3", DataImportMode.TABLE, "T")
        assertEquals(1, m)
        assertEquals(0.1, st2.series[0].xerr[0], 1e-12)
        assertEquals(0.2, st2.series[0].yerr[0], 1e-12)
    }

    @Test fun strokesAssignToCellsByCenter() {
        val t = table(3, 2)
        fun dot(x: Double, y: Double) = com.xnotes.core.model.Stroke(
            Tool.PEN,
            ToolConfig(rgba = Rgba(0, 0, 0, 255)),
            listOf(Sample(x, y, 1.0), Sample(x + 1.0, y + 1.0, 1.0)),
        )
        val a = dot(50.0, 50.0)
        val b = dot(150.0, 150.0)
        val c = dot(500.0, 500.0)
        val m = assignCells(t, listOf(a, b, c))
        assertEquals(2, m.size)
        assertTrue(m[0 to 0]?.contains(a) == true)
        assertTrue(m[1 to 1]?.contains(b) == true)
    }

    @Test fun stripOuterParensOnlyBalanced() {
        // One balanced outer layer at a time; inner pairs are handled where fractions parse.
        assertEquals("(1)/(2)", stripOuterParens("((1)/(2))"))
        // Unbalanced input is left untouched.
        assertEquals("((1)/(2)", stripOuterParens("((1)/(2)"))
        assertEquals("12", stripOuterParens("  (12) "))
        assertEquals("(a", stripOuterParens("(a"))
        assertEquals(0.5, parseTableNumber("((1)/(2))")!!, 1e-12)
        assertEquals(12.0, parseTableNumber("(12)")!!, 1e-12)
    }

    @Test fun dataWorkspaceRoundTrips() {
        val calc = com.xnotes.ui.CalculatorState()
        val solver = com.xnotes.ui.SolverState()
        val plot2d = com.xnotes.ui.Plot2DState()
        val plot3d = com.xnotes.ui.Plot3DState()
        val fields = com.xnotes.ui.FieldState()
        val calculus = com.xnotes.ui.CalculusState()
        val symbolic = com.xnotes.ui.SymbolicState()
        val units = com.xnotes.ui.UnitState()
        val data = com.xnotes.ui.DataState()
        data.title = "Experiment 1"
        data.xLabel = "time (s)"
        data.importCsv("0,0\n1,1\n2,4\n3,9", com.xnotes.ui.DataImportMode.PAIRS, "T")
        data.series[0].fit = com.xnotes.ui.DataFit.POLYNOMIAL
        data.series[0].fitDegree = "2"
        data.series[0].colorArgb = 0xFFFF0000.toInt()
        data.xScale = com.xnotes.ui.DataAxisScale.LOG10
        data.showTangents = true
        val json = com.xnotes.ui.MathStudioData.capture(
            "DATA", calc, solver, plot2d, plot3d, fields, calculus, symbolic, units, data,
        )
        val calc2 = com.xnotes.ui.CalculatorState()
        val solver2 = com.xnotes.ui.SolverState()
        val plot2d2 = com.xnotes.ui.Plot2DState()
        val plot3d2 = com.xnotes.ui.Plot3DState()
        val fields2 = com.xnotes.ui.FieldState()
        val calculus2 = com.xnotes.ui.CalculusState()
        val symbolic2 = com.xnotes.ui.SymbolicState()
        val units2 = com.xnotes.ui.UnitState()
        val data2 = com.xnotes.ui.DataState()
        val tab = com.xnotes.ui.MathStudioData.restore(
            json, calc2, solver2, plot2d2, plot3d2, fields2, calculus2, symbolic2, units2, data2,
        )
        assertEquals("DATA", tab)
        assertEquals("Experiment 1", data2.title)
        assertEquals("time (s)", data2.xLabel)
        assertEquals(1, data2.series.size)
        assertEquals(4, data2.series[0].x.size)
        assertEquals(9.0, data2.series[0].y[3], 1e-12)
        assertEquals(com.xnotes.ui.DataFit.POLYNOMIAL, data2.series[0].fit)
        assertEquals("2", data2.series[0].fitDegree)
        assertEquals(0xFFFF0000.toInt(), data2.series[0].colorArgb)
        assertEquals(com.xnotes.ui.DataAxisScale.LOG10, data2.xScale)
        assertTrue(data2.showTangents)
    }
}
