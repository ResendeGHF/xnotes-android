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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.focus.onFocusChanged
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
import kotlin.math.sqrt

/** One labelled swatch baked into an exported graph. [ramp] draws the height scale instead of a dot. */
internal class ExportLegend(
    val label: String,
    val color: Int,
    val ramp: Boolean = false,
)

/**
 * One plotted equation with its display overrides. [colorArgb] null means the automatic series
 * colour; [visible] false hides the entry without deleting it.
 */
internal data class MathEntry(
    val text: String = "",
    val colorArgb: Int? = null,
    val visible: Boolean = true,
)

/** Fixed palette offered by the per-entry colour picker. */
internal val ENTRY_COLOR_CHOICES = listOf(
    0xFFE06C75.toInt(),
    0xFFD19A66.toInt(),
    0xFFE5C07B.toInt(),
    0xFF98C379.toInt(),
    0xFF56B6C2.toInt(),
    0xFF61AFEF.toInt(),
    0xFFC678DD.toInt(),
    0xFFF472B6.toInt(),
    0xFFABB2BF.toInt(),
    0xFF4BAE62.toInt(),
)

/** Camera presets matching the X / Y / Z / ISO view buttons over the 3D plots. */
internal enum class GraphView { X, Y, Z, ISO }

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
 * short scrolling tray, and Insert stays in the header. On wide screens the plot moves left and
 * the controls dock right so both stay visible at once.
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
    animInsertLabel: String? = null,
    animInserting: Boolean = false,
    onInsertAnim: (() -> Unit)? = null,
    status: String? = null,
    plot: @Composable BoxScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize()) {
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
                if (animInsertLabel != null && onInsertAnim != null) {
                    InsertAnimButton(animInserting, animInsertLabel, onInsertAnim)
                    Spacer(Modifier.width(6.dp))
                }
                InsertGraphButton(inserting, onInsert)
            }
            Spacer(Modifier.height(6.dp))
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlotFrame(
                        legend = legend,
                        ramp = ramp,
                        caption = caption,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        plot = plot,
                    )
                    Column(
                        Modifier
                            .width(320.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        content = controls,
                    )
                }
            } else {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    PlotFrame(legend = legend, ramp = ramp, caption = caption, modifier = Modifier.fillMaxSize(), plot = plot)
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
                return@Column
            }
            if (status != null) {
                Spacer(Modifier.height(4.dp))
                Text(status, color = palette.danger.toComposeColor(), fontSize = 11.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun PlotFrame(
    legend: List<Pair<String, Color>>,
    ramp: Pair<String, String>?,
    caption: String?,
    modifier: Modifier = Modifier,
    plot: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        modifier
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
private fun InsertAnimButton(inserting: Boolean, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val busyLabel = stringResource(R.string.math_3d_computing)
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .border(1.dp, palette.accent.toComposeColor(), RoundedCornerShape(9.dp))
            .clickable(enabled = !inserting, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (inserting) busyLabel else label,
            color = palette.accent.toComposeColor(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Tiles per-frame bitmaps into a labelled filmstrip, then wraps it with the standard legend strip. */
internal fun buildAnimationPng(
    frames: List<Pair<Bitmap, String>>,
    title: String,
    legend: List<ExportLegend>,
    footnote: String,
    theme: GraphTheme,
    cols: Int = 4,
    specJson: String? = null,
): ByteArray? {
    if (frames.isEmpty()) return null
    val cellW = frames.maxOf { it.first.width }
    val cellH = frames.maxOf { it.first.height }
    val labelH = 30
    val n = frames.size
    val c = cols.coerceIn(1, 4).coerceAtMost(n)
    val rows = (n + c - 1) / c
    val strip = Bitmap.createBitmap(cellW * c, (cellH + labelH) * rows, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(strip)
    canvas.drawColor(theme.paper)
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.dim; textSize = 24f; typeface = Typeface.MONOSPACE
    }
    frames.forEachIndexed { i, (bmp, tag) ->
        val x = (i % c) * cellW
        val y = (i / c) * (cellH + labelH)
        canvas.drawBitmap(bmp, null, android.graphics.Rect(x, y, x + cellW, y + cellH), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawText(tag, (x + 8).toFloat(), (y + cellH + 22).toFloat(), labelPaint)
        if (bmp !== strip) bmp.recycle()
    }
    val png = writeGraphPng(strip, title, legend, footnote, theme)
    if (specJson == null) return png
    return if (png == null) null else com.xnotes.platform.MathAnimPng.embed(png, specJson)
}

internal fun animFrameTs(lo: Double, hi: Double, steps: Int, maxFrames: Int = 12): DoubleArray {
    val s = steps.coerceIn(2, 400)
    val k = minOf(s, maxFrames).coerceAtLeast(2)
    val a = minOf(lo, hi); val b = maxOf(lo, hi)
    return DoubleArray(k) { i -> a + (b - a) * i / (k - 1) }
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

// ------------------------------------------------- per-entry colour + visibility

/**
 * The colour dot leading an equation row. Tapping it opens a small palette; Auto follows the
 * series colour. [effective] is the colour the entry is drawn with right now.
 */
@Composable
internal fun EntryColorDot(
    colorArgb: Int?,
    effective: Color,
    description: String,
    onPick: (Int?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(24.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(effective)
            .border(
                1.dp,
                (if (colorArgb != null) palette.text else palette.border).toComposeColor(),
                androidx.compose.foundation.shape.CircleShape,
            )
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        if (colorArgb == null) {
            Icon(
                Icons.Outlined.Palette,
                contentDescription = description,
                tint = palette.paper.toComposeColor(),
                modifier = Modifier.size(13.dp),
            )
        }
    }
    if (open) {
        EntryColorDialog(
            current = colorArgb,
            onDismiss = { open = false },
            onPick = { onPick(it); open = false },
        )
    }
}

@Composable
private fun EntryColorDialog(current: Int?, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(stringResource(R.string.math_pick_color), color = palette.text.toComposeColor(), fontSize = 15.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(9.dp))
                        .clickable { onPick(null) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(palette.textDim.toComposeColor()),
                    )
                    Text(
                        stringResource(R.string.math_color_auto),
                        color = palette.text.toComposeColor(),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (current == null) Icon(Icons.Outlined.Check, contentDescription = null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp))
                }
                for (row in ENTRY_COLOR_CHOICES.chunked(5)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in row) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Color(c))
                                    .border(
                                        2.dp,
                                        if (current == c) palette.text.toComposeColor() else Color.Transparent,
                                        androidx.compose.foundation.shape.CircleShape,
                                    )
                                    .clickable { onPick(c) },
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Eye toggle hiding an equation without deleting it. */
@Composable
internal fun EntryEye(visible: Boolean, description: String, onToggle: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            contentDescription = description,
            tint = (if (visible) palette.textDim else palette.disabled).toComposeColor(),
            modifier = Modifier.size(19.dp),
        )
    }
}

// ------------------------------------------------- numeric domain fields

/**
 * A number field bound to a Double in state. Commits live while the text parses, keeps the raw
 * keystrokes while focused, and resyncs when the value moves underneath (pinch zoom, reset).
 */
@Composable
internal fun DomainField(
    value: Double,
    onCommit: (Double) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var str by remember { mutableStateOf(Numbers.tick(value)) }
    var lastExt by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    if (value != lastExt && !focused) {
        str = Numbers.tick(value)
        lastExt = value
    }
    MathTextField(
        value = str,
        onValueChange = { next ->
            str = next
            next.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { v ->
                lastExt = v
                onCommit(v)
            }
        },
        label = label,
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) {
                str = Numbers.tick(value)
                lastExt = value
            }
        },
    )
}

// ------------------------------------------------- 3D view presets

/** Rotation matrix (row-major, as produced by [com.xnotes.ui.Quat.matrix]) back to a quaternion. */
internal fun quatFromMatrix(m: DoubleArray): Quat {
    if (m.size < 9) return Quat.IDENTITY
    val t = m[0] + m[4] + m[8]
    return if (t > 0) {
        val s = sqrt(t + 1.0) * 2
        Quat(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s).normalized()
    } else if (m[0] > m[4] && m[0] > m[8]) {
        val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2
        Quat((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s).normalized()
    } else if (m[4] > m[8]) {
        val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2
        Quat((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s).normalized()
    } else {
        val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2
        Quat((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s).normalized()
    }
}

/** The default isometric tilt, shared by reset and the ISO preset. */
internal fun isoQuat(): Quat =
    Quat.axisAngle(1.0, 0.0, 0.0, -0.46).times(Quat.axisAngle(0.0, 0.0, 1.0, 0.72))

/**
 * Points the camera down an axis or back to isometric. Looking from +x/+y keeps z up;
 * Z looks straight down with x right and y up.
 */
internal fun OrbitState.setView(view: GraphView) {
    quat = when (view) {
        GraphView.X -> quatFromMatrix(doubleArrayOf(0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0))
        GraphView.Y -> quatFromMatrix(doubleArrayOf(-1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0))
        GraphView.Z -> Quat.IDENTITY
        GraphView.ISO -> isoQuat()
    }
}

/** Floating semi-transparent X / Y / Z / ISO buttons over a 3D plot. */
@Composable
internal fun BoxScope.ViewPresetButtons(orbit: OrbitState) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(palette.panel.toComposeColor().copy(alpha = 0.78f))
            .padding(horizontal = 3.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (v in GraphView.entries) {
            Text(
                v.name,
                color = palette.text.toComposeColor(),
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .clickable { orbit.setView(v) }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }
    }
}

// ------------------------------------------------- 3D space frame: coloured axes + grid with values

/** Axis colours: x red, y green, z blue — readable on light and dark paper. */
internal const val AXIS_X_COLOR = 0xFFE06C75.toInt()
internal const val AXIS_Y_COLOR = 0xFF63B26B.toInt()
internal const val AXIS_Z_COLOR = 0xFF4E9BE6.toInt()

/**
 * Draws the domain box, the three coloured axes from its centre, and — when [showGrid] — a
 * labelled grid on the three faces turned away from the camera, so intersections stay readable
 * while orbiting. [proj] maps world points to pixels.
 */
internal fun drawQuatSpaceFrame(
    canvas: android.graphics.Canvas,
    proj: (Double, Double, Double) -> Triple<Float, Float, Float>,
    quat: Quat,
    cx: Double, cy: Double, cz: Double,
    hx: Double, hy: Double, hz: Double,
    w: Int, h: Int,
    theme: GraphTheme,
    showGrid: Boolean,
) {
    if (!(hx.isFinite() && hy.isFinite() && hz.isFinite()) || hx <= 0 || hy <= 0 || hz <= 0) return
    val m = quat.matrix()
    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.grid; strokeWidth = 1.8f; alpha = 150 }
    val corners = ArrayList<Triple<Float, Float, Float>>(8)
    for (sz in listOf(-1.0, 1.0)) for (sy in listOf(-1.0, 1.0)) for (sx in listOf(-1.0, 1.0)) {
        corners += proj(cx + sx * hx, cy + sy * hy, cz + sz * hz)
    }
    for ((a, b) in listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0, 4 to 5, 5 to 7, 7 to 6, 6 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)) {
        canvas.drawLine(corners[a].first, corners[a].second, corners[b].first, corners[b].second, edge)
    }
    // Coloured axes from the box centre.
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (min(w, h) * 0.045f).coerceIn(16f, 34f)
        typeface = Typeface.MONOSPACE
    }
    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.6f; alpha = 220 }
    val o = proj(cx, cy, cz)
    val axes = listOf(
        Triple(proj(cx + hx * 0.92, cy, cz), "x", AXIS_X_COLOR),
        Triple(proj(cx, cy + hy * 0.92, cz), "y", AXIS_Y_COLOR),
        Triple(proj(cx, cy, cz + hz * 0.92), "z", AXIS_Z_COLOR),
    )
    for ((end, name, col) in axes) {
        axisPaint.color = col
        canvas.drawLine(o.first, o.second, end.first, end.second, axisPaint)
        labelPaint.color = col
        canvas.drawText(name, end.first + 5f, end.second + 6f, labelPaint)
    }
    if (!showGrid) return
    // The grid lives on the three back faces: the min or max face whose outward normal
    // points away from the viewer (view-space depth shrinking along it).
    val backX = if (m[6] > 0) cx - hx else cx + hx
    val backY = if (m[7] > 0) cy - hy else cy + hy
    val backZ = if (m[8] > 0) cz - hz else cz + hz
    // (fixed value, first varying axis lo/hi, second varying axis lo/hi)
    drawGridFace(canvas, proj, 0, backX, cy - hy, cy + hy, cz - hz, cz + hz, w, h, theme)
    drawGridFace(canvas, proj, 1, backY, cx - hx, cx + hx, cz - hz, cz + hz, w, h, theme)
    drawGridFace(canvas, proj, 2, backZ, cx - hx, cx + hx, cy - hy, cy + hy, w, h, theme)
}

/** One gridded back face: [fixed] is the frozen axis (0=x, 1=y, 2=z), the grid spans [u0,u1]×[v0,v1]. */
private fun drawGridFace(
    canvas: android.graphics.Canvas,
    proj: (Double, Double, Double) -> Triple<Float, Float, Float>,
    fixed: Int,
    fixedValue: Double,
    u0: Double, u1: Double,
    v0: Double, v1: Double,
    w: Int, h: Int,
    theme: GraphTheme,
) {
    val uSpan = abs(u1 - u0); val vSpan = abs(v1 - v0)
    if (!uSpan.isFinite() || !vSpan.isFinite() || uSpan <= 0 || vSpan <= 0) return
    fun point(u: Double, v: Double): Triple<Double, Double, Double> = when (fixed) {
        0 -> Triple(fixedValue, u, v)
        1 -> Triple(u, fixedValue, v)
        else -> Triple(u, v, fixedValue)
    }
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.grid; strokeWidth = 1.2f; alpha = 120 }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.dim
        textSize = (min(w, h) * 0.032f).coerceIn(13f, 26f)
        typeface = Typeface.MONOSPACE
    }
    fun draw(u: Double, v: Double): Triple<Float, Float, Float> {
        val (x, y, z) = point(u, v)
        return proj(x, y, z)
    }
    val uStep = niceStep(uSpan, 5)
    val vStep = niceStep(vSpan, 5)
    // Lines of constant u span v; lines of constant v span u. Each line is labelled
    // with its tick value at whichever end is nearer the viewer, so numbers stay readable.
    var u = kotlin.math.floor(u0 / uStep) * uStep
    var guard = 0
    while (u <= u1 && guard++ < 14) {
        val a = draw(u, v0); val b = draw(u, v1)
        canvas.drawLine(a.first, a.second, b.first, b.second, gridPaint)
        val at = if (a.third >= b.third) a else b
        canvas.drawText(Numbers.tick(u), at.first + 3f, at.second - 3f, textPaint)
        u += uStep
    }
    var v = kotlin.math.floor(v0 / vStep) * vStep
    guard = 0
    while (v <= v1 && guard++ < 14) {
        val a = draw(u0, v); val b = draw(u1, v)
        canvas.drawLine(a.first, a.second, b.first, b.second, gridPaint)
        val at = if (a.third >= b.third) a else b
        canvas.drawText(Numbers.tick(v), at.first + 3f, at.second - 3f, textPaint)
        v += vStep
    }
}
