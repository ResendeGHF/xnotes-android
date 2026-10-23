package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.math.EvalScope
import com.xnotes.math.Evaluator
import com.xnotes.math.ExpressionParser
import com.xnotes.math.Numbers
import com.xnotes.math.Numerical
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class FieldMode(val labelRes: Int) {
    SCALAR_2D(R.string.math_field_scalar_2d),
    VECTOR_2D(R.string.math_field_vector_2d),
    SCALAR_3D(R.string.math_field_scalar_3d),
    VECTOR_3D(R.string.math_field_vector_3d),
}

internal class FieldState {
    var mode by mutableStateOf(FieldMode.SCALAR_2D)
    var scalar2 by mutableStateOf("sin(x) * cos(y)")
    var p2 by mutableStateOf("-y")
    var q2 by mutableStateOf("x")
    var scalar3 by mutableStateOf("exp(-(x^2 + y^2 + z^2))")
    var p3 by mutableStateOf("-y")
    var q3 by mutableStateOf("x")
    var r3 by mutableStateOf("0.35z")
    var xRange by mutableStateOf("5")
    var yRange by mutableStateOf("5")
    var zRange by mutableStateOf("3")
    var density by mutableFloatStateOf(12f)
    var level by mutableFloatStateOf(0.55f)
    var probeX by mutableStateOf("0.5")
    var probeY by mutableStateOf("0.5")
    var probeZ by mutableStateOf("0.5")
    var yaw by mutableFloatStateOf(0.72f)
    var pitch by mutableFloatStateOf(0.46f)
    var zoom by mutableFloatStateOf(1f)
    var valueLo by mutableStateOf<Double?>(null)
    var valueHi by mutableStateOf<Double?>(null)

    fun resetView() {
        yaw = 0.72f; pitch = 0.46f; zoom = 1f
        xRange = "5"; yRange = "5"; zRange = "3"
    }
}

private class FieldSnap(
    val mode: FieldMode,
    val scalar2: String,
    val p2: String,
    val q2: String,
    val scalar3: String,
    val p3: String,
    val q3: String,
    val r3: String,
    val xRange: Double,
    val yRange: Double,
    val zRange: Double,
    val density: Int,
    val level: Double,
    val yaw: Float,
    val pitch: Float,
    val zoom: Float,
    val probeX: String,
    val probeY: String,
    val probeZ: String,
)

private class FieldRaster(val bitmap: Bitmap, val lo: Double, val hi: Double)

private data class Camera(
    val yaw: Float,
    val pitch: Float,
    val zoom: Float,
    val probeX: String,
    val probeY: String,
    val probeZ: String,
)

private class CloudPoint(val x: Double, val y: Double, val z: Double, val value: Double)

private class ScalarCloud(
    val points: List<CloudPoint>,
    val lo: Double,
    val hi: Double,
    val xRange: Double,
    val yRange: Double,
    val zRange: Double,
)

private class Arrow3(
    val x: Double, val y: Double, val z: Double,
    val dx: Double, val dy: Double, val dz: Double,
    val mag: Double,
)

private class VectorCloud(
    val arrows: List<Arrow3>,
    val maxMag: Double,
    val xRange: Double,
    val yRange: Double,
    val zRange: Double,
)

