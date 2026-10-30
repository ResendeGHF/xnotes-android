package com.xnotes.ui

import android.graphics.Bitmap
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SurfaceMode(val labelRes: Int) {
    CARTESIAN(R.string.math_3d_real_surface),
    CYLINDRICAL(R.string.math_3d_cylindrical),
    SPHERICAL(R.string.math_3d_spherical),
    PARAMETRIC(R.string.math_3d_parametric),
    IMPLICIT(R.string.math_3d_implicit),
    COMPLEX(R.string.math_3d_complex_surface),
}

internal enum class ComplexComponent(val labelRes: Int) {
    REAL(R.string.math_component_real),
    IMAG(R.string.math_component_imag),
    MAGNITUDE(R.string.math_component_magnitude),
    ARGUMENT(R.string.math_component_argument),
}

internal data class Param3D(
    val x: String = "sin(u)*cos(v)",
    val y: String = "sin(u)*sin(v)",
    val z: String = "cos(u)",
    val colorArgb: Int? = null,
    val visible: Boolean = true,
)

internal class Plot3DState {
    var mode by mutableStateOf(SurfaceMode.CARTESIAN)
    var cartesianList by mutableStateOf(listOf(MathEntry("sin(sqrt(x^2 + y^2))")))
    var cylList by mutableStateOf(listOf(MathEntry("2 + cos(3*th)")))
    var sphList by mutableStateOf(listOf(MathEntry("3")))
    var paramList by mutableStateOf(listOf(Param3D()))
    var implicitList by mutableStateOf(listOf(MathEntry("x^2 + y^2 + z^2 - 4")))
    var complexExpression by mutableStateOf("gamma(z)")
    var component by mutableStateOf(ComplexComponent.MAGNITUDE)
    /** Explicit plot domain per axis: every mode samples and draws inside this box. */
    var xMin by mutableStateOf(-5.0)
    var xMax by mutableStateOf(5.0)
    var yMin by mutableStateOf(-5.0)
    var yMax by mutableStateOf(5.0)
    var zMin by mutableStateOf(-5.0)
    var zMax by mutableStateOf(5.0)
    var showGrid by mutableStateOf(true)
    var resolution by mutableFloatStateOf(40f)
    val orbit: OrbitState = OrbitState()
    var valueLo by mutableStateOf<Double?>(null)
    var valueHi by mutableStateOf<Double?>(null)
    val anim: AnimState = AnimState()

    fun resetView() {
        orbit.reset()
        xMin = -5.0; xMax = 5.0; yMin = -5.0; yMax = 5.0; zMin = -5.0; zMax = 5.0
    }

    fun addFor(mode: SurfaceMode) {
        when (mode) {
            SurfaceMode.CARTESIAN -> if (cartesianList.size < 4) cartesianList += MathEntry("x^2/8 - y^2/8")
            SurfaceMode.CYLINDRICAL -> if (cylList.size < 4) cylList += MathEntry("2 + 0.5*sin(4*th)")
            SurfaceMode.SPHERICAL -> if (sphList.size < 4) sphList += MathEntry("3 + sin(4*th)*cos(3*ph)")
            SurfaceMode.PARAMETRIC -> if (paramList.size < 4) paramList += Param3D("cos(u)*cos(v)", "cos(u)*sin(v)", "sin(u)")
            SurfaceMode.IMPLICIT -> if (implicitList.size < 4) implicitList += MathEntry("x^2 + y^2 - z")
            SurfaceMode.COMPLEX -> Unit
        }
    }
    fun removeAt(mode: SurfaceMode, i: Int) {
        when (mode) {
            SurfaceMode.CARTESIAN -> if (cartesianList.size > 1) cartesianList = cartesianList.toMutableList().also { it.removeAt(i) }
            SurfaceMode.CYLINDRICAL -> if (cylList.size > 1) cylList = cylList.toMutableList().also { it.removeAt(i) }
            SurfaceMode.SPHERICAL -> if (sphList.size > 1) sphList = sphList.toMutableList().also { it.removeAt(i) }
            SurfaceMode.PARAMETRIC -> if (paramList.size > 1) paramList = paramList.toMutableList().also { it.removeAt(i) }
            SurfaceMode.IMPLICIT -> if (implicitList.size > 1) implicitList = implicitList.toMutableList().also { it.removeAt(i) }
            SurfaceMode.COMPLEX -> Unit
        }
    }
    fun setStyle(mode: SurfaceMode, i: Int, colorArgb: Int?, visible: Boolean) {
        when (mode) {
            SurfaceMode.CARTESIAN -> cartesianList = cartesianList.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
            SurfaceMode.CYLINDRICAL -> cylList = cylList.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
            SurfaceMode.SPHERICAL -> sphList = sphList.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
            SurfaceMode.PARAMETRIC -> paramList = paramList.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
            SurfaceMode.IMPLICIT -> implicitList = implicitList.toMutableList().also { it[i] = it[i].copy(colorArgb = colorArgb, visible = visible) }
            SurfaceMode.COMPLEX -> Unit
        }
    }
}

