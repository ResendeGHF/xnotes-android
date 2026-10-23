package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SurfaceMode(val labelRes: Int) {
    REAL(R.string.math_3d_real_surface),
    COMPLEX(R.string.math_3d_complex_surface),
}

internal enum class ComplexComponent(val labelRes: Int) {
    REAL(R.string.math_component_real),
    IMAG(R.string.math_component_imag),
    MAGNITUDE(R.string.math_component_magnitude),
    ARGUMENT(R.string.math_component_argument),
}

internal class Plot3DState {
    var mode by mutableStateOf(SurfaceMode.REAL)
    var xExpression by mutableStateOf("sin(sqrt(x^2 + y^2))")
    var complexExpression by mutableStateOf("gamma(z)")
    var component by mutableStateOf(ComplexComponent.MAGNITUDE)
    var range by mutableStateOf("5")
    var resolution by mutableFloatStateOf(24f)
    var yaw by mutableFloatStateOf(0.72f)
    var pitch by mutableFloatStateOf(0.46f)
    var zoom by mutableFloatStateOf(1f)
    var valueLo by mutableStateOf<Double?>(null)
    var valueHi by mutableStateOf<Double?>(null)

    fun resetView() {
        yaw = 0.72f; pitch = 0.46f; zoom = 1f
    }
}

private class SurfaceData(
    val values: Array<DoubleArray>,
    val lo: Double,
    val hi: Double,
    val maxAbs: Double,
)

private class Plot3DSnap(
    val mode: SurfaceMode,
    val expression: String,
    val component: ComplexComponent,
    val range: Double,
    val n: Int,
    val yaw: Float,
    val pitch: Float,
    val zoom: Float,
)

@Composable
internal fun Plot3DPane(state: Plot3DState, onInsert: (ByteArray) -> Unit) {
    val palette = LocalPalette.current
    val theme = rememberGraphTheme()
    val title = stringResource(R.string.math_tab_graph3d)
    val failedText = stringResource(R.string.math_insert_failed)
    val heightLabel = stringResource(R.string.math_ramp_height)
    val scope = rememberCoroutineScope()
    val complex = state.mode == SurfaceMode.COMPLEX
    var inserting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val expression = if (complex) state.complexExpression else state.xExpression
    val legend = listOf(
        (if (complex) "f(z) = ${expression.ifBlank { "—" }}" else "z = ${expression.ifBlank { "—" }}") to palette.accent.toComposeColor(),
    )
    val lo = state.valueLo
    val hi = state.valueHi
    val componentLabel = if (complex) stringResource(state.component.labelRes) else ""
    GraphWorkspace(
        modes = SurfaceMode.entries.map { it.name to stringResource(it.labelRes) },
        selected = state.mode.name,
        onSelect = { state.mode = SurfaceMode.valueOf(it) },
        legend = legend,
        ramp = if (lo != null && hi != null) Numbers.tick(lo) to Numbers.tick(hi) else null,
        caption = if (complex) stringResource(state.component.labelRes) else null,
        onReset = state::resetView,
        inserting = inserting,
        onInsert = {
            if (inserting) return@GraphWorkspace
            val snap = Plot3DSnap(
                mode = state.mode,
                expression = expression,
                component = state.component,
                range = state.range.toDoubleOrNull()?.coerceIn(0.1, 1e5) ?: 5.0,
                n = state.resolution.toInt().coerceIn(14, 32),
                yaw = state.yaw,
                pitch = state.pitch,
                zoom = state.zoom,
            )
            scope.launch {
                inserting = true
                failed = false
                val bytes = withContext(Dispatchers.Default) {
                    exportSurface(snap, theme, title, heightLabel, componentLabel)
                }
                inserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        },
        status = if (failed) failedText else null,
        plot = { SurfaceView(state, Modifier.fillMaxSize()) },
        controls = {
            MathTextField(
                value = expression,
                onValueChange = { if (complex) state.complexExpression = it else state.xExpression = it },
                label = if (complex) stringResource(R.string.math_3d_complex_function) else stringResource(R.string.math_3d_function_xy),
                modifier = Modifier.fillMaxWidth(),
            )
            if (complex) {
                MathToolSelector(
                    ComplexComponent.entries.map { it.name to stringResource(it.labelRes) },
                    selected = state.component.name,
                ) { state.component = ComplexComponent.valueOf(it) }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.math_3d_range), color = palette.textDim.toComposeColor(), fontSize = 10.sp)
                SpacerBox()
                MathTextField(
                    value = state.range,
                    onValueChange = { state.range = it.filter { c -> c.isDigit() || c == '.' } },
                    label = "±",
                    modifier = Modifier.width(88.dp),
                )
            }
            CommittedSlider(
                value = state.resolution,
                onCommit = { state.resolution = it },
                valueRange = 14f..32f,
                label = stringResource(R.string.math_3d_resolution),
            )
        },
    )
}

