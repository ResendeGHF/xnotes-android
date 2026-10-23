package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.math.Complex
import com.xnotes.math.EvalScope
import com.xnotes.math.Evaluator
import com.xnotes.math.Expr
import com.xnotes.math.ExpressionParser
import com.xnotes.math.Numbers
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class Plot2DMode(val labelRes: Int) {
    CARTESIAN(R.string.math_2d_cartesian),
    POLAR(R.string.math_2d_polar),
    PARAMETRIC(R.string.math_2d_parametric),
    IMPLICIT(R.string.math_2d_implicit),
    COMPLEX(R.string.math_2d_complex),
}

/** State kept across tab switches for the 2D plotter. */
internal class Plot2DState {
    var mode by mutableStateOf(Plot2DMode.CARTESIAN)
    var expressions by mutableStateOf(listOf("sin(x)", "cos(x)/2"))
    var parametricX by mutableStateOf("cos(3t)")
    var parametricY by mutableStateOf("sin(2t)")
    var implicit by mutableStateOf("x^2 + y^2 - 4")
    var complex by mutableStateOf("gamma(z)")
    var polarTurns by mutableStateOf("2")
    var xMin by mutableStateOf(-10.0)
    var xMax by mutableStateOf(10.0)
    var yMin by mutableStateOf(-6.25)
    var yMax by mutableStateOf(6.25)
    var showGrid by mutableStateOf(true)

    fun resetView() {
        xMin = -10.0; xMax = 10.0; yMin = -6.25; yMax = 6.25
    }

    fun addExpression() {
        if (expressions.size >= 6) return
        expressions = expressions + when (mode) {
            Plot2DMode.POLAR -> "1 + cos(theta)"
            else -> "x"
        }
    }

    fun setExpression(index: Int, text: String) {
        if (index !in expressions.indices) return
        expressions = expressions.toMutableList().also { it[index] = text }
    }

    fun removeExpression(index: Int) {
        if (expressions.size <= 1) return
        expressions = expressions.toMutableList().also { it.removeAt(index) }
    }

    fun snapshot(): Plot2DSnap = Plot2DSnap(
        mode = mode,
        expressions = expressions.toList(),
        parametricX = parametricX,
        parametricY = parametricY,
        implicit = implicit,
        complex = complex,
        polarTurns = polarTurns.toDoubleOrNull()?.coerceIn(0.05, 64.0) ?: 2.0,
        xMin = xMin, xMax = xMax, yMin = yMin, yMax = yMax,
        showGrid = showGrid,
    )
}

internal class Plot2DSnap(
    val mode: Plot2DMode,
    val expressions: List<String>,
    val parametricX: String,
    val parametricY: String,
    val implicit: String,
    val complex: String,
    val polarTurns: Double,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    val showGrid: Boolean,
)

private data class ViewBox(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double)

private class LivePlot(
    val mode: Plot2DMode,
    val curves: List<List<Polyline>>,
    val implicit: List<WorldSegment>,
    val domain: ImageBitmap?,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
)

