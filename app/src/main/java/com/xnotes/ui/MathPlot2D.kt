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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
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

/** One parametric curve x(t), y(t). [t0]/[t1] bound the parameter interval, blank = 0..2π. */
internal data class ParamCurve2D(
    val x: String = "",
    val y: String = "",
    val colorArgb: Int? = null,
    val visible: Boolean = true,
    val t0: String = "",
    val t1: String = "",
)

/** Everything the 2D plotter keeps: the entry lists carry each curve's colour and visibility. */
internal class Plot2DState {
    var mode by mutableStateOf(Plot2DMode.CARTESIAN)
    var expressions by mutableStateOf(listOf<MathEntry>())
    var paramCurves by mutableStateOf(listOf<ParamCurve2D>())
    var implicitList by mutableStateOf(listOf<MathEntry>())
    var complex by mutableStateOf("")
    var polarTurns by mutableStateOf("2")
    var xMin by mutableStateOf(-10.0)
    var xMax by mutableStateOf(10.0)
    var yMin by mutableStateOf(-6.25)
    var yMax by mutableStateOf(6.25)
    var showGrid by mutableStateOf(true)
    val anim = AnimState()

    fun resetView() {
        xMin = -10.0; xMax = 10.0; yMin = -6.25; yMax = 6.25
    }

    fun addExpression() {
        if (expressions.size >= 6) return
        expressions = expressions + when (mode) {
            Plot2DMode.POLAR -> MathEntry("")
            else -> MathEntry("")
        }
    }

    fun setExpression(index: Int, text: String) {
        if (index !in expressions.indices) return
        expressions = expressions.toMutableList().also { it[index] = it[index].copy(text = text) }
    }

    fun setExpressionStyle(index: Int, colorArgb: Int?, visible: Boolean) {
        if (index !in expressions.indices) return
        expressions = expressions.toMutableList().also { it[index] = it[index].copy(colorArgb = colorArgb, visible = visible) }
    }

    fun setExpressionDomain(index: Int, a0: String, a1: String, b0: String, b1: String) {
        if (index !in expressions.indices) return
        expressions = expressions.toMutableList().also { it[index] = it[index].copy(domA0 = a0, domA1 = a1, domB0 = b0, domB1 = b1) }
    }

    fun removeExpression(index: Int) {
        if (index !in expressions.indices) return
        expressions = expressions.toMutableList().also { it.removeAt(index) }
    }

    fun addParam() {
        if (paramCurves.size >= 6) return
        paramCurves = paramCurves + ParamCurve2D("", "")
    }
    fun setParam(i: Int, x: String, y: String) {
        if (i !in paramCurves.indices) return
        paramCurves = paramCurves.toMutableList().also { it[i] = it[i].copy(x = x, y = y) }
    }
    fun setParamStyle(i: Int, colorArgb: Int?, visible: Boolean) {
        if (i !in paramCurves.indices) return
        paramCurves = paramCurves.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
    }
    fun setParamDomain(i: Int, t0: String, t1: String) {
        if (i !in paramCurves.indices) return
        paramCurves = paramCurves.toMutableList().also { it[i] = it[i].copy(t0 = t0, t1 = t1) }
    }
    fun removeParam(i: Int) {
        if (i !in paramCurves.indices) return
        paramCurves = paramCurves.toMutableList().also { it.removeAt(i) }
    }

    fun addImplicit() {
        if (implicitList.size >= 6) return
        implicitList = implicitList + MathEntry("")
    }
    fun setImplicit(i: Int, text: String) {
        if (i !in implicitList.indices) return
        implicitList = implicitList.toMutableList().also { it[i] = it[i].copy(text = text) }
    }
    fun setImplicitStyle(i: Int, colorArgb: Int?, visible: Boolean) {
        if (i !in implicitList.indices) return
        implicitList = implicitList.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
    }
    fun setImplicitDomain(i: Int, a0: String, a1: String, b0: String, b1: String) {
        if (i !in implicitList.indices) return
        implicitList = implicitList.toMutableList().also { it[i] = it[i].copy(domA0 = a0, domA1 = a1, domB0 = b0, domB1 = b1) }
    }
    fun removeImplicit(i: Int) {
        if (i !in implicitList.indices) return
        implicitList = implicitList.toMutableList().also { it.removeAt(i) }
    }

    fun snapshot(): Plot2DSnap {
        // Item A: t is not valid for complex functions, so the snapshot stays static there.
        val isComplex = mode == Plot2DMode.COMPLEX
        return Plot2DSnap(
            mode = mode,
            expressions = expressions.toList(),
            paramCurves = paramCurves.toList(),
            implicitList = implicitList.toList(),
            complex = complex,
            polarTurns = polarTurns.toDoubleOrNull()?.coerceIn(0.05, 64.0) ?: 2.0,
            xMin = xMin, xMax = xMax, yMin = yMin, yMax = yMax,
            showGrid = showGrid,
            anim = if (isComplex) null else anim.snap(),
            animT = if (anim.enabled && !isComplex) anim.tCurrent else null,
        )
    }
}

internal data class Plot2DSnap(
    val mode: Plot2DMode,
    val expressions: List<MathEntry>,
    val paramCurves: List<ParamCurve2D>,
    val implicitList: List<MathEntry>,
    val complex: String,
    val polarTurns: Double,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    val showGrid: Boolean,
    val anim: AnimSnap? = null,
    val animT: Double? = null,
)

