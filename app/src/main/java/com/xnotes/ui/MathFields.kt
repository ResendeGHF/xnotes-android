package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.draw.clip
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
import kotlin.math.max
import kotlin.math.min
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

internal data class Vec2Field(val p: String = "-y", val q: String = "x", val colorArgb: Int? = null, val visible: Boolean = true)
internal data class Vec3Field(val p: String = "-y", val q: String = "x", val r: String = "0.35z", val colorArgb: Int? = null, val visible: Boolean = true)

internal class FieldState {
    var mode by mutableStateOf(FieldMode.SCALAR_2D)
    var scalar2List by mutableStateOf(listOf(MathEntry("sin(x) * cos(y)")))
    var vector2List by mutableStateOf(listOf(Vec2Field()))
    var scalar3List by mutableStateOf(listOf(MathEntry("exp(-(x^2 + y^2 + z^2))")))
    var vector3List by mutableStateOf(listOf(Vec3Field()))
    var xRange by mutableStateOf("5")
    var yRange by mutableStateOf("5")
    var zRange by mutableStateOf("3")
    /** View centre: the sampled/rendered region moves here (item C). */
    var centerX by mutableStateOf("0")
    var centerY by mutableStateOf("0")
    var centerZ by mutableStateOf("0")
    var density by mutableFloatStateOf(12f)
    var showGrid by mutableStateOf(true)
    var level by mutableFloatStateOf(0.55f)
    /** When true the field is sampled only along a curve (2D) or surface (3D) instead of the grid. */
    var usePath2D by mutableStateOf(false)
    var pathX2 by mutableStateOf("cos(s)")
    var pathY2 by mutableStateOf("sin(s)")
    var pathS0 by mutableStateOf("0")
    var pathS1 by mutableStateOf("6.28")
    var usePath3D by mutableStateOf(false)
    var surfX3 by mutableStateOf("sin(u)*cos(v)")
    var surfY3 by mutableStateOf("sin(u)*sin(v)")
    var surfZ3 by mutableStateOf("cos(u)")
    var surfU0 by mutableStateOf("-3.14")
    var surfU1 by mutableStateOf("3.14")
    var probeX by mutableStateOf("0.5")
    var probeY by mutableStateOf("0.5")
    var probeZ by mutableStateOf("0.5")
    val orbit: OrbitState = OrbitState()
    val anim: AnimState = AnimState()
    var valueLo by mutableStateOf<Double?>(null)
    var valueHi by mutableStateOf<Double?>(null)

    fun resetView() {
        orbit.reset()
        xRange = "5"; yRange = "5"; zRange = "3"
        centerX = "0"; centerY = "0"; centerZ = "0"
    }
    fun addEntry() {
        when (mode) {
            FieldMode.SCALAR_2D -> if (scalar2List.size < 4) scalar2List += MathEntry("x^2 - y^2")
            FieldMode.VECTOR_2D -> if (vector2List.size < 4) vector2List += Vec2Field("y", "-x")
            FieldMode.SCALAR_3D -> if (scalar3List.size < 4) scalar3List += MathEntry("x^2 + y^2 - z")
            FieldMode.VECTOR_3D -> if (vector3List.size < 4) vector3List += Vec3Field("z", "x", "y")
        }
    }
    fun removeEntry(i: Int) {
        when (mode) {
            FieldMode.SCALAR_2D -> if (scalar2List.size > 1) scalar2List = scalar2List.toMutableList().also { it.removeAt(i) }
            FieldMode.VECTOR_2D -> if (vector2List.size > 1) vector2List = vector2List.toMutableList().also { it.removeAt(i) }
            FieldMode.SCALAR_3D -> if (scalar3List.size > 1) scalar3List = scalar3List.toMutableList().also { it.removeAt(i) }
            FieldMode.VECTOR_3D -> if (vector3List.size > 1) vector3List = vector3List.toMutableList().also { it.removeAt(i) }
        }
    }
    fun setEntryStyle(i: Int, colorArgb: Int?, visible: Boolean) {
        when (mode) {
            FieldMode.SCALAR_2D -> scalar2List = scalar2List.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
            FieldMode.VECTOR_2D -> vector2List = vector2List.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
            FieldMode.SCALAR_3D -> scalar3List = scalar3List.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
            FieldMode.VECTOR_3D -> vector3List = vector3List.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
        }
    }
    fun center(): Triple<Double, Double, Double> = Triple(
        centerX.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0,
        centerY.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0,
        centerZ.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0,
    )

    fun snapshot(): FieldSnap {
        val a = anim
        val lo = a.tMinStr.toDoubleOrNull(); val hi = a.tMaxStr.toDoubleOrNull()
        val range = if (a.enabled && lo != null && hi != null && lo.isFinite() && hi.isFinite() && lo != hi)
            AnimSnap(lo, hi, a.tStepsStr.toIntOrNull()?.coerceIn(2, 400) ?: 60, a.tCurrent, false) else null
        val (ccx, ccy, ccz) = center()
        val is3dSnap = mode == FieldMode.SCALAR_3D || mode == FieldMode.VECTOR_3D
        val onPath = if (is3dSnap) usePath3D else usePath2D
        fun num(text: String, fallback: Double): Double =
            text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback
        return FieldSnap(
            mode = mode,
            scalar2 = scalar2List.toList(), vector2 = vector2List.toList(),
            scalar3 = scalar3List.toList(), vector3 = vector3List.toList(),
            xRange = rangeOf(xRange), yRange = rangeOf(yRange), zRange = rangeOf(zRange, 3.0),
            density = density.toInt().coerceIn(6, 20),
            level = level.toDouble().coerceIn(0.02, 0.98),
            quat = orbit.quat, zoom = orbit.zoom,
            showGrid = showGrid,
            probeX = probeX, probeY = probeY, probeZ = probeZ,
            t = if (anim.enabled) anim.tCurrent else null,
            animRange = range,
            cx = ccx, cy = ccy, cz = ccz,
            usePath = onPath,
            pathX = if (is3dSnap) surfX3 else pathX2,
            pathY = if (is3dSnap) surfY3 else pathY2,
            pathZ = surfZ3,
            pathR0 = if (is3dSnap) num(surfU0, -3.14) else num(pathS0, 0.0),
            pathR1 = if (is3dSnap) num(surfU1, 3.14) else num(pathS1, 6.28),
        )
    }
}

internal class FieldSnap(
    val mode: FieldMode,
    val scalar2: List<MathEntry>,
    val vector2: List<Vec2Field>,
    val scalar3: List<MathEntry>,
    val vector3: List<Vec3Field>,
    val xRange: Double,
    val yRange: Double,
    val zRange: Double,
    val density: Int,
    val level: Double,
    val quat: Quat,
    val zoom: Float,
    val showGrid: Boolean = true,
    val probeX: String,
    val probeY: String,
    val probeZ: String,
    val t: Double? = null,
    val animRange: AnimSnap? = null,
    val cx: Double = 0.0,
    val cy: Double = 0.0,
    val cz: Double = 0.0,
    /** When true the field is sampled only along [pathX]/[pathY] (2D, parameter s) or the
     *  [surfX]/[surfY]/[surfZ] surface (3D, parameters u,v) instead of the full grid/cube. */
    val usePath: Boolean = false,
    val pathX: String = "",
    val pathY: String = "",
    val pathZ: String = "",
    val pathR0: Double = 0.0,
    val pathR1: Double = 6.28,
)

