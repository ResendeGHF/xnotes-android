package com.xnotes.ui

import android.graphics.Bitmap
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.math.Numbers
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- model

internal enum class DataLineStyle(val label: String) { SOLID("Solid"), DASHED("Dashed"), DOTTED("Dotted"), NONE("Off") }
internal enum class DataAxisScale(val label: String) { LINEAR("Linear"), LOG10("Log"), POWER("Power") }
internal enum class DataFit(val label: String) {
    NONE("Off"), LINEAR("Linear"), POLYNOMIAL("Polynomial"),
    EXPONENTIAL("Exponential"), LOGARITHMIC("Logarithmic"), POWER("Power law"), GAUSSIAN("Gaussian"),
}
internal enum class DataSmooth(val label: String) { NONE("Off"), MOVING_AVERAGE("Moving avg"), MEDIAN("Median") }

internal enum class DataPalette(val label: String, val colors: List<Int>) {
    STUDIO(
        "Studio",
        listOf(0xFF4E9BE6.toInt(), 0xFFE06C75.toInt(), 0xFF63B26B.toInt(), 0xFFD19A66.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFF472B6.toInt(), 0xFFE5C07B.toInt()),
    ),
    TABLEAU(
        "Tableau",
        listOf(0xFF4E79A7.toInt(), 0xFFF28E2B.toInt(), 0xFFE15759.toInt(), 0xFF76B7B2.toInt(), 0xFF59A14F.toInt(), 0xFFEDC948.toInt(), 0xFFB07AA1.toInt(), 0xFFFF9DA7.toInt(), 0xFF9C755F.toInt(), 0xFFBAB0AC.toInt()),
    ),
    PASTEL(
        "Pastel",
        listOf(0xFF6BAED6.toInt(), 0xFFFF9E6D.toInt(), 0xFFFF8A8A.toInt(), 0xFF74C476.toInt(), 0xFFDADA8D.toInt(), 0xFF9E9AC8.toInt(), 0xFF8C96C6.toInt(), 0xFFF49CB6.toInt()),
    ),
    NEON(
        "Neon",
        listOf(0xFF00E5FF.toInt(), 0xFFFF4081.toInt(), 0xFF76FF03.toInt(), 0xFFFFEA00.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF6D00.toInt()),
    ),
}

/** One plotted dataset with its style and analysis choices. Errors use NaN for "none". */
internal class DataSeries(name: String) {
    var name by mutableStateOf(name)
    var x by mutableStateOf<List<Double>>(emptyList())
    var y by mutableStateOf<List<Double>>(emptyList())
    var xerr by mutableStateOf<List<Double>>(emptyList())
    var yerr by mutableStateOf<List<Double>>(emptyList())
    var colorArgb by mutableStateOf<Int?>(null)
    var visible by mutableStateOf(true)
    var showPoints by mutableStateOf(true)
    var lineStyle by mutableStateOf(DataLineStyle.SOLID)
    var showErrors by mutableStateOf(true)
    var fillUnder by mutableStateOf(false)
    var tangents by mutableStateOf(false)
    var spline by mutableStateOf(false)
    var fit by mutableStateOf(DataFit.NONE)
    var fitDegree by mutableStateOf("2")
    var smooth by mutableStateOf(DataSmooth.NONE)
    var smoothWindow by mutableStateOf("5")

    fun pointCount(): Int = min(x.size, y.size)
}

internal class DataState {
    var series by mutableStateOf(listOf(DataSeries("Series 1")))
    var title by mutableStateOf("")
    var xLabel by mutableStateOf("x")
    var yLabel by mutableStateOf("y")
    var xMin by mutableStateOf(0.0)
    var xMax by mutableStateOf(1.0)
    var yMin by mutableStateOf(0.0)
    var yMax by mutableStateOf(1.0)
    var xScale by mutableStateOf(DataAxisScale.LINEAR)
    var yScale by mutableStateOf(DataAxisScale.LINEAR)
    var xPower by mutableStateOf("4")
    var yPower by mutableStateOf("2")
    var tickTarget by mutableStateOf("6")
    var showGrid by mutableStateOf(true)
    var palette by mutableStateOf(DataPalette.STUDIO)
    var showTangents by mutableStateOf(false)
    var tangentCount by mutableStateOf("5")
    var tangentFrom by mutableStateOf("")
    var tangentTo by mutableStateOf("")

    fun seriesColor(i: Int): Int = palette.colors[i % palette.colors.size]

    fun autoFitDomain() {
        var loX = Double.POSITIVE_INFINITY; var hiX = Double.NEGATIVE_INFINITY
        var loY = Double.POSITIVE_INFINITY; var hiY = Double.NEGATIVE_INFINITY
        for (s in series) {
            if (!s.visible) continue
            val n = s.pointCount()
            for (i in 0 until n) {
                val x = s.x[i]; val y = s.y[i]
                if (!x.isFinite() || !y.isFinite()) continue
                if (x < loX) loX = x; if (x > hiX) hiX = x
                if (y < loY) loY = y; if (y > hiY) hiY = y
            }
        }
        if (!loX.isFinite()) { loX = 0.0; hiX = 1.0 }
        if (!loY.isFinite()) { loY = 0.0; hiY = 1.0 }
        if (loX == hiX) { loX -= 1.0; hiX += 1.0 }
        if (loY == hiY) { loY -= 1.0; hiY += 1.0 }
        val mx = (hiX - loX) * 0.05; val my = (hiY - loY) * 0.05
        xMin = loX - mx; xMax = hiX + mx; yMin = loY - my; yMax = hiY + my
    }

    fun snapshot(): DataSnap = DataSnap(
        series = series.map { s ->
            DataSeriesSnap(
                name = s.name, x = s.x.toList(), y = s.y.toList(),
                xerr = s.xerr.toList(), yerr = s.yerr.toList(),
                colorArgb = s.colorArgb, visible = s.visible,
                showPoints = s.showPoints, lineStyle = s.lineStyle,
                showErrors = s.showErrors, fillUnder = s.fillUnder, tangents = s.tangents, spline = s.spline,
                fit = s.fit, fitDegree = s.fitDegree.toIntOrNull()?.coerceIn(1, 9) ?: 2,
                smooth = s.smooth, smoothWindow = s.smoothWindow.toIntOrNull()?.coerceIn(3, 101) ?: 5,
            )
        },
        x0 = xMin, x1 = xMax, y0 = yMin, y1 = yMax,
        xScale = xScale, yScale = yScale,
        xPower = xPower.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 4.0,
        yPower = yPower.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 2.0,
        tickTarget = tickTarget.toIntOrNull()?.coerceIn(2, 20) ?: 6,
        showGrid = showGrid, palette = palette,
        showTangents = showTangents,
        tangentCount = tangentCount.toIntOrNull()?.coerceIn(1, 20) ?: 5,
        tangentFrom = tangentFrom.toDoubleOrNull(),
        tangentTo = tangentTo.toDoubleOrNull(),
    )
}

internal data class DataSeriesSnap(
    val name: String,
    val x: List<Double>,
    val y: List<Double>,
    val xerr: List<Double>,
    val yerr: List<Double>,
    val colorArgb: Int?,
    val visible: Boolean,
    val showPoints: Boolean,
    val lineStyle: DataLineStyle,
    val showErrors: Boolean,
    val fillUnder: Boolean,
    val tangents: Boolean,
    val spline: Boolean,
    val fit: DataFit,
    val fitDegree: Int,
    val smooth: DataSmooth,
    val smoothWindow: Int,
)

internal data class DataSnap(
    val series: List<DataSeriesSnap>,
    val x0: Double, val x1: Double, val y0: Double, val y1: Double,
    val xScale: DataAxisScale, val yScale: DataAxisScale,
    val xPower: Double, val yPower: Double,
    val tickTarget: Int,
    val showGrid: Boolean,
    val palette: DataPalette,
    val showTangents: Boolean,
    val tangentCount: Int,
    val tangentFrom: Double?,
    val tangentTo: Double?,
)

// ---------------------------------------------------------------- analysis (pure, unit-testable)

internal fun dataTransform(v: Double, scale: DataAxisScale, power: Double): Double = when (scale) {
    DataAxisScale.LINEAR -> v
    DataAxisScale.LOG10 -> if (v > 0) log10(v) else Double.NaN
    DataAxisScale.POWER -> if (v == 0.0) 0.0 else Math.signum(v) * abs(v).pow(power)
}