@Composable
private fun SpacerBox() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
}

@Composable
private fun SurfaceView(state: Plot3DState, modifier: Modifier = Modifier) {
    val complex = state.mode == SurfaceMode.COMPLEX
    val expression = rememberSettled(if (complex) state.complexExpression else state.xExpression, 180)
    val range = rememberSettled(state.range.toDoubleOrNull()?.coerceIn(0.1, 1e5) ?: 5.0, 180)
    val n = state.resolution.toInt().coerceIn(14, 32)
    val theme = rememberGraphTheme()
    var surface by remember { mutableStateOf<SurfaceData?>(null) }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    LaunchedEffect(expression, state.mode, state.component, range, n) {
        val data = withContext(Dispatchers.Default) {
            computeSurface(expression, state.mode == SurfaceMode.COMPLEX, state.component, range, n)
        }
        surface = data
        state.valueLo = data?.lo
        state.valueHi = data?.hi
    }
    LaunchedEffect(surface, view, theme) {
        val data = surface ?: return@LaunchedEffect
        snapshotFlow { Triple(state.yaw, state.pitch, state.zoom) }
            .conflate()
            .collect { (yaw, pitch, zoom) ->
                val (w, h) = cappedSize(view.width, view.height, 420)
                val bmp = withContext(Dispatchers.Default) {
                    rasterSurface(data, range, yaw, pitch, zoom, w, h, theme)
                }
                frame = bmp.asImageBitmap()
                kotlinx.coroutines.delay(28)
            }
    }
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { view = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    state.yaw += pan.x * 0.008f
                    state.pitch = (state.pitch + pan.y * 0.008f).coerceIn(-1.45f, 1.45f)
                    state.zoom = (state.zoom * zoom).coerceIn(0.35f, 4f)
                }
            },
    ) {
        frame?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (frame == null) {
            Text(
                stringResource(R.string.math_3d_computing),
                color = LocalPalette.current.textDim.toComposeColor(),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
            )
        }
    }
}