private data class Vec3(val x: Double, val y: Double, val z: Double)
private class Mesh(val pts: Array<Array<Vec3>>, val vals: Array<DoubleArray>, val colorArgb: Int? = null)
private class Cloud3(val pts: List<Vec3>, val vals: List<Double>, val lo: Double, val hi: Double, val colorArgb: Int? = null)
private class MeshSet(val meshes: List<Mesh>, val clouds: List<Cloud3>, val lo: Double, val hi: Double)

internal data class Plot3DSnap(
    val mode: SurfaceMode,
    val exprs: List<MathEntry>,
    val params: List<Param3D>,
    val component: ComplexComponent,
    val x0: Double,
    val x1: Double,
    val y0: Double,
    val y1: Double,
    val z0: Double,
    val z1: Double,
    val n: Int,
    val quat: Quat,
    val zoom: Float,
    val t: Double?,
    val shareT: Boolean,
    val animRange: AnimSnap?,
    val showGrid: Boolean = true,
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
    AnimTicker(state.anim)
    val colors = mathPlotColors()
    fun effectiveColor(index: Int, custom: Int?): androidx.compose.ui.graphics.Color =
        custom?.let { androidx.compose.ui.graphics.Color(it) } ?: colors[index % colors.size]
    val legend: List<Triple<String, androidx.compose.ui.graphics.Color, Int>> = when (state.mode) {
        SurfaceMode.CARTESIAN -> state.cartesianList.mapIndexedNotNull { i, e -> if (!e.visible) null else Triple("z${i + 1} = ${e.text.ifBlank { "—" }}", effectiveColor(i, e.colorArgb), e.colorArgb ?: colors[i % colors.size].toArgb()) }
        SurfaceMode.CYLINDRICAL -> state.cylList.mapIndexedNotNull { i, e -> if (!e.visible) null else Triple("r${i + 1} = ${e.text.ifBlank { "—" }}", effectiveColor(i, e.colorArgb), e.colorArgb ?: colors[i % colors.size].toArgb()) }
        SurfaceMode.SPHERICAL -> state.sphList.mapIndexedNotNull { i, e -> if (!e.visible) null else Triple("ρ${i + 1} = ${e.text.ifBlank { "—" }}", effectiveColor(i, e.colorArgb), e.colorArgb ?: colors[i % colors.size].toArgb()) }
        SurfaceMode.PARAMETRIC -> state.paramList.mapIndexedNotNull { i, _ -> val p = state.paramList[i]; if (!p.visible) null else Triple("P${i + 1}(u,v)", effectiveColor(i, p.colorArgb), p.colorArgb ?: colors[i % colors.size].toArgb()) }
        SurfaceMode.IMPLICIT -> state.implicitList.mapIndexedNotNull { i, e -> if (!e.visible) null else Triple("${e.text.ifBlank { "—" }} = 0", effectiveColor(i, e.colorArgb), e.colorArgb ?: colors[i % colors.size].toArgb()) }
        SurfaceMode.COMPLEX -> listOf(Triple("f(z) = ${state.complexExpression.ifBlank { "—" }}", palette.accent.toComposeColor(), palette.accent.packed()))
    } + if (state.anim.enabled && !complex) listOf(Triple("t=${"%.3f".format(state.anim.tCurrent)}", palette.textDim.toComposeColor(), palette.textDim.packed())) else emptyList()
    val lo = state.valueLo
    val hi = state.valueHi
    val componentLabel = if (complex) stringResource(state.component.labelRes) else ""
    val animLabel = stringResource(R.string.math_insert_animation)
    var animInserting by remember { mutableStateOf(false) }
    val animOn = state.anim.enabled && !complex
    GraphWorkspace(
        modes = SurfaceMode.entries.map { it.name to stringResource(it.labelRes) },
        selected = state.mode.name,
        onSelect = { state.mode = SurfaceMode.valueOf(it) },
        legend = legend.map { it.first to it.second },
        ramp = if (lo != null && hi != null) Numbers.tick(lo) to Numbers.tick(hi) else null,
        caption = if (complex) stringResource(state.component.labelRes) else if (animOn) "t=${"%.3f".format(state.anim.tCurrent)} (insert captures current frame)" else null,
        showGrid = state.showGrid,
        onToggleGrid = { state.showGrid = !state.showGrid },
        onReset = state::resetView,
        inserting = inserting,
        onInsert = {
            if (inserting) return@GraphWorkspace
            val snap = buildSnap(state)
            scope.launch {
                inserting = true; failed = false
                val bytes = withContext(Dispatchers.Default) { exportSurface(snap, theme, title, heightLabel, componentLabel) }
                inserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        },
        animInsertLabel = if (animOn) animLabel else null,
        animInserting = animInserting,
        onInsertAnim = if (animOn) ({
            if (animInserting || inserting) return@GraphWorkspace
            val base = buildSnap(state)
            val exported = legend.map { (label, _, argb) -> ExportLegend(label, argb) }
            scope.launch {
                animInserting = true; failed = false
                val bytes = withContext(Dispatchers.Default) { exportSurfaceAnimation(base, theme, title, exported) }
                animInserting = false
                if (bytes == null) failed = true else onInsert(bytes)
            }
        }) else null,
        status = if (failed) failedText else null,
        plot = { SurfaceView(state, Modifier.fillMaxSize()) },
        controls = { Plot3DInputs(state) },
    )
}

private fun buildSnap(state: Plot3DState): Plot3DSnap {
    val complex = state.mode == SurfaceMode.COMPLEX
    val exprs = when (state.mode) {
        SurfaceMode.CARTESIAN -> state.cartesianList
        SurfaceMode.CYLINDRICAL -> state.cylList
        SurfaceMode.SPHERICAL -> state.sphList
        SurfaceMode.IMPLICIT -> state.implicitList
        SurfaceMode.COMPLEX -> listOf(MathEntry(state.complexExpression))
        SurfaceMode.PARAMETRIC -> emptyList()
    }
    // The t parameter is not valid for complex surfaces (item A): always static there.
    val t = if (state.anim.enabled && !complex) state.anim.tCurrent else null
    val lo = state.anim.tMinStr.toDoubleOrNull(); val hi = state.anim.tMaxStr.toDoubleOrNull()
    val animRange = if (!complex && state.anim.enabled && lo != null && hi != null && lo.isFinite() && hi.isFinite() && lo != hi)
        AnimSnap(lo, hi, 60, t ?: lo, state.anim.shareT) else null
    fun bound(lo: Double, hi: Double): Pair<Double, Double> {
        if (!lo.isFinite() || !hi.isFinite() || lo == hi) return -5.0 to 5.0
        return minOf(lo, hi) to maxOf(lo, hi)
    }
    val (x0, x1) = bound(state.xMin, state.xMax)
    val (y0, y1) = bound(state.yMin, state.yMax)
    val (z0, z1) = bound(state.zMin, state.zMax)
    return Plot3DSnap(state.mode, exprs, state.paramList.toList(), state.component,
        x0, x1, y0, y1, z0, z1,
        state.resolution.toInt().coerceIn(14, 72),
        state.orbit.quat, state.orbit.zoom, t, state.anim.shareT, animRange, state.showGrid)
}

@Composable
private fun Plot3DInputs(state: Plot3DState) {
    val palette = LocalPalette.current
    val colors = mathPlotColors()
    val eyeLabel = stringResource(R.string.math_toggle_visibility)
    val colorLabel = stringResource(R.string.math_pick_color)
    fun effectiveColor(index: Int, custom: Int?): androidx.compose.ui.graphics.Color =
        custom?.let { androidx.compose.ui.graphics.Color(it) } ?: colors[index % colors.size]
    @Composable
    fun listEditor(items: List<MathEntry>, set: (Int, String) -> Unit, label: (Int) -> String) {
        items.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                EntryColorDot(e.colorArgb, effectiveColor(i, e.colorArgb), colorLabel) {
                    state.setStyle(state.mode, i, it, e.visible)
                }
                MathTextField(e.text, { set(i, it) }, label(i), Modifier.weight(1f))
                EntryEye(e.visible, eyeLabel) {
                    state.setStyle(state.mode, i, e.colorArgb, !e.visible)
                }
                if (items.size > 1) MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) { state.removeAt(state.mode, i) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addFor(state.mode) }
        }
    }
    when (state.mode) {
        SurfaceMode.CARTESIAN -> listEditor(state.cartesianList, { i, v -> state.cartesianList = state.cartesianList.toMutableList().also { it[i] = it[i].copy(text = v) } }, { "z${it + 1}(x,y)" })
        SurfaceMode.CYLINDRICAL -> {
            listEditor(state.cylList, { i, v -> state.cylList = state.cylList.toMutableList().also { it[i] = it[i].copy(text = v) } }, { "r${it + 1}(θ,z)" })
            MathHint(stringResource(R.string.math_3d_hint_cylindrical))
        }
        SurfaceMode.SPHERICAL -> {
            listEditor(state.sphList, { i, v -> state.sphList = state.sphList.toMutableList().also { it[i] = it[i].copy(text = v) } }, { "ρ${it + 1}(θ,φ)" })
            MathHint(stringResource(R.string.math_3d_hint_spherical))
        }
        SurfaceMode.PARAMETRIC -> {
            state.paramList.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    EntryColorDot(p.colorArgb, effectiveColor(i, p.colorArgb), colorLabel) {
                        state.setStyle(state.mode, i, it, p.visible)
                    }
                    MathSectionTitle("P${i + 1}(u,v)", Modifier.weight(1f))
                    EntryEye(p.visible, eyeLabel) {
                        state.setStyle(state.mode, i, p.colorArgb, !p.visible)
                    }
                    if (state.paramList.size > 1) MathIconButton(Icons.Outlined.Close, stringResource(R.string.math_remove), tint = palette.textDim.toComposeColor()) { state.removeAt(state.mode, i) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MathTextField(p.x, { v -> state.paramList = state.paramList.toMutableList().also { list -> list[i] = p.copy(x = v) } }, "x", Modifier.weight(1f))
                    MathTextField(p.y, { v -> state.paramList = state.paramList.toMutableList().also { list -> list[i] = p.copy(y = v) } }, "y", Modifier.weight(1f))
                    MathTextField(p.z, { v -> state.paramList = state.paramList.toMutableList().also { list -> list[i] = p.copy(z = v) } }, "z", Modifier.weight(1f))
                }
            }
            MathSecondaryButton(stringResource(R.string.math_2d_add)) { state.addFor(state.mode) }
        }
        SurfaceMode.IMPLICIT -> listEditor(state.implicitList, { i, v -> state.implicitList = state.implicitList.toMutableList().also { it[i] = it[i].copy(text = v) } }, { "f${it + 1}(x,y,z)=0" })
        SurfaceMode.COMPLEX -> {
            MathTextField(state.complexExpression, { state.complexExpression = it }, stringResource(R.string.math_3d_complex_function), Modifier.fillMaxWidth())
            MathToolSelector(ComplexComponent.entries.map { it.name to stringResource(it.labelRes) }, state.component.name) { state.component = ComplexComponent.valueOf(it) }
        }
    }
    Plot3DDomain(state)
    CommittedSlider(state.resolution, { state.resolution = it }, 14f..72f, stringResource(R.string.math_3d_resolution))
    // No t parameter for complex surfaces: the animation panel stays hidden there (item A).
    // buildSnap/export also force t=null for complex, so switching modes never leaks animation in.
    if (state.mode != SurfaceMode.COMPLEX) {
        AnimPanel(state.anim, showShare = state.mode == SurfaceMode.PARAMETRIC)
    }
}