private class FieldRaster(val bitmap: Bitmap, val lo: Double, val hi: Double)
private class CloudPoint(val x: Double, val y: Double, val z: Double, val value: Double)
private class ScalarCloud(val points: List<CloudPoint>, val lo: Double, val hi: Double, val xRange: Double, val yRange: Double, val zRange: Double, val colorArgb: Int? = null)
private class Arrow3(val x: Double, val y: Double, val z: Double, val dx: Double, val dy: Double, val dz: Double, val mag: Double)
private class VectorCloud(val arrows: List<Arrow3>, val maxMag: Double, val xRange: Double, val yRange: Double, val zRange: Double, val colorArgb: Int? = null)
/** One entry's arrows on a shared surface, in that entry's colour. */
private class ArrowSet(val arrows: List<Arrow3>, val colorArgb: Int?)
/** Shared surface sampling behind on-surface arrows, for the coloured wireframe. */
private class SurfBase(val pts: Array<Array<Triple<Double, Double, Double>>>)
/** On-surface scalar grids with their value range. */
private class SurfField(val grids: List<SurfGrid>, val range: Pair<Double, Double>)
/** On-surface vector arrows with their magnitude and the shared surface. */
private class VecSurfField(val sets: List<ArrowSet>, val maxMag: Double, val base: SurfBase?)

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
    var animInserting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf("…") }
    val lo = state.valueLo
    val hi = state.valueHi
    val series = mathPlotColors()
    val baseLegend = fieldLegend(state, series)
    val legend = baseLegend + if (state.anim.enabled) listOf("t=${"%.3f".format(state.anim.tCurrent)}" to series[baseLegend.size % series.size]) else emptyList()
    val animLabel = stringResource(R.string.math_insert_animation)
    val animOn = state.anim.enabled
    AnimTicker(state.anim)
    LaunchedEffect(state.mode, state.scalar2List, state.vector2List, state.scalar3List, state.vector3List, state.probeX, state.probeY, state.probeZ, state.anim.tCurrent) {
        delay(180)
        val snap = state.snapshot()
        readout = withContext(Dispatchers.Default) { probeReadout(snap) }
    }
    GraphWorkspace(
        modes = FieldMode.entries.map { it.name to stringResource(it.labelRes) },
        selected = state.mode.name,
        onSelect = { state.mode = FieldMode.valueOf(it) },
        legend = legend,
        ramp = if (lo != null && hi != null && lo != hi) Numbers.tick(lo) to Numbers.tick(hi) else null,
        caption = if (state.anim.enabled) "t=${"%.3f".format(state.anim.tCurrent)} (insert captures current frame)" else null,
        showGrid = state.showGrid,
        onToggleGrid = { state.showGrid = !state.showGrid },
        onReset = state::resetView,
        inserting = inserting,
        onInsert = {
            if (inserting) return@GraphWorkspace
            val snap = state.snapshot()
            scope.launch {
                inserting = true; failed = false
                val bytes = withContext(Dispatchers.Default) { exportField(snap, theme, title, magnitude, heightLabel) }
                inserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        },
        animInsertLabel = if (animOn) animLabel else null,
        animInserting = animInserting,
        onInsertAnim = if (animOn) ({
            if (animInserting || inserting) return@GraphWorkspace
            val base = state.snapshot()
            scope.launch {
                animInserting = true; failed = false
                val bytes = withContext(Dispatchers.Default) { exportFieldAnimation(base, theme, title, magnitude, heightLabel) }
                animInserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        }) else null,
        status = if (failed) failedText else null,
        plot = { if (is3d) Field3DView(state, Modifier.fillMaxSize()) else Field2DView(state, Modifier.fillMaxSize()) },
        controls = {
            val colors = mathPlotColors()
            val palette = LocalPalette.current
            val onPath = if (is3d) state.usePath3D else state.usePath2D
            val eyeLabel = stringResource(R.string.math_toggle_visibility)
            val colorLabel = stringResource(R.string.math_pick_color)
            fun effectiveColor(index: Int, custom: Int?): androidx.compose.ui.graphics.Color =
                custom?.let { androidx.compose.ui.graphics.Color(it) } ?: colors[index % colors.size]
            MathToolSelector(
                listOf("grid" to "Grid", "path" to "On path"),
                selected = if (onPath) "path" else "grid",
                modifier = Modifier.fillMaxWidth(),
            ) { if (is3d) state.usePath3D = it == "path" else state.usePath2D = it == "path" }
            if (onPath) {
                if (!is3d) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MathTextField(state.pathX2, { state.pathX2 = it }, "x(s)", Modifier.weight(1f))
                        MathTextField(state.pathY2, { state.pathY2 = it }, "y(s)", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MathTextField(state.pathS0, { state.pathS0 = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "s min", Modifier.weight(1f))
                        MathTextField(state.pathS1, { state.pathS1 = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "s max", Modifier.weight(1f))
                    }
                    MathHint("The field is sampled only along the curve C(s).")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MathTextField(state.surfX3, { state.surfX3 = it }, "x(u,v)", Modifier.weight(1f))
                        MathTextField(state.surfY3, { state.surfY3 = it }, "y(u,v)", Modifier.weight(1f))
                        MathTextField(state.surfZ3, { state.surfZ3 = it }, "z(u,v)", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MathTextField(state.surfU0, { state.surfU0 = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "u,v min", Modifier.weight(1f))
                        MathTextField(state.surfU1, { state.surfU1 = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "u,v max", Modifier.weight(1f))
                    }
                    MathHint("The field is sampled only on the surface S(u,v).")
                }
            }
            when {
                !is3d && !isVector -> {
                    state.scalar2List.forEachIndexed { i, e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            EntryColorDot(e.colorArgb, effectiveColor(i, e.colorArgb), colorLabel) {
                                state.setEntryStyle(i, it, e.visible)
                            }
                            MathTextField(e.text, { state.scalar2List = state.scalar2List.toMutableList().also { list -> list[i] = e.copy(text = it) } }, stringResource(R.string.math_field_scalar_function) + " ${i + 1}", Modifier.weight(1f))
                            EntryEye(e.visible, eyeLabel) {
                                state.setEntryStyle(i, e.colorArgb, !e.visible)
                            }
                            if (state.scalar2List.size > 1) MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) { state.removeEntry(i) }
                        }
                    }
                    MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addEntry() }
                }
                !is3d -> {
                    state.vector2List.forEachIndexed { i, v ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            EntryColorDot(v.colorArgb, effectiveColor(i, v.colorArgb), colorLabel) {
                                state.setEntryStyle(i, it, v.visible)
                            }
                            MathTextField(v.p, { state.vector2List = state.vector2List.toMutableList().also { list -> list[i] = v.copy(p = it) } }, "P${i + 1}(x,y)", Modifier.weight(1f))
                            MathTextField(v.q, { state.vector2List = state.vector2List.toMutableList().also { list -> list[i] = v.copy(q = it) } }, "Q${i + 1}(x,y)", Modifier.weight(1f))
                            EntryEye(v.visible, eyeLabel) {
                                state.setEntryStyle(i, v.colorArgb, !v.visible)
                            }
                            if (state.vector2List.size > 1) MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) { state.removeEntry(i) }
                        }
                    }
                    MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addEntry() }
                }
                !isVector -> {
                    state.scalar3List.forEachIndexed { i, e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            EntryColorDot(e.colorArgb, effectiveColor(i, e.colorArgb), colorLabel) {
                                state.setEntryStyle(i, it, e.visible)
                            }
                            MathTextField(e.text, { state.scalar3List = state.scalar3List.toMutableList().also { list -> list[i] = e.copy(text = it) } }, stringResource(R.string.math_field_scalar_function_xyz) + " ${i + 1}", Modifier.weight(1f))
                            EntryEye(e.visible, eyeLabel) {
                                state.setEntryStyle(i, e.colorArgb, !e.visible)
                            }
                            if (state.scalar3List.size > 1) MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) { state.removeEntry(i) }
                        }
                    }
                    MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addEntry() }
                }
                else -> {
                    state.vector3List.forEachIndexed { i, v ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            EntryColorDot(v.colorArgb, effectiveColor(i, v.colorArgb), colorLabel) {
                                state.setEntryStyle(i, it, v.visible)
                            }
                            MathTextField(v.p, { state.vector3List = state.vector3List.toMutableList().also { list -> list[i] = v.copy(p = it) } }, "P${i + 1}", Modifier.weight(1f))
                            MathTextField(v.q, { state.vector3List = state.vector3List.toMutableList().also { list -> list[i] = v.copy(q = it) } }, "Q${i + 1}", Modifier.weight(1f))
                            MathTextField(v.r, { state.vector3List = state.vector3List.toMutableList().also { list -> list[i] = v.copy(r = it) } }, "R${i + 1}", Modifier.weight(1f))
                            EntryEye(v.visible, eyeLabel) {
                                state.setEntryStyle(i, v.colorArgb, !v.visible)
                            }
                            if (state.vector3List.size > 1) MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) { state.removeEntry(i) }
                        }
                    }
                    MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addEntry() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MathTextField(state.xRange, { state.xRange = filterRange(it) }, "x ±", Modifier.weight(1f))
                MathTextField(state.yRange, { state.yRange = filterRange(it) }, "y ±", Modifier.weight(1f))
                if (is3d) MathTextField(state.zRange, { state.zRange = filterRange(it) }, "z ±", Modifier.weight(1f))
            }
            MathCard(Modifier.fillMaxWidth()) {
                MathSectionTitle("Centre", Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MathTextField(state.centerX, { state.centerX = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "cx", Modifier.weight(1f))
                    MathTextField(state.centerY, { state.centerY = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "cy", Modifier.weight(1f))
                    if (is3d) MathTextField(state.centerZ, { state.centerZ = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "cz", Modifier.weight(1f))
                }
            }
            if (isVector || is3d) CommittedSlider(state.density, { state.density = it }, 6f..20f, stringResource(if (is3d) R.string.math_field_grid else R.string.math_field_density))
            if (state.mode == FieldMode.SCALAR_3D && !state.usePath3D) CommittedSlider(state.level, { state.level = it }, 0.02f..0.98f, stringResource(R.string.math_field_level), readout = { "%.2f".format(it) })
            AnimPanel(state.anim)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MathTextField(state.probeX, { state.probeX = it }, "x₀", Modifier.weight(1f))
                MathTextField(state.probeY, { state.probeY = it }, "y₀", Modifier.weight(1f))
                if (is3d) MathTextField(state.probeZ, { state.probeZ = it }, "z₀", Modifier.weight(1f))
            }
            MathResultCard(stringResource(R.string.math_field_probe), readout)
        },
    )
}

@Composable
private fun fieldLegend(state: FieldState, series: List<androidx.compose.ui.graphics.Color>): List<Pair<String, androidx.compose.ui.graphics.Color>> {
    fun eff(index: Int, custom: Int?): androidx.compose.ui.graphics.Color =
        custom?.let { androidx.compose.ui.graphics.Color(it) } ?: series[index % series.size]
    val base: List<Pair<String, androidx.compose.ui.graphics.Color>> = when (state.mode) {
        FieldMode.SCALAR_2D -> state.scalar2List.mapIndexedNotNull { i, e ->
            if (!e.visible) null else "f${i + 1}(x,y)=${e.text.ifBlank { "—" }}" to eff(i, e.colorArgb)
        }
        FieldMode.VECTOR_2D -> state.vector2List.mapIndexedNotNull { i, v ->
            if (!v.visible) null else "F${i + 1}=(P${i + 1},Q${i + 1})" to eff(i, v.colorArgb)
        }
        FieldMode.SCALAR_3D -> state.scalar3List.mapIndexedNotNull { i, e ->
            if (!e.visible) null else "f${i + 1}(x,y,z)=${e.text.ifBlank { "—" }}" to eff(i, e.colorArgb)
        }
        FieldMode.VECTOR_3D -> state.vector3List.mapIndexedNotNull { i, v ->
            if (!v.visible) null else "F${i + 1}=(P${i + 1},Q${i + 1},R${i + 1})" to eff(i, v.colorArgb)
        }
    }
    val is3d = state.mode == FieldMode.SCALAR_3D || state.mode == FieldMode.VECTOR_3D
    val onPath = if (is3d) state.usePath3D else state.usePath2D
    if (!onPath) return base
    return base + if (!is3d) listOf("C: x=${state.pathX2.ifBlank { "—" }}  y=${state.pathY2.ifBlank { "—" }}" to series[base.size % series.size])
    else listOf("S(u,v)" to series[base.size % series.size])
}