internal fun dataBackTransform(t: Double, scale: DataAxisScale, power: Double): Double = when (scale) {
    DataAxisScale.LINEAR -> t
    DataAxisScale.LOG10 -> 10.0.pow(t)
    DataAxisScale.POWER -> if (t == 0.0) 0.0 else Math.signum(t) * abs(t).pow(1.0 / power)
}

/** Weighted least squares for [y] ≈ Σ c[k]·f_k(x); returns coefficients, or null when singular. */
internal fun weightedPolyFit(fs: List<(Double) -> Double>, xs: DoubleArray, ys: DoubleArray, ws: DoubleArray): DoubleArray? {
    val m = fs.size
    val a = Array(m) { DoubleArray(m + 1) }
    for (i in 0 until m) for (j in 0 until m) {
        var s = 0.0
        for (k in xs.indices) s += ws[k] * fs[i](xs[k]) * fs[j](xs[k])
        a[i][j] = s
    }
    for (i in 0 until m) {
        var s = 0.0
        for (k in xs.indices) s += ws[k] * fs[i](xs[k]) * ys[k]
        a[i][m] = s
    }
    // Gaussian elimination with partial pivoting.
    for (col in 0 until m) {
        var piv = col
        for (row in col + 1 until m) if (abs(a[row][col]) > abs(a[piv][col])) piv = row
        if (abs(a[piv][col]) < 1e-14) return null
        val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
        val div = a[col][col]
        for (j in col..m) a[col][j] /= div
        for (row in 0 until m) {
            if (row == col) continue
            val f = a[row][col]
            if (f != 0.0) for (j in col..m) a[row][j] -= f * a[col][j]
        }
    }
    return DoubleArray(m) { a[it][m] }
}

internal fun weightedRSquared(ys: DoubleArray, yh: DoubleArray, ws: DoubleArray): Double {
    var mean = 0.0; var wsum = 0.0
    for (i in ys.indices) { mean += ws[i] * ys[i]; wsum += ws[i] }
    if (wsum <= 0) return Double.NaN
    mean /= wsum
    var ssTot = 0.0; var ssRes = 0.0
    for (i in ys.indices) {
        ssTot += ws[i] * (ys[i] - mean) * (ys[i] - mean)
        val d = ys[i] - yh[i]
        if (d.isFinite()) ssRes += ws[i] * d * d else return Double.NaN
    }
    if (ssTot <= 0) return if (ssRes <= 0) 1.0 else 0.0
    return (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0)
}

internal class DataFitResult(
    val label: String,
    val rSquared: Double,
    val predict: (Double) -> Double,
    val slope: ((Double) -> Double)? = null,
)

private fun fitWeight(yerr: Double): Double =
    if (yerr.isFinite() && yerr > 0) 1.0 / (yerr * yerr) else 1.0

/** Fit y ≈ f(x) over finite points; weights come from y errors when present. */
internal fun fitSeries(xs: DoubleArray, ys: DoubleArray, yerrs: DoubleArray?, kind: DataFit, degree: Int): DataFitResult? {
    val idx = xs.indices.filter { xs[it].isFinite() && ys[it].isFinite() }
    if (idx.size < 2) return null
    val x = DoubleArray(idx.size) { xs[idx[it]] }
    val y = DoubleArray(idx.size) { ys[idx[it]] }
    val w = DoubleArray(idx.size) { i -> if (yerrs != null) fitWeight(yerrs[idx[i]]) else 1.0 }
    return when (kind) {
        DataFit.NONE -> null
        DataFit.LINEAR, DataFit.POLYNOMIAL -> {
            val d = if (kind == DataFit.LINEAR) 1 else degree.coerceIn(1, 9)
            if (idx.size < d + 1) return null
            val fs = List(d + 1) { k -> { t: Double -> t.pow(k) } }
            val c = weightedPolyFit(fs, x, y, w) ?: return null
            val yh = DoubleArray(x.size) { i -> c.indices.sumOf { k -> c[k] * x[i].pow(k) } }
            val r2 = weightedRSquared(y, yh, w)
            val label = if (d == 1) {
                "y = ${Numbers.format(c[0])} + ${Numbers.format(c[1])}·x"
            } else {
                "y = " + c.mapIndexed { k, ck -> "${Numbers.format(ck)}·x^$k" }.joinToString(" + ")
            }
            DataFitResult(
                label = label,
                rSquared = r2,
                predict = { t -> c.indices.sumOf { k -> c[k] * t.pow(k) } },
                slope = { t -> c.indices.drop(1).sumOf { k -> k * c[k] * t.pow(k - 1) } },
            )
        }
        DataFit.EXPONENTIAL -> {
            // ln y = ln a + b·x, weight (y/σy)².
            val sel = idx.filter { y[it] > 0 }
            if (sel.size < 2) return null
            val lx = DoubleArray(sel.size) { x[sel[it]] }
            val ly = DoubleArray(sel.size) { ln(y[sel[it]]) }
            val lw = DoubleArray(sel.size) { i ->
                val s = if (yerrs != null) yerrs[sel[i]] else Double.NaN
                if (s.isFinite() && s > 0) (y[sel[i]] / s).let { it * it } else 1.0
            }
            val c = weightedPolyFit(listOf({ _: Double -> 1.0 }, { t: Double -> t }), lx, ly, lw) ?: return null
            val a = kotlin.math.exp(c[0]); val b = c[1]
            val yh = DoubleArray(x.size) { a * kotlin.math.exp(b * x[it]) }
            DataFitResult(
                label = "y = ${Numbers.format(a)}·e^(${Numbers.format(b)}·x)",
                rSquared = weightedRSquared(y, yh, w),
                predict = { t -> a * kotlin.math.exp(b * t) },
                slope = { t -> a * b * kotlin.math.exp(b * t) },
            )
        }
        DataFit.LOGARITHMIC -> {
            val sel = idx.filter { x[it] > 0 }
            if (sel.size < 2) return null
            val lx = DoubleArray(sel.size) { ln(x[sel[it]]) }
            val ly = DoubleArray(sel.size) { y[sel[it]] }
            val lw = DoubleArray(sel.size) { w[sel[it]] }
            val c = weightedPolyFit(listOf({ _: Double -> 1.0 }, { t: Double -> t }), lx, ly, lw) ?: return null
            DataFitResult(
                label = "y = ${Numbers.format(c[0])} + ${Numbers.format(c[1])}·ln(x)",
                rSquared = weightedRSquared(y, DoubleArray(x.size) { c[0] + c[1] * ln(x[it].coerceAtLeast(1e-300)) }, w),
                predict = { t -> if (t > 0) c[0] + c[1] * ln(t) else Double.NaN },
                slope = { t -> if (t > 0) c[1] / t else Double.NaN },
            )
        }
        DataFit.POWER -> {
            val sel = idx.filter { x[it] > 0 && y[it] > 0 }
            if (sel.size < 2) return null
            val lx = DoubleArray(sel.size) { ln(x[sel[it]]) }
            val ly = DoubleArray(sel.size) { ln(y[sel[it]]) }
            val lw = DoubleArray(sel.size) { i ->
                val s = if (yerrs != null) yerrs[sel[i]] else Double.NaN
                if (s.isFinite() && s > 0) (y[sel[i]] / s).let { it * it } else 1.0
            }
            val c = weightedPolyFit(listOf({ _: Double -> 1.0 }, { t: Double -> t }), lx, ly, lw) ?: return null
            val a = kotlin.math.exp(c[0]); val b = c[1]
            DataFitResult(
                label = "y = ${Numbers.format(a)}·x^${Numbers.format(b)}",
                rSquared = weightedRSquared(y, DoubleArray(x.size) { if (x[it] > 0) a * x[it].pow(b) else Double.NaN }, w),
                predict = { t -> if (t > 0) a * t.pow(b) else Double.NaN },
                slope = { t -> if (t > 0) a * b * t.pow(b - 1) else Double.NaN },
            )
        }
        DataFit.GAUSSIAN -> fitGaussian(x, y, w)
    }
}