/** Explicit domain box for every 3D mode: sampling and the space grid follow these bounds. */
@Composable
private fun Plot3DDomain(state: Plot3DState) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DomainField(state.zMin, { state.zMin = it }, stringResource(R.string.math_z_min), Modifier.weight(1f))
            DomainField(state.zMax, { state.zMax = it }, stringResource(R.string.math_z_max), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SurfaceView(state: Plot3DState, modifier: Modifier = Modifier) {
    val theme = rememberGraphTheme()
    var meshes by remember { mutableStateOf<MeshSet?>(null) }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var view by remember { mutableStateOf(IntSize(1, 1)) }
    fun styleOf(e: MathEntry) = e.text + "" + (e.colorArgb?.toString() ?: "") + "" + (if (e.visible) "1" else "0")
    val exprKey = rememberSettled(listOf(state.cartesianList, state.cylList, state.sphList, state.implicitList).flatten().map(::styleOf).joinToString("") + state.complexExpression + state.paramList.map { it.x + "" + it.y + "" + it.z + "" + (it.colorArgb?.toString() ?: "") + "" + (if (it.visible) "1" else "0") }.joinToString(""), 200)
    val domainKey = rememberSettled(state.xMin.toString() + "|" + state.xMax + "|" + state.yMin + "|" + state.yMax + "|" + state.zMin + "|" + state.zMax + "|" + state.showGrid, 200)
    val nKey = state.resolution.toInt().coerceIn(14, 72)
    val tKey = state.anim.tCurrent
    val tOn = state.anim.enabled && state.mode != SurfaceMode.COMPLEX
    LaunchedEffect(exprKey, state.mode, state.component, domainKey, nKey, tKey, tOn, state.anim.shareT) {
        val snap = buildSnap(state)
        val data = withContext(Dispatchers.Default) { computeMeshes(snap) }
        meshes = data
        state.valueLo = data?.lo
        state.valueHi = data?.hi
    }
    LaunchedEffect(meshes, view, theme) {
        val data = meshes
        if (data == null) { frame = null; return@LaunchedEffect }
        snapshotFlow { Triple(state.orbit.quat, state.orbit.zoom, state.mode) }.conflate().collect { (q, z, _) ->
            val (w, h) = cappedSize(view.width, view.height, 640)
            val snap = buildSnap(state)
            val bmp = withContext(Dispatchers.Default) { rasterMeshes(data, snap, q, z, w, h, theme) }
            frame = bmp.asImageBitmap()
            kotlinx.coroutines.delay(28)
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { view = it }.pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            state.orbit.drag(pan.x, pan.y)
            state.orbit.zoom = (state.orbit.zoom * zoom).coerceIn(0.35f, 4f)
        }
    }) {
        frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (frame == null) Text(stringResource(R.string.math_3d_computing), color = LocalPalette.current.textDim.toComposeColor(), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.Center))
        ViewPresetButtons(state.orbit)
        if (state.mode != SurfaceMode.COMPLEX) AnimPlayOverlay(state.anim)
    }
}