@Composable
private fun Field2DView(state: FieldState, modifier: Modifier = Modifier) {
    val theme = rememberGraphTheme()
    val mode = state.mode
    AnimTicker(state.anim)
    fun styleOf(text: String, colorArgb: Int?, visible: Boolean) = text + "" + (colorArgb?.toString() ?: "") + "" + (if (visible) "1" else "0")
    fun parseEntry(raw: String): MathEntry {
        val parts = raw.split("")
        return MathEntry(parts.getOrElse(0) { "" }, parts.getOrNull(1)?.toIntOrNull(), parts.getOrElse(2) { "1" } != "0")
    }
    val sKey = rememberSettled(state.scalar2List.joinToString("") { styleOf(it.text, it.colorArgb, it.visible) }, 180)
    val vKey = rememberSettled(state.vector2List.map { styleOf(it.p, it.colorArgb, it.visible) + "" + it.q }.joinToString(""), 180)
    val xRange = rememberSettled(rangeOf(state.xRange), 180)
    val yRange = rememberSettled(rangeOf(state.yRange), 180)
    val centerKey2 = rememberSettled(state.centerX + "|" + state.centerY, 180)
    val pathKey2 = rememberSettled(state.pathX2 + "|" + state.pathY2 + "|" + state.pathS0 + "|" + state.pathS1 + "|" + state.usePath2D, 180)
    val density = state.density.toInt().coerceIn(6, 20)
    val tKey = state.anim.tCurrent; val tOn = state.anim.enabled
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    LaunchedEffect(mode, sKey, vKey, xRange, yRange, centerKey2, pathKey2, density, view, theme, tKey, tOn, state.showGrid) {
        if (view.width < 2 || view.height < 2) return@LaunchedEffect
        val (ccx, ccy, _) = state.center()
        val snap = state.snapshot()
        val (w, h) = cappedSize(view.width, view.height, 640)
        val t = if (tOn) tKey else null
        val raster = withContext(Dispatchers.Default) {
            if (!snap.usePath) {
                if (mode == FieldMode.SCALAR_2D) renderScalar2Multi(sKey.split("").map(::parseEntry), xRange, yRange, w, h, theme, t, ccx, ccy, snap.showGrid)
                else renderVector2Multi(vKey.split("").map { val p = it.split(""); val head = parseEntry(p.getOrElse(0) { "" }); Vec2Field(head.text, p.getOrElse(1) { "" }, head.colorArgb, head.visible) }, xRange, yRange, density, w, h, theme, t, ccx, ccy, snap.showGrid)
            } else {
                if (mode == FieldMode.SCALAR_2D) renderScalarPath2D(snap.scalar2, snap, w, h, theme, t)
                else renderVectorPath2D(snap.vector2, snap, w, h, theme, t)
            }
        }
        state.valueLo = raster?.lo; state.valueHi = raster?.hi
        frame = raster?.bitmap?.asImageBitmap()
    }
    Box(modifier.clipToBounds().onSizeChanged { view = it }.pointerInput(Unit) {
        detectTransformGestures { _, pan, _, _ ->
            val xr = rangeOf(state.xRange); val yr = rangeOf(state.yRange)
            val (ccx, ccy, _) = state.center()
            state.centerX = "%.4f".format(ccx - pan.x / size.width * 2 * xr)
            state.centerY = "%.4f".format(ccy + pan.y / size.height * 2 * yr)
        }
    }) {
        frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (frame == null) Text(stringResource(R.string.math_3d_computing), color = LocalPalette.current.textDim.toComposeColor(), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Center))
        AnimPlayOverlay(state.anim)
    }
}

@Composable
private fun Field3DView(state: FieldState, modifier: Modifier = Modifier) {
    val theme = rememberGraphTheme()
    val mode = state.mode
    AnimTicker(state.anim)
    fun styleOf3(text: String, colorArgb: Int?, visible: Boolean) = text + "" + (colorArgb?.toString() ?: "") + "" + (if (visible) "1" else "0")
    fun parseEntry3(raw: String): MathEntry {
        val parts = raw.split("")
        return MathEntry(parts.getOrElse(0) { "" }, parts.getOrNull(1)?.toIntOrNull(), parts.getOrElse(2) { "1" } != "0")
    }
    val sKey = rememberSettled(state.scalar3List.joinToString("") { styleOf3(it.text, it.colorArgb, it.visible) }, 200)
    val vKey = rememberSettled(state.vector3List.map { styleOf3(it.p, it.colorArgb, it.visible) + "" + it.q + "" + it.r }.joinToString(""), 200)
    val xRange = rememberSettled(rangeOf(state.xRange), 180)
    val yRange = rememberSettled(rangeOf(state.yRange), 180)
    val zRange = rememberSettled(rangeOf(state.zRange, 3.0), 180)
    val density = state.density.toInt().coerceIn(6, 20)
    val level = state.level.toDouble()
    val centerKey3 = rememberSettled(state.centerX + "|" + state.centerY + "|" + state.centerZ, 200)
    val pathKey3 = rememberSettled(state.surfX3 + "|" + state.surfY3 + "|" + state.surfZ3 + "|" + state.surfU0 + "|" + state.surfU1 + "|" + state.usePath3D, 200)
    val tKey = state.anim.tCurrent; val tOn = state.anim.enabled
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    var scalarClouds by remember { mutableStateOf<List<ScalarCloud>?>(null) }
    var vectorClouds by remember { mutableStateOf<List<VectorCloud>?>(null) }
    var pathScalar by remember { mutableStateOf<SurfField?>(null) }
    var pathVector by remember { mutableStateOf<VecSurfField?>(null) }
    LaunchedEffect(mode, sKey, vKey, xRange, yRange, zRange, centerKey3, pathKey3, density, level, tKey, tOn, state.showGrid) {
        val t = if (tOn) tKey else null
        val snap = state.snapshot()
        val (ccx, ccy, ccz) = state.center()
        if (snap.usePath) {
            pathScalar = null; pathVector = null; scalarClouds = null; vectorClouds = null
            if (mode == FieldMode.SCALAR_3D) {
                val built = withContext(Dispatchers.Default) { scalarSurfGrids(snap, t) }
                pathScalar = built
                state.valueLo = built?.range?.first; state.valueHi = built?.range?.second
            } else {
                val built = withContext(Dispatchers.Default) { vectorSurfArrows(snap, t) }
                pathVector = built
                state.valueLo = 0.0; state.valueHi = built?.maxMag
            }
            return@LaunchedEffect
        }
        pathScalar = null; pathVector = null
        if (mode == FieldMode.SCALAR_3D) {
            val clouds = withContext(Dispatchers.Default) { sKey.split("").mapNotNull { val e = parseEntry3(it); if (!e.visible) null else buildScalar3(e, xRange, yRange, zRange, density.coerceIn(8, 16), level, t, ccx, ccy, ccz) } }
            scalarClouds = clouds; vectorClouds = null
            state.valueLo = clouds.minOfOrNull { it.lo }; state.valueHi = clouds.maxOfOrNull { it.hi }
        } else {
            val clouds = withContext(Dispatchers.Default) {
                vKey.split("").mapNotNull {
                    val p = it.split(""); val head = parseEntry3(p.getOrElse(0) { "" })
                    if (!head.visible) null else buildVectors3(Vec3Field(head.text, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }, head.colorArgb, true), xRange, yRange, zRange, density.coerceIn(4, 9), t, ccx, ccy, ccz)
                }
            }
            vectorClouds = clouds; scalarClouds = null
            state.valueLo = 0.0; state.valueHi = clouds.maxOfOrNull { it.maxMag }
        }
    }
    LaunchedEffect(scalarClouds, vectorClouds, pathScalar, pathVector, view, theme) {
        val sc = scalarClouds; val vc = vectorClouds; val ps = pathScalar; val pv = pathVector
        if (view.width < 2 || sc == null && vc == null && ps == null && pv == null) return@LaunchedEffect
        snapshotFlow { Triple(state.orbit.quat, state.orbit.zoom, state.centerX + state.centerY + state.centerZ) }.conflate().collect { (q, z, _) ->
            val (w, h) = cappedSize(view.width, view.height, 640)
            val (ccx, ccy, ccz) = state.center()
            val snap = state.snapshot()
            val bmp = withContext(Dispatchers.Default) {
                when {
                    ps != null -> rasterSurfGrids(ps.grids, ps.range.first, ps.range.second, q, z, w, h, theme, snap.xRange, snap.yRange, snap.zRange, ccx, ccy, ccz, snap.showGrid)
                    pv != null -> rasterSurfArrows(pv.sets, pv.maxMag, pv.base, q, z, w, h, theme, snap.xRange, snap.yRange, snap.zRange, ccx, ccy, ccz, snap.showGrid)
                    sc != null -> rasterScalar3Multi(sc, q, z, w, h, theme, ccx, ccy, ccz, snap.showGrid)
                    vc != null -> rasterVector3Multi(vc, q, z, w, h, theme, ccx, ccy, ccz, snap.showGrid)
                    else -> null
                }
            }
            if (bmp != null) frame = bmp.asImageBitmap()
            delay(28)
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { view = it }.pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ -> state.orbit.drag(pan.x, pan.y); state.orbit.zoom = (state.orbit.zoom * zoom).coerceIn(0.35f, 4f) }
    }) {
        frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (frame == null) Text(stringResource(R.string.math_3d_computing), color = LocalPalette.current.textDim.toComposeColor(), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Center))
        ViewPresetButtons(state.orbit)
        AnimPlayOverlay(state.anim)
    }
}