private data class ViewBox(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double)

private class LivePlot(
    val mode: Plot2DMode,
    val curves: List<List<Polyline>>,
    val curveColors: List<Int>,
    val implicit: List<List<WorldSegment>>,
    val implicitColors: List<Int>,
    val domain: ImageBitmap?,
    val xMin: Double,
    val xMax: Double,
    val yMin: Double,
    val yMax: Double,
    val animT: Double? = null,
)

@Composable
internal fun Plot2DPane(state: Plot2DState, onInsert: (ByteArray) -> Unit) {
    val theme = rememberGraphTheme()
    val title = stringResource(R.string.math_tab_graph2d)
    val phase = stringResource(R.string.math_2d_phase_legend)
    val failedText = stringResource(R.string.math_insert_failed)
    val animLabel = stringResource(R.string.math_insert_animation)
    val scope = rememberCoroutineScope()
    val colors = mathPlotColors()
    var inserting by remember { mutableStateOf(false) }
    var animInserting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val legend = plot2DLegend(state, colors)
    val animOn = state.anim.enabled && state.mode != Plot2DMode.COMPLEX
    val animLo = state.anim.tMinStr.toDoubleOrNull()
    val animHi = state.anim.tMaxStr.toDoubleOrNull()
    val animRangeOk = animLo != null && animHi != null && animLo.isFinite() && animHi.isFinite() && animLo != animHi
    GraphWorkspace(
        modes = Plot2DMode.entries.map { it.name to stringResource(it.labelRes) },
        selected = state.mode.name,
        onSelect = { state.mode = Plot2DMode.valueOf(it) },
        legend = legend.map { it.label to it.color },
        caption = if (state.mode == Plot2DMode.COMPLEX) phase else null,
        showGrid = state.showGrid,
        onToggleGrid = { state.showGrid = !state.showGrid },
        onReset = { state.resetView(); failed = false },
        inserting = inserting,
        insertEnabled = when (state.mode) {
            Plot2DMode.CARTESIAN, Plot2DMode.POLAR -> state.expressions.any { it.visible && it.text.isNotBlank() }
            Plot2DMode.PARAMETRIC -> state.paramCurves.any { it.visible && (it.x.isNotBlank() || it.y.isNotBlank()) }
            Plot2DMode.IMPLICIT -> state.implicitList.any { it.visible && it.text.isNotBlank() }
            Plot2DMode.COMPLEX -> state.complex.isNotBlank()
        },
        onInsert = {
            if (inserting) return@GraphWorkspace
            val snap = state.snapshot()
            val exported = legend.map { ExportLegend(it.label, it.argb) } +
                if (snap.mode == Plot2DMode.COMPLEX) listOf(ExportLegend(phase, theme.dim)) else emptyList()
            scope.launch {
                inserting = true
                failed = false
                val bytes = withContext(Dispatchers.Default) { exportPlot2D(snap, theme, title, exported) }
                inserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        },
        animInsertLabel = if (animOn && animRangeOk) animLabel else null,
        animInserting = animInserting,
        onInsertAnim = if (animOn) ({
            if (animInserting || inserting) return@GraphWorkspace
            val base = state.snapshot()
            val exported = legend.map { ExportLegend(it.label, it.argb) }
            scope.launch {
                animInserting = true
                failed = false
                val bytes = withContext(Dispatchers.Default) { exportPlot2DAnimation(base, theme, title, exported) }
                animInserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        }) else null,
        status = if (failed) failedText else null,
        plot = { Plot2DCanvas(state, Modifier.fillMaxSize()) },
        controls = { Plot2DInputs(state) },
    )
}

/** One visible legend row: display colour plus the packed colour baked into exports. */
private class Plot2DLegendRow(val label: String, val color: Color, val argb: Int)

@Composable
private fun plot2DLegend(state: Plot2DState, colors: List<Color>): List<Plot2DLegendRow> {
    // Item A: t is not valid for complex functions, so it never appears there.
    val showT = state.anim.enabled && state.mode != Plot2DMode.COMPLEX
    val series = mathPlotColors()
    fun effective(index: Int, custom: Int?): Pair<Color, Int> {
        val c = custom?.let { Color(it) } ?: series[index % series.size]
        return c to c.toArgb()
    }
    val base: List<Plot2DLegendRow> = when (state.mode) {
        Plot2DMode.CARTESIAN -> state.expressions.mapIndexedNotNull { index, e ->
            if (!e.visible) null else {
                val (c, a) = effective(index, e.colorArgb)
                Plot2DLegendRow("f${index + 1}(x) = ${e.text.ifBlank { "—" }}${entryDomainLabel(e)?.let { "  $it" } ?: ""}", c, a)
            }
        }
        Plot2DMode.POLAR -> state.expressions.mapIndexedNotNull { index, e ->
            if (!e.visible) null else {
                val (c, a) = effective(index, e.colorArgb)
                Plot2DLegendRow("r${index + 1}(θ) = ${e.text.ifBlank { "—" }}${entryDomainLabel(e)?.let { "  $it" } ?: ""}", c, a)
            }
        }
        Plot2DMode.PARAMETRIC -> state.paramCurves.mapIndexedNotNull { index, p ->
            if (!p.visible) null else {
                val (c, a) = effective(index, p.colorArgb)
                val tDom = if (p.t0.isBlank() && p.t1.isBlank()) "" else
                    entryRange(p.t0, p.t1, 0.0, 2.0 * PI)?.let { (lo, hi) -> "  [${Numbers.tick(lo)}, ${Numbers.tick(hi)}]" } ?: ""
                Plot2DLegendRow("C${index + 1}: x=${p.x.ifBlank { "—" }} y=${p.y.ifBlank { "—" }}$tDom", c, a)
            }
        }
        Plot2DMode.IMPLICIT -> state.implicitList.mapIndexedNotNull { index, e ->
            if (!e.visible) null else {
                val (c, a) = effective(index, e.colorArgb)
                Plot2DLegendRow("C${index + 1}: ${e.text.ifBlank { "—" }} = 0${entryDomainLabel(e)?.let { "  $it" } ?: ""}", c, a)
            }
        }
        Plot2DMode.COMPLEX -> listOf(Plot2DLegendRow("f(z) = ${state.complex.ifBlank { "—" }}", colors[0], colors[0].toArgb()))
    }
    if (showT) {
        return base + Plot2DLegendRow("t = ${"%.3f".format(state.anim.tCurrent)}", colors[base.size % colors.size], colors[base.size % colors.size].toArgb())
    }
    return base
}

@Composable
private fun Plot2DInputs(state: Plot2DState) {
    val palette = LocalPalette.current
    val colors = mathPlotColors()
    val eyeLabel = stringResource(R.string.math_toggle_visibility)
    val colorLabel = stringResource(R.string.math_pick_color)
    val domainLabel = stringResource(R.string.math_domain_entry)
    var openDomains by remember { mutableStateOf(setOf<String>()) }
    fun toggleDom(key: String) {
        openDomains = if (key in openDomains) openDomains - key else openDomains + key
    }
    fun effectiveColor(index: Int, custom: Int?): Color =
        custom?.let { Color(it) } ?: colors[index % colors.size]
    when (state.mode) {
        Plot2DMode.CARTESIAN, Plot2DMode.POLAR -> {
            val polar = state.mode == Plot2DMode.POLAR
            state.expressions.forEachIndexed { index, e ->
                val domKey = "E:$index"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    EntryColorDot(e.colorArgb, effectiveColor(index, e.colorArgb), colorLabel) {
                        state.setExpressionStyle(index, it, e.visible)
                    }
                    MathTextField(
                        value = e.text,
                        onValueChange = { state.setExpression(index, it) },
                        label = if (polar) {
                            stringResource(R.string.math_2d_r_theta, index + 1)
                        } else {
                            stringResource(R.string.math_2d_f_x, index + 1)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    EntryEye(e.visible, eyeLabel) {
                        state.setExpressionStyle(index, e.colorArgb, !e.visible)
                    }
                    EntryDomainToggle(domKey in openDomains, domainLabel) { toggleDom(domKey) }
                    MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) {
                        state.removeExpression(index)
                    }
                }
                EntryDomainEditor(
                    expanded = domKey in openDomains,
                    a0 = e.domA0, a1 = e.domA1, b0 = e.domB0, b1 = e.domB1,
                    onA0 = { state.setExpressionDomain(index, it, e.domA1, e.domB0, e.domB1) },
                    onA1 = { state.setExpressionDomain(index, e.domA0, it, e.domB0, e.domB1) },
                    onB0 = { state.setExpressionDomain(index, e.domA0, e.domA1, it, e.domB1) },
                    onB1 = { state.setExpressionDomain(index, e.domA0, e.domA1, e.domB0, it) },
                    labelA0 = stringResource(if (polar) R.string.math_theta_min else R.string.math_x_min),
                    labelA1 = stringResource(if (polar) R.string.math_theta_max else R.string.math_x_max),
                    labelB0 = stringResource(if (polar) R.string.math_r_min else R.string.math_y_min),
                    labelB1 = stringResource(if (polar) R.string.math_r_max else R.string.math_y_max),
                    showSecondary = true,
                )
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
            Plot2DDomain(state)
            AnimPanel(state.anim)
        }
        Plot2DMode.PARAMETRIC -> {
            state.paramCurves.forEachIndexed { index, p ->
                val domKey = "P:$index"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    EntryColorDot(p.colorArgb, effectiveColor(index, p.colorArgb), colorLabel) {
                        state.setParamStyle(index, it, p.visible)
                    }
                    MathTextField(p.x, { state.setParam(index, it, p.y) }, stringResource(R.string.math_2d_x_t) + " ${index + 1}", Modifier.weight(1f))
                    MathTextField(p.y, { state.setParam(index, p.x, it) }, stringResource(R.string.math_2d_y_t) + " ${index + 1}", Modifier.weight(1f))
                    EntryEye(p.visible, eyeLabel) {
                        state.setParamStyle(index, p.colorArgb, !p.visible)
                    }
                    EntryDomainToggle(domKey in openDomains, domainLabel) { toggleDom(domKey) }
                    MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) {
                        state.removeParam(index)
                    }
                }
                EntryDomainEditor(
                    expanded = domKey in openDomains,
                    a0 = p.t0, a1 = p.t1, b0 = "", b1 = "",
                    onA0 = { state.setParamDomain(index, it, p.t1) },
                    onA1 = { state.setParamDomain(index, p.t0, it) },
                    onB0 = {}, onB1 = {},
                    labelA0 = stringResource(R.string.math_t_min),
                    labelA1 = stringResource(R.string.math_t_max),
                    labelB0 = "", labelB1 = "",
                    showSecondary = false,
                )
            }
            MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addParam() }
            Plot2DDomain(state)
            AnimPanel(state.anim, showShare = true)
        }
        Plot2DMode.IMPLICIT -> {
            state.implicitList.forEachIndexed { index, e ->
                val domKey = "I:$index"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    EntryColorDot(e.colorArgb, effectiveColor(index, e.colorArgb), colorLabel) {
                        state.setImplicitStyle(index, it, e.visible)
                    }
                    MathTextField(
                        e.text, { state.setImplicit(index, it) }, stringResource(R.string.math_2d_implicit_equation) + " ${index + 1}", Modifier.weight(1f),
                    )
                    EntryEye(e.visible, eyeLabel) {
                        state.setImplicitStyle(index, e.colorArgb, !e.visible)
                    }
                    EntryDomainToggle(domKey in openDomains, domainLabel) { toggleDom(domKey) }
                    MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) {
                        state.removeImplicit(index)
                    }
                }
                EntryDomainEditor(
                    expanded = domKey in openDomains,
                    a0 = e.domA0, a1 = e.domA1, b0 = e.domB0, b1 = e.domB1,
                    onA0 = { state.setImplicitDomain(index, it, e.domA1, e.domB0, e.domB1) },
                    onA1 = { state.setImplicitDomain(index, e.domA0, it, e.domB0, e.domB1) },
                    onB0 = { state.setImplicitDomain(index, e.domA0, e.domA1, it, e.domB1) },
                    onB1 = { state.setImplicitDomain(index, e.domA0, e.domA1, e.domB0, it) },
                    labelA0 = stringResource(R.string.math_x_min),
                    labelA1 = stringResource(R.string.math_x_max),
                    labelB0 = stringResource(R.string.math_y_min),
                    labelB1 = stringResource(R.string.math_y_max),
                    showSecondary = true,
                )
            }
            MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addImplicit() }
            Plot2DDomain(state)
            AnimPanel(state.anim)
        }
        Plot2DMode.COMPLEX -> {
            MathTextField(
                state.complex, { state.complex = it }, stringResource(R.string.math_2d_complex_function), Modifier.fillMaxWidth(),
            )
            Plot2DDomain(state)
        }
    }
}