private suspend fun exportSurface(
    snap: Plot3DSnap,
    theme: GraphTheme,
    title: String,
    heightLabel: String,
    component: String,
): ByteArray? = try {
    val data = computeSurface(snap.expression, snap.mode == SurfaceMode.COMPLEX, snap.component, snap.range, snap.n)
        ?: return null
    val bmp = rasterSurface(data, snap.range, snap.yaw, snap.pitch, snap.zoom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme)
    val label = if (snap.mode == SurfaceMode.COMPLEX) "f(z) = ${snap.expression}" else "z = ${snap.expression}"
    val legend = listOf(
        ExportLegend(
            "$label    $heightLabel  ${Numbers.tick(data.lo)} → ${Numbers.tick(data.hi)}",
            theme.series[0],
            ramp = true,
        ),
    )
    val foot = buildString {
        append("x, y ∈ [−${Numbers.tick(snap.range)}, ${Numbers.tick(snap.range)}]")
        if (component.isNotEmpty()) append("    ").append(component)
    }
    writeGraphPng(bmp, title, legend, foot, theme)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private suspend fun computeSurface(
    expression: String,
    complex: Boolean,
    component: ComplexComponent,
    range: Double,
    n: Int,
): SurfaceData? {
    val expr: Expr = runCatching { ExpressionParser.parse(expression) }.getOrNull() ?: return null
    val scope = EvalScope()
    val values = Array(n) { r ->
        coroutineContext.ensureActive()
        DoubleArray(n) { c ->
            val x = -range + 2.0 * range * c / (n - 1)
            val y = -range + 2.0 * range * r / (n - 1)
            if (!complex) {
                bindXY(scope, x, y)
                evalReal(scope, expr)
            } else {
                bindPlane(scope, x, y)
                val v = runCatching { Evaluator.evaluate(expr, scope) }.getOrDefault(Complex.NaN)
                when (component) {
                    ComplexComponent.REAL -> v.re
                    ComplexComponent.IMAG -> v.im
                    ComplexComponent.MAGNITUDE -> if (v.isFinite) v.abs() else Double.NaN
                    ComplexComponent.ARGUMENT -> if (v.isFinite && v.abs() > 0.0) v.arg() else Double.NaN
                }
            }
        }
    }
    val finite = ArrayList<Double>(n * n)
    for (row in values) for (v in row) if (v.isFinite()) finite += v
    if (finite.isEmpty()) return null
    finite.sort()
    val lo = finite[(finite.size * 0.015).toInt().coerceIn(0, finite.lastIndex)]
    val hi = finite[(finite.size * 0.985).toInt().coerceIn(0, finite.lastIndex)]
    val actualLo = if (hi == lo) lo - 0.5 else lo
    val actualHi = if (hi == lo) hi + 0.5 else hi
    val maxAbs = max(abs(actualLo), abs(actualHi)).coerceAtLeast(1e-9)
    return SurfaceData(values, actualLo, actualHi, maxAbs)
}

/** Paints the surface into one bitmap. The live view only blits that bitmap, so orbiting stays off the UI thread. */
private suspend fun rasterSurface(
    data: SurfaceData,
    range: Double,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    width: Int,
    height: Int,
    theme: GraphTheme,
): Bitmap {
    val w = width.coerceIn(2, 1200)
    val h = height.coerceIn(2, 1200)
    val n = data.values.size
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val cyaw = cos(yaw)
    val syaw = sin(yaw)
    val cp = cos(pitch)
    val sp = sin(pitch)
    val cx = w / 2f
    val cy = h * 0.54f
    val scale = min(w, h) * 0.36f * zoom
    val maxAbs = data.maxAbs
    val count = n * n
    val px = FloatArray(count)
    val py = FloatArray(count)
    val pd = FloatArray(count)
    for (r in 0 until n) {
        if (r % 4 == 0) coroutineContext.ensureActive()
        val y = -range + 2.0 * range * r / (n - 1)
        for (c in 0 until n) {
            val x = -range + 2.0 * range * c / (n - 1)
            val z = data.values[r][c]
            val i = r * n + c
            if (!z.isFinite()) {
                pd[i] = Float.NaN
                continue
            }
            val nx = (x / range).toFloat()
            val ny = (y / range).toFloat()
            val nz = ((z / maxAbs).coerceIn(-1.25, 1.25) * 0.62).toFloat()
            val x1 = nx * cyaw - ny * syaw
            val y1 = nx * syaw + ny * cyaw
            val y2 = y1 * cp + nz * sp
            px[i] = cx + x1 * scale
            py[i] = cy - y2 * scale
            pd[i] = y1 * sp - nz * cp
        }
    }
    val cells = (n - 1) * (n - 1)
    val order = IntArray(cells)
    val depth = FloatArray(cells)
    val color = IntArray(cells)
    var used = 0
    val span = (data.hi - data.lo).coerceAtLeast(1e-12)
    for (r in 0 until n - 1) {
        for (c in 0 until n - 1) {
            val i00 = r * n + c
            val i10 = i00 + 1
            val i11 = i00 + n + 1
            val i01 = i00 + n
            if (!pd[i00].isFinite() || !pd[i10].isFinite() || !pd[i11].isFinite() || !pd[i01].isFinite()) continue
            val avg = (data.values[r][c] + data.values[r][c + 1] + data.values[r + 1][c + 1] + data.values[r + 1][c]) / 4.0
            if (!avg.isFinite()) continue
            val t = ((avg - data.lo) / span).coerceIn(0.0, 1.0)
            order[used] = i00
            depth[used] = (pd[i00] + pd[i10] + pd[i11] + pd[i01]) / 4f
            color[used] = shaded(heightArgb(t), t)
            used++
        }
    }
    val sorted = ArrayList<Int>(used)
    for (i in 0 until used) sorted += i
    sorted.sortBy { depth[it] }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    val path = android.graphics.Path()
    val axis = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = theme.axis
        strokeWidth = 2f
        alpha = 150
    }
    val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = theme.dim
        textSize = (min(w, h) * 0.04f).coerceIn(16f, 36f)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    fun project(x: Double, y: Double, z: Double): Pair<Float, Float> {
        val nx = (x / range).toFloat()
        val ny = (y / range).toFloat()
        val nz = ((z / maxAbs).coerceIn(-1.25, 1.25) * 0.62).toFloat()
        val xA = nx * cyaw - ny * syaw
        val yA = nx * syaw + ny * cyaw
        val yB = yA * cp + nz * sp
        return (cx + xA * scale) to (cy - yB * scale)
    }
    fun axis(x1: Double, y1: Double, z1: Double, label: String) {
        val a = project(0.0, 0.0, 0.0)
        val b = project(x1, y1, z1)
        canvas.drawLine(a.first, a.second, b.first, b.second, axis)
        canvas.drawText(label, b.first + 6f, b.second + 6f, labelPaint)
    }
    axis(range * 0.9, 0.0, 0.0, "x")
    axis(0.0, range * 0.9, 0.0, "y")
    axis(0.0, 0.0, maxAbs * 0.9, "z")
    for (slot in sorted) {
        val i00 = order[slot]
        val r = i00 / n
        val c = i00 - r * n
        val i10 = i00 + 1
        val i11 = i00 + n + 1
        val i01 = i00 + n
        path.rewind()
        path.moveTo(px[i00], py[i00])
        path.lineTo(px[i10], py[i10])
        path.lineTo(px[i11], py[i11])
        path.lineTo(px[i01], py[i01])
        path.close()
        paint.color = color[slot]
        canvas.drawPath(path, paint)
    }
    return bmp
}

private fun shaded(base: Int, t: Double): Int {
    val light = (0.76 + 0.24 * t).toFloat()
    return android.graphics.Color.rgb(
        (android.graphics.Color.red(base) * light).toInt().coerceIn(0, 255),
        (android.graphics.Color.green(base) * light).toInt().coerceIn(0, 255),
        (android.graphics.Color.blue(base) * light).toInt().coerceIn(0, 255),
    )
}

/** Blue → cyan → green → yellow → red, the usual height ramp in scientific plotting. */
internal fun heightColor(t: Double): Color {
    val stops = listOf(
        Color(0xFF2E5AAC),
        Color(0xFF2C9BB5),
        Color(0xFF4BAE62),
        Color(0xFFD8B33C),
        Color(0xFFC74B3A),
    )
    val x = t.coerceIn(0.0, 1.0) * (stops.size - 1)
    val i = x.toInt().coerceIn(0, stops.lastIndex - 1)
    val f = (x - i).toFloat()
    val a = stops[i]
    val b = stops[i + 1]
    return Color(
        a.red + (b.red - a.red) * f,
        a.green + (b.green - a.green) * f,
        a.blue + (b.blue - a.blue) * f,
        1f,
    )
}