@Composable
internal fun FieldPane(state: FieldState, onInsert: (ByteArray) -> Unit) {
    val theme = rememberGraphTheme()
    val title = stringResource(R.string.math_tab_fields)
    val failedText = stringResource(R.string.math_insert_failed)
    val magnitude = stringResource(R.string.math_ramp_magnitude)
    val heightLabel = stringResource(R.string.math_ramp_height)
    val scope = rememberCoroutineScope()
    val is3d = state.mode == FieldMode.SCALAR_3D || state.mode == FieldMode.VECTOR_3D
    val isVector = state.mode == FieldMode.VECTOR_2D || state.mode == FieldMode.VECTOR_3D
    var inserting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf("…") }
    val lo = state.valueLo
    val hi = state.valueHi
    val series = mathPlotColors()
    val legend = fieldLegend(state)
    LaunchedEffect(
        state.mode, state.scalar2, state.p2, state.q2, state.scalar3, state.p3, state.q3, state.r3,
        state.probeX, state.probeY, state.probeZ,
    ) {
        delay(180)
        val snap = state.snapshot()
        readout = withContext(Dispatchers.Default) { probeReadout(snap) }
    }
    GraphWorkspace(
        modes = FieldMode.entries.map { it.name to stringResource(it.labelRes) },
        selected = state.mode.name,
        onSelect = { state.mode = FieldMode.valueOf(it) },
        legend = legend.mapIndexed { index, label -> label to series[index % series.size] },
        ramp = if (lo != null && hi != null && lo != hi) Numbers.tick(lo) to Numbers.tick(hi) else null,
        onReset = state::resetView,
        inserting = inserting,
        onInsert = {
            if (inserting) return@GraphWorkspace
            val snap = state.snapshot()
            scope.launch {
                inserting = true
                failed = false
                val bytes = withContext(Dispatchers.Default) {
                    exportField(snap, theme, title, magnitude, heightLabel)
                }
                inserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        },
        status = if (failed) failedText else null,
        plot = {
            if (is3d) Field3DView(state, Modifier.fillMaxSize())
            else Field2DView(state, Modifier.fillMaxSize())
        },
        controls = {
            when {
                !is3d && !isVector -> MathTextField(
                    state.scalar2, { state.scalar2 = it }, stringResource(R.string.math_field_scalar_function), Modifier.fillMaxWidth(),
                )
                !is3d -> Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
                    MathTextField(state.p2, { state.p2 = it }, "P(x, y)", Modifier.weight(1f))
                    MathTextField(state.q2, { state.q2 = it }, "Q(x, y)", Modifier.weight(1f))
                }
                !isVector -> MathTextField(
                    state.scalar3, { state.scalar3 = it }, stringResource(R.string.math_field_scalar_function_xyz), Modifier.fillMaxWidth(),
                )
                else -> Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
                    MathTextField(state.p3, { state.p3 = it }, "P", Modifier.weight(1f))
                    MathTextField(state.q3, { state.q3 = it }, "Q", Modifier.weight(1f))
                    MathTextField(state.r3, { state.r3 = it }, "R", Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
                MathTextField(state.xRange, { state.xRange = filterRange(it) }, "x ±", Modifier.weight(1f))
                MathTextField(state.yRange, { state.yRange = filterRange(it) }, "y ±", Modifier.weight(1f))
                if (is3d) MathTextField(state.zRange, { state.zRange = filterRange(it) }, "z ±", Modifier.weight(1f))
            }
            if (isVector || is3d) {
                CommittedSlider(
                    value = state.density,
                    onCommit = { state.density = it },
                    valueRange = 6f..16f,
                    label = stringResource(if (is3d) R.string.math_field_grid else R.string.math_field_density),
                )
            }
            if (state.mode == FieldMode.SCALAR_3D) {
                CommittedSlider(
                    value = state.level,
                    onCommit = { state.level = it },
                    valueRange = 0.02f..0.98f,
                    label = stringResource(R.string.math_field_level),
                    readout = { "%.2f".format(it) },
                )
            }
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
                MathTextField(state.probeX, { state.probeX = it }, "x₀", Modifier.weight(1f))
                MathTextField(state.probeY, { state.probeY = it }, "y₀", Modifier.weight(1f))
                if (is3d) MathTextField(state.probeZ, { state.probeZ = it }, "z₀", Modifier.weight(1f))
            }
            MathResultCard(stringResource(R.string.math_field_probe), readout)
        },
    )
}

@Composable
private fun fieldLegend(state: FieldState): List<String> = when (state.mode) {
    FieldMode.SCALAR_2D -> listOf("f(x, y) = ${state.scalar2.ifBlank { "—" }}")
    FieldMode.VECTOR_2D -> listOf("P = ${state.p2.ifBlank { "—" }}", "Q = ${state.q2.ifBlank { "—" }}")
    FieldMode.SCALAR_3D -> listOf("f(x, y, z) = ${state.scalar3.ifBlank { "—" }}")
    FieldMode.VECTOR_3D -> listOf(
        "P = ${state.p3.ifBlank { "—" }}",
        "Q = ${state.q3.ifBlank { "—" }}",
        "R = ${state.r3.ifBlank { "—" }}",
    )
}

private fun FieldState.snapshot(): FieldSnap = FieldSnap(
    mode = mode,
    scalar2 = scalar2, p2 = p2, q2 = q2,
    scalar3 = scalar3, p3 = p3, q3 = q3, r3 = r3,
    xRange = rangeOf(xRange), yRange = rangeOf(yRange), zRange = rangeOf(zRange, 3.0),
    density = density.toInt().coerceIn(6, 16),
    level = level.toDouble().coerceIn(0.02, 0.98),
    yaw = yaw, pitch = pitch, zoom = zoom,
    probeX = probeX, probeY = probeY, probeZ = probeZ,
)