private suspend fun exportSurface(snap: Plot3DSnap, theme: GraphTheme, title: String, heightLabel: String, component: String): ByteArray? = try {
    val data = computeMeshes(snap) ?: return null
    val bmp = rasterMeshes(data, snap, snap.quat, snap.zoom, EXPORT_PLOT_W, EXPORT_PLOT_H, theme)
    fun shownExprs(): List<String> = snap.exprs.filter { it.visible }.map { it.text }
    fun shownParams(): List<Param3D> = snap.params.filter { it.visible }
    val label = when (snap.mode) {
        SurfaceMode.CARTESIAN -> shownExprs().mapIndexed { i, e -> "z${i + 1}=$e" }.joinToString("  ")
        SurfaceMode.CYLINDRICAL -> shownExprs().mapIndexed { i, e -> "r${i + 1}=$e" }.joinToString("  ")
        SurfaceMode.SPHERICAL -> shownExprs().mapIndexed { i, e -> "ρ${i + 1}=$e" }.joinToString("  ")
        SurfaceMode.PARAMETRIC -> shownParams().mapIndexed { i, _ -> "P${i + 1}(u,v)" }.joinToString("  ")
        SurfaceMode.IMPLICIT -> shownExprs().mapIndexed { i, e -> "f${i + 1}=$e" }.joinToString("  ")
        SurfaceMode.COMPLEX -> "f(z) = ${snap.exprs.firstOrNull()?.text}"
    }
    val legend = listOf(ExportLegend("$label    $heightLabel  ${Numbers.tick(data.lo)} → ${Numbers.tick(data.hi)}", theme.series[0], ramp = true))
    val foot = buildString {
        append("x ∈ [${Numbers.tick(snap.x0)}, ${Numbers.tick(snap.x1)}]    y ∈ [${Numbers.tick(snap.y0)}, ${Numbers.tick(snap.y1)}]    z ∈ [${Numbers.tick(snap.z0)}, ${Numbers.tick(snap.z1)}]")
        if (snap.t != null) append("    t=${Numbers.tick(snap.t)}")
        if (component.isNotEmpty() && snap.mode == SurfaceMode.COMPLEX) append("    ").append(component)
    }
    writeGraphPng(bmp, title, legend, foot, theme)
} catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { null }