private fun fitGaussian(x: DoubleArray, y: DoubleArray, w: DoubleArray): DataFitResult? {
    var wsum = 0.0; var mean = 0.0
    var peak = Double.NEGATIVE_INFINITY
    for (i in x.indices) {
        wsum += w[i] * y[i]
        mean += w[i] * y[i] * x[i]
        if (y[i] > peak) peak = y[i]
    }
    if (!(wsum > 0) || !peak.isFinite()) return null
    mean /= wsum
    var vari = 0.0; var vw = 0.0
    for (i in x.indices) {
        vari += w[i] * y[i] * (x[i] - mean) * (x[i] - mean)
        vw += w[i] * y[i]
    }
    if (!(vw > 0)) return null
    var a = peak; var b = mean; var c = sqrt(maxOf(vari / vw, 1e-12))
    // Two Gauss–Newton refinements with a numeric Jacobian.
    repeat(2) {
        val r = DoubleArray(x.size) { i -> y[i] - a * kotlin.math.exp(-((x[i] - b) * (x[i] - b)) / (2 * c * c)) }
        val jac = Array(x.size) { DoubleArray(3) }
        for (i in x.indices) {
            val e = 1e-7 * maxOf(1.0, abs(a)) to 0.0
            val g = { aa: Double, bb: Double, cc: Double ->
                aa * kotlin.math.exp(-((x[i] - bb) * (x[i] - bb)) / (2 * cc * cc))
            }
            jac[i][0] = (g(a + e.first, b, c) - g(a - e.first, b, c)) / (2 * e.first)
            val eb = 1e-7 * maxOf(1.0, abs(b))
            jac[i][1] = (g(a, b + eb, c) - g(a, b - eb, c)) / (2 * eb)
            val ec = 1e-7 * maxOf(1.0, abs(c))
            jac[i][2] = (g(a, b, c + ec) - g(a, b, c - ec)) / (2 * ec)
        }
        // Normal equations JᵀWJ δ = JᵀWr.
        val jtr = DoubleArray(3)
        val jtj = Array(3) { DoubleArray(4) }
        for (i in x.indices) {
            for (p in 0..2) {
                jtr[p] += w[i] * jac[i][p] * r[i]
                for (q in 0..2) jtj[p][q] += w[i] * jac[i][p] * jac[i][q]
            }
        }
        for (p in 0..2) jtj[p][3] = jtr[p]
        var ok = true
        for (col in 0..2) {
            var piv = col
            for (row in col + 1..2) if (abs(jtj[row][col]) > abs(jtj[piv][col])) piv = row
            if (abs(jtj[piv][col]) < 1e-14) { ok = false; break }
            val tmp = jtj[col]; jtj[col] = jtj[piv]; jtj[piv] = tmp
            val div = jtj[col][col]
            for (j in col..3) jtj[col][j] /= div
            for (row in 0..2) {
                if (row == col) continue
                val f = jtj[row][col]
                if (f != 0.0) for (j in col..3) jtj[row][j] -= f * jtj[col][j]
            }
        }
        if (!ok) return@repeat
        a += jtj[0][3]; b += jtj[1][3]; c = (c + jtj[2][3]).coerceAtLeast(1e-9)
        if (!a.isFinite() || !b.isFinite() || !c.isFinite()) return null
    }
    val yh = DoubleArray(x.size) { a * kotlin.math.exp(-((x[it] - b) * (x[it] - b)) / (2 * c * c)) }
    return DataFitResult(
        label = "y = ${Numbers.format(a)}·e^(-(x−${Numbers.format(b)})²/2·${Numbers.format(c)}²)",
        rSquared = weightedRSquared(y, yh, w),
        predict = { t -> a * kotlin.math.exp(-((t - b) * (t - b)) / (2 * c * c)) },
        slope = { t -> a * kotlin.math.exp(-((t - b) * (t - b)) / (2 * c * c)) * (-(t - b) / (c * c)) },
    )
}

/** Moving average (centred, shrinking at the edges) or median filter over the y values. */
internal fun smoothSeries(ys: DoubleArray, kind: DataSmooth, window: Int): DoubleArray? {
    if (kind == DataSmooth.NONE || ys.isEmpty()) return null
    val w = window.coerceIn(3, 101) or 1
    val half = w / 2
    return DoubleArray(ys.size) { i ->
        val lo = maxOf(0, i - half); val hi = minOf(ys.size - 1, i + half)
        val slice = ys.slice(lo..hi).filter { it.isFinite() }
        if (slice.isEmpty()) Double.NaN
        else if (kind == DataSmooth.MOVING_AVERAGE) slice.average()
        else slice.sorted().let { it[it.size / 2] }
    }
}

/** Natural cubic spline through sorted distinct x; null when fewer than 3 usable points. */
internal class DataSpline private constructor(
    private val xs: DoubleArray,
    private val a: DoubleArray,
    private val b: DoubleArray,
    private val c: DoubleArray,
    private val d: DoubleArray,
) {
    fun eval(t: Double): Double {
        if (t <= xs.first()) return a.first()
        if (t >= xs.last()) return a.last()
        var lo = 0; var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (t < xs[mid]) hi = mid else lo = mid
        }
        val h = t - xs[lo]
        return a[lo] + b[lo] * h + c[lo] * h * h + d[lo] * h * h * h
    }

    companion object {
        fun of(px: DoubleArray, py: DoubleArray): DataSpline? {
            // Average duplicate x values, keep order.
            val merged = ArrayList<Pair<Double, Double>>()
            val order = px.indices.filter { px[it].isFinite() && py[it].isFinite() }.sortedBy { px[it] }
            for (i in order) {
                val last = merged.lastOrNull()
                if (last != null && last.first == px[i]) merged[merged.lastIndex] = last.first to (last.second + py[i]) / 2
                else merged += px[i] to py[i]
            }
            if (merged.size < 3) return null
            val n = merged.size
            val xs = DoubleArray(n) { merged[it].first }
            val a = DoubleArray(n) { merged[it].second }
            val h = DoubleArray(n - 1) { xs[it + 1] - xs[it] }
            if (h.any { it <= 0 }) return null
            // Tridiagonal solve for the second derivatives.
            val alpha = DoubleArray(n)
            for (i in 1 until n - 1) alpha[i] = 3.0 / h[i] * (a[i + 1] - a[i]) - 3.0 / h[i - 1] * (a[i] - a[i - 1])
            val l = DoubleArray(n); val mu = DoubleArray(n); val z = DoubleArray(n)
            l[0] = 1.0
            for (i in 1 until n - 1) {
                l[i] = 2.0 * (xs[i + 1] - xs[i - 1]) - h[i - 1] * mu[i - 1]
                if (l[i] == 0.0) return null
                mu[i] = h[i] / l[i]
                z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i]
            }
            l[n - 1] = 1.0
            val c = DoubleArray(n); val b = DoubleArray(n - 1); val d = DoubleArray(n - 1)
            var cc = 0.0
            for (j in n - 2 downTo 0) {
                c[j] = z[j] - mu[j] * cc
                cc = c[j]
                b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2.0 * c[j]) / 3.0
                d[j] = (c[j + 1] - c[j]) / (3.0 * h[j])
            }
            return DataSpline(xs, a, b, c.copyOf(n - 1), d)
        }
    }
}

// ---------------------------------------------------------------- geometry + painting

private class DataStroke(val pts: FloatArray, val color: Int, val width: Float, val dash: FloatArray?)
private class DataFillPoly(val pts: FloatArray, val color: Int)
private class DataDot(val x: Float, val y: Float, val color: Int)
private class DataSeg(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val color: Int, val width: Float, val dash: FloatArray? = null)
private class DataTick(val x: Float, val y: Float, val label: String)

private class DataGeom(
    val uMin: Double, val uMax: Double, val vMin: Double, val vMax: Double,
    val grid: List<FloatArray>,
    val ticks: List<DataTick>,
    val fills: List<DataFillPoly>,
    val curves: List<DataStroke>,
    val points: List<DataDot>,
    val errBars: List<DataSeg>,
    val tangents: List<DataSeg>,
    val legend: List<Pair<String, Int>>,
)

private fun legendColor(series: DataSeriesSnap, index: Int, palette: DataPalette): Int =
    series.colorArgb ?: palette.colors[index % palette.colors.size]

