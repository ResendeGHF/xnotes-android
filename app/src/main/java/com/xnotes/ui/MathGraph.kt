package com.xnotes.ui

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.math.Numbers
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One labelled swatch baked into an exported graph. [ramp] draws the height scale instead of a dot. */
internal class ExportLegend(
    val label: String,
    val color: Int,
    val ramp: Boolean = false,
)

/** Palette colours flattened to Android ints so a bitmap can be painted off the UI thread. */
internal class GraphTheme(
    val paper: Int,
    val ink: Int,
    val dim: Int,
    val grid: Int,
    val axis: Int,
    val border: Int,
    val accent: Int,
    val series: List<Int>,
)

/** A sampled polyline in world coordinates. Doubles, so a zoom near a large offset stays accurate. */
internal class Polyline(val xy: DoubleArray)

internal const val EXPORT_PLOT_W = 1200
internal const val EXPORT_PLOT_H = 760

@Composable
internal fun rememberGraphTheme(): GraphTheme {
    val palette = LocalPalette.current
    val series = mathPlotColors()
    return remember(palette, series) {
        GraphTheme(
            paper = palette.paper.packed(),
            ink = palette.text.packed(),
            dim = palette.textDim.packed(),
            grid = palette.border.packed(),
            axis = palette.textDim.packed(),
            border = palette.border.packed(),
            accent = palette.accent.packed(),
            series = series.map { it.toArgb() },
        )
    }
}

/**
 * Holds [value] still until it stops changing. Text and the view window use this so a keystroke
 * or a finger-drag does not restart a plot sample on every event.
 */
@Composable
internal fun <T> rememberSettled(value: T, delayMillis: Long = 140L): T {
    var settled by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        delay(delayMillis)
        settled = value
    }
    return settled
}

/**
 * The shared frame for every graphical pane: the plot keeps the screen, the expressions sit in a
 * short scrolling tray, and Insert stays in the header.
 */
@Composable
internal fun GraphWorkspace(
    modes: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    legend: List<Pair<String, Color>>,
    ramp: Pair<String, String>? = null,
    caption: String? = null,
    showGrid: Boolean? = null,
    onToggleGrid: (() -> Unit)? = null,
    onReset: () -> Unit,
    inserting: Boolean,
    onInsert: () -> Unit,
    status: String? = null,
    plot: @Composable BoxScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MathToolSelector(modes, selected, Modifier.weight(1f), onSelect)
            if (onToggleGrid != null && showGrid != null) {
                MathIconButton(
                    Icons.Outlined.GridOn,
                    stringResource(R.string.math_toggle_grid),
                    tint = if (showGrid) palette.accent.toComposeColor() else null,
                    onClick = onToggleGrid,
                )
            }
            MathIconButton(Icons.Outlined.Refresh, stringResource(R.string.math_reset_view), onClick = onReset)
            Spacer(Modifier.width(2.dp))
            InsertGraphButton(inserting, onInsert)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(12.dp))
                .background(palette.paper.toComposeColor()),
        ) {
            plot()
            if (legend.isNotEmpty() || ramp != null || caption != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.paper.toComposeColor().copy(alpha = 0.92f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    if (legend.isNotEmpty()) PlotLegend(legend)
                    if (ramp != null) {
                        Spacer(Modifier.height(3.dp))
                        RampRow(ramp.first, ramp.second)
                    }
                    if (caption != null) {
                        Text(
                            caption,
                            color = palette.textDim.toComposeColor(),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (status != null) {
            Spacer(Modifier.height(4.dp))
            Text(status, color = palette.danger.toComposeColor(), fontSize = 11.sp, maxLines = 2)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 210.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = controls,
        )
    }
}

@Composable
private fun RampRow(low: String, high: String) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(low, color = palette.textDim.toComposeColor(), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            heightColor(0.0),
                            heightColor(0.25),
                            heightColor(0.5),
                            heightColor(0.75),
                            heightColor(1.0),
                        ),
                    ),
                ),
        )
        Text(high, color = palette.textDim.toComposeColor(), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun InsertGraphButton(inserting: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val label = stringResource(if (inserting) R.string.math_3d_computing else R.string.math_insert_graph)
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (inserting) palette.surfaceHi.toComposeColor() else palette.accent.toComposeColor())
            .clickable(enabled = !inserting, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Outlined.AddPhotoAlternate,
            contentDescription = null,
            tint = (if (inserting) palette.disabled else palette.onAccent).toComposeColor(),
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            color = (if (inserting) palette.disabled else palette.onAccent).toComposeColor(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** A slider that reports a value only when the finger lifts, so dragging does not resample. */
@Composable
internal fun CommittedSlider(
    value: Float,
    onCommit: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    modifier: Modifier = Modifier,
    readout: (Float) -> String = { it.toInt().toString() },
) {
    val palette = LocalPalette.current
    var draft by remember(value) { mutableFloatStateOf(value) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 10.sp)
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
        )
        Text(
            readout(draft),
            color = palette.textDim.toComposeColor(),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp),
        )
    }
}