private suspend fun exportField(snap: FieldSnap, theme: GraphTheme, title: String, magnitude: String, heightLabel: String): ByteArray? = try {
    val raster = when {
        snap.usePath && snap.mode == FieldMode.SCALAR_2D ->
            renderScalarPath2D(snap.scalar2, snap, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, snap.t)
        snap.usePath && snap.mode == FieldMode.VECTOR_2D ->
            renderVectorPath2D(snap.vector2, snap, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, snap.t)
        snap.usePath && snap.mode == FieldMode.SCALAR_3D -> {
            val built = scalarSurfGrids(snap, snap.t) ?: return null
            rasterSurfGrids(built.grids, built.range.first, built.range.second, snap.quat, snap.zoom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme,
                snap.xRange, snap.yRange, snap.zRange, snap.cx, snap.cy, snap.cz, snap.showGrid)
                .let { FieldRaster(it, built.range.first, built.range.second) }
        }
        snap.usePath && snap.mode == FieldMode.VECTOR_3D -> {
            val built = vectorSurfArrows(snap, snap.t) ?: return null
            rasterSurfArrows(built.sets, built.maxMag, built.base, snap.quat, snap.zoom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme,
                snap.xRange, snap.yRange, snap.zRange, snap.cx, snap.cy, snap.cz, snap.showGrid)
                .let { FieldRaster(it, 0.0, built.maxMag) }
        }
        else -> when (snap.mode) {
        FieldMode.SCALAR_2D -> renderScalar2Multi(snap.scalar2, snap.xRange, snap.yRange, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, snap.t, snap.cx, snap.cy, snap.showGrid)
        FieldMode.VECTOR_2D -> renderVector2Multi(snap.vector2, snap.xRange, snap.yRange, snap.density, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, snap.t, snap.cx, snap.cy, snap.showGrid)
        FieldMode.SCALAR_3D -> {
            val clouds = snap.scalar3.filter { it.visible }.mapNotNull { buildScalar3(it, snap.xRange, snap.yRange, snap.zRange, snap.density.coerceIn(8, 16), snap.level, snap.t, snap.cx, snap.cy, snap.cz) }
            if (clouds.isEmpty()) return null
            rasterScalar3Multi(clouds, snap.quat, snap.zoom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, snap.cx, snap.cy, snap.cz, snap.showGrid)?.let { FieldRaster(it, clouds.minOf { c -> c.lo }, clouds.maxOf { c -> c.hi }) }
        }
        FieldMode.VECTOR_3D -> {
            val clouds = snap.vector3.filter { it.visible }.mapNotNull {
                buildVectors3(it, snap.xRange, snap.yRange, snap.zRange, snap.density.coerceIn(4, 9), snap.t, snap.cx, snap.cy, snap.cz)
            }
            if (clouds.isEmpty()) return null
            rasterVector3Multi(clouds, snap.quat, snap.zoom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme, snap.cx, snap.cy, snap.cz, snap.showGrid)?.let { FieldRaster(it, 0.0, clouds.maxOf { c -> c.maxMag }) }
        }
        }
    } ?: return null
    val shown: List<Pair<String, Int?>> = when (snap.mode) {
        FieldMode.SCALAR_2D -> snap.scalar2.filter { it.visible }.map { "f(x,y)=${it.text}" to it.colorArgb }
        FieldMode.VECTOR_2D -> snap.vector2.filter { it.visible }.map { v: Vec2Field -> "F=(P=${v.p}, Q=${v.q})" to v.colorArgb }
        FieldMode.SCALAR_3D -> snap.scalar3.filter { it.visible }.map { "f(x,y,z)=${it.text}" to it.colorArgb }
        FieldMode.VECTOR_3D -> snap.vector3.filter { it.visible }.map { v: Vec3Field -> "F=(P=${v.p}, Q=${v.q}, R=${v.r})" to v.colorArgb }
    }
    val labels = shown.map { it.first } + if (snap.t != null) listOf("t=${Numbers.tick(snap.t)}") else emptyList<String>() + if (snap.usePath) {
        if (snap.mode == FieldMode.SCALAR_3D || snap.mode == FieldMode.VECTOR_3D) listOf("on S(u,v)")
        else listOf("on C(s): x=${snap.pathX.ifBlank { "—" }} y=${snap.pathY.ifBlank { "—" }}")
    } else emptyList<String>()
    val rampName = if (snap.mode == FieldMode.VECTOR_2D || snap.mode == FieldMode.VECTOR_3D) magnitude else heightLabel
    val legend = shown.mapIndexed { index, (label, custom) -> ExportLegend(label, custom ?: theme.series[index % theme.series.size]) } + ExportLegend("$rampName  ${Numbers.tick(raster.lo)} → ${Numbers.tick(raster.hi)}", theme.series[0], ramp = true)
    val foot = buildString {
        append("x ∈ [±${Numbers.tick(snap.xRange)}]    y ∈ [±${Numbers.tick(snap.yRange)}]")
        if (snap.mode == FieldMode.SCALAR_3D || snap.mode == FieldMode.VECTOR_3D) append("    z ∈ [±${Numbers.tick(snap.zRange)}]")
        if (snap.usePath) {
            if (snap.mode == FieldMode.SCALAR_3D || snap.mode == FieldMode.VECTOR_3D) append("    on S(u,v) u,v ∈ [${Numbers.tick(snap.pathR0)}, ${Numbers.tick(snap.pathR1)}]")
            else append("    on C(s) s ∈ [${Numbers.tick(snap.pathR0)}, ${Numbers.tick(snap.pathR1)}]")
        }
        if (snap.cx != 0.0 || snap.cy != 0.0 || snap.cz != 0.0) {
            append("    c=(${Numbers.tick(snap.cx)}, ${Numbers.tick(snap.cy)}, ${Numbers.tick(snap.cz)})")
        }
        if (snap.t != null) append("    t=${Numbers.tick(snap.t)}")
        append("    ").append(probeReadout(snap))
    }
    writeGraphPng(raster.bitmap, title, legend, foot, theme)
} catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { null }

private fun fieldFrame(snap: FieldSnap, t: Double): FieldSnap =
    FieldSnap(snap.mode, snap.scalar2, snap.vector2, snap.scalar3, snap.vector3,
        snap.xRange, snap.yRange, snap.zRange, snap.density, snap.level,
        snap.quat, snap.zoom, snap.showGrid, snap.probeX, snap.probeY, snap.probeZ, t, snap.animRange,
        snap.cx, snap.cy, snap.cz, snap.usePath, snap.pathX, snap.pathY, snap.pathZ, snap.pathR0, snap.pathR1)

private suspend fun exportFieldAnimation(base: FieldSnap, theme: GraphTheme, title: String, magnitude: String, heightLabel: String): ByteArray? {
    return try {
    val range = base.animRange ?: return null
    val ts = animFrameTs(range.lo, range.hi, range.steps, 8)
    val fw = 480; val fh = 300
    val frames = ArrayList<Pair<Bitmap, String>>(ts.size)
    for (t in ts) {
        coroutineContext.ensureActive()
        val snap = fieldFrame(base, t)
        val raster = when {
            snap.usePath && snap.mode == FieldMode.SCALAR_2D ->
                renderScalarPath2D(snap.scalar2, snap, fw, fh, theme, t)
            snap.usePath && snap.mode == FieldMode.VECTOR_2D ->
                renderVectorPath2D(snap.vector2, snap, fw, fh, theme, t)
            snap.usePath && snap.mode == FieldMode.SCALAR_3D -> {
                val built = scalarSurfGrids(snap, t) ?: continue
                rasterSurfGrids(built.grids, built.range.first, built.range.second, snap.quat, snap.zoom, fw, fh, theme,
                    snap.xRange, snap.yRange, snap.zRange, snap.cx, snap.cy, snap.cz, snap.showGrid)
                    .let { FieldRaster(it, built.range.first, built.range.second) }
            }
            snap.usePath && snap.mode == FieldMode.VECTOR_3D -> {
                val built = vectorSurfArrows(snap, t) ?: continue
                rasterSurfArrows(built.sets, built.maxMag, built.base, snap.quat, snap.zoom, fw, fh, theme,
                    snap.xRange, snap.yRange, snap.zRange, snap.cx, snap.cy, snap.cz, snap.showGrid)
                    .let { FieldRaster(it, 0.0, built.maxMag) }
            }
            else -> when (snap.mode) {
            FieldMode.SCALAR_2D -> renderScalar2Multi(snap.scalar2, snap.xRange, snap.yRange, fw, fh, theme, t, snap.cx, snap.cy, snap.showGrid)
            FieldMode.VECTOR_2D -> renderVector2Multi(snap.vector2, snap.xRange, snap.yRange, minOf(snap.density, 12), fw, fh, theme, t, snap.cx, snap.cy, snap.showGrid)
            FieldMode.SCALAR_3D -> {
                val clouds = snap.scalar3.filter { it.visible }.mapNotNull { buildScalar3(it, snap.xRange, snap.yRange, snap.zRange, minOf(snap.density, 12), snap.level, t, snap.cx, snap.cy, snap.cz) }
                if (clouds.isEmpty()) null
                else rasterScalar3Multi(clouds, snap.quat, snap.zoom, fw, fh, theme, snap.cx, snap.cy, snap.cz, snap.showGrid)?.let { FieldRaster(it, clouds.minOf { c -> c.lo }, clouds.maxOf { c -> c.hi }) }
            }
            FieldMode.VECTOR_3D -> {
                val clouds = snap.vector3.filter { it.visible }.mapNotNull { buildVectors3(it, snap.xRange, snap.yRange, snap.zRange, minOf(snap.density, 7), t, snap.cx, snap.cy, snap.cz) }
                if (clouds.isEmpty()) null
                else rasterVector3Multi(clouds, snap.quat, snap.zoom, fw, fh, theme, snap.cx, snap.cy, snap.cz, snap.showGrid)?.let { FieldRaster(it, 0.0, clouds.maxOf { c -> c.maxMag }) }
            }
            }
        } ?: continue
        frames += raster.bitmap to "t=${Numbers.tick(t)}"
    }
    if (frames.isEmpty()) return null
    val shownBase: List<Pair<String, Int?>> = when (base.mode) {
        FieldMode.SCALAR_2D -> base.scalar2.filter { it.visible }.map { "f(x,y)=${it.text}" to it.colorArgb }
        FieldMode.VECTOR_2D -> base.vector2.filter { it.visible }.map { "F=(P=${it.p}, Q=${it.q})" to it.colorArgb }
        FieldMode.SCALAR_3D -> base.scalar3.filter { it.visible }.map { "f(x,y,z)=${it.text}" to it.colorArgb }
        FieldMode.VECTOR_3D -> base.vector3.filter { it.visible }.map { "F=(P=${it.p}, Q=${it.q}, R=${it.r})" to it.colorArgb }
    }
    val labels = shownBase.map { it.first }
    val rampName = if (base.mode == FieldMode.VECTOR_2D || base.mode == FieldMode.VECTOR_3D) magnitude else heightLabel
    // lo/hi across frames unknown cheaply; label ramp generically.
    val legend = shownBase.mapIndexed { i, (l, custom) -> ExportLegend(l, custom ?: theme.series[i % theme.series.size]) } + ExportLegend(rampName, theme.series[0], ramp = true)
    val c = if (base.cx != 0.0 || base.cy != 0.0 || base.cz != 0.0) "    c=(${Numbers.tick(base.cx)}, ${Numbers.tick(base.cy)}, ${Numbers.tick(base.cz)})" else ""
    val foot = "frames=${ts.size} t∈[${Numbers.tick(minOf(range.lo, range.hi))}, ${Numbers.tick(maxOf(range.lo, range.hi))}]$c    ${probeReadout(fieldFrame(base, ts.last()))}"
    buildAnimationPng(frames, title, legend, foot, theme, specJson = MathAnimSpec.captureFields(base)?.toJson())
} catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { null }
}

private suspend fun renderScalar2Multi(entries: List<MathEntry>, xRange: Double, yRange: Double, width: Int, height: Int, theme: GraphTheme, t: Double?, cx: Double = 0.0, cy: Double = 0.0, grid: Boolean = true): FieldRaster? {
    val parsed = entries.map { e ->
        if (!e.visible) null else runCatching { ExpressionParser.parse(e.text) }.getOrNull()?.let { it to e }
    }
    if (parsed.all { it == null }) return null
    val n = 110
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    val nanGrid = Array(n) { DoubleArray(n) { Double.NaN } }
    val allValues = parsed.map { pe ->
        if (pe == null) nanGrid else {
            Array(n) { r ->
                coroutineContext.ensureActive()
                DoubleArray(n) { c ->
                    val x = cx - xRange + 2 * xRange * c / (n - 1); val y = cy + yRange - 2 * yRange * r / (n - 1)
                    bindXY(scope, x, y); evalReal(scope, pe.first)
                }
            }
        }
    }
    val heatIdx = parsed.indexOfFirst { it != null }.coerceAtLeast(0)
    val (lo, hi) = robustRange(allValues[heatIdx])
    val bmp = heatBitmap(allValues[heatIdx], lo, hi, width, height, theme.paper)
    val canvas = android.graphics.Canvas(bmp)
    // Contours for every visible entry: its own colour, else first ink and rest series.
    val x0 = cx - xRange; val x1 = cx + xRange; val y0 = cy - yRange; val y1 = cy + yRange
    allValues.forEachIndexed { idx, values ->
        val pe = parsed[idx] ?: return@forEachIndexed
        val (l, h) = robustRange(values)
        val contours = ArrayList<WorldSegment>()
        for (k in 1 until 8) contours += marchingSquares(values, n, n, x0, x1, y1, y0, l + (h - l) * k / 8)
        val col = pe.second.colorArgb ?: if (idx == 0) theme.ink else theme.series[idx % theme.series.size]
        drawWorldOn(canvas, contours, col, x0, x1, y0, y1, bmp.width, bmp.height)
    }
    if (grid) drawPlotFrame(canvas, bmp.width, bmp.height, x0, x1, y0, y1, theme, true)
    else drawAxes2(canvas, x0, x1, y0, y1, bmp.width, bmp.height, theme.axis)
    return FieldRaster(bmp, lo, hi)
}