/** Sample [f] over [lo, hi], breaking into strokes wherever it is not finite. */
private fun sampleFn(f: (Double) -> Double, lo: Double, hi: Double, n: Int = 160): List<FloatArray> {
    val out = ArrayList<FloatArray>()
    var cur: MutableList<Float>? = null
    for (i in 0..n) {
        val x = lo + (hi - lo) * i / n
        val y = runCatching { f(x) }.getOrDefault(Double.NaN)
        if (!x.isFinite() || !y.isFinite()) {
            if (cur != null && cur.size >= 4) out.add(cur.toFloatArray())
            cur = null
        } else {
            if (cur == null) cur = ArrayList()
            cur.add(x.toFloat()); cur.add(y.toFloat())
        }
    }
    if (cur != null && cur.size >= 4) out.add(cur.toFloatArray())
    return out
}

private fun buildDataGeom(snap: DataSnap): DataGeom? {
    if (!snap.x0.isFinite() || !snap.x1.isFinite() || !snap.y0.isFinite() || !snap.y1.isFinite()) return null
    if (snap.x0 >= snap.x1 || snap.y0 >= snap.y1) return null
    val fx = { v: Double -> dataTransform(v, snap.xScale, snap.xPower) }
    val fy = { v: Double -> dataTransform(v, snap.yScale, snap.yPower) }
    val u0 = fx(snap.x0); val u1 = fx(snap.x1); val v0 = fy(snap.y0); val v1 = fy(snap.y1)
    if (!listOf(u0, u1, v0, v1).all { it.isFinite() } || u0 >= u1 || v0 >= v1) return null
    val legend = ArrayList<Pair<String, Int>>()
    val grid = ArrayList<FloatArray>()
    val ticks = ArrayList<DataTick>()
    // Grid + ticks per axis in data units (log: decades; power: transformed nice steps).
    fun axisTicks(lo: Double, hi: Double, scale: DataAxisScale, power: Double, horizontal: Boolean) {
        if (scale == DataAxisScale.LOG10) {
            if (lo <= 0 || hi <= 0) return
            val k0 = kotlin.math.ceil(log10(lo)).toInt()
            val k1 = floor(log10(hi)).toInt()
            for (k in k0..k1) {
                val v = 10.0.pow(k)
                val t = if (horizontal) fx(v) else fy(v)
                if (!t.isFinite()) continue
                val f = t.toFloat()
                if (horizontal) {
                    grid.add(floatArrayOf(f, v0.toFloat(), f, v1.toFloat()))
                    ticks.add(DataTick(f, v0.toFloat(), Numbers.tick(v)))
                } else {
                    grid.add(floatArrayOf(u0.toFloat(), f, u1.toFloat(), f))
                    ticks.add(DataTick(u0.toFloat(), f, Numbers.tick(v)))
                }
            }
            return
        }
        val span: Double
        val step: Double
        val toT: (Double) -> Double
        val toV: (Double) -> Double
        if (scale == DataAxisScale.POWER) {
            val t0 = fx(lo); val t1 = fx(hi)
            if (!t0.isFinite() || !t1.isFinite() || t0 >= t1) return
            span = t1 - t0; step = niceStep(span, snap.tickTarget)
            toT = { it }; toV = { dataBackTransform(it, scale, power) }
        } else {
            span = hi - lo; step = niceStep(span, snap.tickTarget)
            toT = { if (horizontal) fx(it) else fy(it) }; toV = { it }
        }
        var g = floor((if (scale == DataAxisScale.POWER) fx(lo) else lo) / step) * step
        var guard = 0
        val endT = if (scale == DataAxisScale.POWER) fx(hi) else hi
        while (g <= endT + step * 1e-9 && guard++ < 40) {
            val dataVal = toV(g)
            val t = toT(if (scale == DataAxisScale.POWER) g else dataVal)
            if (t.isFinite() && dataVal.isFinite()) {
                val f = t.toFloat()
                if (horizontal) {
                    grid.add(floatArrayOf(f, v0.toFloat(), f, v1.toFloat()))
                    ticks.add(DataTick(f, v0.toFloat(), Numbers.tick(dataVal)))
                } else {
                    grid.add(floatArrayOf(u0.toFloat(), f, u1.toFloat(), f))
                    ticks.add(DataTick(u0.toFloat(), f, Numbers.tick(dataVal)))
                }
            }
            g += step
        }
    }
    axisTicks(snap.x0, snap.x1, snap.xScale, snap.xPower, true)
    axisTicks(snap.y0, snap.y1, snap.yScale, snap.yPower, false)
    val fills = ArrayList<DataFillPoly>()
    val curves = ArrayList<DataStroke>()
    val points = ArrayList<DataDot>()
    val errBars = ArrayList<DataSeg>()
    val tangents = ArrayList<DataSeg>()
    val dashLong = floatArrayOf(10f, 6f)
    val dashDot = floatArrayOf(2f, 5f)
    snap.series.forEachIndexed { si, s ->
        if (!s.visible) return@forEachIndexed
        val col = legendColor(s, si, snap.palette)
        legend.add(s.name.ifBlank { "Series ${si + 1}" } to col)
        val n = min(s.x.size, s.y.size)
        val kept = (0 until n).filter { s.x[it].isFinite() && s.y[it].isFinite() && fx(s.x[it]).isFinite() && fy(s.y[it]).isFinite() }
        if (kept.isEmpty()) return@forEachIndexed
        val xs = kept.map { s.x[it] }.toDoubleArray()
        val ys = kept.map { s.y[it] }.toDoubleArray()
        val order = xs.indices.sortedBy { xs[it] }
        val sx = DoubleArray(order.size) { xs[order[it]] }
        val sy = DoubleArray(order.size) { ys[order[it]] }
        // Fit first: overlay curve + legend entry.
        var fit: DataFitResult? = null
        var slopeFn: ((Double) -> Double)? = null
        var curveFn: ((Double) -> Double)? = null
        if (s.fit != DataFit.NONE) {
            val yerrs = DoubleArray(order.size) { i -> s.yerr.getOrElse(order[i]) { Double.NaN } }
            fit = fitSeries(sx, sy, yerrs, s.fit, s.fitDegree)
            if (fit != null) {
                legend.add("${s.name.ifBlank { "Series ${si + 1}" }}: ${fit.label} (R²=${"%.3f".format(fit.rSquared)})" to col)
                val f = fit
                val flo = maxOf(sx.first(), snap.x0); val fhi = minOf(sx.last(), snap.x1)
                if (fhi > flo) {
                    for (stroke in sampleFn({ t -> f.predict(t) }, flo, fhi)) {
                        val pts = FloatArray(stroke.size)
                        var ok = true
                        for (i in stroke.indices step 2) {
                            val u = fx(stroke[i].toDouble()); val v = fy(stroke[i + 1].toDouble())
                            if (!u.isFinite() || !v.isFinite()) { ok = false; break }
                            pts[i] = u.toFloat(); pts[i + 1] = v.toFloat()
                        }
                        if (ok) curves.add(DataStroke(pts, col, 3.0f, dashLong))
                    }
                }
                slopeFn = fit.slope
                curveFn = { t -> fit.predict(t) }
            }
        }
        // Display curve: spline or polyline through the data.
        var display: ((Double) -> Double)? = null
        if (s.lineStyle != DataLineStyle.NONE || s.fillUnder || s.tangents) {
            if (s.spline) {
                DataSpline.of(sx, sy)?.let { sp ->
                    display = { t -> sp.eval(t) }
                    val flo = maxOf(sx.first(), snap.x0); val fhi = minOf(sx.last(), snap.x1)
                    if (fhi > flo && s.lineStyle != DataLineStyle.NONE) {
                        for (stroke in sampleFn({ t -> sp.eval(t) }, flo, fhi)) {
                            val pts = FloatArray(stroke.size)
                            var ok = true
                            for (i in stroke.indices step 2) {
                                val u = fx(stroke[i].toDouble()); val v = fy(stroke[i + 1].toDouble())
                                if (!u.isFinite() || !v.isFinite()) { ok = false; break }
                                pts[i] = u.toFloat(); pts[i + 1] = v.toFloat()
                            }
                            if (ok) curves.add(DataStroke(pts, col, 2.4f, null))
                        }
                    }
                }
            }
            if (display == null) {
                display = if (order.size >= 2) { t ->
                    var lo = 0
                    while (lo < sx.size - 2 && t > sx[lo + 1]) lo++
                    val x0 = sx[lo]; val x1 = sx[lo + 1]
                    if (x1 == x0) sy[lo] else sy[lo] + (sy[lo + 1] - sy[lo]) * (t - x0) / (x1 - x0)
                } else { _ ->
                    // A lone point draws no curve; tangents through it are horizontal.
                    sy.getOrElse(0) { Double.NaN }
                }
                if (s.lineStyle != DataLineStyle.NONE && order.size >= 2) {
                    val pts = FloatArray(order.size * 2)
                    for (i in order.indices) {
                        pts[i * 2] = fx(sx[i]).toFloat()
                        pts[i * 2 + 1] = fy(sy[i]).toFloat()
                    }
                    val dash = when (s.lineStyle) {
                        DataLineStyle.DASHED -> dashLong
                        DataLineStyle.DOTTED -> dashDot
                        else -> null
                    }
                    curves.add(DataStroke(pts, col, 2.4f, dash))
                }
            }
        }
        if (curveFn == null) curveFn = display
        // Fill under the display curve down to y = 0 (clamped into view).
        if (s.fillUnder && display != null) {
            val df = display
            val flo = maxOf(sx.first(), snap.x0); val fhi = minOf(sx.last(), snap.x1)
            if (fhi > flo) {
                val vb = fy(0.0).let { if (it.isFinite()) it.coerceIn(v0, v1) else v0 }
                val poly = ArrayList<Float>()
                val steps = 120
                for (i in 0..steps) {
                    val x = flo + (fhi - flo) * i / steps
                    val y = runCatching { df(x) }.getOrDefault(Double.NaN)
                    val u = fx(x); val v = fy(y)
                    if (!u.isFinite() || !v.isFinite()) continue
                    poly.add(u.toFloat()); poly.add(v.toFloat())
                }
                if (poly.size >= 4) {
                    val first = poly[0]; val last = poly[poly.size - 2]
                    val full = FloatArray(poly.size + 4)
                    poly.toFloatArray().copyInto(full)
                    full[poly.size] = last; full[poly.size + 1] = vb.toFloat()
                    full[poly.size + 2] = first; full[poly.size + 3] = vb.toFloat()
                    fills.add(DataFillPoly(full, (col and 0x00FFFFFF.toInt()) or 0x2E000000.toInt()))
                }
            }
        }
        // Smoothed overlay.
        if (s.smooth != DataSmooth.NONE) {
            smoothSeries(sy, s.smooth, s.smoothWindow)?.let { sm ->
                val pts = FloatArray(order.size * 2)
                var okCount = 0
                for (i in order.indices) {
                    if (!sm[i].isFinite()) continue
                    val u = fx(sx[i]); val v = fy(sm[i])
                    if (!u.isFinite() || !v.isFinite()) continue
                    pts[okCount * 2] = u.toFloat(); pts[okCount * 2 + 1] = v.toFloat()
                    okCount++
                }
                if (okCount >= 2) curves.add(DataStroke(pts.copyOf(okCount * 2), col, 2.0f, dashDot))
            }
        }
        // Points and error bars.
        if (s.showPoints) {
            for (i in order.indices) points.add(DataDot(fx(sx[i]).toFloat(), fy(sy[i]).toFloat(), col))
        }
        if (s.showErrors) {
            for (i in order.indices) {
                val gi = order[i]
                val xe = s.xerr.getOrElse(gi) { Double.NaN }
                val ye = s.yerr.getOrElse(gi) { Double.NaN }
                val ux = fx(sx[i]); val uy = fy(sy[i])
                if (xe.isFinite() && xe > 0) {
                    val a = fx(sx[i] - xe); val b = fx(sx[i] + xe)
                    if (a.isFinite() && b.isFinite()) errBars.add(DataSeg(a.toFloat(), uy.toFloat(), b.toFloat(), uy.toFloat(), col, 1.6f))
                }
                if (ye.isFinite() && ye > 0) {
                    val a = fy(sy[i] - ye); val b = fy(sy[i] + ye)
                    if (a.isFinite() && b.isFinite()) errBars.add(DataSeg(ux.toFloat(), a.toFloat(), ux.toFloat(), b.toFloat(), col, 1.6f))
                }
            }
        }
        // Tangents at up to K data points inside the range.
        if (snap.showTangents && s.tangents) {
            val lo = snap.tangentFrom ?: sx.first()
            val hi = snap.tangentTo ?: sx.last()
            val cand = order.filter { sx[it] in lo..hi && fx(sx[it]).isFinite() && fy(sy[it]).isFinite() }
            if (cand.isNotEmpty()) {
                val k = minOf(snap.tangentCount, cand.size)
                val picked = (0 until k).map { cand[it * cand.size / k] }
                val dx = (hi - lo).let { if (it > 0 && it.isFinite()) it * 0.06 else (snap.x1 - snap.x0) * 0.03 }
                val slopeOf: (Double) -> Double = slopeFn ?: { t ->
                    val h = maxOf(abs(t) * 1e-4, 1e-7)
                    val cf = curveFn
                    if (cf == null) Double.NaN
                    else (runCatching { cf(t + h) }.getOrDefault(Double.NaN) - runCatching { cf(t - h) }.getOrDefault(Double.NaN)) / (2 * h)
                }
                for (gi in picked) {
                    val x = sx[gi]; val y = sy[gi]
                    val m = runCatching { slopeOf(x) }.getOrDefault(Double.NaN)
                    if (!m.isFinite()) continue
                    val xa = x - dx; val xb = x + dx
                    val ua = fx(xa); val ub = fx(xb)
                    val va = fy(y - m * dx); val vb = fy(y + m * dx)
                    if (!ua.isFinite() || !ub.isFinite() || !va.isFinite() || !vb.isFinite()) continue
                    tangents.add(DataSeg(ua.toFloat(), va.toFloat(), ub.toFloat(), vb.toFloat(), col, 2.0f, dashLong))
                }
            }
        }
    }
    return DataGeom(u0, u1, v0, v1, grid, ticks, fills, curves, points, errBars, tangents, legend)
}