internal fun windowFootnote(xMin: Double, xMax: Double, yMin: Double, yMax: Double): String =
    "x ∈ [${Numbers.tick(xMin)}, ${Numbers.tick(xMax)}]    y ∈ [${Numbers.tick(yMin)}, ${Numbers.tick(yMax)}]"

internal fun drawPlotFrame(
    canvas: android.graphics.Canvas,
    w: Int,
    h: Int,
    xMin: Double,
    xMax: Double,
    yMin: Double,
    yMax: Double,
    theme: GraphTheme,
    grid: Boolean,
) {
    val xSpan = (xMax - xMin).takeIf { it.isFinite() && abs(it) > 1e-15 } ?: return
    val ySpan = (yMax - yMin).takeIf { it.isFinite() && abs(it) > 1e-15 } ?: return
    fun sx(x: Double) = ((x - xMin) / xSpan * w).toFloat()
    fun sy(y: Double) = ((yMax - y) / ySpan * h).toFloat()
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.grid; strokeWidth = 1.4f; alpha = 90 }
    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.axis; strokeWidth = 2.2f }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.dim
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }
    if (grid) {
        val stepX = niceStep(xSpan, max(2, w / 140))
        val stepY = niceStep(ySpan, max(2, h / 110))
        var gx = kotlin.math.floor(xMin / stepX) * stepX
        var guard = 0
        while (gx <= xMax && guard++ < 80) {
            canvas.drawLine(sx(gx), 0f, sx(gx), h.toFloat(), gridPaint)
            gx += stepX
        }
        var gy = kotlin.math.floor(yMin / stepY) * stepY
        guard = 0
        while (gy <= yMax && guard++ < 80) {
            canvas.drawLine(0f, sy(gy), w.toFloat(), sy(gy), gridPaint)
            gy += stepY
        }
        gx = kotlin.math.floor(xMin / stepX) * stepX
        guard = 0
        while (gx <= xMax && guard++ < 80) {
            if (abs(gx) > stepX * 1e-6) {
                val label = Numbers.tick(gx)
                canvas.drawText(label, sx(gx) + 4f, (h - 10).toFloat(), text)
            }
            gx += stepX
        }
        gy = kotlin.math.floor(yMin / stepY) * stepY
        guard = 0
        while (gy <= yMax && guard++ < 80) {
            if (abs(gy) > stepY * 1e-6) canvas.drawText(Numbers.tick(gy), 8f, sy(gy) - 4f, text)
            gy += stepY
        }
    }
    if (0.0 in yMin..yMax) canvas.drawLine(0f, sy(0.0), w.toFloat(), sy(0.0), axisPaint)
    if (0.0 in xMin..xMax) canvas.drawLine(sx(0.0), 0f, sx(0.0), h.toFloat(), axisPaint)
}