private suspend fun exportSurfaceAnimation(base: Plot3DSnap, theme: GraphTheme, title: String, legend: List<ExportLegend>): ByteArray? {
    return try {
    val range = base.animRange ?: return null
    val ts = animFrameTs(range.lo, range.hi, range.steps, 8)
    val fw = 400; val fh = 260
    val frames = ArrayList<Pair<Bitmap, String>>(ts.size)
    // Use reduced resolution for filmstrip speed, full orbit kept.
    val small = base.copy(n = minOf(base.n, 36))
    for (t in ts) {
        coroutineContext.ensureActive()
        val snap = small.copy(t = t, animRange = AnimSnap(range.lo, range.hi, range.steps, t, range.shareT), shareT = range.shareT)
        val data = computeMeshes(snap) ?: continue
        frames += rasterMeshes(data, snap, snap.quat, snap.zoom, fw, fh, theme) to "t=${Numbers.tick(t)}"
    }
    if (frames.isEmpty()) return null
    val foot = "x ∈ [${Numbers.tick(base.x0)}, ${Numbers.tick(base.x1)}] y ∈ [${Numbers.tick(base.y0)}, ${Numbers.tick(base.y1)}] z ∈ [${Numbers.tick(base.z0)}, ${Numbers.tick(base.z1)}]    frames=${ts.size} t∈[${Numbers.tick(minOf(range.lo, range.hi))}, ${Numbers.tick(maxOf(range.lo, range.hi))}]"
    buildAnimationPng(frames, title, legend, foot, theme, specJson = MathAnimSpec.capture3D(base)?.toJson())
} catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { null }
}

private suspend fun computeMeshes(snap: Plot3DSnap): MeshSet? {
    val meshes = ArrayList<Mesh>(); val clouds = ArrayList<Cloud3>()
    val allVals = ArrayList<Double>()
    suspend fun addMesh(m: Mesh?) { if (m != null) { meshes += m; for (row in m.vals) for (v in row) if (v.isFinite()) allVals += v } }
    when (snap.mode) {
        SurfaceMode.CARTESIAN -> for (e in snap.exprs) { if (e.visible) addMesh(cartesianMesh(e.text, snap, e.colorArgb)) }
        SurfaceMode.CYLINDRICAL -> for (e in snap.exprs) { if (e.visible) addMesh(cylindricalMesh(e.text, snap, e.colorArgb)) }
        SurfaceMode.SPHERICAL -> for (e in snap.exprs) { if (e.visible) addMesh(sphericalMesh(e.text, snap, e.colorArgb)) }
        SurfaceMode.PARAMETRIC -> for (p in snap.params) { if (p.visible) addMesh(parametricMesh(p, snap, p.colorArgb)) }
        SurfaceMode.COMPLEX -> for (e in snap.exprs) { addMesh(complexMesh(e.text, snap, e.colorArgb)) }
        SurfaceMode.IMPLICIT -> for (e in snap.exprs) {
            if (!e.visible) continue
            val c = implicitCloud(e.text, snap, e.colorArgb)
            if (c != null) { clouds += c; allVals += c.vals.filter { it.isFinite() } }
        }
    }
    if (meshes.isEmpty() && clouds.isEmpty()) return null
    if (allVals.isEmpty()) return null
    allVals.sort()
    val lo = allVals[(allVals.size * 0.015).toInt().coerceIn(0, allVals.lastIndex)]
    val hi = allVals[(allVals.size * 0.985).toInt().coerceIn(0, allVals.lastIndex)]
    val aLo = if (hi == lo) lo - 0.5 else lo; val aHi = if (hi == lo) hi + 0.5 else hi
    return MeshSet(meshes, clouds, aLo, aHi)
}

private fun scopeWithT(t: Double?): EvalScope {
    val s = EvalScope()
    if (t != null && t.isFinite()) { s.vars["t"] = Complex.real(t); s.vars["tAnim"] = Complex.real(t) }
    return s
}