/** Explicit plot domain for every 2D mode: the sampling window is exactly what is typed here. */
@Composable
private fun Plot2DDomain(state: Plot2DState) {
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
    }
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
    val seriesArgb = colors.map { it.toArgb() }
    val xMin = state.xMin
    val xMax = state.xMax
    val yMin = state.yMin
    val yMax = state.yMax
    val showGrid = state.showGrid
    val mode = state.mode
    AnimTicker(state.anim)
    /** Entries exist but nothing plottable: show the grid, not the spinner. */
    val content = when (mode) {
        Plot2DMode.CARTESIAN, Plot2DMode.POLAR -> state.expressions.any { it.visible && it.text.isNotBlank() }
        Plot2DMode.PARAMETRIC -> state.paramCurves.any { it.visible && (it.x.isNotBlank() || it.y.isNotBlank()) }
        Plot2DMode.IMPLICIT -> state.implicitList.any { it.visible && it.text.isNotBlank() }
        Plot2DMode.COMPLEX -> state.complex.isNotBlank()
    }
    val view = rememberSettled(ViewBox(xMin, xMax, yMin, yMax), 80)
    /** Style trailer inside a settled key: colour ARGB (empty = auto) + visibility flag. */
    fun styleOf(colorArgb: Int?, visible: Boolean) = "\u0003" + (colorArgb?.toString() ?: "") + "\u0003" + (if (visible) "1" else "0")
    fun domOf(e: MathEntry) = "\u0004" + e.domA0 + "\u0004" + e.domA1 + "\u0004" + e.domB0 + "\u0004" + e.domB1
    fun parseStyle(raw: String): MathEntry {
        val domParts = raw.split("\u0004")
        val parts = domParts.getOrElse(0) { "" }.split("\u0003")
        return MathEntry(parts.getOrElse(0) { "" }, parts.getOrNull(1)?.toIntOrNull(), parts.getOrElse(2) { "1" } != "0",
            domParts.getOrElse(1) { "" }, domParts.getOrElse(2) { "" }, domParts.getOrElse(3) { "" }, domParts.getOrElse(4) { "" })
    }
    val expressions = rememberSettled(state.expressions.joinToString("\u0001") { it.text + styleOf(it.colorArgb, it.visible) + domOf(it) }, 160)
    val params = rememberSettled(state.paramCurves.map { it.x + "\u0002" + it.y + styleOf(it.colorArgb, it.visible) + "\u0004" + it.t0 + "\u0004" + it.t1 }.joinToString("\u0001"), 160)
    val implicits = rememberSettled(state.implicitList.joinToString("\u0001") { it.text + styleOf(it.colorArgb, it.visible) + domOf(it) }, 160)
    val complex = rememberSettled(state.complex, 180)
    val turns = rememberSettled(state.polarTurns, 160)
    val animT = state.anim.tCurrent
    val animOn = state.anim.enabled
    val animSnapKey = if (animOn) "${state.anim.tMinStr}|${state.anim.tMaxStr}|${state.anim.tStepsStr}|${state.anim.shareT}" else "off"
    val animSnapSettled = rememberSettled(animSnapKey, 160)
    var picture by remember { mutableStateOf<LivePlot?>(null) }
    var viewPx by remember { mutableStateOf(800 to 600) }

    androidx.compose.foundation.layout.Box(
        modifier.clipToBounds().onSizeChanged { viewPx = it.width to it.height },
    ) {
        val pxW = viewPx.first
        val pxH = viewPx.second
        LaunchedEffect(mode, expressions, params, implicits, complex, turns, view, pxW, pxH, animT, animSnapSettled) {
            val exprList = if (expressions.isEmpty()) emptyList() else expressions.split("\u0001").map(::parseStyle)
            val paramList = if (params.isEmpty()) emptyList() else params.split("\u0001").map {
                val parts = it.split("\u0002")
                val yAndStyle = parts.getOrElse(1) { "" }.split("\u0003")
                val style = parseStyle("\u0003" + yAndStyle.getOrElse(1) { "" } + "\u0003" + yAndStyle.getOrElse(2) { "1" })
                ParamCurve2D(parts.getOrElse(0) { "" }, yAndStyle.getOrElse(0) { "" }, style.colorArgb, style.visible, style.domA0, style.domA1)
            }
            val implList = if (implicits.isEmpty()) emptyList() else implicits.split("\u0001").map(::parseStyle)
            val shareT = animSnapSettled.endsWith("|true")
            val tVal: Double? = if (animOn) animT else null
            val snap = Plot2DSnap(
                mode = mode,
                expressions = exprList,
                paramCurves = paramList,
                implicitList = implList,
                complex = complex,
                polarTurns = turns.toDoubleOrNull()?.coerceIn(0.05, 64.0) ?: 2.0,
                xMin = view.xMin, xMax = view.xMax, yMin = view.yMin, yMax = view.yMax,
                showGrid = true,
                animT = tVal,
                anim = if (animOn) AnimSnap(0.0, 1.0, 60, tVal ?: 0.0, shareT) else null,
            )
            // Resolve full anim range for shared parametric sampling.
            val fullAnim: AnimSnap? = if (animOn) {
                val lo = state.anim.tMinStr.toDoubleOrNull()
                val hi = state.anim.tMaxStr.toDoubleOrNull()
                if (lo != null && hi != null && lo.isFinite() && hi.isFinite() && lo != hi)
                    AnimSnap(lo, hi, 60, tVal ?: lo, shareT) else null
            } else null
            val (dw, dh) = if (mode == Plot2DMode.COMPLEX) cappedSize(pxW, pxH, 150) else 0 to 0
            val sampled = withContext(Dispatchers.Default) { samplePlot2D(snap, dw, dh, fullAnim, seriesArgb) }
            picture = LivePlot(
                mode = snap.mode,
                curves = sampled.curves,
                curveColors = sampled.curveColors,
                implicit = sampled.implicitCurves,
                implicitColors = sampled.implicitColors,
                domain = sampled.domain?.asImageBitmap(),
                xMin = snap.xMin, xMax = snap.xMax, yMin = snap.yMin, yMax = snap.yMax,
                animT = tVal,
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
                if (mode == Plot2DMode.POLAR) {
                    // Coordinate-matched polar grid: concentric range rings with radial
                    // labels plus spokes, instead of the cartesian square grid.
                    val cx = sx(0.0); val cy = sy(0.0)
                    val maxR = max(
                        max(hypot(xMin, yMin), hypot(xMin, yMax)),
                        max(hypot(xMax, yMin), hypot(xMax, yMax)),
                    )
                    if (maxR.isFinite() && maxR > 0.0) {
                        val stepR = niceStep(maxR, max(2, (min(size.width, size.height) / 90f).toInt()))
                        var r = stepR
                        var guard = 0
                        while (r <= maxR && guard++ < 40) {
                            val rp = (r / (xMax - xMin) * size.width)
                            drawCircle(grid.copy(alpha = 0.45f), rp.toFloat(), Offset(cx, cy), style = Stroke(width = 1f))
                            val layout = measurer.measure(Numbers.tick(r), textStyle)
                            val lx = (cx + rp.toFloat() + 3f).coerceIn(0f, size.width - layout.size.width)
                            val ly = (cy + 3f).coerceIn(0f, size.height - layout.size.height)
                            if (lx + layout.size.width <= size.width && ly + layout.size.height <= size.height) {
                                drawText(layout, topLeft = Offset(lx, ly))
                            }
                            r += stepR
                        }
                        val diag = hypot(size.width, size.height)
                        var k = 0
                        while (k < 12 && guard++ < 60) {
                            val a = k * PI / 12.0
                            val dx = (cos(a) * diag).toFloat()
                            val dy = (-sin(a) * diag).toFloat()
                            drawLine(grid.copy(alpha = 0.45f), Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), strokeWidth = 1f)
                            k++
                        }
                    }
                    drawLine(axis.copy(alpha = 0.85f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.4f)
                    drawLine(axis.copy(alpha = 0.85f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.4f)
                    val zLayout = measurer.measure("0", textStyle)
                    if (cx in 0f..size.width && cy in 0f..size.height) {
                        drawText(zLayout, topLeft = Offset((cx - zLayout.size.width - 3f).coerceIn(0f, size.width - zLayout.size.width), (cy + 3f).coerceIn(0f, size.height - zLayout.size.height)))
                    }
                } else {
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
                    drawPath(path, Color(plot.curveColors.getOrElse(index) { seriesArgb[index % seriesArgb.size] }), style = stroke)
                }
                plot.implicit.forEachIndexed { index, segs ->
                    if (segs.isEmpty()) return@forEachIndexed
                    val path = Path()
                    for (segment in segs) {
                        path.moveTo(sx(segment.x1), sy(segment.y1))
                        path.lineTo(sx(segment.x2), sy(segment.y2))
                    }
                    drawPath(path, Color(plot.implicitColors.getOrElse(index) { seriesArgb[index % seriesArgb.size] }), style = stroke)
                }
            }
            if (plot == null && content) {
                val layout = measurer.measure(computingLabel, TextStyle(color = palette.textDim.toComposeColor(), fontSize = 13.sp))
                drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2f, (size.height - layout.size.height) / 2f))
            }
        }
        if (state.mode != Plot2DMode.COMPLEX) AnimPlayOverlay(state.anim)
    }
}