private fun renderDataBitmap(snap: DataSnap, theme: GraphTheme, w: Int, h: Int, title: String, xLabel: String, yLabel: String): Bitmap? {
    val geom = buildDataGeom(snap) ?: return null
    val out = Bitmap.createBitmap(w.coerceIn(2, 1600), h.coerceIn(2, 1600), Bitmap.Config.ARGB_8888)
    paintDataFrame(android.graphics.Canvas(out), geom, out.width, out.height, theme, title, xLabel, yLabel)
    return out
}

private fun paintDataFrame(
    c: android.graphics.Canvas,
    geom: DataGeom,
    w: Int, h: Int,
    theme: GraphTheme,
    title: String,
    xLabel: String,
    yLabel: String,
) {
    // Shared by the live bitmap and exports: everything below draws into [c].
    val padL = maxOf(54f, w * 0.085f)
    val padB = maxOf(44f, h * 0.11f)
    val padT = if (title.isNotBlank()) maxOf(30f, h * 0.07f) else 12f
    val padR = 14f
    val pw = (w - padL - padR).coerceAtLeast(2f)
    val ph = (h - padT - padB).coerceAtLeast(2f)
    fun sx(u: Float): Float = padL + (u - geom.uMin.toFloat()) / (geom.uMax - geom.uMin).toFloat() * pw
    fun sy(v: Float): Float = padT + ph - (v - geom.vMin.toFloat()) / (geom.vMax - geom.vMin).toFloat() * ph
    val uSpan = (geom.uMax - geom.uMin).takeIf { it > 0 } ?: 1.0
    val vSpan = (geom.vMax - geom.vMin).takeIf { it > 0 } ?: 1.0
    c.drawColor(theme.paper)
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.grid; strokeWidth = 1.2f; alpha = 110 }
    val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.grid; strokeWidth = 1.8f; alpha = 170; style = Paint.Style.STROKE }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.dim; textSize = (min(w, h) * 0.03f).coerceIn(12f, 24f); typeface = Typeface.MONOSPACE
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.ink; textSize = (min(w, h) * 0.042f).coerceIn(16f, 34f); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    if (geom.grid.isNotEmpty()) {
        for (g in geom.grid) c.drawLine(sx(g[0]), sy(g[1]), sx(g[2]), sy(g[3]), gridPaint)
    }
    c.drawRect(padL, padT, padL + pw, padT + ph, framePaint)
    for (t in geom.ticks) {
        val onX = abs(t.y - geom.vMin) < vSpan * 1e-9
        if (onX) c.drawText(t.label, sx(t.x), padT + ph + textPaint.textSize + 2f, textPaint.apply { textAlign = Paint.Align.CENTER })
        else c.drawText(t.label, padL - 6f, sy(t.y) + textPaint.textSize * 0.35f, textPaint.apply { textAlign = Paint.Align.RIGHT })
    }
    textPaint.textAlign = Paint.Align.LEFT
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    for (f in geom.fills) {
        val path = android.graphics.Path()
        path.moveTo(sx(f.pts[0]), sy(f.pts[1]))
        var i = 2
        while (i < f.pts.size) { path.lineTo(sx(f.pts[i]), sy(f.pts[i + 1])); i += 2 }
        path.close()
        fillPaint.color = f.color
        c.save()
        c.clipRect(padL, padT, padL + pw, padT + ph)
        c.drawPath(path, fillPaint)
        c.restore()
    }
    c.save()
    c.clipRect(padL, padT, padL + pw, padT + ph)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    for (curve in geom.curves) {
        if (curve.pts.size < 4) continue
        linePaint.color = curve.color
        linePaint.strokeWidth = curve.width * (min(w, h) / 480f).coerceIn(0.8f, 2.5f)
        linePaint.pathEffect = curve.dash?.let { DashPathEffect(it, 0f) }
        val path = android.graphics.Path()
        path.moveTo(sx(curve.pts[0]), sy(curve.pts[1]))
        var i = 2
        while (i < curve.pts.size) { path.lineTo(sx(curve.pts[i]), sy(curve.pts[i + 1])); i += 2 }
        c.drawPath(path, linePaint)
    }
    linePaint.pathEffect = null
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val dotR = (min(w, h) * 0.0075f).coerceIn(2.5f, 6f)
    for (p in geom.points) {
        dotPaint.color = p.color
        c.drawCircle(sx(p.x), sy(p.y), dotR, dotPaint)
    }
    val errPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND }
    val cap = (min(w, h) * 0.012f).coerceIn(3f, 8f)
    for (e in geom.errBars) {
        errPaint.color = e.color
        val x0 = sx(e.x0); val y0 = sy(e.y0); val x1 = sx(e.x1); val y1 = sy(e.y1)
        c.drawLine(x0, y0, x1, y1, errPaint)
        if (abs(x1 - x0) < 1e-6) {
            c.drawLine(x0 - cap, y0, x0 + cap, y0, errPaint)
            c.drawLine(x1 - cap, y1, x1 + cap, y1, errPaint)
        } else {
            c.drawLine(x0, y0 - cap, x0, y0 + cap, errPaint)
            c.drawLine(x1, y1 - cap, x1, y1 + cap, errPaint)
        }
    }
    for (t in geom.tangents) {
        linePaint.color = t.color
        linePaint.strokeWidth = 2.0f
        linePaint.pathEffect = t.dash?.let { DashPathEffect(it, 0f) }
        c.drawLine(sx(t.x0), sy(t.y0), sx(t.x1), sy(t.y1), linePaint)
    }
    linePaint.pathEffect = null
    c.restore()
    if (title.isNotBlank()) c.drawText(title, padL + pw / 2f, padT * 0.62f, titlePaint)
    val labPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.dim; textSize = textPaint.textSize; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    if (xLabel.isNotBlank()) c.drawText(xLabel, padL + pw / 2f, (h - 6).toFloat(), labPaint)
    if (yLabel.isNotBlank()) {
        c.save()
        c.rotate(-90f, 12f, padT + ph / 2f)
        c.drawText(yLabel, 12f, padT + ph / 2f, labPaint)
        c.restore()
    }
}