private suspend fun cartesianMesh(text: String, snap: Plot3DSnap, colorArgb: Int? = null): Mesh? {
    val expr = runCatching { ExpressionParser.parse(text) }.getOrNull() ?: return null
    val n = snap.n
    val pts = Array(n) { Array(n) { Vec3(0.0, 0.0, 0.0) } }
    val vals = Array(n) { DoubleArray(n) }
    val scope = scopeWithT(snap.t)
    for (ri in 0 until n) {
        coroutineContext.ensureActive()
        for (ci in 0 until n) {
            val x = snap.x0 + (snap.x1 - snap.x0) * ci / (n - 1); val y = snap.y0 + (snap.y1 - snap.y0) * ri / (n - 1)
            bindXY(scope, x, y)
            val z = evalReal(scope, expr)
            pts[ri][ci] = Vec3(x, y, z); vals[ri][ci] = z
        }
    }
    return Mesh(pts, vals, colorArgb)
}

private suspend fun cylindricalMesh(text: String, snap: Plot3DSnap, colorArgb: Int? = null): Mesh? {
    val expr = runCatching { ExpressionParser.parse(text) }.getOrNull() ?: return null
    val n = snap.n
    val pts = Array(n) { Array(n) { Vec3(0.0, 0.0, 0.0) } }
    val vals = Array(n) { DoubleArray(n) }
    val scope = scopeWithT(snap.t)
    for (ri in 0 until n) {
        coroutineContext.ensureActive()
        val z = snap.z0 + (snap.z1 - snap.z0) * ri / (n - 1)
        for (ci in 0 until n) {
            val th = 2 * PI * ci / (n - 1)
            scope.vars["th"] = Complex.real(th); scope.vars["theta"] = Complex.real(th); scope.vars["z"] = Complex.real(z)
            val rv = evalReal(scope, expr)
            val x = if (rv.isFinite()) rv * cos(th) else Double.NaN
            val y = if (rv.isFinite()) rv * sin(th) else Double.NaN
            pts[ri][ci] = Vec3(x, y, z); vals[ri][ci] = rv
        }
    }
    return Mesh(pts, vals, colorArgb)
}

private suspend fun sphericalMesh(text: String, snap: Plot3DSnap, colorArgb: Int? = null): Mesh? {
    val expr = runCatching { ExpressionParser.parse(text) }.getOrNull() ?: return null
    val n = snap.n
    val pts = Array(n) { Array(n) { Vec3(0.0, 0.0, 0.0) } }
    val vals = Array(n) { DoubleArray(n) }
    val scope = scopeWithT(snap.t)
    for (ri in 0 until n) {
        coroutineContext.ensureActive()
        val ph = PI * ri / (n - 1)
        for (ci in 0 until n) {
            val th = 2 * PI * ci / (n - 1)
            scope.vars["th"] = Complex.real(th); scope.vars["theta"] = Complex.real(th)
            scope.vars["ph"] = Complex.real(ph); scope.vars["phi"] = Complex.real(ph)
            val rho = evalReal(scope, expr)
            val x = if (rho.isFinite()) rho * sin(ph) * cos(th) else Double.NaN
            val y = if (rho.isFinite()) rho * sin(ph) * sin(th) else Double.NaN
            val z = if (rho.isFinite()) rho * cos(ph) else Double.NaN
            pts[ri][ci] = Vec3(x, y, z); vals[ri][ci] = rho
        }
    }
    return Mesh(pts, vals, colorArgb)
}

private suspend fun parametricMesh(p: Param3D, snap: Plot3DSnap, colorArgb: Int? = null): Mesh? {
    val xe = runCatching { ExpressionParser.parse(p.x) }.getOrNull() ?: return null
    val ye = runCatching { ExpressionParser.parse(p.y) }.getOrNull() ?: return null
    val ze = runCatching { ExpressionParser.parse(p.z) }.getOrNull() ?: return null
    val n = snap.n
    // u ∈ [x0, x1], v ∈ [y0, y1]; when shareT, the v parameter IS the animation t:
    // v spans the anim range and the surface grows up to the current t while playing.
    val pts = Array(n) { Array(n) { Vec3(0.0, 0.0, 0.0) } }
    val vals = Array(n) { DoubleArray(n) }
    val scope = EvalScope()
    if (!snap.shareT && snap.t != null && snap.t.isFinite()) {
        scope.vars["t"] = Complex.real(snap.t)
        scope.vars["tAnim"] = Complex.real(snap.t)
    }
    for (ri in 0 until n) {
        coroutineContext.ensureActive()
        for (ci in 0 until n) {
            val u = snap.x0 + (snap.x1 - snap.x0) * ci / (n - 1)
            var v = snap.y0 + (snap.y1 - snap.y0) * ri / (n - 1)
            if (snap.shareT && snap.animRange != null && snap.t != null) {
                val lo = minOf(snap.animRange.lo, snap.animRange.hi); val hi = maxOf(snap.animRange.lo, snap.animRange.hi)
                // Map row fraction to [lo, current t] so play draws the surface growing.
                val frac = ri.toDouble() / (n - 1)
                v = lo + frac * ((snap.t.coerceIn(lo, hi)) - lo)
                scope.vars["t"] = Complex.real(v)
                scope.vars["tAnim"] = Complex.real(v)
            } else if (snap.t == null) {
                scope.vars["t"] = Complex.real(u)
            }
            scope.vars["u"] = Complex.real(u); scope.vars["v"] = Complex.real(v)
            val x = evalReal(scope, xe); val y = evalReal(scope, ye); val z = evalReal(scope, ze)
            pts[ri][ci] = Vec3(x, y, z); vals[ri][ci] = z
        }
    }
    return Mesh(pts, vals, colorArgb)
}