@Composable
internal fun Plot2DPane(state: Plot2DState, onInsert: (ByteArray) -> Unit) {
    val theme = rememberGraphTheme()
    val title = stringResource(R.string.math_tab_graph2d)
    val phase = stringResource(R.string.math_2d_phase_legend)
    val failedText = stringResource(R.string.math_insert_failed)
    val scope = rememberCoroutineScope()
    val colors = mathPlotColors()
    var inserting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val legend = plot2DLegend(state, colors)
    GraphWorkspace(
        modes = Plot2DMode.entries.map { it.name to stringResource(it.labelRes) },
        selected = state.mode.name,
        onSelect = { state.mode = Plot2DMode.valueOf(it) },
        legend = legend,
        caption = if (state.mode == Plot2DMode.COMPLEX) phase else null,
        showGrid = state.showGrid,
        onToggleGrid = { state.showGrid = !state.showGrid },
        onReset = { state.resetView(); failed = false },
        inserting = inserting,
        onInsert = {
            if (inserting) return@GraphWorkspace
            val snap = state.snapshot()
            val exported = legend.mapIndexed { index, (label, _) ->
                ExportLegend(label, theme.series[index % theme.series.size])
            } + if (snap.mode == Plot2DMode.COMPLEX) listOf(ExportLegend(phase, theme.dim)) else emptyList()
            scope.launch {
                inserting = true
                failed = false
                val bytes = withContext(Dispatchers.Default) { exportPlot2D(snap, theme, title, exported) }
                inserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        },
        status = if (failed) failedText else null,
        plot = { Plot2DCanvas(state, Modifier.fillMaxSize()) },
        controls = { Plot2DInputs(state) },
    )
}

@Composable
private fun plot2DLegend(state: Plot2DState, colors: List<Color>): List<Pair<String, Color>> = when (state.mode) {
    Plot2DMode.CARTESIAN -> state.expressions.mapIndexed { index, text ->
        "f${index + 1}(x) = ${text.ifBlank { "—" }}" to colors[index % colors.size]
    }
    Plot2DMode.POLAR -> state.expressions.mapIndexed { index, text ->
        "r${index + 1}(θ) = ${text.ifBlank { "—" }}" to colors[index % colors.size]
    }
    Plot2DMode.PARAMETRIC -> listOf("x(t) = ${state.parametricX}    y(t) = ${state.parametricY}" to colors[0])
    Plot2DMode.IMPLICIT -> listOf("${state.implicit.ifBlank { "—" }} = 0" to colors[0])
    Plot2DMode.COMPLEX -> listOf("f(z) = ${state.complex.ifBlank { "—" }}" to colors[0])
}

@Composable
private fun Plot2DInputs(state: Plot2DState) {
    val palette = LocalPalette.current
    val colors = mathPlotColors()
    when (state.mode) {
        Plot2DMode.CARTESIAN, Plot2DMode.POLAR -> {
            state.expressions.forEachIndexed { index, text ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    BoxSpacer(colors[index % colors.size])
                    MathTextField(
                        value = text,
                        onValueChange = { state.setExpression(index, it) },
                        label = if (state.mode == Plot2DMode.POLAR) {
                            stringResource(R.string.math_2d_r_theta, index + 1)
                        } else {
                            stringResource(R.string.math_2d_f_x, index + 1)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (state.expressions.size > 1) {
                        MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) {
                            state.removeExpression(index)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addExpression() }
                if (state.mode == Plot2DMode.POLAR) {
                    Spacer(Modifier.weight(1f))
                    MathTextField(
                        value = state.polarTurns,
                        onValueChange = { state.polarTurns = it.filter { c -> c.isDigit() || c == '.' } },
                        label = stringResource(R.string.math_2d_turns),
                        modifier = Modifier.width(86.dp),
                    )
                }
            }
        }
        Plot2DMode.PARAMETRIC -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MathTextField(state.parametricX, { state.parametricX = it }, stringResource(R.string.math_2d_x_t), Modifier.weight(1f))
            MathTextField(state.parametricY, { state.parametricY = it }, stringResource(R.string.math_2d_y_t), Modifier.weight(1f))
        }
        Plot2DMode.IMPLICIT -> MathTextField(
            state.implicit, { state.implicit = it }, stringResource(R.string.math_2d_implicit_equation), Modifier.fillMaxWidth(),
        )
        Plot2DMode.COMPLEX -> MathTextField(
            state.complex, { state.complex = it }, stringResource(R.string.math_2d_complex_function), Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BoxSpacer(color: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(12.dp).clip(CircleShape).background(color),
    )
}

@Composable
private fun Plot2DCanvas(state: Plot2DState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val grid = palette.border.toComposeColor()
    val axis = palette.textDim.toComposeColor()
    val textStyle = TextStyle(color = palette.textDim.toComposeColor(), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val computingLabel = stringResource(R.string.math_3d_computing)
    val colors = mathPlotColors()
    val xMin = state.xMin
    val xMax = state.xMax
    val yMin = state.yMin
    val yMax = state.yMax
    val showGrid = state.showGrid
    val mode = state.mode
    val view = rememberSettled(ViewBox(xMin, xMax, yMin, yMax), 80)
    val expressions = rememberSettled(state.expressions.joinToString("\u0001"), 160)
    val parametricX = rememberSettled(state.parametricX, 160)
    val parametricY = rememberSettled(state.parametricY, 160)
    val implicit = rememberSettled(state.implicit, 160)
    val complex = rememberSettled(state.complex, 180)
    val turns = rememberSettled(state.polarTurns, 160)
    var picture by remember { mutableStateOf<LivePlot?>(null) }

    BoxWithConstraints(modifier.clipToBounds()) {
        val pxW = constraints.maxWidth
        val pxH = constraints.maxHeight
        LaunchedEffect(mode, expressions, parametricX, parametricY, implicit, complex, turns, view, pxW, pxH) {
            val snap = Plot2DSnap(
                mode = mode,
                expressions = expressions.split("\u0001"),
                parametricX = parametricX,
                parametricY = parametricY,
                implicit = implicit,
                complex = complex,
                polarTurns = turns.toDoubleOrNull()?.coerceIn(0.05, 64.0) ?: 2.0,
                xMin = view.xMin, xMax = view.xMax, yMin = view.yMin, yMax = view.yMax,
                showGrid = true,
            )
            val (dw, dh) = if (mode == Plot2DMode.COMPLEX) cappedSize(pxW, pxH, 150) else 0 to 0
            val sampled = withContext(Dispatchers.Default) { samplePlot2D(snap, dw, dh) }
            picture = LivePlot(
                mode = snap.mode,
                curves = sampled.curves,
                implicit = sampled.implicit,
                domain = sampled.domain?.asImageBitmap(),
                xMin = snap.xMin, xMax = snap.xMax, yMin = snap.yMin, yMax = snap.yMax,
            )
        }
        val shown = picture?.takeIf { it.mode == mode }
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val xSpan = state.xMax - state.xMin
                    val ySpan = state.yMax - state.yMin
                    if (xSpan == 0.0 || ySpan == 0.0) return@detectTransformGestures
                    val factor = zoom.coerceIn(0.4f, 2.5f)
                    val newX = xSpan / factor
                    val newY = ySpan / factor
                    val fx = (centroid.x / size.width).coerceIn(0f, 1f)
                    val fy = (centroid.y / size.height).coerceIn(0f, 1f)
                    val cx = state.xMin + fx * xSpan
                    val cy = state.yMax - fy * ySpan
                    val dx = pan.x / size.width * newX
                    val dy = pan.y / size.height * newY
                    state.xMin = cx - fx * newX - dx
                    state.xMax = state.xMin + newX
                    state.yMin = cy - (1f - fy) * newY + dy
                    state.yMax = state.yMin + newY
                }
            },
        ) {
            val plot = shown
            fun sx(x: Double): Float = ((x - xMin) / (xMax - xMin) * size.width).toFloat()
            fun sy(y: Double): Float = ((yMax - y) / (yMax - yMin) * size.height).toFloat()
            if (plot?.domain != null) {
                val left = sx(plot.xMin)
                val right = sx(plot.xMax)
                val top = sy(plot.yMax)
                val bottom = sy(plot.yMin)
                drawImage(
                    plot.domain,
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    dstSize = IntSize((right - left).toInt().coerceAtLeast(1), (bottom - top).toInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium,
                )
            }
            if (showGrid && xMax > xMin && yMax > yMin) {
                val stepX = niceStep(xMax - xMin, max(2, (size.width / 90f).toInt()))
                val stepY = niceStep(yMax - yMin, max(2, (size.height / 70f).toInt()))
                var gx = floor(xMin / stepX) * stepX
                var guard = 0
                while (gx <= xMax && guard++ < 40) {
                    drawLine(grid.copy(alpha = 0.45f), Offset(sx(gx), 0f), Offset(sx(gx), size.height), strokeWidth = 1f)
                    gx += stepX
                }
                var gy = floor(yMin / stepY) * stepY
                guard = 0
                while (gy <= yMax && guard++ < 40) {
                    drawLine(grid.copy(alpha = 0.45f), Offset(0f, sy(gy)), Offset(size.width, sy(gy)), strokeWidth = 1f)
                    gy += stepY
                }
                if (0.0 in yMin..yMax) drawLine(axis.copy(alpha = 0.85f), Offset(0f, sy(0.0)), Offset(size.width, sy(0.0)), strokeWidth = 1.4f)
                if (0.0 in xMin..xMax) drawLine(axis.copy(alpha = 0.85f), Offset(sx(0.0), 0f), Offset(sx(0.0), size.height), strokeWidth = 1.4f)
                val labelY = if (0.0 in yMin..yMax) sy(0.0) else size.height - 4f
                gx = floor(xMin / stepX) * stepX
                guard = 0
                while (gx <= xMax && guard++ < 12) {
                    if (abs(gx) > stepX * 1e-6) {
                        val layout = measurer.measure(Numbers.tick(gx), textStyle)
                        val ty = (if (labelY > size.height - 16f) labelY - layout.size.height - 2f else labelY + 2f)
                            .coerceIn(0f, size.height - layout.size.height)
                        drawText(layout, topLeft = Offset(sx(gx) + 2f, ty))
                    }
                    gx += stepX
                }
                gy = floor(yMin / stepY) * stepY
                guard = 0
                while (gy <= yMax && guard++ < 12) {
                    if (abs(gy) > stepY * 1e-6) {
                        val layout = measurer.measure(Numbers.tick(gy), textStyle)
                        drawText(layout, topLeft = Offset(4f, (sy(gy) - layout.size.height - 2f).coerceIn(0f, size.height - layout.size.height)))
                    }
                    gy += stepY
                }
            }
            if (plot != null) {
                val stroke = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                plot.curves.forEachIndexed { index, strokes ->
                    val path = Path()
                    for (stroke in strokes) {
                        val xy = stroke.xy
                        if (xy.size < 4) continue
                        path.moveTo(sx(xy[0]), sy(xy[1]))
                        var i = 2
                        while (i < xy.size) {
                            path.lineTo(sx(xy[i]), sy(xy[i + 1]))
                            i += 2
                        }
                    }
                    drawPath(path, colors[index % colors.size], style = stroke)
                }
                if (plot.implicit.isNotEmpty()) {
                    val path = Path()
                    for (segment in plot.implicit) {
                        path.moveTo(sx(segment.x1), sy(segment.y1))
                        path.lineTo(sx(segment.x2), sy(segment.y2))
                    }
                    drawPath(path, colors[0], style = stroke)
                }
            }
            if (plot == null) {
                val layout = measurer.measure(computingLabel, TextStyle(color = palette.textDim.toComposeColor(), fontSize = 13.sp))
                drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2f, (size.height - layout.size.height) / 2f))
            }
        }
    }
}

private class PlotGeometry(
    val curves: List<List<Polyline>>,
    val implicit: List<WorldSegment>,
    val domain: Bitmap?,
)

private suspend fun exportPlot2D(
    snap: Plot2DSnap,
    theme: GraphTheme,
    title: String,
    legend: List<ExportLegend>,
): ByteArray? = try {
    val (dw, dh) = cappedSize(EXPORT_PLOT_W, EXPORT_PLOT_H, 220)
    val sampled = samplePlot2D(snap, dw, dh)
    val plot = Bitmap.createBitmap(EXPORT_PLOT_W, EXPORT_PLOT_H, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(plot)
    canvas.drawColor(theme.paper)
    sampled.domain?.let { domain ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(domain, null, android.graphics.Rect(0, 0, EXPORT_PLOT_W, EXPORT_PLOT_H), paint)
        domain.recycle()
    }
    if (snap.showGrid || snap.mode != Plot2DMode.COMPLEX) {
        drawPlotFrame(canvas, EXPORT_PLOT_W, EXPORT_PLOT_H, snap.xMin, snap.xMax, snap.yMin, snap.yMax, theme, snap.showGrid)
    }
    sampled.curves.forEachIndexed { index, strokes ->
        drawPolylines(
            canvas, strokes, theme.series[index % theme.series.size],
            snap.xMin, snap.xMax, snap.yMin, snap.yMax, EXPORT_PLOT_W, EXPORT_PLOT_H, 3.4f,
        )
    }
    if (sampled.implicit.isNotEmpty()) {
        drawWorldSegments(
            canvas, sampled.implicit, theme.series[0],
            snap.xMin, snap.xMax, snap.yMin, snap.yMax, EXPORT_PLOT_W, EXPORT_PLOT_H, 3.2f,
        )
    }
    val foot = buildString {
        append(windowFootnote(snap.xMin, snap.xMax, snap.yMin, snap.yMax))
        when (snap.mode) {
            Plot2DMode.PARAMETRIC -> append("    t ∈ [0, 2π]")
            Plot2DMode.POLAR -> append("    θ ∈ [0, ${Numbers.tick(snap.polarTurns)}·2π]")
            else -> Unit
        }
    }
    writeGraphPng(plot, title, legend, foot, theme)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private suspend fun samplePlot2D(snap: Plot2DSnap, domainW: Int, domainH: Int): PlotGeometry {
    coroutineContext.ensureActive()
    val curves = ArrayList<List<Polyline>>()
    var implicit = emptyList<WorldSegment>()
    var domain: Bitmap? = null
    when (snap.mode) {
        Plot2DMode.CARTESIAN -> for (text in snap.expressions) {
            val expr = runCatching { ExpressionParser.parse(text) }.getOrNull()
            curves += if (expr == null) emptyList() else sampleCartesian(expr, snap.xMin, snap.xMax, snap.yMin, snap.yMax)
        }
        Plot2DMode.POLAR -> for (text in snap.expressions) {
            val expr = runCatching { ExpressionParser.parse(text) }.getOrNull()
            curves += if (expr == null) emptyList() else samplePolar(expr, snap.polarTurns)
        }
        Plot2DMode.PARAMETRIC -> {
            val xExpr = runCatching { ExpressionParser.parse(snap.parametricX) }.getOrNull()
            val yExpr = runCatching { ExpressionParser.parse(snap.parametricY) }.getOrNull()
            curves += if (xExpr == null || yExpr == null) emptyList() else sampleParametric(xExpr, yExpr, snap)
        }
        Plot2DMode.IMPLICIT -> {
            val expr = runCatching { ExpressionParser.parse(snap.implicit) }.getOrNull()
            if (expr != null) implicit = sampleImplicit(expr, snap)
        }
        Plot2DMode.COMPLEX -> if (domainW > 1 && domainH > 1) {
            domain = renderDomain(snap.complex, domainW, domainH, snap.xMin, snap.xMax, snap.yMin, snap.yMax)
        }
    }
    return PlotGeometry(curves, implicit, domain)
}

private class StrokeBuf {
    private var data = DoubleArray(64)
    private var n = 0
    val out = ArrayList<Polyline>()

    fun add(x: Double, y: Double) {
        if (n + 2 > data.size) data = data.copyOf(data.size * 2)
        data[n++] = x
        data[n++] = y
    }

    fun flush() {
        if (n >= 4) out += Polyline(data.copyOf(n))
        n = 0
    }
}

private suspend fun sampleCartesian(expr: Expr, xMin: Double, xMax: Double, yMin: Double, yMax: Double): List<Polyline> {
    val n = 320
    val span = (yMax - yMin).takeIf { it.isFinite() && it > 0.0 } ?: return emptyList()
    val scope = EvalScope()
    val buf = StrokeBuf()
    var prev = Double.NaN
    for (i in 0..n) {
        if (i % 40 == 0) coroutineContext.ensureActive()
        val x = xMin + i.toDouble() / n * (xMax - xMin)
        scope.vars["x"] = Complex.real(x)
        val y = runCatching { Evaluator.evaluate(expr, scope).realValue() }.getOrDefault(Double.NaN)
        if (!y.isFinite()) {
            buf.flush(); prev = Double.NaN; continue
        }
        if (prev.isFinite() && abs(y - prev) > span * 1.6) buf.flush()
        buf.add(x, y)
        prev = y
    }
    buf.flush()
    return buf.out
}

private suspend fun samplePolar(expr: Expr, turns: Double): List<Polyline> {
    val n = 480
    val scope = EvalScope()
    val buf = StrokeBuf()
    for (i in 0..n) {
        if (i % 40 == 0) coroutineContext.ensureActive()
        val theta = i.toDouble() / n * turns * 2.0 * PI
        val angle = Complex.real(theta)
        scope.vars["theta"] = angle
        scope.vars["t"] = angle
        val r = runCatching { Evaluator.evaluate(expr, scope).realValue() }.getOrDefault(Double.NaN)
        if (!r.isFinite()) {
            buf.flush(); continue
        }
        buf.add(r * cos(theta), r * sin(theta))
    }
    buf.flush()
    return buf.out
}

private suspend fun sampleParametric(xExpr: Expr, yExpr: Expr, snap: Plot2DSnap): List<Polyline> {
    val n = 400
    val limit = hypot(snap.xMax - snap.xMin, snap.yMax - snap.yMin) * 0.45
    val scope = EvalScope()
    val buf = StrokeBuf()
    var prevX = Double.NaN
    var prevY = Double.NaN
    for (i in 0..n) {
        if (i % 40 == 0) coroutineContext.ensureActive()
        val t = i.toDouble() / n * 2.0 * PI
        scope.vars["t"] = Complex.real(t)
        val x = runCatching { Evaluator.evaluate(xExpr, scope).realValue() }.getOrDefault(Double.NaN)
        val y = runCatching { Evaluator.evaluate(yExpr, scope).realValue() }.getOrDefault(Double.NaN)
        if (!x.isFinite() || !y.isFinite()) {
            buf.flush(); prevX = Double.NaN; prevY = Double.NaN; continue
        }
        if (prevX.isFinite() && hypot(x - prevX, y - prevY) > limit) buf.flush()
        buf.add(x, y)
        prevX = x; prevY = y
    }
    buf.flush()
    return buf.out
}

private suspend fun sampleImplicit(expr: Expr, snap: Plot2DSnap): List<WorldSegment> {
    val nx = 72
    val ny = 72
    val scope = EvalScope()
    val values = Array(ny) { r ->
        coroutineContext.ensureActive()
        DoubleArray(nx) { c ->
            val x = snap.xMin + (c + 0.5) / nx * (snap.xMax - snap.xMin)
            val y = snap.yMin + (r + 0.5) / ny * (snap.yMax - snap.yMin)
            scope.vars["x"] = Complex.real(x)
            scope.vars["y"] = Complex.real(y)
            runCatching { Evaluator.evaluate(expr, scope).realValue() }.getOrDefault(Double.NaN)
        }
    }
    return marchingSquares(values, nx, ny, snap.xMin, snap.xMax, snap.yMin, snap.yMax, 0.0)
}

private fun drawWorldSegments(
    canvas: android.graphics.Canvas,
    segments: List<WorldSegment>,
    color: Int,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    w: Int,
    h: Int,
    strokePx: Float,
) {
    val xSpan = (xMax - xMin).takeIf { it.isFinite() && abs(it) > 1e-15 } ?: return
    val ySpan = (yMax - yMin).takeIf { it.isFinite() && abs(it) > 1e-15 } ?: return
    fun sx(x: Double) = ((x - xMin) / xSpan * w).toFloat()
    fun sy(y: Double) = ((yMax - y) / ySpan * h).toFloat()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val path = android.graphics.Path()
    for (segment in segments) {
        path.moveTo(sx(segment.x1), sy(segment.y1))
        path.lineTo(sx(segment.x2), sy(segment.y2))
    }
    canvas.drawPath(path, paint)
}

@Composable
internal fun mathPlotColors(): List<Color> {
    val palette = LocalPalette.current
    return remember(palette) {
        listOf(
            palette.accent.toComposeColor(),
            Color(0xFFE06C75),
            Color(0xFF61AFEF),
            Color(0xFF98C379),
            Color(0xFFC678DD),
            Color(0xFFE5C07B),
        )
    }
}

internal fun evalXY(expr: Expr, x: Double, y: Double): Double =
    runCatching { Evaluator.evaluate(expr, mapOf("x" to x, "y" to y)).realValue() }.getOrDefault(Double.NaN)

internal fun evalXYZ(expr: Expr, x: Double, y: Double, z: Double): Double =
    runCatching { Evaluator.evaluate(expr, mapOf("x" to x, "y" to y, "z" to z)).realValue() }.getOrDefault(Double.NaN)

internal fun bindXY(scope: EvalScope, x: Double, y: Double) {
    scope.vars["x"] = Complex.real(x)
    scope.vars["y"] = Complex.real(y)
}

internal fun bindXYZ(scope: EvalScope, x: Double, y: Double, z: Double) {
    bindXY(scope, x, y)
    scope.vars["z"] = Complex.real(z)
}

internal fun evalReal(scope: EvalScope, expr: Expr): Double =
    runCatching { Evaluator.evaluate(expr, scope).realValue() }.getOrDefault(Double.NaN)

internal fun complexAt(expr: Expr, x: Double, y: Double): Complex =
    runCatching {
        val scope = EvalScope()
        bindPlane(scope, x, y)
        Evaluator.evaluate(expr, scope)
    }.getOrDefault(Complex.NaN)

internal fun bindPlane(scope: EvalScope, x: Double, y: Double) {
    val re = Complex.real(x)
    val im = Complex.real(y)
    scope.vars["x"] = re
    scope.vars["y"] = im
    scope.vars["re"] = re
    scope.vars["im"] = im
    scope.vars["z"] = Complex(x, y)
}

/**
 * Phase portrait of a complex function. Hue is the argument and brightness the magnitude.
 * The bitmap is far smaller than the view; the canvas scales it, including while the view moves.
 */
private suspend fun renderDomain(
    text: String,
    width: Int,
    height: Int,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
): Bitmap? {
    val expr = runCatching { ExpressionParser.parse(text) }.getOrNull() ?: return null
    if (width <= 1 || height <= 1) return null
    val pixels = IntArray(width * height)
    val scope = EvalScope()
    for (py in 0 until height) {
        if (py % 2 == 0) coroutineContext.ensureActive()
        val y = yMax - (py + 0.5) / height * (yMax - yMin)
        val row = py * width
        for (px in 0 until width) {
            val x = xMin + (px + 0.5) / width * (xMax - xMin)
            bindPlane(scope, x, y)
            val v = runCatching { Evaluator.evaluate(expr, scope) }.getOrNull()
            pixels[row + px] = if (v == null || v.isNaN || !v.isFinite) 0xFF111318.toInt() else phaseColor(v)
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

private fun phaseColor(v: Complex): Int {
    val mag = v.abs()
    val phase = ((v.arg() / (2.0 * PI)) % 1.0 + 1.0) % 1.0
    val t = ln(1.0 + mag)
    val value = (0.26 + 0.74 * (t / (1.0 + t))).coerceIn(0.14, 1.0).toFloat()
    val band = (phase * 12.0) % 1.0
    val shade = if (band < 0.045 || band > 0.955) 0.82f else 1f
    return android.graphics.Color.HSVToColor(floatArrayOf((phase * 360.0).toFloat(), 0.82f, (value * shade).coerceIn(0.1f, 1f)))
}

/** One segment of an isoline in world coordinates. */
internal data class WorldSegment(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** Marching squares over a sampled scalar grid; used for implicit curves and field contours. */
internal fun marchingSquares(
    values: Array<DoubleArray>,
    nx: Int,
    ny: Int,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    threshold: Double,
): List<WorldSegment> {
    val out = ArrayList<WorldSegment>()
    if (nx < 2 || ny < 2) return out
    val dx = (xMax - xMin) / (nx - 1)
    val dy = (yMax - yMin) / (ny - 1)
    fun wx(c: Int) = xMin + c * dx
    fun wy(r: Int) = yMin + r * dy
    fun cross(va: Double, vb: Double): Double = if (vb == va) 0.5 else (threshold - va) / (vb - va)
    for (r in 0 until ny - 1) {
        for (c in 0 until nx - 1) {
            val a = values[r][c]
            val b = values[r][c + 1]
            val cc = values[r + 1][c + 1]
            val d = values[r + 1][c]
            if (!a.isFinite() || !b.isFinite() || !cc.isFinite() || !d.isFinite()) continue
            val points = ArrayList<Pair<Double, Double>>(4)
            if ((a < threshold) != (b < threshold)) {
                val t = cross(a, b)
                points += (wx(c) + t * dx) to wy(r)
            }
            if ((b < threshold) != (cc < threshold)) {
                val t = cross(b, cc)
                points += wx(c + 1) to (wy(r) + t * dy)
            }
            if ((d < threshold) != (cc < threshold)) {
                val t = cross(d, cc)
                points += (wx(c) + t * dx) to wy(r + 1)
            }
            if ((a < threshold) != (d < threshold)) {
                val t = cross(a, d)
                points += wx(c) to (wy(r) + t * dy)
            }
            if (points.size == 2) {
                out += WorldSegment(points[0].first, points[0].second, points[1].first, points[1].second)
            } else if (points.size == 4) {
                out += WorldSegment(points[0].first, points[0].second, points[1].first, points[1].second)
                out += WorldSegment(points[2].first, points[2].second, points[3].first, points[3].second)
            }
        }
    }
    return out
}

internal fun niceStep(span: Double, target: Int): Double {
    if (!span.isFinite() || span <= 0.0) return 1.0
    val raw = span / max(1, target)
    val base = 10.0.pow(floor(log10(raw)))
    val f = raw / base
    return base * when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 5.0 -> 5.0
        else -> 10.0
    }
}

/** A small colour swatch used by the plot legends. */
@Composable
internal fun PlotLegend(entries: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((label, color) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(label, color = palette.textDim.toComposeColor(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}