private suspend fun renderVector2Multi(fields: List<Vec2Field>, xRange: Double, yRange: Double, density: Int, width: Int, height: Int, theme: GraphTheme, t: Double?, cx: Double = 0.0, cy: Double = 0.0, grid: Boolean = true): FieldRaster? {
    val visible = fields.filter { it.visible }
    val parsed = visible.mapNotNull { f ->
        val pe = runCatching { ExpressionParser.parse(f.p) }.getOrNull() ?: return@mapNotNull null
        val qe = runCatching { ExpressionParser.parse(f.q) }.getOrNull() ?: return@mapNotNull null
        Triple(pe, qe, f)
    }
    if (parsed.isEmpty()) return null
    val n = 80
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    val (pe0, qe0, _) = parsed.first()
    val x0 = cx - xRange; val x1 = cx + xRange; val y0 = cy - yRange; val y1 = cy + yRange
    val values = Array(n) { r ->
        coroutineContext.ensureActive()
        DoubleArray(n) { c ->
            val x = x0 + 2 * xRange * c / (n - 1); val y = y1 - 2 * yRange * r / (n - 1)
            bindXY(scope, x, y)
            val pv = evalReal(scope, pe0); val qv = evalReal(scope, qe0)
            if (pv.isFinite() && qv.isFinite()) sqrt(pv * pv + qv * qv) else Double.NaN
        }
    }
    val (lo, hi) = robustRange(values)
    val bmp = heatBitmap(values, lo, hi, width, height, theme.paper)
    val canvas = android.graphics.Canvas(bmp)
    var maxMag = 0.0
    val count = density.coerceIn(6, 20)
    data class A(val x: Double, val y: Double, val dx: Double, val dy: Double, val mag: Double, val idx: Int)
    val arrows = ArrayList<A>()
    for ((idx, triple) in parsed.withIndex()) {
        val (pe, qe, _) = triple
        for (j in 0 until count) {
            coroutineContext.ensureActive()
            for (i in 0 until count) {
                // Offset each field slightly so arrows don't perfectly overlap.
                val ox = (idx - (parsed.size - 1) / 2.0) * (2 * xRange / count) * 0.18
                val oy = (idx - (parsed.size - 1) / 2.0) * (2 * yRange / count) * 0.18
                val x = x0 + 2 * xRange * (i + 0.5) / count + ox
                val y = y1 - 2 * yRange * (j + 0.5) / count + oy
                bindXY(scope, x, y)
                val pv = evalReal(scope, pe); val qv = evalReal(scope, qe)
                if (!pv.isFinite() || !qv.isFinite()) continue
                val mag = sqrt(pv * pv + qv * qv)
                if (!mag.isFinite()) continue
                maxMag = max(maxMag, mag)
                arrows += A(x, y, pv, qv, mag, idx)
            }
        }
    }
    if (grid) drawPlotFrame(canvas, bmp.width, bmp.height, x0, x1, y0, y1, theme, true)
    else drawAxes2(canvas, x0, x1, y0, y1, bmp.width, bmp.height, theme.axis)
    val entryColors: List<Int?> = parsed.map { (_, _, f) -> f.colorArgb }
    drawArrows2Multi(canvas, arrows.map { doubleArrayOf(it.x, it.y, it.dx, it.dy, it.mag) to it.idx }, maxMag, x0, x1, y0, y1, count, bmp.width, bmp.height, theme, entryColors)
    return FieldRaster(bmp, 0.0, maxMag.coerceAtLeast(hi))
}

private fun heatBitmap(values: Array<DoubleArray>, lo: Double, hi: Double, width: Int, height: Int, paper: Int): Bitmap {
    val n = values.size
    val small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(n * n)
    val span = (hi - lo).coerceAtLeast(1e-12)
    for (r in 0 until n) for (c in 0 until n) {
        val v = values[r][c]
        pixels[r * n + c] = if (!v.isFinite()) paper else heightColor(((v - lo) / span).coerceIn(0.0, 1.0)).toArgb()
    }
    small.setPixels(pixels, 0, n, 0, 0, n, n)
    val w = width.coerceIn(2, 1400); val h = height.coerceIn(2, 1400)
    val large = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(large)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(small, null, android.graphics.Rect(0, 0, w, h), paint)
    small.recycle()
    return large
}

private fun drawAxes2(canvas: android.graphics.Canvas, x0: Double, x1: Double, y0: Double, y1: Double, w: Int, h: Int, color: Int) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 2f; alpha = 170 }
    val spanX = (x1 - x0).takeIf { it != 0.0 } ?: 1.0
    val spanY = (y1 - y0).takeIf { it != 0.0 } ?: 1.0
    val sx = (((0.0 - x0) / spanX).toFloat().coerceIn(0f, 1f) * w)
    val sy = (((y1 - 0.0) / spanY).toFloat().coerceIn(0f, 1f) * h)
    canvas.drawLine(sx, 0f, sx, h.toFloat(), paint); canvas.drawLine(0f, sy, w.toFloat(), sy, paint)
}

private fun drawWorldOn(canvas: android.graphics.Canvas, segments: List<WorldSegment>, color: Int, x0: Double, x1: Double, y0: Double, y1: Double, w: Int, h: Int) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.8f; alpha = 170 }
    val path = android.graphics.Path()
    val spanX = (x1 - x0).takeIf { it != 0.0 } ?: 1.0
    val spanY = (y1 - y0).takeIf { it != 0.0 } ?: 1.0
    fun sx(x: Double) = (((x - x0) / spanX) * w).toFloat()
    fun sy(y: Double) = (((y1 - y) / spanY) * h).toFloat()
    for (segment in segments) { path.moveTo(sx(segment.x1), sy(segment.y1)); path.lineTo(sx(segment.x2), sy(segment.y2)) }
    canvas.drawPath(path, paint)
}

private fun drawArrows2Multi(canvas: android.graphics.Canvas, arrows: List<Pair<DoubleArray, Int>>, maxMag: Double, x0: Double, x1: Double, y0: Double, y1: Double, density: Int, w: Int, h: Int, theme: GraphTheme, entryColors: List<Int?>? = null) {
    if (maxMag <= 1e-12) return
    val cell = min(w.toFloat() / density, h.toFloat() / density)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.6f; strokeCap = android.graphics.Paint.Cap.ROUND }
    val spanX = (x1 - x0).takeIf { it != 0.0 } ?: 1.0
    val spanY = (y1 - y0).takeIf { it != 0.0 } ?: 1.0
    fun sx(x: Double) = (((x - x0) / spanX) * w).toFloat()
    fun sy(y: Double) = (((y1 - y) / spanY) * h).toFloat()
    for ((arrow, idx) in arrows) {
        val x = arrow[0]; val y = arrow[1]; val dx = arrow[2]; val dy = arrow[3]; val mag = arrow[4]
        if (mag <= 1e-12) continue
        val x0 = sx(x); val y0 = sy(y)
        val pixels = cell * 0.7f * (mag / maxMag).toFloat()
        val x1 = x0 + (dx / mag * pixels).toFloat(); val y1 = y0 - (dy / mag * pixels).toFloat()
        val vx = x1 - x0; val vy = y1 - y0
        val len = sqrt(vx * vx + vy * vy)
        if (len < 1.4f) continue
        paint.color = entryColors?.getOrNull(idx) ?: if (arrows.distinctBy { it.second }.size <= 1) heightArgb((mag / maxMag).coerceIn(0.15, 0.95)) else theme.series[idx % theme.series.size]
        canvas.drawLine(x0, y0, x1, y1, paint)
        val ux = vx / len; val uy = vy / len
        val head = (cell * 0.22f).coerceIn(3f, 9f)
        canvas.drawLine(x1, y1, x1 - ux * head - uy * head * 0.55f, y1 - uy * head + ux * head * 0.55f, paint)
        canvas.drawLine(x1, y1, x1 - ux * head + uy * head * 0.55f, y1 - uy * head - ux * head * 0.55f, paint)
    }
}

/** Samples the 2D path curve C(s) = [pathX, pathY] over [s0, s1]; the path parameter is `s`
 *  so it never clashes with the animation parameter `t`, which path expressions may also use. */
private suspend fun samplePathCurve(
    snap: FieldSnap, n: Int, t: Double?,
): Pair<List<Double>, List<Double>>? {
    val xe = runCatching { ExpressionParser.parse(snap.pathX) }.getOrNull() ?: return null
    val ye = runCatching { ExpressionParser.parse(snap.pathY) }.getOrNull() ?: return null
    val s0 = snap.pathR0; val s1 = snap.pathR1
    if (!s0.isFinite() || !s1.isFinite() || s0 == s1 || abs(s1 - s0) > 1e6) return null
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    val xs = ArrayList<Double>(n + 1); val ys = ArrayList<Double>(n + 1)
    for (i in 0..n) {
        if (i % 32 == 0) coroutineContext.ensureActive()
        val s = s0 + (s1 - s0) * i / n
        scope.vars["s"] = com.xnotes.math.Complex.real(s)
        val x = evalReal(scope, xe); val y = evalReal(scope, ye)
        xs += x; ys += y
    }
    return xs to ys
}

