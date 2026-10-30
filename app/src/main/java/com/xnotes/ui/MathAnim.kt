package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared time parameter for animated plots. Disabled = static plot. */
internal class AnimState {
    var enabled by mutableStateOf(false)
    var tMinStr by mutableStateOf("0")
    var tMaxStr by mutableStateOf("6.28")
    var tStepsStr by mutableStateOf("60")
    var tCurrent by mutableStateOf(0.0)
    var playing by mutableStateOf(false)
    /** Parametric sharing: animation t IS the curve parameter t (item 11). Default OFF. */
    var shareT by mutableStateOf(false)

    fun snap(): AnimSnap? {
        if (!enabled) return null
        val lo = tMinStr.toDoubleOrNull() ?: return null
        val hi = tMaxStr.toDoubleOrNull() ?: return null
        if (!lo.isFinite() || !hi.isFinite() || lo == hi) return null
        val steps = tStepsStr.toIntOrNull()?.coerceIn(2, 400) ?: 60
        return AnimSnap(lo, hi, steps, tCurrent.coerceIn(minOf(lo, hi), maxOf(lo, hi)), shareT)
    }

    fun values(): DoubleArray {
        val s = snap() ?: return doubleArrayOf()
        val (lo, hi) = if (s.lo <= s.hi) s.lo to s.hi else s.hi to s.lo
        return DoubleArray(s.steps) { k -> lo + (hi - lo) * k / (s.steps - 1) }
    }
}

internal data class AnimSnap(val lo: Double, val hi: Double, val steps: Int, val current: Double, val shareT: Boolean)

@Composable
internal fun AnimPanel(state: AnimState, showShare: Boolean = false, title: String = "Animation") {
    val palette = LocalPalette.current
    MathCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.enabled, onCheckedChange = {
                state.enabled = it
                if (!it) state.playing = false
            })
            MathSectionTitle(title, Modifier.weight(1f))
            if (state.enabled) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(palette.accent.toComposeColor())
                        .clickable { state.playing = !state.playing }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(if (state.playing) "❚❚" else "▶", color = palette.onAccent.toComposeColor(), fontSize = 12.sp)
                }
            }
        }
        if (state.enabled) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MathTextField(state.tMinStr, { state.tMinStr = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "t min", Modifier.weight(1f))
                MathTextField(state.tMaxStr, { state.tMaxStr = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, "t max", Modifier.weight(1f))
                MathTextField(state.tStepsStr, { state.tStepsStr = it.filter(Char::isDigit) }, "steps", Modifier.weight(1f))
            }
            if (showShare) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.shareT, onCheckedChange = { state.shareT = it })
                    Text("Link t to curve", color = palette.textDim.toComposeColor(), fontSize = 11.sp)
                }
            }
            val snap = state.snap()
            if (snap != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("t = ${formatT(state.tCurrent)}", color = palette.text.toComposeColor(), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Slider(
                        value = state.tCurrent.toFloat().coerceIn(minOf(snap.lo, snap.hi).toFloat(), maxOf(snap.lo, snap.hi).toFloat()),
                        onValueChange = { state.tCurrent = it.toDouble() },
                        valueRange = minOf(snap.lo, snap.hi).toFloat()..maxOf(snap.lo, snap.hi).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun formatT(v: Double): String = if (v.isFinite()) "%.3f".format(v) else "—"

/** Advances [state] while playing. Call from any plot canvas. */
@Composable
internal fun AnimTicker(state: AnimState) {
    if (state.enabled && state.playing) {
        val snap = state.snap()
        LaunchedEffect(snap?.lo, snap?.hi, snap?.steps) {
            if (snap == null) return@LaunchedEffect
            val lo = minOf(snap.lo, snap.hi); val hi = maxOf(snap.lo, snap.hi)
            val step = (hi - lo) / snap.steps.coerceAtLeast(2)
            while (true) {
                kotlinx.coroutines.delay(50)
                var next = state.tCurrent + step
                if (next > hi) next = lo
                state.tCurrent = next
            }
        }
    }
}

@Composable
internal fun androidx.compose.foundation.layout.BoxScope.AnimPlayOverlay(state: AnimState) {
    if (!state.enabled) return
    val palette = LocalPalette.current
    Box(
        Modifier.align(Alignment.TopEnd).padding(8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.panel.toComposeColor().copy(alpha = 0.92f))
            .clickable { state.playing = !state.playing }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (state.playing) "Pause" else "Play",
                tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp),
            )
            Text("t=${formatT(state.tCurrent)}", color = palette.text.toComposeColor(), fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

// --------------------------------------------------------------------------- quaternion trackball

/** Unit quaternion camera. No clamps: full 360° on every axis (items 7, 14). */
internal data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-12) return IDENTITY
        return Quat(w / n, x / n, y / n, z / n)
    }
    operator fun times(o: Quat): Quat = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    ).normalized()

    /** 3x3 rotation matrix, row-major. */
    fun matrix(): DoubleArray {
        val (w, x, y, z) = this.normalized()
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)
        fun axisAngle(ax: Double, ay: Double, az: Double, angle: Double): Quat {
            val n = sqrt(ax * ax + ay * ay + az * az)
            if (n < 1e-12) return IDENTITY
            val h = angle / 2
            val s = sin(h) / n
            return Quat(cos(h), ax * s, ay * s, az * s).normalized()
        }
    }
}

internal class OrbitState {
    var quat by mutableStateOf(Quat.axisAngle(1.0, 0.0, 0.0, -0.46).times(Quat.axisAngle(0.0, 0.0, 1.0, 0.72)))
    var zoom by mutableStateOf(1f)

    fun reset() {
        quat = Quat.axisAngle(1.0, 0.0, 0.0, -0.46).times(Quat.axisAngle(0.0, 0.0, 1.0, 0.72))
        zoom = 1f
    }

    /**
     * Free trackball with content-follows-finger direction: dragging right moves the
     * graph right, dragging up moves it up. No clamps: full 360° on every axis.
     */
    fun drag(dxPx: Float, dyPx: Float) {
        val k = 0.008
        val yaw = Quat.axisAngle(0.0, 0.0, 1.0, dxPx * k)
        val pitch = Quat.axisAngle(1.0, 0.0, 0.0, dyPx * k)
        quat = (yaw.times(pitch)).times(quat)
    }
}

/** Projects world (x,y,z) with a quaternion camera around [center]. Ranges normalize each axis to [-1,1]. */
internal fun quatProject(
    x: Double, y: Double, z: Double,
    quat: Quat, zoom: Float,
    w: Int, h: Int,
    xRange: Double, yRange: Double, zRange: Double,
    cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0,
): Triple<Float, Float, Float> {
    val m = quat.matrix()
    val nx = ((x - cx) / xRange).coerceIn(-1.6, 1.6)
    val ny = ((y - cy) / yRange).coerceIn(-1.6, 1.6)
    val nz = ((z - cz) / zRange).coerceIn(-1.6, 1.6)
    val rx = m[0] * nx + m[1] * ny + m[2] * nz
    val ry = m[3] * nx + m[4] * ny + m[5] * nz
    val rz = m[6] * nx + m[7] * ny + m[8] * nz
    val scale = minOf(w, h) * 0.36f * zoom
    return Triple((w / 2f + rx * scale).toFloat(), (h * 0.54f - ry * scale).toFloat(), rz.toFloat())
}