// ---------------------------------------------------------------- import / export

internal enum class DataImportMode(val label: String) {
    PAIRS("Column pairs"),
    XYERR("x y [xerr] [yerr]"),
    TABLE("Table layout"),
}

private fun numCell(row: List<String>, i: Int): Double? =
    row.getOrNull(i)?.let { parseTableNumber(it) }

private fun DataState.addSeries(
    name: String,
    xs: List<Double>,
    ys: List<Double>,
    xerr: List<Double> = emptyList(),
    yerr: List<Double> = emptyList(),
): DataSeries? {
    val n = minOf(xs.size, ys.size).coerceAtMost(5000)
    if (n < 1) return null
    val s = DataSeries(name)
    s.x = xs.take(n); s.y = ys.take(n)
    s.xerr = List(n) { xerr.getOrElse(it) { Double.NaN } }
    s.yerr = List(n) { yerr.getOrElse(it) { Double.NaN } }
    series = series + s
    return s
}

/**
 * Turn pasted/table CSV rows into datasets. PAIRS makes one series per column pair;
 * XYERR reads a single x y [xerr] [yerr] dataset; TABLE follows the table convention
 * (2 cols = x y, 3 = +yerr, 4 = +xerr +yerr, otherwise pairs). Returns series added.
 */
internal fun DataState.importCsv(text: String, mode: DataImportMode, baseName: String = "Data"): Int {
    val rows = parseTableCsv(text)
    if (rows.isEmpty()) return 0
    // A lone empty placeholder gives way to the first real import.
    if (series.size == 1 && series[0].pointCount() == 0) series = emptyList()
    val width = rows.maxOfOrNull { it.size } ?: 0
    if (width < 2) return 0
    var added = 0
    fun pairSeries(xc: Int, yc: Int, name: String): Boolean {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (r in rows) {
            val x = numCell(r, xc); val y = numCell(r, yc)
            if (x != null && y != null) { xs.add(x); ys.add(y) }
        }
        if (xs.size < 2) return false
        addSeries(name, xs, ys) ?: return false
        return true
    }
    when (mode) {
        DataImportMode.PAIRS -> {
            var i = 0
            while (i + 1 < width) {
                if (pairSeries(i, i + 1, "$baseName ${series.size + 1}")) added++
                i += 2
            }
        }
        DataImportMode.XYERR -> {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            val xe = ArrayList<Double>(); val ye = ArrayList<Double>()
            for (r in rows) {
                val x = numCell(r, 0); val y = numCell(r, 1) ?: continue
                if (x == null) continue
                xs.add(x); ys.add(y)
                xe.add(numCell(r, 2) ?: Double.NaN)
                ye.add(numCell(r, if (width >= 4) 3 else 2) ?: Double.NaN)
            }
            if (xs.size >= 2) {
                // 3 columns mean y errors; 4 mean x and y errors.
                val xerr = if (width >= 4) xe else List(xs.size) { Double.NaN }
                val yerr = if (width >= 3) ye else List(xs.size) { Double.NaN }
                if (addSeries("$baseName ${series.size + 1}", xs, ys, xerr, yerr) != null) added++
            }
        }
        DataImportMode.TABLE -> {
            if (width == 4) {
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                val xe = ArrayList<Double>(); val ye = ArrayList<Double>()
                for (r in rows) {
                    val x = numCell(r, 0); val y = numCell(r, 1) ?: continue
                    if (x == null) continue
                    xs.add(x); ys.add(y)
                    xe.add(numCell(r, 2) ?: Double.NaN)
                    ye.add(numCell(r, 3) ?: Double.NaN)
                }
                if (xs.size >= 2 && addSeries("$baseName ${series.size + 1}", xs, ys, xe, ye) != null) added++
            } else if (width == 3) {
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ye = ArrayList<Double>()
                for (r in rows) {
                    val x = numCell(r, 0); val y = numCell(r, 1) ?: continue
                    if (x == null) continue
                    xs.add(x); ys.add(y); ye.add(numCell(r, 2) ?: Double.NaN)
                }
                if (xs.size >= 2 && addSeries("$baseName ${series.size + 1}", xs, ys, List(xs.size) { Double.NaN }, ye) != null) added++
            } else {
                var i = 0
                while (i + 1 < width) {
                    if (pairSeries(i, i + 1, "$baseName ${series.size + 1}")) added++
                    i += 2
                }
            }
        }
    }
    if (added > 0) autoFitDomain()
    return added
}

// ---------------------------------------------------------------- pane