private suspend fun complexMesh(text: String, snap: Plot3DSnap, colorArgb: Int? = null): Mesh? {
    val expr = runCatching { ExpressionParser.parse(text) }.getOrNull() ?: return null
    val n = snap.n
    val pts = Array(n) { Array(n) { Vec3(0.0, 0.0, 0.0) } }
    val vals = Array(n) { DoubleArray(n) }
    // Item A: no t binding for complex surfaces; the parameter is meaningless there.
    val scope = EvalScope()
    for (ri in 0 until n) {
        coroutineContext.ensureActive()
        for (ci in 0 until n) {
            val x = snap.x0 + (snap.x1 - snap.x0) * ci / (n - 1); val y = snap.y0 + (snap.y1 - snap.y0) * ri / (n - 1)
            bindPlane(scope, x, y)
            val v = runCatching { Evaluator.evaluate(expr, scope) }.getOrDefault(Complex.NaN)
            val h = when (snap.component) {
                ComplexComponent.REAL -> v.re; ComplexComponent.IMAG -> v.im
                ComplexComponent.MAGNITUDE -> if (v.isFinite) v.abs() else Double.NaN
                ComplexComponent.ARGUMENT -> if (v.isFinite && v.abs() > 0.0) v.arg() else Double.NaN
            }
            pts[ri][ci] = Vec3(x, y, h); vals[ri][ci] = h
        }
    }
    return Mesh(pts, vals, colorArgb)
}

private suspend fun implicitCloud(text: String, snap: Plot3DSnap, colorArgb: Int? = null): Cloud3? {
    val expr = runCatching { ExpressionParser.parse(text) }.getOrNull() ?: return null
    val n = snap.n.coerceIn(14, 48)
    val scope = scopeWithT(snap.t)
    val tol = minOf(snap.x1 - snap.x0, snap.y1 - snap.y0, snap.z1 - snap.z0).coerceAtLeast(0.0) * 0.06 + 0.25
    val pts = ArrayList<Vec3>(); val vals = ArrayList<Double>()
    for (k in 0 until n) {
        coroutineContext.ensureActive()
        for (j in 0 until n) for (i in 0 until n) {
            val x = snap.x0 + (snap.x1 - snap.x0) * (i + 0.5) / n; val y = snap.y0 + (snap.y1 - snap.y0) * (j + 0.5) / n; val z = snap.z0 + (snap.z1 - snap.z0) * (k + 0.5) / n
            bindXYZ(scope, x, y, z)
            val v = evalReal(scope, expr)
            if (v.isFinite() && abs(v) < tol) { pts += Vec3(x, y, z); vals += v }
        }
    }
    if (pts.isEmpty()) return null
    val sorted = vals.filter { it.isFinite() }.sorted()
    if (sorted.isEmpty()) return null
    val lo = sorted[(sorted.size * 0.02).toInt().coerceIn(0, sorted.lastIndex)]
    val hi = sorted[(sorted.size * 0.98).toInt().coerceIn(0, sorted.lastIndex)]
    val capped = if (pts.size > 4000) {
        val step = pts.size / 4000 + 1
        pts.filterIndexed { idx, _ -> idx % step == 0 } to vals.filterIndexed { idx, _ -> idx % step == 0 }
    } else pts to vals
    return Cloud3(capped.first, capped.second, lo, hi, colorArgb)
}