internal fun drawPolylines(
    canvas: android.graphics.Canvas,
    strokes: List<Polyline>,
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
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val path = android.graphics.Path()
    for (stroke in strokes) {
        val xy = stroke.xy
        if (xy.size < 4) continue
        path.rewind()
        var i = 0
        while (i < xy.size) {
            val sx = ((xy[i] - xMin) / xSpan * w).toFloat()
            val sy = ((yMax - xy[i + 1]) / ySpan * h).toFloat()
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            i += 2
        }
        canvas.drawPath(path, paint)
    }
}

/** Plot bitmap plus a legend strip, encoded as PNG. Recycles [plot]. */
internal fun writeGraphPng(
    plot: Bitmap,
    title: String,
    legend: List<ExportLegend>,
    footnote: String,
    theme: GraphTheme,
): ByteArray {
    val pad = 32
    val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.ink
        textSize = 32f
        typeface = Typeface.MONOSPACE
    }
    val layouts = legend.map { item ->
        val width = (plot.width - if (item.ramp) 168 else 56).coerceAtLeast(80)
        StaticLayout.Builder.obtain(item.label, 0, item.label.length, body, width)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
    }
    val legendHeight = if (layouts.isEmpty()) 0 else layouts.sumOf { it.height + 22 }
    val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.dim
        textSize = 26f
        typeface = Typeface.MONOSPACE
    }
    val foot = StaticLayout.Builder.obtain(footnote, 0, footnote.length, footPaint, plot.width.coerceAtLeast(80))
        .setMaxLines(3)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
    val width = plot.width + pad * 2
    val height = pad + 58 + plot.height + 22 + legendHeight + foot.height + pad
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(theme.paper)
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.ink
        textSize = 42f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    canvas.drawText(title, pad.toFloat(), (pad + 38).toFloat(), titlePaint)
    val plotTop = pad + 56
    canvas.drawBitmap(plot, pad.toFloat(), plotTop.toFloat(), null)
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.border
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawRect(
        pad.toFloat(),
        plotTop.toFloat(),
        (pad + plot.width).toFloat(),
        (plotTop + plot.height).toFloat(),
        border,
    )
    var y = plotTop + plot.height + 20
    val swatch = Paint(Paint.ANTI_ALIAS_FLAG)
    layouts.forEachIndexed { index, layout ->
        val item = legend[index]
        val mid = y + layout.height / 2f
        val textLeft = if (item.ramp) {
            val bar = android.graphics.RectF(pad.toFloat(), mid - 9f, (pad + 132).toFloat(), mid + 9f)
            swatch.shader = LinearGradient(
                bar.left, bar.centerY(), bar.right, bar.centerY(),
                intArrayOf(heightArgb(0.0), heightArgb(0.25), heightArgb(0.5), heightArgb(0.75), heightArgb(1.0)),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(bar, 8f, 8f, swatch)
            swatch.shader = null
            pad + 148
        } else {
            swatch.color = item.color
            canvas.drawCircle((pad + 12).toFloat(), mid, 10f, swatch)
            pad + 36
        }
        canvas.save()
        canvas.translate(textLeft.toFloat(), y.toFloat())
        layout.draw(canvas)
        canvas.restore()
        y += layout.height + 18
    }
    canvas.save()
    canvas.translate(pad.toFloat(), (y + 6).toFloat())
    foot.draw(canvas)
    canvas.restore()
    plot.recycle()
    val bytes = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.PNG, 100, bytes)
    out.recycle()
    return bytes.toByteArray()
}

internal fun heightArgb(t: Double): Int = heightColor(t).toArgb()

internal fun Rgba.packed(): Int = android.graphics.Color.argb(a, r, g, b)

internal fun cappedSize(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
    if (width <= 1 || height <= 1) return 2 to 2
    val scale = min(1f, maxDim.toFloat() / max(width, height).toFloat())
    return max(2, (width * scale).toInt()) to max(2, (height * scale).toInt())
}