@Composable
internal fun DataPane(state: DataState, onInsert: (ByteArray) -> Unit) {
    val theme = rememberGraphTheme()
    val title = stringResource(R.string.math_tab_data)
    val failedText = stringResource(R.string.math_insert_failed)
    val scope = rememberCoroutineScope()
    var inserting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val colors = mathPlotColors()
    val legend: List<Pair<String, Color>> = state.series.mapIndexedNotNull { i, s ->
        if (!s.visible) null
        else {
            val label = buildString {
                append(s.name.ifBlank { "Series ${i + 1}" })
                if (s.fit != DataFit.NONE) append(" · ${s.fit.label}")
            }
            label to (s.colorArgb?.let { Color(it) } ?: colors[i % colors.size])
        }
    }
    val xPowNum = state.xPower.toDoubleOrNull() ?: 0.0
    val yPowNum = state.yPower.toDoubleOrNull() ?: 0.0
    val scaleCaption = "x: ${state.xScale.label}${if (state.xScale == DataAxisScale.POWER) " ${Numbers.tick(xPowNum)}" else ""}" +
        "  ·  y: ${state.yScale.label}${if (state.yScale == DataAxisScale.POWER) " ${Numbers.tick(yPowNum)}" else ""}"
    GraphWorkspace(
        modes = listOf("DATA" to stringResource(R.string.math_tab_data)),
        selected = "DATA",
        onSelect = {},
        legend = legend,
        caption = scaleCaption,
        showGrid = state.showGrid,
        onToggleGrid = { state.showGrid = !state.showGrid },
        onReset = { state.autoFitDomain() },
        inserting = inserting,
        onInsert = {
            if (inserting) return@GraphWorkspace
            val snap = state.snapshot()
            scope.launch {
                inserting = true; failed = false
                val bytes = withContext(Dispatchers.Default) { exportData(snap, theme, title.ifBlank { state.title }) }
                inserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        },
        status = if (failed) failedText else null,
        plot = { DataPlot(state, Modifier.fillMaxSize()) },
        controls = { DataControls(state) },
    )
}

private fun dataScaleCaption(s: DataSnap): String {
    fun part(scale: DataAxisScale, p: Double) = scale.label + if (scale == DataAxisScale.POWER) " ${Numbers.tick(p)}" else ""
    return "x: ${part(s.xScale, s.xPower)}  ·  y: ${part(s.yScale, s.yPower)}"
}

private suspend fun exportData(snap: DataSnap, theme: GraphTheme, title: String): ByteArray? = try {
    val geom = buildDataGeom(snap) ?: return null
    val bmp = Bitmap.createBitmap(EXPORT_PLOT_W, EXPORT_PLOT_H, Bitmap.Config.ARGB_8888)
    paintDataFrame(android.graphics.Canvas(bmp), geom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, title, "", "")
    val legend = geom.legend.map { ExportLegend(it.first, it.second) }
    val foot = buildString {
        append(windowFootnote(snap.x0, snap.x1, snap.y0, snap.y1))
        append("    ${dataScaleCaption(snap)}")
        if (snap.showTangents) append("    tangents ≤ ${snap.tangentCount}")
    }
    writeGraphPng(bmp, title.ifBlank { "Data" }, legend, foot, theme)
} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Throwable) { null }