/** High-quality raster: quaternion camera, per-quad Lambert lighting, depth-sorted meshes + clouds. */
private suspend fun rasterMeshes(data: MeshSet, snap: Plot3DSnap, quat: Quat, zoom: Float, width: Int, height: Int, theme: GraphTheme): Bitmap {
    val w = width.coerceIn(2, 1400); val h = height.coerceIn(2, 1400)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(theme.paper)
    val cxm = (snap.x0 + snap.x1) / 2; val cym = (snap.y0 + snap.y1) / 2; val czm = (snap.z0 + snap.z1) / 2
    val xH = (snap.x1 - snap.x0) / 2; val yH = (snap.y1 - snap.y0) / 2; val zH = (snap.z1 - snap.z0) / 2
    fun proj(p: Vec3): Triple<Float, Float, Float>? {
        if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite()) return null
        return quatProject(p.x, p.y, p.z, quat, zoom, w, h, xH, yH, zH, cxm, cym, czm)
    }
    drawQuatSpaceFrame(
        canvas,
        { x, y, z -> quatProject(x, y, z, quat, zoom, w, h, xH, yH, zH, cxm, cym, czm) },
        quat, cxm, cym, czm, xH, yH, zH, w, h, theme, snap.showGrid,
    )
    // Light in view space.
    val lx = 0.45; val ly = 0.55; val lz = 0.7
    data class Cell(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float, val depth: Float, val color: Int)
    val cells = ArrayList<Cell>()
    val span = (data.hi - data.lo).coerceAtLeast(1e-12)
    for (mesh in data.meshes) {
        val n = mesh.pts.size
        val px = Array(n) { FloatArray(n) }; val py = Array(n) { FloatArray(n) }; val pd = Array(n) { FloatArray(n) }; val ok = Array(n) { BooleanArray(n) }
        for (ri in 0 until n) for (ci in 0 until n) {
            val pr = proj(mesh.pts[ri][ci])
            if (pr == null || !mesh.vals[ri][ci].isFinite()) { ok[ri][ci] = false; continue }
            ok[ri][ci] = true; px[ri][ci] = pr.first; py[ri][ci] = pr.second; pd[ri][ci] = pr.third
        }
        for (ri in 0 until n - 1) {
            if (ri % 8 == 0) coroutineContext.ensureActive()
            for (ci in 0 until n - 1) {
                if (!ok[ri][ci] || !ok[ri][ci + 1] || !ok[ri + 1][ci + 1] || !ok[ri + 1][ci]) continue
                val avg = (mesh.vals[ri][ci] + mesh.vals[ri][ci + 1] + mesh.vals[ri + 1][ci + 1] + mesh.vals[ri + 1][ci]) / 4.0
                if (!avg.isFinite()) continue
                val t = ((avg - data.lo) / span).coerceIn(0.0, 1.0)
                // Normal from screen-space edges for Lambert shading.
                val ax = px[ri][ci + 1] - px[ri][ci]; val ay = py[ri][ci + 1] - py[ri][ci]
                val bx = px[ri + 1][ci] - px[ri][ci]; val by = py[ri + 1][ci] - py[ri][ci]
                var nz = ax * by - ay * bx
                nz = if (nz == 0f) 1f else abs(nz)
                val norm = sqrt((ax * ax + ay * ay + 1f).toDouble()) * sqrt((bx * bx + by * by + 1f).toDouble())
                val shade = (0.68 + 0.32 * (nz.toDouble() / norm).coerceIn(0.0, 1.0))
                val lit = (0.72 + 0.28 * shade)
                val base = mesh.colorArgb ?: heightArgb(t)
                val col = android.graphics.Color.rgb(
                    (android.graphics.Color.red(base) * lit).toInt().coerceIn(0, 255),
                    (android.graphics.Color.green(base) * lit).toInt().coerceIn(0, 255),
                    (android.graphics.Color.blue(base) * lit).toInt().coerceIn(0, 255),
                )
                cells += Cell(px[ri][ci], py[ri][ci], px[ri][ci + 1], py[ri][ci + 1], px[ri + 1][ci + 1], py[ri + 1][ci + 1], px[ri + 1][ci], py[ri + 1][ci], (pd[ri][ci] + pd[ri][ci + 1] + pd[ri + 1][ci + 1] + pd[ri + 1][ci]) / 4f, col)
            }
        }
    }
    cells.sortBy { it.depth }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    val path = android.graphics.Path()
    for (c in cells) {
        path.rewind(); path.moveTo(c.x0, c.y0); path.lineTo(c.x1, c.y1); path.lineTo(c.x2, c.y2); path.lineTo(c.x3, c.y3); path.close()
        paint.color = c.color; canvas.drawPath(path, paint)
    }
    // Implicit clouds on top, depth-sorted, attenuated.
    data class Dot(val x: Float, val y: Float, val d: Float, val col: Int, val r: Float)
    val dots = ArrayList<Dot>()
    for (cloud in data.clouds) {
        for (k in cloud.pts.indices) {
            val pr = proj(cloud.pts[k]) ?: continue
            val v = cloud.vals[k]; if (!v.isFinite()) continue
            val t = ((v - data.lo) / span).coerceIn(0.0, 1.0)
            dots += Dot(pr.first, pr.second, pr.third, cloud.colorArgb ?: heightArgb(t), 4.2f)
        }
    }
    dots.sortBy { it.d }
    val dotP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    for (d in dots) { dotP.color = d.col; canvas.drawCircle(d.x, d.y, d.r, dotP) }
    return bmp
}

/** Blue → cyan → green → yellow → red, the usual height ramp in scientific plotting. */
internal fun heightColor(t: Double): androidx.compose.ui.graphics.Color {
    val stops = listOf(
        androidx.compose.ui.graphics.Color(0xFF2E5AAC),
        androidx.compose.ui.graphics.Color(0xFF2C9BB5),
        androidx.compose.ui.graphics.Color(0xFF4BAE62),
        androidx.compose.ui.graphics.Color(0xFFD8B33C),
        androidx.compose.ui.graphics.Color(0xFFC74B3A),
    )
    val x = t.coerceIn(0.0, 1.0) * (stops.size - 1)
    val i = x.toInt().coerceIn(0, stops.lastIndex - 1)
    val f = (x - i).toFloat()
    val a = stops[i]; val b = stops[i + 1]
    return androidx.compose.ui.graphics.Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f)
}