@Composable
private fun Field2DView(state: FieldState, modifier: Modifier = Modifier) {
    val theme = rememberGraphTheme()
    val mode = state.mode
    val scalar = rememberSettled(state.scalar2, 180)
    val p = rememberSettled(state.p2, 180)
    val q = rememberSettled(state.q2, 180)
    val xRange = rememberSettled(rangeOf(state.xRange), 180)
    val yRange = rememberSettled(rangeOf(state.yRange), 180)
    val density = state.density.toInt().coerceIn(6, 16)
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    val accent = LocalPalette.current.accent.toComposeColor()
    LaunchedEffect(mode, scalar, p, q, xRange, yRange, density, view, theme) {
        if (view.width < 2 || view.height < 2) return@LaunchedEffect
        val (w, h) = cappedSize(view.width, view.height, 520)
        val raster = withContext(Dispatchers.Default) {
            if (mode == FieldMode.SCALAR_2D) renderScalar2(scalar, xRange, yRange, w, h, theme)
            else renderVector2(p, q, xRange, yRange, density, w, h, theme)
        }
        state.valueLo = raster?.lo
        state.valueHi = raster?.hi
        frame = raster?.bitmap?.asImageBitmap()
    }
    Box(modifier.clipToBounds().onSizeChanged { view = it }) {
        frame?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (frame == null) {
            Text(
                stringResource(R.string.math_3d_computing),
                color = LocalPalette.current.textDim.toComposeColor(),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        val px = probeOrNull(state.probeX)
        val py = probeOrNull(state.probeY)
        if (px != null && py != null && abs(px) <= xRange && abs(py) <= yRange) {
            Canvas(Modifier.fillMaxSize()) {
                val sx = ((px + xRange) / (2 * xRange) * size.width).toFloat()
                val sy = ((yRange - py) / (2 * yRange) * size.height).toFloat()
                drawCircle(accent, 4f, Offset(sx, sy))
            }
        }
    }
}

@Composable
private fun Field3DView(state: FieldState, modifier: Modifier = Modifier) {
    val theme = rememberGraphTheme()
    val mode = state.mode
    val scalar = rememberSettled(state.scalar3, 180)
    val p = rememberSettled(state.p3, 180)
    val q = rememberSettled(state.q3, 180)
    val r = rememberSettled(state.r3, 180)
    val xRange = rememberSettled(rangeOf(state.xRange), 180)
    val yRange = rememberSettled(rangeOf(state.yRange), 180)
    val zRange = rememberSettled(rangeOf(state.zRange, 3.0), 180)
    val density = state.density.toInt().coerceIn(6, 16)
    val level = state.level.toDouble()
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    var scalarCloud by remember { mutableStateOf<ScalarCloud?>(null) }
    var vectorCloud by remember { mutableStateOf<VectorCloud?>(null) }
    LaunchedEffect(mode, scalar, p, q, r, xRange, yRange, zRange, density, level) {
        if (mode == FieldMode.SCALAR_3D) {
            val cloud = withContext(Dispatchers.Default) {
                buildScalar3(scalar, xRange, yRange, zRange, density.coerceIn(8, 13), level)
            }
            scalarCloud = cloud
            vectorCloud = null
            state.valueLo = cloud?.lo
            state.valueHi = cloud?.hi
        } else {
            val cloud = withContext(Dispatchers.Default) {
                buildVectors3(p, q, r, xRange, yRange, zRange, density.coerceIn(4, 7))
            }
            vectorCloud = cloud
            scalarCloud = null
            state.valueLo = 0.0
            state.valueHi = cloud?.maxMag
        }
    }
    LaunchedEffect(scalarCloud, vectorCloud, view, theme) {
        if (view.width < 2 || scalarCloud == null && vectorCloud == null) return@LaunchedEffect
        snapshotFlow {
            Camera(state.yaw, state.pitch, state.zoom, state.probeX, state.probeY, state.probeZ)
        }
            .conflate()
            .collect { cam ->
                val (w, h) = cappedSize(view.width, view.height, 420)
                val px = probeOrNull(cam.probeX)
                val py = probeOrNull(cam.probeY)
                val pz = probeOrNull(cam.probeZ)
                val bmp = withContext(Dispatchers.Default) {
                    when {
                        scalarCloud != null -> rasterScalar3(scalarCloud!!, cam.yaw, cam.pitch, cam.zoom, w, h, theme, px, py, pz)
                        vectorCloud != null -> rasterVector3(vectorCloud!!, cam.yaw, cam.pitch, cam.zoom, w, h, theme, px, py, pz)
                        else -> null
                    }
                }
                if (bmp != null) frame = bmp.asImageBitmap()
                delay(28)
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
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private suspend fun exportField(
    snap: FieldSnap,
    theme: GraphTheme,
    title: String,
    magnitude: String,
    heightLabel: String,
): ByteArray? = try {
    val raster = when (snap.mode) {
        FieldMode.SCALAR_2D -> renderScalar2(snap.scalar2, snap.xRange, snap.yRange, EXPORT_PLOT_W, EXPORT_PLOT_H, theme)
        FieldMode.VECTOR_2D -> renderVector2(snap.p2, snap.q2, snap.xRange, snap.yRange, snap.density, EXPORT_PLOT_W, EXPORT_PLOT_H, theme)
        FieldMode.SCALAR_3D -> {
            val cloud = buildScalar3(snap.scalar3, snap.xRange, snap.yRange, snap.zRange, snap.density.coerceIn(8, 13), snap.level)
                ?: return null
            rasterScalar3(cloud, snap.yaw, snap.pitch, snap.zoom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, probeOrNull(snap.probeX), probeOrNull(snap.probeY), probeOrNull(snap.probeZ))
                ?.let { FieldRaster(it, cloud.lo, cloud.hi) }
        }
        FieldMode.VECTOR_3D -> {
            val cloud = buildVectors3(snap.p3, snap.q3, snap.r3, snap.xRange, snap.yRange, snap.zRange, snap.density.coerceIn(4, 7))
                ?: return null
            rasterVector3(cloud, snap.yaw, snap.pitch, snap.zoom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, probeOrNull(snap.probeX), probeOrNull(snap.probeY), probeOrNull(snap.probeZ))
                ?.let { FieldRaster(it, 0.0, cloud.maxMag) }
        }
    } ?: return null
    val labels = when (snap.mode) {
        FieldMode.SCALAR_2D -> listOf("f(x, y) = ${snap.scalar2}")
        FieldMode.VECTOR_2D -> listOf("P = ${snap.p2}", "Q = ${snap.q2}")
        FieldMode.SCALAR_3D -> listOf("f(x, y, z) = ${snap.scalar3}")
        FieldMode.VECTOR_3D -> listOf("P = ${snap.p3}", "Q = ${snap.q3}", "R = ${snap.r3}")
    }
    val rampName = if (snap.mode == FieldMode.VECTOR_2D || snap.mode == FieldMode.VECTOR_3D) magnitude else heightLabel
    val legend = labels.mapIndexed { index, label ->
        ExportLegend(label, theme.series[index % theme.series.size])
    } + ExportLegend(
        "$rampName  ${Numbers.tick(raster.lo)} → ${Numbers.tick(raster.hi)}",
        theme.series[0],
        ramp = true,
    )
    val foot = buildString {
        append("x ∈ [±${Numbers.tick(snap.xRange)}]    y ∈ [±${Numbers.tick(snap.yRange)}]")
        if (snap.mode == FieldMode.SCALAR_3D || snap.mode == FieldMode.VECTOR_3D) {
            append("    z ∈ [±${Numbers.tick(snap.zRange)}]")
        }
        append("    ").append(probeReadout(snap))
    }
    writeGraphPng(raster.bitmap, title, legend, foot, theme)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

private suspend fun renderScalar2(
    text: String,
    xRange: Double,
    yRange: Double,
    width: Int,
    height: Int,
    theme: GraphTheme,
): FieldRaster? {
    val expr = runCatching { ExpressionParser.parse(text) }.getOrNull() ?: return null
    val n = 72
    val scope = EvalScope()
    val values = Array(n) { r ->
        coroutineContext.ensureActive()
        DoubleArray(n) { c ->
            val x = -xRange + 2 * xRange * c / (n - 1)
            val y = yRange - 2 * yRange * r / (n - 1)
            bindXY(scope, x, y)
            evalReal(scope, expr)
        }
    }
    val (lo, hi) = robustRange(values)
    val bmp = heatBitmap(values, lo, hi, width, height, theme.paper)
    val canvas = android.graphics.Canvas(bmp)
    val contours = ArrayList<WorldSegment>()
    val levels = 6
    for (k in 1 until levels) {
        contours += marchingSquares(values, n, n, -xRange, xRange, yRange, -yRange, lo + (hi - lo) * k / levels)
    }
    drawWorldOn(canvas, contours, theme.ink, xRange, yRange, bmp.width, bmp.height)
    drawAxes2(canvas, xRange, yRange, bmp.width, bmp.height, theme.axis)
    return FieldRaster(bmp, lo, hi)
}

private suspend fun renderVector2(
    p: String,
    q: String,
    xRange: Double,
    yRange: Double,
    density: Int,
    width: Int,
    height: Int,
    theme: GraphTheme,
): FieldRaster? {
    val pe = runCatching { ExpressionParser.parse(p) }.getOrNull() ?: return null
    val qe = runCatching { ExpressionParser.parse(q) }.getOrNull() ?: return null
    val n = 56
    val scope = EvalScope()
    val values = Array(n) { r ->
        coroutineContext.ensureActive()
        DoubleArray(n) { c ->
            val x = -xRange + 2 * xRange * c / (n - 1)
            val y = yRange - 2 * yRange * r / (n - 1)
            bindXY(scope, x, y)
            val pv = evalReal(scope, pe)
            val qv = evalReal(scope, qe)
            if (pv.isFinite() && qv.isFinite()) sqrt(pv * pv + qv * qv) else Double.NaN
        }
    }
    val (lo, hi) = robustRange(values)
    val bmp = heatBitmap(values, lo, hi, width, height, theme.paper)
    val canvas = android.graphics.Canvas(bmp)
    val arrows = ArrayList<DoubleArray>()
    var maxMag = 0.0
    val count = density.coerceIn(6, 16)
    for (j in 0 until count) {
        coroutineContext.ensureActive()
        for (i in 0 until count) {
            val x = -xRange + 2 * xRange * (i + 0.5) / count
            val y = -yRange + 2 * yRange * (j + 0.5) / count
            bindXY(scope, x, y)
            val pv = evalReal(scope, pe)
            val qv = evalReal(scope, qe)
            if (!pv.isFinite() || !qv.isFinite()) continue
            val mag = sqrt(pv * pv + qv * qv)
            if (!mag.isFinite()) continue
            maxMag = max(maxMag, mag)
            arrows += doubleArrayOf(x, y, pv, qv, mag)
        }
    }
    drawAxes2(canvas, xRange, yRange, bmp.width, bmp.height, theme.axis)
    drawArrows2(canvas, arrows, maxMag, xRange, yRange, count, bmp.width, bmp.height)
    return FieldRaster(bmp, 0.0, maxMag.coerceAtLeast(hi))
}

private fun heatBitmap(values: Array<DoubleArray>, lo: Double, hi: Double, width: Int, height: Int, paper: Int): Bitmap {
    val n = values.size
    val small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(n * n)
    val span = (hi - lo).coerceAtLeast(1e-12)
    for (r in 0 until n) {
        for (c in 0 until n) {
            val v = values[r][c]
            pixels[r * n + c] = if (!v.isFinite()) paper else heightColor(((v - lo) / span).coerceIn(0.0, 1.0)).toArgb()
        }
    }
    small.setPixels(pixels, 0, n, 0, 0, n, n)
    val w = width.coerceIn(2, 1200)
    val h = height.coerceIn(2, 1200)
    val large = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(large)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(small, null, android.graphics.Rect(0, 0, w, h), paint)
    small.recycle()
    return large
}

private fun drawAxes2(canvas: android.graphics.Canvas, xRange: Double, yRange: Double, w: Int, h: Int, color: Int) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 2f
        alpha = 170
    }
    val sx = ((0 + xRange) / (2 * xRange) * w).toFloat()
    val sy = ((yRange - 0) / (2 * yRange) * h).toFloat()
    canvas.drawLine(sx, 0f, sx, h.toFloat(), paint)
    canvas.drawLine(0f, sy, w.toFloat(), sy, paint)
}

private fun drawWorldOn(
    canvas: android.graphics.Canvas,
    segments: List<WorldSegment>,
    color: Int,
    xRange: Double,
    yRange: Double,
    w: Int,
    h: Int,
) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.6f
        alpha = 150
    }
    val path = android.graphics.Path()
    fun sx(x: Double) = ((x + xRange) / (2 * xRange) * w).toFloat()
    fun sy(y: Double) = ((yRange - y) / (2 * yRange) * h).toFloat()
    for (segment in segments) {
        path.moveTo(sx(segment.x1), sy(segment.y1))
        path.lineTo(sx(segment.x2), sy(segment.y2))
    }
    canvas.drawPath(path, paint)
}

private fun drawArrows2(
    canvas: android.graphics.Canvas,
    arrows: List<DoubleArray>,
    maxMag: Double,
    xRange: Double,
    yRange: Double,
    density: Int,
    w: Int,
    h: Int,
) {
    if (maxMag <= 1e-12) return
    val cell = min(w.toFloat() / density, h.toFloat() / density)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.2f
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    fun sx(x: Double) = ((x + xRange) / (2 * xRange) * w).toFloat()
    fun sy(y: Double) = ((yRange - y) / (2 * yRange) * h).toFloat()
    for (arrow in arrows) {
        val x = arrow[0]; val y = arrow[1]; val dx = arrow[2]; val dy = arrow[3]; val mag = arrow[4]
        if (mag <= 1e-12) continue
        val x0 = sx(x)
        val y0 = sy(y)
        val pixels = cell * 0.7f * (mag / maxMag).toFloat()
        val x1 = x0 + (dx / mag * pixels).toFloat()
        val y1 = y0 - (dy / mag * pixels).toFloat()
        val vx = x1 - x0
        val vy = y1 - y0
        val len = sqrt(vx * vx + vy * vy)
        if (len < 1.4f) continue
        paint.color = heightArgb((mag / maxMag).coerceIn(0.15, 0.95))
        canvas.drawLine(x0, y0, x1, y1, paint)
        val ux = vx / len
        val uy = vy / len
        val head = (cell * 0.22f).coerceIn(3f, 9f)
        canvas.drawLine(x1, y1, x1 - ux * head - uy * head * 0.55f, y1 - uy * head + ux * head * 0.55f, paint)
        canvas.drawLine(x1, y1, x1 - ux * head + uy * head * 0.55f, y1 - uy * head - ux * head * 0.55f, paint)
    }
}

private suspend fun buildScalar3(
    text: String,
    xRange: Double,
    yRange: Double,
    zRange: Double,
    density: Int,
    levelFraction: Double,
): ScalarCloud? {
    val expr = runCatching { ExpressionParser.parse(text) }.getOrNull() ?: return null
    val n = density.coerceIn(8, 13)
    val scope = EvalScope()
    val values = ArrayList<Pair<CloudPoint, Double>>(n * n * n)
    for (k in 0 until n) {
        coroutineContext.ensureActive()
        for (j in 0 until n) for (i in 0 until n) {
            val x = -xRange + 2 * xRange * (i + 0.5) / n
            val y = -yRange + 2 * yRange * (j + 0.5) / n
            val z = -zRange + 2 * zRange * (k + 0.5) / n
            bindXYZ(scope, x, y, z)
            val v = evalReal(scope, expr)
            if (v.isFinite()) values += CloudPoint(x, y, z, v) to v
        }
    }
    if (values.isEmpty()) return null
    val sorted = values.map { it.second }.sorted()
    val lo = sorted[(sorted.size * 0.02).toInt().coerceIn(0, sorted.lastIndex)]
    val hi = sorted[(sorted.size * 0.98).toInt().coerceIn(0, sorted.lastIndex)]
    val target = lo + (hi - lo) * levelFraction.coerceIn(0.0, 1.0)
    val tolerance = max((hi - lo) * 0.06, 1e-7)
    var points = values.filter { abs(it.second - target) <= tolerance }.map { it.first }
    if (points.size > 1600) {
        val step = points.size / 1600 + 1
        points = points.filterIndexed { index, _ -> index % step == 0 }
    }
    return ScalarCloud(points, lo, hi, xRange, yRange, zRange)
}

private suspend fun buildVectors3(
    p: String, q: String, r: String,
    xRange: Double, yRange: Double, zRange: Double,
    density: Int,
): VectorCloud? {
    val pe = runCatching { ExpressionParser.parse(p) }.getOrNull() ?: return null
    val qe = runCatching { ExpressionParser.parse(q) }.getOrNull() ?: return null
    val re = runCatching { ExpressionParser.parse(r) }.getOrNull() ?: return null
    val n = density.coerceIn(4, 7)
    val scope = EvalScope()
    val arrows = ArrayList<Arrow3>(n * n * n)
    var maxMag = 0.0
    for (k in 0 until n) {
        coroutineContext.ensureActive()
        for (j in 0 until n) for (i in 0 until n) {
            val x = -xRange + 2 * xRange * (i + 0.5) / n
            val y = -yRange + 2 * yRange * (j + 0.5) / n
            val z = -zRange + 2 * zRange * (k + 0.5) / n
            bindXYZ(scope, x, y, z)
            val pv = evalReal(scope, pe)
            val qv = evalReal(scope, qe)
            val rv = evalReal(scope, re)
            if (!pv.isFinite() || !qv.isFinite() || !rv.isFinite()) continue
            val mag = sqrt(pv * pv + qv * qv + rv * rv)
            if (!mag.isFinite()) continue
            maxMag = max(maxMag, mag)
            arrows += Arrow3(x, y, z, pv, qv, rv, mag)
        }
    }
    return VectorCloud(arrows, maxMag, xRange, yRange, zRange)
}

private fun rasterScalar3(
    cloud: ScalarCloud,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    width: Int,
    height: Int,
    theme: GraphTheme,
    probeX: Double?,
    probeY: Double?,
    probeZ: Double?,
): Bitmap {
    val w = width.coerceIn(2, 1200)
    val h = height.coerceIn(2, 1200)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val projector = Projector(w, h, yaw, pitch, zoom, cloud.xRange, cloud.yRange, cloud.zRange)
    projector.box(canvas, theme.grid)
    projector.axes(canvas, theme.dim)
    val span = (cloud.hi - cloud.lo).coerceAtLeast(1e-12)
    val order = cloud.points.indices.sortedBy { projector.depth(cloud.points[it].x, cloud.points[it].y, cloud.points[it].z) }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    for (index in order) {
        val point = cloud.points[index]
        val at = projector.project(point.x, point.y, point.z)
        val t = ((point.value - cloud.lo) / span).coerceIn(0.0, 1.0)
        paint.color = heightArgb(t)
        canvas.drawCircle(at.first, at.second, 3.2f, paint)
    }
    if (probeX != null && probeY != null && probeZ != null) {
        val at = projector.project(probeX, probeY, probeZ)
        paint.color = theme.accent
        canvas.drawCircle(at.first, at.second, 6f, paint)
    }
    return bmp
}

private fun rasterVector3(
    cloud: VectorCloud,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    width: Int,
    height: Int,
    theme: GraphTheme,
    probeX: Double?,
    probeY: Double?,
    probeZ: Double?,
): Bitmap {
    val w = width.coerceIn(2, 1200)
    val h = height.coerceIn(2, 1200)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val projector = Projector(w, h, yaw, pitch, zoom, cloud.xRange, cloud.yRange, cloud.zRange)
    projector.box(canvas, theme.grid)
    projector.axes(canvas, theme.dim)
    val scale = if (cloud.maxMag > 1e-12) min(cloud.xRange, min(cloud.yRange, cloud.zRange)) * 0.42 / cloud.maxMag else 0.0
    val order = cloud.arrows.indices.sortedBy { arrow ->
        val a = cloud.arrows[arrow]
        projector.depth(a.x, a.y, a.z)
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    for (index in order) {
        val arrow = cloud.arrows[index]
        val start = projector.project(arrow.x, arrow.y, arrow.z)
        val end = projector.project(
            arrow.x + arrow.dx * scale,
            arrow.y + arrow.dy * scale,
            arrow.z + arrow.dz * scale,
        )
        val vx = end.first - start.first
        val vy = end.second - start.second
        val len = sqrt(vx * vx + vy * vy)
        if (len < 1.5f) continue
        paint.color = heightArgb((arrow.mag / cloud.maxMag.coerceAtLeast(1e-12)).coerceIn(0.12, 0.95))
        canvas.drawLine(start.first, start.second, end.first, end.second, paint)
        val ux = vx / len
        val uy = vy / len
        val head = (len * 0.28f).coerceIn(3f, 9f)
        canvas.drawLine(end.first, end.second, end.first - ux * head - uy * head * 0.55f, end.second - uy * head + ux * head * 0.55f, paint)
        canvas.drawLine(end.first, end.second, end.first - ux * head + uy * head * 0.55f, end.second - uy * head - ux * head * 0.55f, paint)
    }
    if (probeX != null && probeY != null && probeZ != null) {
        val at = projector.project(probeX, probeY, probeZ)
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = theme.accent
        canvas.drawCircle(at.first, at.second, 6f, paint)
    }
    return bmp
}

private class Projector(
    val width: Int,
    val height: Int,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    val xRange: Double,
    val yRange: Double,
    val zRange: Double,
) {
    private val cyaw = cos(yaw)
    private val syaw = sin(yaw)
    private val cp = cos(pitch)
    private val sp = sin(pitch)
    private val cx = width / 2f
    private val cy = height * 0.54f
    private val scale = min(width, height) * 0.36f * zoom

    fun project(x: Double, y: Double, z: Double): Pair<Float, Float> {
        val nx = (x / xRange).toFloat()
        val ny = (y / yRange).toFloat()
        val nz = (z / zRange).toFloat().coerceIn(-1.4f, 1.4f)
        val x1 = nx * cyaw - ny * syaw
        val y1 = nx * syaw + ny * cyaw
        val y2 = y1 * cp + nz * sp
        return (cx + x1 * scale) to (cy - y2 * scale)
    }

    fun depth(x: Double, y: Double, z: Double): Float {
        val nx = (x / xRange).toFloat()
        val ny = (y / yRange).toFloat()
        val nz = (z / zRange).toFloat()
        val y1 = nx * syaw + ny * cyaw
        return y1 * sp - nz * cp
    }

    fun box(canvas: android.graphics.Canvas, color: Int) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 1.5f
            alpha = 120
        }
        val corners = ArrayList<Pair<Float, Float>>(8)
        for (sz in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) for (sx in listOf(-1.0, 1.0)) {
            corners += project(sx * xRange, sy * yRange, sz * zRange)
        }
        val edges = listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0, 4 to 5, 5 to 7, 7 to 6, 6 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
        for ((a, b) in edges) {
            canvas.drawLine(corners[a].first, corners[a].second, corners[b].first, corners[b].second, paint)
        }
    }

    fun axes(canvas: android.graphics.Canvas, color: Int) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 2.2f
            alpha = 180
        }
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = (min(width, height) * 0.045f).coerceIn(16f, 34f)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val origin = project(0.0, 0.0, 0.0)
        for ((end, label) in listOf(
            project(xRange * 0.92, 0.0, 0.0) to "x",
            project(0.0, yRange * 0.92, 0.0) to "y",
            project(0.0, 0.0, zRange * 0.92) to "z",
        )) {
            canvas.drawLine(origin.first, origin.second, end.first, end.second, paint)
            canvas.drawText(label, end.first + 4f, end.second + 4f, text)
        }
    }
}