@Composable
private fun DataPlot(state: DataState, modifier: Modifier = Modifier) {
    val theme = rememberGraphTheme()
    fun styleOf(s: DataSeries): String = listOf(
        s.name, s.colorArgb?.toString() ?: "", s.visible, s.showPoints, s.lineStyle.name,
        s.showErrors, s.fillUnder, s.tangents, s.spline, s.fit.name, s.fitDegree,
        s.smooth.name, s.smoothWindow,
        s.x.joinToString(","), s.y.joinToString(","),
        s.xerr.joinToString(","), s.yerr.joinToString(","),
    ).joinToString("")
    val seriesKey = rememberSettled(state.series.joinToString("", transform = ::styleOf), 200)
    val setupKey = rememberSettled(
        listOf(state.xMin, state.xMax, state.yMin, state.yMax, state.xScale, state.yScale, state.xPower, state.yPower,
            state.tickTarget, state.showGrid, state.palette, state.showTangents, state.tangentCount,
            state.tangentFrom, state.tangentTo, state.title, state.xLabel, state.yLabel).joinToString("|"),
        200,
    )
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var view by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    LaunchedEffect(seriesKey, setupKey, theme) {
        val snap = state.snapshot()
        val (w, h) = cappedSize(view.width, view.height, 640)
        frame = withContext(Dispatchers.Default) {
            renderDataBitmap(snap, theme, w, h, state.title, state.xLabel, state.yLabel)
        }?.asImageBitmap()
    }
    Box(modifier.clipToBounds().onSizeChanged { view = it }) {
        frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (frame == null) {
            val empty = state.series.none { it.visible && it.pointCount() > 0 }
            Text(
                stringResource(if (empty) R.string.math_data_nodata else R.string.math_3d_computing),
                color = LocalPalette.current.textDim.toComposeColor(),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

// ---------------------------------------------------------------- controls

@Composable
private fun DataControls(state: DataState) {
    val palette = LocalPalette.current
    val eyeLabel = stringResource(R.string.math_toggle_visibility)
    val colorLabel = stringResource(R.string.math_pick_color)
    val colors = mathPlotColors()
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_data_titles), Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        MathTextField(state.title, { state.title = it }, stringResource(R.string.math_data_title), Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MathTextField(state.xLabel, { state.xLabel = it }, stringResource(R.string.math_data_xlabel), Modifier.weight(1f))
            MathTextField(state.yLabel, { state.yLabel = it }, stringResource(R.string.math_data_ylabel), Modifier.weight(1f))
        }
    }
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_data_palette), Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        MathToolSelector(DataPalette.entries.map { it.name to it.label }, state.palette.name, Modifier.fillMaxWidth()) {
            state.palette = DataPalette.valueOf(it)
        }
    }
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_domain), Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DomainField(state.xMin, { state.xMin = it }, stringResource(R.string.math_x_min), Modifier.weight(1f))
            DomainField(state.xMax, { state.xMax = it }, stringResource(R.string.math_x_max), Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DomainField(state.yMin, { state.yMin = it }, stringResource(R.string.math_y_min), Modifier.weight(1f))
            DomainField(state.yMax, { state.yMax = it }, stringResource(R.string.math_y_max), Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        MathSecondaryButton(stringResource(R.string.math_data_fit_domain), Modifier.fillMaxWidth()) { state.autoFitDomain() }
    }
    MathCard(Modifier.fillMaxWidth()) {
        MathSectionTitle(stringResource(R.string.math_data_scales), Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        DataScaleRow(stringResource(R.string.math_data_xscale), state.xScale, { state.xScale = it },
            state.xPower, { state.xPower = it })
        Spacer(Modifier.height(4.dp))
        DataScaleRow(stringResource(R.string.math_data_yscale), state.yScale, { state.yScale = it },
            state.yPower, { state.yPower = it })
        Spacer(Modifier.height(4.dp))
        MathTextField(state.tickTarget, { state.tickTarget = it.filter(Char::isDigit) },
            stringResource(R.string.math_data_ticks), Modifier.fillMaxWidth())
    }
    MathCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = state.showTangents, onCheckedChange = { state.showTangents = it })
            MathSectionTitle(stringResource(R.string.math_data_tangents), Modifier.weight(1f))
        }
        if (state.showTangents) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MathTextField(state.tangentCount, { state.tangentCount = it.filter(Char::isDigit) },
                    stringResource(R.string.math_data_count), Modifier.weight(1f))
                MathTextField(state.tangentFrom, { state.tangentFrom = it }, stringResource(R.string.math_from), Modifier.weight(1f))
                MathTextField(state.tangentTo, { state.tangentTo = it }, stringResource(R.string.math_to), Modifier.weight(1f))
            }
            Spacer(Modifier.height(2.dp))
            MathHint(stringResource(R.string.math_data_tangent_hint))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MathSectionTitle(stringResource(R.string.math_data_series), Modifier.weight(1f))
        MathSecondaryButton(stringResource(R.string.math_data_add)) {
            state.series = state.series + DataSeries("Series ${state.series.size + 1}")
        }
        var pasting by remember { mutableStateOf(false) }
        MathSecondaryButton(stringResource(R.string.math_data_paste)) { pasting = true }
        if (pasting) {
            PasteDataDialog(
                onConfirm = { text, mode, replace ->
                    if (replace) state.series = emptyList()
                    state.importCsv(text, mode)
                    pasting = false
                },
                onDismiss = { pasting = false },
            )
        }
    }
    if (state.series.isEmpty()) {
        MathHint(stringResource(R.string.math_data_empty))
    }
    state.series.forEachIndexed { index, s ->
        val effColor = s.colorArgb?.let { Color(it) } ?: colors[index % colors.size]
        var editing by remember(s) { mutableStateOf(false) }
        MathCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                EntryColorDot(s.colorArgb, effColor, colorLabel) { s.colorArgb = it }
                MathTextField(s.name, { s.name = it }, "${stringResource(R.string.math_data_name)} ${index + 1}", Modifier.weight(1f))
                EntryEye(s.visible, eyeLabel) { s.visible = !s.visible }
                if (state.series.size >= 1) {
                    MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) {
                        state.series = state.series.toMutableList().also { it.removeAt(index) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CycleButton(stringResource(R.string.math_data_line), s.lineStyle.label, Modifier.weight(1f)) {
                    s.lineStyle = DataLineStyle.entries[(s.lineStyle.ordinal + 1) % DataLineStyle.entries.size]
                }
                ToggleChip(stringResource(R.string.math_data_points), s.showPoints, Modifier.weight(0.85f)) { s.showPoints = !s.showPoints }
                ToggleChip(stringResource(R.string.math_data_errors), s.showErrors, Modifier.weight(0.85f)) { s.showErrors = !s.showErrors }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ToggleChip(stringResource(R.string.math_data_fill), s.fillUnder, Modifier.weight(1f)) { s.fillUnder = !s.fillUnder }
                ToggleChip(stringResource(R.string.math_data_spline), s.spline, Modifier.weight(1f)) { s.spline = !s.spline }
                ToggleChip(stringResource(R.string.math_data_tangent_short), s.tangents, Modifier.weight(1f)) { s.tangents = !s.tangents }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OptionButton(stringResource(R.string.math_data_fit), s.fit.label, DataFit.entries.map { it.label }, Modifier.weight(1f)) {
                    s.fit = DataFit.entries[it]
                }
                if (s.fit == DataFit.POLYNOMIAL) {
                    MathTextField(s.fitDegree, { s.fitDegree = it.filter(Char::isDigit) }, stringResource(R.string.math_data_degree), Modifier.width(84.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OptionButton(stringResource(R.string.math_data_smooth), s.smooth.label, DataSmooth.entries.map { it.label }, Modifier.weight(1f)) {
                    s.smooth = DataSmooth.entries[it]
                }
                if (s.smooth != DataSmooth.NONE) {
                    MathTextField(s.smoothWindow, { s.smoothWindow = it.filter(Char::isDigit) }, stringResource(R.string.math_data_window), Modifier.width(84.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var pasteOne by remember { mutableStateOf(false) }
                MathSecondaryButton(stringResource(R.string.math_data_paste), Modifier.weight(1f)) { pasteOne = true }
                MathSecondaryButton(stringResource(R.string.math_data_points_btn), Modifier.weight(1f)) { editing = true }
                if (pasteOne) {
                    PasteDataDialog(
                        onConfirm = { text, mode, _ ->
                            val fresh = DataState()
                            fresh.series = emptyList()
                            if (fresh.importCsv(text, mode, s.name.ifBlank { "Series" }) > 0) {
                                val src = fresh.series.first()
                                s.x = src.x; s.y = src.y; s.xerr = src.xerr; s.yerr = src.yerr
                                state.autoFitDomain()
                            }
                            pasteOne = false
                        },
                        onDismiss = { pasteOne = false },
                    )
                }
                if (editing) {
                    EditPointsDialog(
                        series = s,
                        onConfirm = { xs, ys, xe, ye ->
                            s.x = xs; s.y = ys; s.xerr = xe; s.yerr = ye
                            state.autoFitDomain()
                            editing = false
                        },
                        onDismiss = { editing = false },
                    )
                }
            }
            if (s.pointCount() == 0) {
                Spacer(Modifier.height(4.dp))
                MathHint(stringResource(R.string.math_data_nopoints))
            }
        }
    }
}

@Composable
private fun CycleButton(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surface.toComposeColor())
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 10.sp, maxLines = 1)
        Text(value, color = palette.text.toComposeColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Text(
        label,
        color = (if (on) palette.selectionForeground else palette.textDim).toComposeColor(),
        fontSize = 11.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    )
}

@Composable
private fun OptionButton(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surface.toComposeColor())
            .clickable { open = true }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Text(value, color = palette.text.toComposeColor(), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label, color = palette.text.toComposeColor(), fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    options.forEachIndexed { i, o ->
                        Text(
                            o,
                            color = palette.text.toComposeColor(),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { open = false; onSelect(i) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun DataScaleRow(label: String, scale: DataAxisScale, onScale: (DataAxisScale) -> Unit, power: String, onPower: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 11.sp, modifier = Modifier.width(52.dp))
        OptionButton("", scale.label, DataAxisScale.entries.map { it.label }, Modifier.weight(1f)) {
            onScale(DataAxisScale.entries[it])
        }
        if (scale == DataAxisScale.POWER) {
            MathTextField(power, { onPower(it.filter { c -> c.isDigit() || c == '.' || c == '-' }) }, "p", Modifier.width(72.dp))
        }
    }
}

@Composable
private fun PasteDataDialog(onConfirm: (String, DataImportMode, Boolean) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var mode by remember { mutableStateOf(DataImportMode.PAIRS) }
    var replace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.math_data_paste_title), color = palette.text.toComposeColor(), fontSize = 16.sp) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.math_data_paste_hint),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 300.dp),
                    textStyle = TextStyle(
                        color = palette.text.toComposeColor(),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    colors = mathFieldColors(),
                    shape = RoundedCornerShape(9.dp),
                )
                MathToolSelector(DataImportMode.entries.map { it.name to it.label }, mode.name, Modifier.fillMaxWidth()) {
                    mode = DataImportMode.valueOf(it)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = replace, onCheckedChange = { replace = it })
                    Text(stringResource(R.string.math_data_replace), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.text.isNotBlank(),
                onClick = { onConfirm(text.text, mode, replace) },
            ) { Text(stringResource(R.string.math_data_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EditPointsDialog(
    series: DataSeries,
    onConfirm: (List<Double>, List<Double>, List<Double>, List<Double>) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var rows by remember(series) {
        val n = maxOf(series.x.size, series.y.size, 1)
        mutableStateOf(List(n) { i ->
            MutableList(4) { c ->
                when (c) {
                    0 -> series.x.getOrNull(i)?.let { Numbers.tick(it) } ?: ""
                    1 -> series.y.getOrNull(i)?.let { Numbers.tick(it) } ?: ""
                    2 -> series.xerr.getOrNull(i)?.takeIf { it.isFinite() && it > 0 }?.let { Numbers.tick(it) } ?: ""
                    else -> series.yerr.getOrNull(i)?.takeIf { it.isFinite() && it > 0 }?.let { Numbers.tick(it) } ?: ""
                }
            }
        })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(series.name.ifBlank { "Series" }, color = palette.text.toComposeColor(), fontSize = 16.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("x", "y", "xerr", "yerr").forEach {
                        Text(it, color = palette.textDim.toComposeColor(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(rows, key = { index, _ -> index }) { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEachIndexed { c, v ->
                                OutlinedTextField(
                                    value = v,
                                    onValueChange = { nv ->
                                        rows = rows.toMutableList().also { all ->
                                            all[r] = row.toMutableList().also { rr -> rr[c] = nv }
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(color = palette.text.toComposeColor(), fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                    colors = mathFieldColors(),
                                    shape = RoundedCornerShape(7.dp),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MathSecondaryButton("+ row", Modifier.weight(1f)) {
                        rows = (rows + listOf(MutableList(4) { "" })).takeLast(2000).toMutableList()
                    }
                    MathSecondaryButton("− row", Modifier.weight(1f), enabled = rows.size > 1) {
                        rows = rows.dropLast(1).toMutableList()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                val xe = ArrayList<Double>(); val ye = ArrayList<Double>()
                for (row in rows) {
                    val x = parseTableNumber(row.getOrElse(0) { "" }) ?: continue
                    val y = parseTableNumber(row.getOrElse(1) { "" }) ?: continue
                    xs.add(x); ys.add(y)
                    xe.add(parseTableNumber(row.getOrElse(2) { "" }) ?: Double.NaN)
                    ye.add(parseTableNumber(row.getOrElse(3) { "" }) ?: Double.NaN)
                }
                onConfirm(xs, ys, xe, ye)
            }) { Text(stringResource(R.string.math_data_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
