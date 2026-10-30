package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.canvas.StylusCalibrator
import com.xnotes.core.geometry.Pt
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.util.Locale

/**
 * Guided stylus-offset calibration.
 *
 * The pad shows [StylusCalibrator.TARGET_COUNT] crosshairs; each stylus tap records
 * where the digitizer reported the touch versus where the crosshair was. The robust
 * mean of `target − reported` is the correction. Finger taps are ignored with a hint,
 * outliers (mis-taps) are dropped and counted, and the residual spread grades the run.
 * A test pad previews the corrected nib before accepting.
 */
@Composable
fun StylusCalibrationDialog(
    onApply: (dxDp: Double, dyDp: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current.density
    var taps by remember { mutableStateOf(listOf<StylusCalibrator.Tap>()) }
    var fingerHint by remember { mutableStateOf(false) }
    var testStrokes by remember { mutableStateOf(listOf<List<Offset>>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stylus_calibrate_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.stylus_calibrate_intro, StylusCalibrator.TARGET_COUNT),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 13.sp,
                )
                if (taps.size < StylusCalibrator.TARGET_COUNT) {
                    Text(
                        stringResource(
                            R.string.stylus_calibrate_tap_n,
                            (taps.size + 1).coerceAtMost(StylusCalibrator.TARGET_COUNT),
                            StylusCalibrator.TARGET_COUNT,
                        ),
                        color = palette.text.toComposeColor(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                if (fingerHint) {
                    Text(
                        stringResource(R.string.stylus_calibrate_use_stylus),
                        color = palette.accent.toComposeColor(),
                        fontSize = 13.sp,
                    )
                }
                CalibrationPad(
                    taps = taps,
                    onStylusTap = { tap ->
                        taps = taps + tap
                        fingerHint = false
                    },
                    onFingerTap = { fingerHint = true },
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                )
                val result = remember(taps, density) {
                    if (taps.size >= StylusCalibrator.MIN_TAPS) {
                        runCatching { StylusCalibrator.estimate(taps, density.toDouble()) }.getOrNull()
                    } else null
                }
                if (taps.isNotEmpty() && result == null) {
                    Text(
                        stringResource(R.string.stylus_calibrate_no_pen),
                        color = palette.textDim.toComposeColor(),
                        fontSize = 13.sp,
                    )
                }
                result?.let { r ->
                    val qualityLabel = when (r.quality) {
                        StylusCalibrator.Quality.EXCELLENT -> stringResource(R.string.stylus_calibrate_excellent)
                        StylusCalibrator.Quality.GOOD -> stringResource(R.string.stylus_calibrate_good)
                        StylusCalibrator.Quality.FAIR -> stringResource(R.string.stylus_calibrate_fair)
                        StylusCalibrator.Quality.POOR -> stringResource(R.string.stylus_calibrate_poor)
                    }
                    Text(
                        stringResource(
                            R.string.stylus_calibrate_result,
                            formatDp(r.offsetXDp),
                            formatDp(r.offsetYDp),
                        ),
                        color = palette.text.toComposeColor(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.stylus_calibrate_spread,
                            formatDp(r.spreadDp),
                            qualityLabel,
                        ),
                        color = palette.textDim.toComposeColor(),
                        fontSize = 13.sp,
                    )
                    if (r.total - r.kept > 0) {
                        Text(
                            stringResource(R.string.stylus_calibrate_dropped, r.total - r.kept),
                            color = palette.textDim.toComposeColor(),
                            fontSize = 13.sp,
                        )
                    }
                    Text(
                        stringResource(R.string.stylus_calibrate_test_help),
                        color = palette.textDim.toComposeColor(),
                        fontSize = 13.sp,
                    )
                    StylusTestPad(
                        offsetDp = Pt(r.offsetXDp, r.offsetYDp),
                        strokes = testStrokes,
                        onStrokes = { testStrokes = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { testStrokes = emptyList() }) {
                            Text(stringResource(R.string.clear))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    taps = emptyList()
                    testStrokes = emptyList()
                    fingerHint = false
                }) { Text(stringResource(R.string.stylus_calibrate_retry)) }
                val r = if (taps.size >= StylusCalibrator.MIN_TAPS) {
                    runCatching { StylusCalibrator.estimate(taps, density.toDouble()) }.getOrNull()
                } else null
                TextButton(
                    enabled = r != null && r.quality != StylusCalibrator.Quality.POOR,
                    onClick = {
                        val rr = r ?: return@TextButton
                        onApply(rr.offsetXDp, rr.offsetYDp)
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun formatDp(v: Double): String = String.format(Locale.US, "%+.1f", v)

/** The crosshair field: one live target at a time over faint completed/dots. */
@Composable
private fun CalibrationPad(
    taps: List<StylusCalibrator.Tap>,
    onStylusTap: (StylusCalibrator.Tap) -> Unit,
    onFingerTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val accent = palette.accent.toComposeColor()
    val textDim = palette.textDim.toComposeColor()
    val surface = palette.surface.toComposeColor()
    BoxWithConstraints(modifier.background(surface, MaterialTheme.shapes.small)) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }
        val targets = remember(wPx, hPx) {
            StylusCalibrator.targetsFor(wPx.toDouble(), hPx.toDouble()).map {
                Offset(it.x.toFloat(), it.y.toFloat())
            }
        }
        val next = taps.size
        // Read fresh at event time: the pointerInput block below does not restart between taps.
        val nextState = rememberUpdatedState(next)
        val targetsState = rememberUpdatedState(targets)
        val tapState = rememberUpdatedState(onStylusTap)
        val fingerState = rememberUpdatedState(onFingerTap)
        Box(
            Modifier.fillMaxSize().pointerInput(targets) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    val n = nextState.value
                    val target = targetsState.value.getOrNull(n)
                    if (!stylus) {
                        fingerState.value()
                    } else if (target != null) {
                        tapState.value(
                            StylusCalibrator.Tap(
                                targetX = target.x.toDouble(),
                                targetY = target.y.toDouble(),
                                reportedX = down.position.x.toDouble(),
                                reportedY = down.position.y.toDouble(),
                            ),
                        )
                    }
                    // Swallow the rest of this gesture so it never leaks to the scroller.
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = 26f
                targets.forEachIndexed { i, t ->
                    when {
                        i < taps.size -> {
                            // Done: small filled dot where the target was.
                            drawCircle(accent, radius = 5f, center = t)
                        }
                        i == next -> {
                            // Live target: crosshair + ring.
                            drawCircle(accent, radius = r, center = t, style = Stroke(width = 3f))
                            drawLine(accent, Offset(t.x - r - 10f, t.y), Offset(t.x - 8f, t.y), strokeWidth = 3f)
                            drawLine(accent, Offset(t.x + 8f, t.y), Offset(t.x + r + 10f, t.y), strokeWidth = 3f)
                            drawLine(accent, Offset(t.x, t.y - r - 10f), Offset(t.x, t.y - 8f), strokeWidth = 3f)
                            drawLine(accent, Offset(t.x, t.y + 8f), Offset(t.x, t.y + r + 10f), strokeWidth = 3f)
                            drawCircle(accent, radius = 2.5f, center = t)
                        }
                        else -> {
                            drawCircle(textDim.copy(alpha = 0.5f), radius = 4f, center = t)
                        }
                    }
                }
            }
            if (next < targets.size) {
                Text(
                    "${next + 1}",
                    color = accent,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
        }
    }
}

/**
 * A scratch pad that previews a candidate correction: each stroke is drawn at
 * `reported + offset`, so the line lands where the nib visually aimed — the same
 * transform the canvases apply. Works with finger too (then it just draws).
 * Shared by the guided dialog and the always-visible field in Settings.
 */
@Composable
fun StylusTestPad(
    offsetDp: Pt,
    strokes: List<List<Offset>>,
    onStrokes: (List<List<Offset>>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current.density
    val ox = (offsetDp.x * density).toFloat()
    val oy = (offsetDp.y * density).toFloat()
    val ink = palette.text.toComposeColor()
    val surface = palette.surface.toComposeColor()
    val border = palette.border.toComposeColor()
    // Read fresh at event time: this block must not restart (and drop the live
    // stroke) when the list grows, so the callbacks come from updated states.
    val strokesState = rememberUpdatedState(strokes)
    val onStrokesState = rememberUpdatedState(onStrokes)
    // The pad's own size in px, so sampled points clamp to its bounds: a pointer
    // that slides off the pad keeps drawing along its edge instead of across
    // the whole screen. Keyed into the gesture block, so it never goes stale.
    var padPx by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(surface, MaterialTheme.shapes.small)
            .onSizeChanged { padPx = it }
            .pointerInput(ox, oy, padPx) {
                fun clamp(p: Offset): Offset = Offset(
                    p.x.coerceIn(0f, padPx.width.toFloat().coerceAtLeast(0f)),
                    p.y.coerceIn(0f, padPx.height.toFloat().coerceAtLeast(0f)),
                )
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val cur = mutableListOf(clamp(Offset(down.position.x + ox, down.position.y + oy)))
                    onStrokesState.value(strokesState.value + listOf(cur.toList()))
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            cur += clamp(Offset(change.position.x + ox, change.position.y + oy))
                            // Replace the in-progress stroke.
                            onStrokesState.value(strokesState.value.dropLast(1) + listOf(cur.toList()))
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Border.
            drawRect(border, style = Stroke(width = 1f))
            // Faint centre cross so drift is visible.
            drawLine(
                border.copy(alpha = 0.6f),
                Offset(w / 2 - 30f, h / 2), Offset(w / 2 + 30f, h / 2), strokeWidth = 1f,
            )
            drawLine(
                border.copy(alpha = 0.6f),
                Offset(w / 2, h / 2 - 30f), Offset(w / 2, h / 2 + 30f), strokeWidth = 1f,
            )
            for (s in strokes) {
                if (s.size < 2) {
                    s.firstOrNull()?.let { drawCircle(ink, radius = 3f, center = it) }
                    continue
                }
                val path = Path().apply {
                    moveTo(s[0].x, s[0].y)
                    for (i in 1 until s.size) lineTo(s[i].x, s[i].y)
                }
                drawPath(path, ink, style = Stroke(width = 4f))
            }
        }
        if (strokes.isEmpty()) {
            Text(
                "✎",
                color = border,
                fontSize = 28.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