/** Scalar field drawn only along its curve: each field becomes a ramp-coloured polyline. */
private suspend fun renderScalarPath2D(
    entries: List<MathEntry>, snap: FieldSnap, width: Int, height: Int, theme: GraphTheme, t: Double?,
): FieldRaster? {
    val exprs = entries.filter { it.visible }.mapNotNull { runCatching { ExpressionParser.parse(it.text) }.getOrNull() }
    if (exprs.isEmpty()) return null
    val n = (snap.density * 16).coerceIn(48, 320)
    val (xs, ys) = samplePathCurve(snap, n, t) ?: return null
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    val allVals = exprs.map { expr ->
        DoubleArray(n + 1) { i ->
            val x = xs[i]; val y = ys[i]
            if (!x.isFinite() || !y.isFinite()) Double.NaN
            else { bindXY(scope, x, y); evalReal(scope, expr) }
        }
    }
    val finite = allVals.flatMap { it.asIterable() }.filter { it.isFinite() }.sorted()
    if (finite.isEmpty()) return null
    val lo = finite[(finite.size * 0.015).toInt().coerceIn(0, finite.lastIndex)]
    val hi = finite[(finite.size * 0.985).toInt().coerceIn(0, finite.lastIndex)]
    val (loF, hiF) = if (hi <= lo) lo - 0.5 to lo + 0.5 else lo to hi
    val span = (hiF - loF).coerceAtLeast(1e-12)
    val bmp = Bitmap.createBitmap(width.coerceIn(2, 1400), height.coerceIn(2, 1400), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val x0 = snap.cx - snap.xRange; val x1 = snap.cx + snap.xRange
    val y0 = snap.cy - snap.yRange; val y1 = snap.cy + snap.yRange
    if (snap.showGrid) drawPlotFrame(canvas, bmp.width, bmp.height, x0, x1, y0, y1, theme, true)
    else drawAxes2(canvas, x0, x1, y0, y1, bmp.width, bmp.height, theme.axis)
    val spanX = (x1 - x0).takeIf { it != 0.0 } ?: 1.0
    val spanY = (y1 - y0).takeIf { it != 0.0 } ?: 1.0
    fun sx(x: Double) = (((x - x0) / spanX) * bmp.width).toFloat()
    fun sy(y: Double) = (((y1 - y) / spanY) * bmp.height).toFloat()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = (min(bmp.width, bmp.height) * 0.012f).coerceIn(3f, 7f)
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val offPx = (min(bmp.width, bmp.height) * 0.012f).coerceIn(3f, 7f) * 1.1f
    allVals.forEachIndexed { idx, vals ->
        val lane = (idx - (allVals.size - 1) / 2.0) * offPx
        for (i in 0 until n) {
            if (!xs[i].isFinite() || !ys[i].isFinite() || !xs[i + 1].isFinite() || !ys[i + 1].isFinite()) continue
            val a = vals[i]; val b = vals[i + 1]
            if (!a.isFinite() || !b.isFinite()) continue
            var dx = sx(xs[i + 1]) - sx(xs[i]); var dy = sy(ys[i + 1]) - sy(ys[i])
            val len = sqrt(dx * dx + dy * dy)
            var ox = 0f; var oy = 0f
            if (len > 1e-6f) { dx /= len; dy /= len; ox = (-dy * lane).toFloat(); oy = (dx * lane).toFloat() }
            paint.color = heightArgb((((a + b) / 2 - loF) / span).coerceIn(0.0, 1.0))
            canvas.drawLine(sx(xs[i]) + ox, sy(ys[i]) + oy, sx(xs[i + 1]) + ox, sy(ys[i + 1]) + oy, paint)
        }
    }
    return FieldRaster(bmp, loF, hiF)
}

/** Vector field drawn only along its curve: arrows ride the path instead of filling the grid. */
private suspend fun renderVectorPath2D(
    fields: List<Vec2Field>, snap: FieldSnap, width: Int, height: Int, theme: GraphTheme, t: Double?,
): FieldRaster? {
    val visible = fields.filter { it.visible }
    val parsed = visible.mapNotNull { f ->
        val pe = runCatching { ExpressionParser.parse(f.p) }.getOrNull() ?: return@mapNotNull null
        val qe = runCatching { ExpressionParser.parse(f.q) }.getOrNull() ?: return@mapNotNull null
        Triple(pe, qe, f)
    }
    if (parsed.isEmpty()) return null
    val m = snap.density.coerceIn(6, 20)
    val (xs, ys) = samplePathCurve(snap, (m * 16).coerceIn(48, 320), t) ?: return null
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    val x0 = snap.cx - snap.xRange; val x1 = snap.cx + snap.xRange
    val y0 = snap.cy - snap.yRange; val y1 = snap.cy + snap.yRange
    val bmp = Bitmap.createBitmap(width.coerceIn(2, 1400), height.coerceIn(2, 1400), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    data class A(val x: Double, val y: Double, val dx: Double, val dy: Double, val mag: Double, val idx: Int)
    val arrows = ArrayList<A>()
    var maxMag = 0.0
    for ((idx, triple) in parsed.withIndex()) {
        val (pe, qe, _) = triple
        for (j in 0 until m) {
            val i = ((j + 0.5) * xs.size / m).toInt().coerceIn(0, xs.size - 1)
            val x = xs[i]; val y = ys[i]
            if (!x.isFinite() || !y.isFinite()) continue
            bindXY(scope, x, y)
            val pv = evalReal(scope, pe); val qv = evalReal(scope, qe)
            if (!pv.isFinite() || !qv.isFinite()) continue
            val mag = sqrt(pv * pv + qv * qv)
            if (!mag.isFinite() || mag <= 1e-12) continue
            maxMag = max(maxMag, mag)
            arrows += A(x, y, pv, qv, mag, idx)
        }
    }
    if (snap.showGrid) drawPlotFrame(canvas, bmp.width, bmp.height, x0, x1, y0, y1, theme, true)
    else drawAxes2(canvas, x0, x1, y0, y1, bmp.width, bmp.height, theme.axis)
    // The curve itself, one lane per entry in that entry's colour, so arrows read as riding it.
    run {
        val spanX = ((x1 - x0).takeIf { it != 0.0 } ?: 1.0); val spanY = ((y1 - y0).takeIf { it != 0.0 } ?: 1.0)
        val (txs, tys) = samplePathCurve(snap, 160, t) ?: (emptyList<Double>() to emptyList())
        val lanePx = (minOf(bmp.width, bmp.height) * 0.012f).coerceIn(3f, 7f) * 1.1f
        val trace = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = lanePx; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        parsed.forEachIndexed { idx, (_, _, f) ->
            val lane = (idx - (parsed.size - 1) / 2.0) * lanePx
            trace.color = f.colorArgb ?: theme.series[idx % theme.series.size]
            val path = android.graphics.Path()
            var started = false
            var pxPrev = 0f; var pyPrev = 0f
            for (i in txs.indices) {
                val x = txs[i]; val y = tys[i]
                if (!x.isFinite() || !y.isFinite()) { started = false; continue }
                var px = (((x - x0) / spanX) * bmp.width).toFloat()
                var py = (((y1 - y) / spanY) * bmp.height).toFloat()
                if (started) {
                    var dx = px - pxPrev; var dy = py - pyPrev
                    val len = sqrt(dx * dx + dy * dy)
                    var ox = 0f; var oy = 0f
                    if (len > 1e-6f) { dx /= len; dy /= len; ox = (-dy * lane).toFloat(); oy = (dx * lane).toFloat() }
                    path.moveTo(pxPrev + ox, pyPrev + oy); path.lineTo(px + ox, py + oy)
                }
                pxPrev = px; pyPrev = py; started = true
            }
            canvas.drawPath(path, trace)
        }
    }
    val entryColors: List<Int?> = parsed.map { (_, _, f) -> f.colorArgb }
    drawArrows2Multi(
        canvas,
        arrows.map { doubleArrayOf(it.x, it.y, it.dx, it.dy, it.mag) to it.idx },
        maxMag, x0, x1, y0, y1, m, bmp.width, bmp.height, theme, entryColors,
    )
    return FieldRaster(bmp, 0.0, maxMag.coerceAtLeast(1e-12))
}

private suspend fun buildScalar3(entry: MathEntry, xRange: Double, yRange: Double, zRange: Double, density: Int, levelFraction: Double, t: Double?, cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0): ScalarCloud? {
    val expr = runCatching { ExpressionParser.parse(entry.text) }.getOrNull() ?: return null
    val n = density.coerceIn(8, 16)
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    val values = ArrayList<Pair<CloudPoint, Double>>(n * n * n)
    for (k in 0 until n) {
        coroutineContext.ensureActive()
        for (j in 0 until n) for (i in 0 until n) {
            val x = cx - xRange + 2 * xRange * (i + 0.5) / n; val y = cy - yRange + 2 * yRange * (j + 0.5) / n; val z = cz - zRange + 2 * zRange * (k + 0.5) / n
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
    if (points.size > 2600) { val step = points.size / 2600 + 1; points = points.filterIndexed { index, _ -> index % step == 0 } }
    return ScalarCloud(points, lo, hi, xRange, yRange, zRange, entry.colorArgb)
}

private suspend fun buildVectors3(field: Vec3Field, xRange: Double, yRange: Double, zRange: Double, density: Int, t: Double?, cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0): VectorCloud? {
    val pe = runCatching { ExpressionParser.parse(field.p) }.getOrNull() ?: return null
    val qe = runCatching { ExpressionParser.parse(field.q) }.getOrNull() ?: return null
    val re = runCatching { ExpressionParser.parse(field.r) }.getOrNull() ?: return null
    val n = density.coerceIn(4, 9)
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    val arrows = ArrayList<Arrow3>(n * n * n)
    var maxMag = 0.0
    for (k in 0 until n) {
        coroutineContext.ensureActive()
        for (j in 0 until n) for (i in 0 until n) {
            val x = cx - xRange + 2 * xRange * (i + 0.5) / n; val y = cy - yRange + 2 * yRange * (j + 0.5) / n; val z = cz - zRange + 2 * zRange * (k + 0.5) / n
            bindXYZ(scope, x, y, z)
            val pv = evalReal(scope, pe); val qv = evalReal(scope, qe); val rv = evalReal(scope, re)
            if (!pv.isFinite() || !qv.isFinite() || !rv.isFinite()) continue
            val mag = sqrt(pv * pv + qv * qv + rv * rv)
            if (!mag.isFinite()) continue
            maxMag = max(maxMag, mag)
            arrows += Arrow3(x, y, z, pv, qv, rv, mag)
        }
    }
    return VectorCloud(arrows, maxMag, xRange, yRange, zRange, field.colorArgb)
}

private fun quatProjector(w: Int, h: Int, q: Quat, zoom: Float, xR: Double, yR: Double, zR: Double, cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0): (Double, Double, Double) -> Triple<Float, Float, Float> {
    val m = q.matrix()
    val px = w / 2f; val py = h * 0.54f; val scale = min(w, h) * 0.36f * zoom
    return { x, y, z ->
        val nx = ((x - cx) / xR).coerceIn(-1.6, 1.6); val ny = ((y - cy) / yR).coerceIn(-1.6, 1.6); val nz = ((z - cz) / zR).coerceIn(-1.6, 1.6)
        val rx = m[0] * nx + m[1] * ny + m[2] * nz; val ry = m[3] * nx + m[4] * ny + m[5] * nz; val rz = m[6] * nx + m[7] * ny + m[8] * nz
        Triple((px + rx * scale).toFloat(), (py - ry * scale).toFloat(), rz.toFloat())
    }
}

private fun rasterScalar3Multi(clouds: List<ScalarCloud>, quat: Quat, zoom: Float, width: Int, height: Int, theme: GraphTheme, cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0, showGrid: Boolean = true): Bitmap {
    val w = width.coerceIn(2, 1400); val h = height.coerceIn(2, 1400)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val xR = clouds.maxOf { it.xRange }; val yR = clouds.maxOf { it.yRange }; val zR = clouds.maxOf { it.zRange }
    val proj = quatProjector(w, h, quat, zoom, xR, yR, zR, cx, cy, cz)
    drawQuatSpaceFrame(canvas, proj, quat, cx, cy, cz, xR, yR, zR, w, h, theme, showGrid)
    data class Dot(val x: Float, val y: Float, val d: Float, val c: Int)
    val dots = ArrayList<Dot>()
    for (cloud in clouds) {
        val span = (cloud.hi - cloud.lo).coerceAtLeast(1e-12)
        for (pt in cloud.points) {
            val pr = proj(pt.x, pt.y, pt.z)
            val col = cloud.colorArgb ?: heightArgb(((pt.value - cloud.lo) / span).coerceIn(0.0, 1.0))
            dots += Dot(pr.first, pr.second, pr.third, col)
        }
    }
    dots.sortBy { it.d }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    for (d in dots) { paint.color = d.c; canvas.drawCircle(d.x, d.y, 4.0f, paint) }
    return bmp
}

private fun rasterVector3Multi(clouds: List<VectorCloud>, quat: Quat, zoom: Float, width: Int, height: Int, theme: GraphTheme, cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0, showGrid: Boolean = true): Bitmap {
    val w = width.coerceIn(2, 1400); val h = height.coerceIn(2, 1400)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val xR = clouds.maxOf { it.xRange }; val yR = clouds.maxOf { it.yRange }; val zR = clouds.maxOf { it.zRange }
    val proj = quatProjector(w, h, quat, zoom, xR, yR, zR, cx, cy, cz)
    drawQuatSpaceFrame(canvas, proj, quat, cx, cy, cz, xR, yR, zR, w, h, theme, showGrid)
    val maxMag = clouds.maxOfOrNull { it.maxMag }?.coerceAtLeast(1e-12) ?: 1.0
    val scale = min(xR, min(yR, zR)) * 0.42 / maxMag
    data class L(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val d: Float, val c: Int)
    val lines = ArrayList<L>()
    for ((ci, cloud) in clouds.withIndex()) {
        for (a in cloud.arrows) {
            val s = proj(a.x, a.y, a.z); val e = proj(a.x + a.dx * scale, a.y + a.dy * scale, a.z + a.dz * scale)
            val vx = e.first - s.first; val vy = e.second - s.second
            if (sqrt(vx * vx + vy * vy) < 1.5f) continue
            val col = cloud.colorArgb ?: if (clouds.size <= 1) heightArgb((a.mag / maxMag).coerceIn(0.12, 0.95)) else theme.series[ci % theme.series.size]
            lines += L(s.first, s.second, e.first, e.second, (s.third + e.third) / 2f, col)
        }
    }
    lines.sortBy { it.d }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.6f; strokeCap = android.graphics.Paint.Cap.ROUND }
    for (l in lines) {
        paint.color = l.c; canvas.drawLine(l.x0, l.y0, l.x1, l.y1, paint)
        val vx = l.x1 - l.x0; val vy = l.y1 - l.y0
        val len = sqrt(vx * vx + vy * vy); if (len < 1e-6f) continue
        val ux = vx / len; val uy = vy / len
        val head = (len * 0.28f).coerceIn(3f, 10f)
        canvas.drawLine(l.x1, l.y1, l.x1 - ux * head - uy * head * 0.55f, l.y1 - uy * head + ux * head * 0.55f, paint)
        canvas.drawLine(l.x1, l.y1, l.x1 - ux * head + uy * head * 0.55f, l.y1 - uy * head - ux * head * 0.55f, paint)
    }
    return bmp
}


/** A sampled parametric surface S(u,v) with one scalar value per node. */
private class SurfGrid(val pts: Array<Array<CloudPoint>>, val colorArgb: Int? = null)

/** Scalar field evaluated only on its surface S(u,v) = [pathX, pathY, pathZ]. */
private suspend fun scalarSurfGrids(snap: FieldSnap, t: Double?): SurfField? {
    if (snap.pathX.isBlank() || snap.pathY.isBlank() || snap.pathZ.isBlank()) return null
    val xe = runCatching { ExpressionParser.parse(snap.pathX) }.getOrNull() ?: return null
    val ye = runCatching { ExpressionParser.parse(snap.pathY) }.getOrNull() ?: return null
    val ze = runCatching { ExpressionParser.parse(snap.pathZ) }.getOrNull() ?: return null
    val exprs = snap.scalar3.filter { it.visible }.mapNotNull { e ->
        runCatching { ExpressionParser.parse(e.text) }.getOrNull()?.let { it to e.colorArgb }
    }
    if (exprs.isEmpty()) return null
    val u0 = snap.pathR0; val u1 = snap.pathR1
    if (!u0.isFinite() || !u1.isFinite() || u0 == u1 || abs(u1 - u0) > 1e6) return null
    val n = snap.density.coerceIn(8, 18)
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    val grids = exprs.map { (expr, color) ->
        Array(n) { r ->
            coroutineContext.ensureActive()
            Array(n) { c ->
                val u = u0 + (u1 - u0) * c / (n - 1)
                val v = u0 + (u1 - u0) * r / (n - 1)
                scope.vars["u"] = com.xnotes.math.Complex.real(u)
                scope.vars["v"] = com.xnotes.math.Complex.real(v)
                val x = evalReal(scope, xe); val y = evalReal(scope, ye); val z = evalReal(scope, ze)
                val value = if (!x.isFinite() || !y.isFinite() || !z.isFinite()) Double.NaN
                else { bindXYZ(scope, x, y, z); evalReal(scope, expr) }
                CloudPoint(x, y, z, value)
            }
        }.let { SurfGrid(it, color) }
    }
    val finite = grids.flatMap { g -> g.pts.flatMap { row -> row.map { it.value } } }.filter { it.isFinite() }.sorted()
    if (finite.isEmpty()) return null
    val lo = finite[(finite.size * 0.015).toInt().coerceIn(0, finite.lastIndex)]
    val hi = finite[(finite.size * 0.985).toInt().coerceIn(0, finite.lastIndex)]
    return SurfField(grids, if (hi <= lo) (lo - 0.5 to lo + 0.5) else (lo to hi))
}

/** Vector field evaluated only on its surface S(u,v); arrows ride the surface. */
private suspend fun vectorSurfArrows(snap: FieldSnap, t: Double?): VecSurfField? {
    if (snap.pathX.isBlank() || snap.pathY.isBlank() || snap.pathZ.isBlank()) return null
    val xe = runCatching { ExpressionParser.parse(snap.pathX) }.getOrNull() ?: return null
    val ye = runCatching { ExpressionParser.parse(snap.pathY) }.getOrNull() ?: return null
    val ze = runCatching { ExpressionParser.parse(snap.pathZ) }.getOrNull() ?: return null
    val parsed = snap.vector3.filter { it.visible }.mapNotNull { f ->
        val pe = runCatching { ExpressionParser.parse(f.p) }.getOrNull() ?: return@mapNotNull null
        val qe = runCatching { ExpressionParser.parse(f.q) }.getOrNull() ?: return@mapNotNull null
        val re = runCatching { ExpressionParser.parse(f.r) }.getOrNull() ?: return@mapNotNull null
        Triple(Triple(pe, qe, re), f.colorArgb, f)
    }
    if (parsed.isEmpty()) return null
    val u0 = snap.pathR0; val u1 = snap.pathR1
    if (!u0.isFinite() || !u1.isFinite() || u0 == u1 || abs(u1 - u0) > 1e6) return null
    val n = snap.density.coerceIn(6, 14)
    val scope = EvalScope()
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    // Surface base points are shared by every field entry.
    val base = Array(n) { r ->
        coroutineContext.ensureActive()
        Array(n) { c ->
            val u = u0 + (u1 - u0) * c / (n - 1)
            val v = u0 + (u1 - u0) * r / (n - 1)
            scope.vars["u"] = com.xnotes.math.Complex.real(u)
            scope.vars["v"] = com.xnotes.math.Complex.real(v)
            Triple(evalReal(scope, xe), evalReal(scope, ye), evalReal(scope, ze))
        }
    }
    val lists = parsed.map { (fns, color, _) ->
        val (pe, qe, re) = fns
        val arrows = ArrayList<Arrow3>()
        for (r in 0 until n) for (c in 0 until n) {
            if ((r + c) % 2 == 1) continue // thin the arrows so the surface stays readable
            val (x, y, z) = base[r][c]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            bindXYZ(scope, x, y, z)
            val pv = evalReal(scope, pe); val qv = evalReal(scope, qe); val rv = evalReal(scope, re)
            if (!pv.isFinite() || !qv.isFinite() || !rv.isFinite()) continue
            val mag = sqrt(pv * pv + qv * qv + rv * rv)
            if (!mag.isFinite() || mag <= 1e-12) continue
            arrows += Arrow3(x, y, z, pv, qv, rv, mag)
        }
        ArrowSet(arrows, color)
    }
    val maxMag = lists.flatMap { it.arrows }.maxOfOrNull { it.mag } ?: return null
    if (maxMag <= 1e-12) return null
    return VecSurfField(lists, maxMag, SurfBase(base))
}

private fun rasterSurfGrids(
    grids: List<SurfGrid>, lo: Double, hi: Double,
    quat: Quat, zoom: Float, width: Int, height: Int, theme: GraphTheme,
    xR: Double, yR: Double, zR: Double, cx: Double, cy: Double, cz: Double,
    showGrid: Boolean = true,
): Bitmap {
    val w = width.coerceIn(2, 1400); val h = height.coerceIn(2, 1400)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val proj = quatProjector(w, h, quat, zoom, xR, yR, zR, cx, cy, cz)
    drawQuatSpaceFrame(canvas, proj, quat, cx, cy, cz, xR, yR, zR, w, h, theme, showGrid)
    val span = (hi - lo).coerceAtLeast(1e-12)
    data class Cell(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float, val depth: Float, val color: Int)
    data class Dot(val x: Float, val y: Float, val d: Float, val c: Int)
    val cells = ArrayList<Cell>(); val dots = ArrayList<Dot>()
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    grids.forEachIndexed { gi, grid ->
        val n = grid.pts.size
        val px = Array(n) { FloatArray(n) }; val py = Array(n) { FloatArray(n) }
        val pd = Array(n) { FloatArray(n) }; val ok = Array(n) { BooleanArray(n) }
        for (r in 0 until n) for (c in 0 until n) {
            val point = grid.pts[r][c]
            ok[r][c] = point.value.isFinite()
            if (!ok[r][c]) continue
            val pr = proj(point.x, point.y, point.z)
            px[r][c] = pr.first; py[r][c] = pr.second; pd[r][c] = pr.third
        }
        if (gi == 0) {
            for (r in 0 until n - 1) {
                for (c in 0 until n - 1) {
                    if (!ok[r][c] || !ok[r][c + 1] || !ok[r + 1][c + 1] || !ok[r + 1][c]) continue
                    val avg = (grid.pts[r][c].value + grid.pts[r][c + 1].value + grid.pts[r + 1][c + 1].value + grid.pts[r + 1][c].value) / 4.0
                    val col = grid.colorArgb ?: heightArgb(((avg - lo) / span).coerceIn(0.0, 1.0))
                    cells += Cell(px[r][c], py[r][c], px[r][c + 1], py[r][c + 1], px[r + 1][c + 1], py[r + 1][c + 1], px[r + 1][c], py[r + 1][c],
                        (pd[r][c] + pd[r][c + 1] + pd[r + 1][c + 1] + pd[r + 1][c]) / 4f, col)
                }
            }
        } else {
            for (r in 0 until n) for (c in 0 until n) {
                if (!ok[r][c]) continue
                val col = grid.colorArgb ?: heightArgb(((grid.pts[r][c].value - lo) / span).coerceIn(0.0, 1.0))
                dots += Dot(px[r][c], py[r][c], pd[r][c], col)
            }
        }
    }
    cells.sortBy { it.depth }
    val path = android.graphics.Path()
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    for (cell in cells) {
        path.rewind()
        path.moveTo(cell.x0, cell.y0); path.lineTo(cell.x1, cell.y1)
        path.lineTo(cell.x2, cell.y2); path.lineTo(cell.x3, cell.y3); path.close()
        fill.color = cell.color
        canvas.drawPath(path, fill)
    }
    dots.sortBy { it.d }
    for (d in dots) { paint.color = d.c; canvas.drawCircle(d.x, d.y, 4.0f, paint) }
    return bmp
}

private fun rasterSurfArrows(
    sets: List<ArrowSet>, maxMag: Double, base: SurfBase?,
    quat: Quat, zoom: Float, width: Int, height: Int, theme: GraphTheme,
    xR: Double, yR: Double, zR: Double, cx: Double, cy: Double, cz: Double,
    showGrid: Boolean = true,
): Bitmap {
    val w = width.coerceIn(2, 1400); val h = height.coerceIn(2, 1400)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val proj = quatProjector(w, h, quat, zoom, xR, yR, zR, cx, cy, cz)
    drawQuatSpaceFrame(canvas, proj, quat, cx, cy, cz, xR, yR, zR, w, h, theme, showGrid)
    // The surface itself, one wireframe lane per entry in that entry's colour.
    if (base != null && base.pts.isNotEmpty()) {
        val n = base.pts.size
        val wire = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.6f
        }
        val lane = 4f
        sets.forEachIndexed { si, set ->
            wire.color = set.colorArgb ?: theme.dim
            val ox = ((si - (sets.size - 1) / 2.0) * lane).toFloat()
            val path = android.graphics.Path()
            for (r in 0 until n) {
                var started = false
                for (c in 0 until n) {
                    val (x, y, z) = base.pts[r][c]
                    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) { started = false; continue }
                    val pr = proj(x, y, z)
                    if (!started) { path.moveTo(pr.first + ox, pr.second); started = true }
                    else path.lineTo(pr.first + ox, pr.second)
                }
            }
            for (c in 0 until n) {
                var started = false
                for (r in 0 until n) {
                    val (x, y, z) = base.pts[r][c]
                    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) { started = false; continue }
                    val pr = proj(x, y, z)
                    if (!started) { path.moveTo(pr.first + ox, pr.second); started = true }
                    else path.lineTo(pr.first + ox, pr.second)
                }
            }
            canvas.drawPath(path, wire)
        }
    }
    val scale = min(xR, min(yR, zR)) * 0.42 / maxMag.coerceAtLeast(1e-12)
    data class L(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val d: Float, val c: Int)
    val lines = ArrayList<L>()
    for ((ci, set) in sets.withIndex()) {
        for (a in set.arrows) {
            val s = proj(a.x, a.y, a.z); val e = proj(a.x + a.dx * scale, a.y + a.dy * scale, a.z + a.dz * scale)
            val vx = e.first - s.first; val vy = e.second - s.second
            if (sqrt(vx * vx + vy * vy) < 1.5f) continue
            val col = set.colorArgb ?: if (sets.size <= 1) heightArgb((a.mag / maxMag).coerceIn(0.12, 0.95)) else theme.series[ci % theme.series.size]
            lines += L(s.first, s.second, e.first, e.second, (s.third + e.third) / 2f, col)
        }
    }
    lines.sortBy { it.d }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.6f; strokeCap = android.graphics.Paint.Cap.ROUND }
    for (l in lines) {
        paint.color = l.c; canvas.drawLine(l.x0, l.y0, l.x1, l.y1, paint)
        val vx = l.x1 - l.x0; val vy = l.y1 - l.y0
        val len = sqrt(vx * vx + vy * vy); if (len < 1e-6f) continue
        val ux = vx / len; val uy = vy / len
        val head = (len * 0.28f).coerceIn(3f, 10f)
        canvas.drawLine(l.x1, l.y1, l.x1 - ux * head - uy * head * 0.55f, l.y1 - uy * head + ux * head * 0.55f, paint)
        canvas.drawLine(l.x1, l.y1, l.x1 - ux * head + uy * head * 0.55f, l.y1 - uy * head - ux * head * 0.55f, paint)
    }
    return bmp
}