private class PlotGeometry(
    val curves: List<List<Polyline>>,
    val curveColors: List<Int>,
    val implicitCurves: List<List<WorldSegment>>,
    val implicitColors: List<Int>,
    val domain: Bitmap?,
)

private suspend fun exportPlot2D(
    snap: Plot2DSnap,
    theme: GraphTheme,
    title: String,
    legend: List<ExportLegend>,
): ByteArray? = try {
    val plot = renderPlot2DFrame(snap, theme, EXPORT_PLOT_W, EXPORT_PLOT_H)
    val foot = plot2DFoot(snap)
    writeGraphPng(plot, title, legend, foot, theme)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private suspend fun exportPlot2DAnimation(
    base: Plot2DSnap,
    theme: GraphTheme,
    title: String,
    legend: List<ExportLegend>,
): ByteArray? {
    return try {
    val lo = base.anim?.lo ?: return null
    val hi = base.anim.hi
    val steps = base.anim.steps
    val shareT = base.anim.shareT
    val ts = animFrameTs(lo, hi, steps, 12)
    val fw = 480; val fh = 300
    val frames = ArrayList<Pair<Bitmap, String>>(ts.size)
    for (t in ts) {
        coroutineContext.ensureActive()
        val fullAnim = AnimSnap(lo, hi, steps, t, shareT)
        val snap = Plot2DSnap(base.mode, base.expressions, base.paramCurves, base.implicitList, base.complex,
            base.polarTurns, base.xMin, base.xMax, base.yMin, base.yMax, base.showGrid,
            anim = AnimSnap(lo, hi, steps, t, shareT), animT = t)
        frames += renderPlot2DFrame(snap, theme, fw, fh, fullAnim) to "t=${Numbers.tick(t)}"
    }
    val foot = plot2DFoot(base.copy(animT = null)) + "    frames=${ts.size} t∈[${Numbers.tick(minOf(lo, hi))}, ${Numbers.tick(maxOf(lo, hi))}]"
    buildAnimationPng(frames, title, legend, foot, theme, specJson = MathAnimSpec.capture2D(base)?.toJson())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}
}

internal suspend fun renderPlot2DFrame(snap: Plot2DSnap, theme: GraphTheme, w: Int, h: Int, fullAnim: AnimSnap? = null): Bitmap {
    val full = fullAnim ?: snap.anim
    val (dw, dh) = if (snap.mode == Plot2DMode.COMPLEX) cappedSize(w, h, 220) else 0 to 0
    val sampled = samplePlot2D(snap, dw, dh, full, theme.series)
    val plot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(plot)
    canvas.drawColor(theme.paper)
    sampled.domain?.let { domain ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(domain, null, android.graphics.Rect(0, 0, w, h), paint)
        domain.recycle()
    }
    if (snap.showGrid || snap.mode != Plot2DMode.COMPLEX) {
        if (snap.mode == Plot2DMode.POLAR) {
            drawPolarPlotFrame(canvas, w, h, snap.xMin, snap.xMax, snap.yMin, snap.yMax, theme, snap.showGrid)
        } else {
            drawPlotFrame(canvas, w, h, snap.xMin, snap.xMax, snap.yMin, snap.yMax, theme, snap.showGrid)
        }
    }
    sampled.curves.forEachIndexed { index, strokes ->
        drawPolylines(canvas, strokes, sampled.curveColors.getOrElse(index) { theme.series[index % theme.series.size] },
            snap.xMin, snap.xMax, snap.yMin, snap.yMax, w, h, 3.4f)
    }
    sampled.implicitCurves.forEachIndexed { index, segs ->
        if (segs.isNotEmpty()) drawWorldSegments(canvas, segs, sampled.implicitColors.getOrElse(index) { theme.series[index % theme.series.size] },
            snap.xMin, snap.xMax, snap.yMin, snap.yMax, w, h, 3.2f)
    }
    return plot
}

private fun plot2DFoot(snap: Plot2DSnap): String = buildString {
    append(windowFootnote(snap.xMin, snap.xMax, snap.yMin, snap.yMax))
    when (snap.mode) {
        Plot2DMode.PARAMETRIC -> {
            if (snap.anim != null) append("    t ∈ [${Numbers.tick(snap.anim.lo)}, ${Numbers.tick(snap.anim.hi)}] t=${Numbers.tick(snap.animT ?: 0.0)}${if (snap.anim.shareT) " shared" else ""}")
            else append("    t ∈ [0, 2π]")
            snap.paramCurves.forEachIndexed { i, p ->
                if (p.t0.isNotBlank() || p.t1.isNotBlank()) {
                    entryRange(p.t0, p.t1, 0.0, 2.0 * PI)?.let { (lo, hi) ->
                        append("    P${i + 1} t∈[${Numbers.tick(lo)}, ${Numbers.tick(hi)}]")
                    }
                }
            }
        }
        Plot2DMode.POLAR -> {
            append("    θ ∈ [0, ${Numbers.tick(snap.polarTurns)}·2π]")
            snap.expressions.forEachIndexed { i, e ->
                entryDomainLabel(e)?.let { append("    r${i + 1} θ∈$it") }
            }
        }
        Plot2DMode.CARTESIAN -> {
            snap.expressions.forEachIndexed { i, e ->
                entryDomainLabel(e)?.let { append("    f${i + 1} x∈$it") }
            }
        }
        Plot2DMode.IMPLICIT -> {
            snap.implicitList.forEachIndexed { i, e ->
                entryDomainLabel(e)?.let { append("    C${i + 1} x∈$it") }
            }
        }
        else -> Unit
    }
    // Item A: complex mode never carries t.
    if (snap.animT != null && snap.mode != Plot2DMode.PARAMETRIC && snap.mode != Plot2DMode.COMPLEX) append("    t=${Numbers.tick(snap.animT)}")
}

private suspend fun samplePlot2D(snap: Plot2DSnap, domainW: Int, domainH: Int, fullAnim: AnimSnap? = null, series: List<Int> = emptyList()): PlotGeometry {
    coroutineContext.ensureActive()
    val curves = ArrayList<List<Polyline>>()
    val curveColors = ArrayList<Int>()
    val implicits = ArrayList<List<WorldSegment>>()
    val implicitColors = ArrayList<Int>()
    var domain: Bitmap? = null
    fun seriesColor(i: Int): Int = series.getOrElse(i % series.size.coerceAtLeast(1)) { 0xFF888888.toInt() }
    when (snap.mode) {
        Plot2DMode.CARTESIAN -> snap.expressions.forEachIndexed { i, e ->
            if (!e.visible) { curves.add(emptyList()); curveColors.add(seriesColor(i)); return@forEachIndexed }
            val expr = runCatching { ExpressionParser.parse(e.text) }.getOrNull()
            val xr = entryRange(e.domA0, e.domA1, snap.xMin, snap.xMax)
            curves.add(if (expr == null || xr == null) emptyList()
                else sampleCartesian(expr, xr.first, xr.second, snap.yMin, snap.yMax, snap.animT, entryClip(e.domB0, e.domB1)))
            curveColors.add(e.colorArgb ?: seriesColor(i))
        }
        Plot2DMode.POLAR -> snap.expressions.forEachIndexed { i, e ->
            if (!e.visible) { curves.add(emptyList()); curveColors.add(seriesColor(i)); return@forEachIndexed }
            val expr = runCatching { ExpressionParser.parse(e.text) }.getOrNull()
            val thR = entryRange(e.domA0, e.domA1, 0.0, snap.polarTurns * 2.0 * PI)
            curves.add(if (expr == null || thR == null) emptyList()
                else samplePolar(expr, thR.first, thR.second, snap.animT, entryClip(e.domB0, e.domB1)))
            curveColors.add(e.colorArgb ?: seriesColor(i))
        }
        Plot2DMode.PARAMETRIC -> snap.paramCurves.forEachIndexed { i, p ->
            if (!p.visible) { curves.add(emptyList()); curveColors.add(seriesColor(i)); return@forEachIndexed }
            val xExpr = runCatching { ExpressionParser.parse(p.x) }.getOrNull()
            val yExpr = runCatching { ExpressionParser.parse(p.y) }.getOrNull()
            val tR = entryRange(p.t0, p.t1, 0.0, 2.0 * PI)
            curves.add(if (xExpr == null || yExpr == null || tR == null) emptyList()
                else sampleParametric(xExpr, yExpr, snap, fullAnim, tR))
            curveColors.add(p.colorArgb ?: seriesColor(i))
        }
        Plot2DMode.IMPLICIT -> snap.implicitList.forEachIndexed { i, e ->
            if (!e.visible) { implicits.add(emptyList()); implicitColors.add(seriesColor(i)); return@forEachIndexed }
            val expr = runCatching { ExpressionParser.parse(e.text) }.getOrNull()
            val xr = entryRange(e.domA0, e.domA1, snap.xMin, snap.xMax)
            val yr = entryRange(e.domB0, e.domB1, snap.yMin, snap.yMax)
            implicits.add(if (expr == null || xr == null || yr == null) emptyList()
                else sampleImplicit(expr, xr.first, xr.second, yr.first, yr.second, snap.animT))
            implicitColors.add(e.colorArgb ?: seriesColor(i))
        }
        Plot2DMode.COMPLEX -> if (domainW > 1 && domainH > 1) {
            domain = renderDomain(snap.complex, domainW, domainH, snap.xMin, snap.xMax, snap.yMin, snap.yMax)
        }
    }
    return PlotGeometry(curves, curveColors, implicits, implicitColors, domain)
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

internal suspend fun sampleCartesian(expr: Expr, xMin: Double, xMax: Double, yMin: Double, yMax: Double, tAnim: Double? = null, yClip: Pair<Double, Double>? = null): List<Polyline> {
    val n = 400
    val span = (yMax - yMin).takeIf { it.isFinite() && it > 0.0 } ?: return emptyList()
    val scope = EvalScope()
    if (tAnim != null && tAnim.isFinite()) scope.vars["t"] = Complex.real(tAnim)
    val buf = StrokeBuf()
    var prev = Double.NaN
    for (i in 0..n) {
        if (i % 40 == 0) coroutineContext.ensureActive()
        val x = xMin + i.toDouble() / n * (xMax - xMin)
        scope.vars["x"] = Complex.real(x)
        val y = runCatching { Evaluator.evaluate(expr, scope).realValue() }.getOrDefault(Double.NaN)
        if (!y.isFinite() || (yClip != null && (y < yClip.first || y > yClip.second))) {
            buf.flush(); prev = Double.NaN; continue
        }
        if (prev.isFinite() && abs(y - prev) > span * 1.6) buf.flush()
        buf.add(x, y)
        prev = y
    }
    buf.flush()
    return buf.out
}

internal suspend fun samplePolar(expr: Expr, thMin: Double, thMax: Double, tAnim: Double? = null, rClip: Pair<Double, Double>? = null): List<Polyline> {
    val n = 560
    val scope = EvalScope()
    if (tAnim != null && tAnim.isFinite()) scope.vars["tAnim"] = Complex.real(tAnim)
    val buf = StrokeBuf()
    for (i in 0..n) {
        if (i % 40 == 0) coroutineContext.ensureActive()
        val theta = thMin + i.toDouble() / n * (thMax - thMin)
        val angle = Complex.real(theta)
        scope.vars["theta"] = angle
        // Preserve theta in t unless an animation drives t separately.
        if (tAnim == null) scope.vars["t"] = angle else scope.vars["t"] = Complex.real(tAnim)
        val r = runCatching { Evaluator.evaluate(expr, scope).realValue() }.getOrDefault(Double.NaN)
        if (!r.isFinite() || (rClip != null && (r < rClip.first || r > rClip.second))) {
            buf.flush(); continue
        }
        buf.add(r * cos(theta), r * sin(theta))
    }
    buf.flush()
    return buf.out
}

internal suspend fun sampleParametric(xExpr: Expr, yExpr: Expr, snap: Plot2DSnap, fullAnim: AnimSnap? = null, domT: Pair<Double, Double>? = null): List<Polyline> {
    val n = 480
    val limit = hypot(snap.xMax - snap.xMin, snap.yMax - snap.yMin) * 0.45
    val scope = EvalScope()
    val buf = StrokeBuf()
    var prevX = Double.NaN
    var prevY = Double.NaN
    val shareT = fullAnim?.shareT == true && snap.animT != null && fullAnim != null
    val tLo: Double
    val tHi: Double
    if (shareT && fullAnim != null) {
        val lo = minOf(fullAnim.lo, fullAnim.hi); val hi = maxOf(fullAnim.lo, fullAnim.hi)
        tLo = lo; tHi = (snap.animT ?: hi).coerceIn(lo, hi)
        if (tHi <= tLo) return emptyList()
    } else {
        tLo = domT?.first ?: 0.0; tHi = domT?.second ?: 2.0 * PI
        if (tHi <= tLo) return emptyList()
        if (snap.animT != null && snap.animT.isFinite() && fullAnim?.shareT != true) scope.vars["tAnim"] = Complex.real(snap.animT)
        // When not sharing, animation t is also visible as tAnim; expressions using t keep parametric t.
        if (snap.animT != null && snap.animT.isFinite()) scope.vars["tAnim"] = Complex.real(snap.animT)
    }
    for (i in 0..n) {
        if (i % 40 == 0) coroutineContext.ensureActive()
        val frac = i.toDouble() / n
        val t = if (shareT) tLo + frac * (tHi - tLo) else tLo + frac * (tHi - tLo)
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

internal suspend fun sampleImplicit(expr: Expr, xMin: Double, xMax: Double, yMin: Double, yMax: Double, tAnim: Double? = null): List<WorldSegment> {
    val nx = 96
    val ny = 96
    val scope = EvalScope()
    if (tAnim != null && tAnim.isFinite()) scope.vars["t"] = Complex.real(tAnim)
    val values = Array(ny) { r ->
        coroutineContext.ensureActive()
        DoubleArray(nx) { c ->
            val x = xMin + (c + 0.5) / nx * (xMax - xMin)
            val y = yMin + (r + 0.5) / ny * (yMax - yMin)
            scope.vars["x"] = Complex.real(x)
            scope.vars["y"] = Complex.real(y)
            runCatching { Evaluator.evaluate(expr, scope).realValue() }.getOrDefault(Double.NaN)
        }
    }
    return marchingSquares(values, nx, ny, xMin, xMax, yMin, yMax, 0.0)
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