private fun robustRange(values: Array<DoubleArray>): Pair<Double, Double> {
    val finite = ArrayList<Double>()
    for (row in values) for (v in row) if (v.isFinite()) finite += v
    if (finite.isEmpty()) return 0.0 to 1.0
    finite.sort()
    var lo = finite[(finite.size * 0.015).toInt().coerceIn(0, finite.lastIndex)]
    var hi = finite[(finite.size * 0.985).toInt().coerceIn(0, finite.lastIndex)]
    if (hi <= lo) {
        lo -= 0.5
        hi += 0.5
    }
    return lo to hi
}

private fun rangeOf(text: String, fallback: Double = 5.0): Double =
    text.toDoubleOrNull()?.coerceIn(0.05, 1e4) ?: fallback

private fun filterRange(text: String): String = text.filter { it.isDigit() || it == '.' || it == '-' }

private fun probeOrNull(text: String): Double? =
    runCatching { Evaluator.evaluate(ExpressionParser.parse(text)).realValue() }.getOrNull()?.takeIf { it.isFinite() }

private fun probeReadout(snap: FieldSnap): String {
    val p = probeOrNull(snap.probeX) ?: 0.0
    val q = probeOrNull(snap.probeY) ?: 0.0
    val r = probeOrNull(snap.probeZ) ?: 0.0
    fun num(v: Double) = if (v.isFinite()) Numbers.format(v) else "—"
    return runCatching {
        when (snap.mode) {
            FieldMode.SCALAR_2D -> {
                val expr = ExpressionParser.parse(snap.scalar2)
                val value = evalXY(expr, p, q)
                val g = Numerical.gradient2({ x, y -> evalXY(expr, x, y) }, p, q)
                "f(${Numbers.tick(p)}, ${Numbers.tick(q)}) = ${num(value)}    |∇f| = ${num(sqrt(g.first * g.first + g.second * g.second))}"
            }
            FieldMode.VECTOR_2D -> {
                val pe = ExpressionParser.parse(snap.p2)
                val qe = ExpressionParser.parse(snap.q2)
                val div = Numerical.divergence2({ x, y -> evalXY(pe, x, y) }, { x, y -> evalXY(qe, x, y) }, p, q)
                val curl = Numerical.curl2({ x, y -> evalXY(pe, x, y) }, { x, y -> evalXY(qe, x, y) }, p, q)
                "div(${Numbers.tick(p)}, ${Numbers.tick(q)}) = ${num(div)}    curl z = ${num(curl)}"
            }
            FieldMode.SCALAR_3D -> {
                val expr = ExpressionParser.parse(snap.scalar3)
                val value = evalXYZ(expr, p, q, r)
                val g = Numerical.gradient3({ x, y, z -> evalXYZ(expr, x, y, z) }, p, q, r)
                "f = ${num(value)}    |∇f| = ${num(sqrt(g.first * g.first + g.second * g.second + g.third * g.third))}"
            }
            FieldMode.VECTOR_3D -> {
                val pe = ExpressionParser.parse(snap.p3)
                val qe = ExpressionParser.parse(snap.q3)
                val re = ExpressionParser.parse(snap.r3)
                val div = Numerical.divergence3(
                    { x, y, z -> evalXYZ(pe, x, y, z) },
                    { x, y, z -> evalXYZ(qe, x, y, z) },
                    { x, y, z -> evalXYZ(re, x, y, z) },
                    p, q, r,
                )
                val curl = Numerical.curl3(
                    { x, y, z -> evalXYZ(pe, x, y, z) },
                    { x, y, z -> evalXYZ(qe, x, y, z) },
                    { x, y, z -> evalXYZ(re, x, y, z) },
                    p, q, r,
                )
                val curlMag = sqrt(curl.first * curl.first + curl.second * curl.second + curl.third * curl.third)
                "div = ${num(div)}    |curl| = ${num(curlMag)}"
            }
        }
    }.getOrElse { "—" }
}