private fun robustRange(values: Array<DoubleArray>): Pair<Double, Double> {
    val finite = ArrayList<Double>()
    for (row in values) for (v in row) if (v.isFinite()) finite += v
    if (finite.isEmpty()) return 0.0 to 1.0
    finite.sort()
    var lo = finite[(finite.size * 0.015).toInt().coerceIn(0, finite.lastIndex)]
    var hi = finite[(finite.size * 0.985).toInt().coerceIn(0, finite.lastIndex)]
    if (hi <= lo) { lo -= 0.5; hi += 0.5 }
    return lo to hi
}

private fun rangeOf(text: String, fallback: Double = 5.0): Double = text.toDoubleOrNull()?.coerceIn(0.05, 1e4) ?: fallback
private fun filterRange(text: String): String = text.filter { it.isDigit() || it == '.' || it == '-' }
private fun probeOrNull(text: String): Double? = runCatching { Evaluator.evaluate(ExpressionParser.parse(text)).realValue() }.getOrNull()?.takeIf { it.isFinite() }

private fun probeReadout(snap: FieldSnap): String {
    val p = probeOrNull(snap.probeX) ?: 0.0; val q = probeOrNull(snap.probeY) ?: 0.0; val r = probeOrNull(snap.probeZ) ?: 0.0
    fun num(v: Double) = if (v.isFinite()) Numbers.format(v) else "—"
    fun withT(scope: EvalScope) { if (snap.t != null && snap.t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(snap.t) }
    return runCatching {
        when (snap.mode) {
            FieldMode.SCALAR_2D -> {
                val expr = ExpressionParser.parse(snap.scalar2.firstOrNull { it.visible }?.text ?: return@runCatching "—")
                val scope = EvalScope(); withT(scope)
                val value = runCatching { scope.vars["x"] = com.xnotes.math.Complex.real(p); scope.vars["y"] = com.xnotes.math.Complex.real(q); Evaluator.evaluate(expr, scope).realValue() }.getOrDefault(Double.NaN)
                "f(${Numbers.tick(p)}, ${Numbers.tick(q)}) = ${num(value)}"
            }
            FieldMode.VECTOR_2D -> {
                val f = snap.vector2.firstOrNull { it.visible } ?: return@runCatching "—"
                val pe = ExpressionParser.parse(f.p); val qe = ExpressionParser.parse(f.q)
                val div = Numerical.divergence2({ x, y -> evalXYT(pe, x, y, snap.t) }, { x, y -> evalXYT(qe, x, y, snap.t) }, p, q)
                val curl = Numerical.curl2({ x, y -> evalXYT(pe, x, y, snap.t) }, { x, y -> evalXYT(qe, x, y, snap.t) }, p, q)
                "div = ${num(div)} curl z = ${num(curl)}"
            }
            FieldMode.SCALAR_3D -> {
                val expr = ExpressionParser.parse(snap.scalar3.firstOrNull { it.visible }?.text ?: return@runCatching "—")
                val value = evalXYZT(expr, p, q, r, snap.t)
                "f = ${num(value)}"
            }
            FieldMode.VECTOR_3D -> {
                val f = snap.vector3.firstOrNull { it.visible } ?: return@runCatching "—"
                val pe = ExpressionParser.parse(f.p); val qe = ExpressionParser.parse(f.q); val re = ExpressionParser.parse(f.r)
                val div = Numerical.divergence3({ x, y, z -> evalXYZT(pe, x, y, z, snap.t) }, { x, y, z -> evalXYZT(qe, x, y, z, snap.t) }, { x, y, z -> evalXYZT(re, x, y, z, snap.t) }, p, q, r)
                "div = ${num(div)}"
            }
        }
    }.getOrElse { "—" }
}

private fun evalXYT(expr: com.xnotes.math.Expr, x: Double, y: Double, t: Double?): Double {
    val scope = EvalScope()
    bindXY(scope, x, y)
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    return evalReal(scope, expr)
}
private fun evalXYZT(expr: com.xnotes.math.Expr, x: Double, y: Double, z: Double, t: Double?): Double {
    val scope = EvalScope()
    bindXYZ(scope, x, y, z)
    if (t != null && t.isFinite()) scope.vars["t"] = com.xnotes.math.Complex.real(t)
    return evalReal(scope, expr)
}
