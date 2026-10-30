package com.xnotes.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.scan.DocScan
import com.xnotes.scan.InkVectorizer
import com.xnotes.scan.TracePreprocess
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * "Trace from camera", step 1: a just-taken photo with draggable page corners.
 * The corners de-warp the perspective (like a document scanner); [onTraceStrokes]
 * hands the flattened page to the trace flow, [onSaved] stores the filtered scan
 * itself as a note without tracing. [onRetake] shoots again.
 */
@Composable
fun ScanCaptureDialog(
    editor: Editor,
    photo: File,
    initialName: String,
    root: String,
    parentDocId: String,
    onClose: () -> Unit,
    onRetake: () -> Unit,
    onTraceStrokes: (warped: File, name: String) -> Unit,
    onSaved: (uri: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()

    var editBmp by remember { mutableStateOf<Bitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // Page corners as fractions of the photo (TL,TR,BR,BL), resolution-independent.
    var quadF by remember { mutableStateOf<FloatArray?>(null) }
    var dragIdx by remember { mutableIntStateOf(-1) }
    var loupeAt by remember { mutableStateOf<Offset?>(null) }
    var filterIdx by remember { mutableIntStateOf(0) }
    var previewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var noteName by remember { mutableStateOf(initialName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A warped file handed to trace/save; owned from here unless handed off.
    var warpedTmp by remember { mutableStateOf<File?>(null) }
    var handedOff by remember { mutableStateOf(false) }
    // Displayed previews retire a generation late (see TraceScanDialog).
    val previewRetire = remember { com.xnotes.scan.BitmapRetire<Bitmap>(2) { it.recycle() } }

    fun moveCorner(i: Int, x: Float, y: Float) {
        val q = (quadF ?: return).copyOf()
        q[2 * i] = x.coerceIn(0f, 1f)
        q[2 * i + 1] = y.coerceIn(0f, 1f)
        quadF = q
    }

    // Decode the photo once at edit size (EXIF turn applied).
    LaunchedEffect(photo) {
        val bmp = withContext(Dispatchers.IO) { TracePreprocess.decodeBounded(photo, SCAN_EDIT_SIDE) }
        if (bmp == null) loadError = context.getString(R.string.err_read_image)
        editBmp = bmp
    }

    // Auto corners once the photo is decoded.
    LaunchedEffect(editBmp) {
        val bmp = editBmp ?: return@LaunchedEffect
        val quad = withContext(Dispatchers.Default) {
            val lum = TracePreprocess.toLuminance(bmp)
            runCatching { DocScan.detectQuad(lum, bmp.width, bmp.height) }.getOrNull()
        }
        quadF = (quad ?: DocScan.Quad.inset(bmp.width, bmp.height)).toFractions(bmp.width, bmp.height)
    }

    fun autoCorners() {
        val bmp = editBmp ?: return
        scope.launch {
            busy = true
            val quad = withContext(Dispatchers.Default) {
                val lum = TracePreprocess.toLuminance(bmp)
                runCatching { DocScan.detectQuad(lum, bmp.width, bmp.height) }.getOrNull()
            }
            busy = false
            if (quad != null) {
                quadF = quad.toFractions(bmp.width, bmp.height)
                error = null
            } else {
                error = context.getString(R.string.scan_no_edges)
            }
        }
    }

    // Filtered preview for the chosen save filter.
    LaunchedEffect(editBmp, filterIdx) {
        val bmp = editBmp ?: return@LaunchedEffect
        val f = filterIdx
        if (f == 0) {
            val old = previewBmp
            previewBmp = null
            previewRetire.retire(old)
            return@LaunchedEffect
        }
        val out = try {
            withContext(Dispatchers.Default) {
                runCatching {
                    if (f == 1) {
                        val dst = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                        val m = ColorMatrix().apply { setSaturation(0f) }
                        android.graphics.Canvas(dst).drawBitmap(
                            bmp, 0f, 0f,
                            Paint().apply { colorFilter = ColorMatrixColorFilter(m) },
                        )
                        dst
                    } else {
                        val lum = TracePreprocess.toLuminance(bmp)
                        val level = InkVectorizer.otsuThreshold(lum)
                        TracePreprocess.maskPreview(InkVectorizer.binarize(lum, level, false), bmp.width, bmp.height)
                    }
                }.getOrNull()
            }
        } catch (e: OutOfMemoryError) {
            error = context.getString(R.string.trace_too_big)
            null
        }
        val old = previewBmp
        previewBmp = out
        previewRetire.retire(old)
    }

    DisposableEffect(Unit) {
        onDispose {
            previewRetire.clear()
            editBmp?.recycle()
            previewBmp?.recycle()
            if (!handedOff) warpedTmp?.delete()
        }
    }

    /** Flatten the quad at full working size; null on failure. IO. */
    fun warpFull(): Bitmap? {
        val q = quadF ?: return null
        val side = TracePreprocess.cappedWorkSide()
        val full = TracePreprocess.decodeBounded(photo, side) ?: return null
        val qpx = DocScan.Quad.fromFractions(q, full.width, full.height).toPixels(full.width, full.height)
        val (ow, oh) = DocScan.quadOutputSize(qpx, side)
        val warped = DocScan.warpPerspective(full, qpx, ow, oh)
        full.recycle()
        return warped
    }

    fun confirm(trace: Boolean) {
        if (busy) return
        busy = true
        error = null
        val snapFilter = filterIdx
        val snapName = noteName.ifBlank { initialName }
        scope.launch {
            val warped = try {
                withContext(Dispatchers.IO) { runCatching { warpFull() }.getOrNull() }
            } catch (e: OutOfMemoryError) {
                null
            }
            if (warped == null) {
                busy = false
                error = context.getString(R.string.trace_too_big)
                return@launch
            }
            if (trace) {
                val tmp = withContext(Dispatchers.IO) {
                    runCatching {
                        File.createTempFile("scan-trace", ".jpg", context.cacheDir).also { f ->
                            f.outputStream().use { warped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        }
                    }.getOrNull()
                }
                warped.recycle()
                busy = false
                if (tmp == null) {
                    error = context.getString(R.string.err_create_note)
                    return@launch
                }
                warpedTmp = tmp
                handedOff = true
                onTraceStrokes(tmp, snapName)
            } else {
                val suffix = if (snapFilter == 2) ".png" else ".jpg"
                val filtered = withContext(Dispatchers.Default) {
                    when (snapFilter) {
                        1 -> {
                            val dst = Bitmap.createBitmap(warped.width, warped.height, Bitmap.Config.ARGB_8888)
                            val m = ColorMatrix().apply { setSaturation(0f) }
                            android.graphics.Canvas(dst).drawBitmap(
                                warped, 0f, 0f,
                                Paint().apply { colorFilter = ColorMatrixColorFilter(m) },
                            )
                            warped.recycle()
                            dst
                        }
                        2 -> {
                            val lum = TracePreprocess.toLuminance(warped)
                            val level = InkVectorizer.otsuThreshold(lum)
                            val dst = TracePreprocess.maskPreview(InkVectorizer.binarize(lum, level, false), warped.width, warped.height)
                            warped.recycle()
                            dst
                        }
                        else -> warped
                    }
                }
                val tmp = withContext(Dispatchers.IO) {
                    runCatching {
                        File.createTempFile("scan-save", suffix, context.cacheDir).also { f ->
                            f.outputStream().use {
                                if (snapFilter == 2) filtered.compress(Bitmap.CompressFormat.PNG, 100, it)
                                else filtered.compress(Bitmap.CompressFormat.JPEG, 92, it)
                            }
                        }
                    }.getOrNull()
                }
                filtered.recycle()
                if (tmp == null) {
                    busy = false
                    error = context.getString(R.string.err_create_note)
                    return@launch
                }
                warpedTmp = tmp
                handedOff = true
                val uri = withContext(Dispatchers.IO) {
                    editor.createScanNoteFile(root, parentDocId, snapName, tmp, suffix)
                }
                busy = false
                if (uri != null) onSaved(uri) else error = context.getString(R.string.err_create_note)
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxSize().background(palette.panel.toComposeColor()),
            color = palette.panel.toComposeColor(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (!busy) onClose() }) {
                        Icon(XnotesIcons.close, stringResource(R.string.close), tint = text)
                    }
                    Text(stringResource(R.string.scan_title), color = text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                if (loadError != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(loadError ?: "", color = palette.danger.toComposeColor())
                    }
                    return@Column
                }
                val bmp = editBmp
                val quad = quadF
                if (bmp == null || quad == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Column
                }
                QuadEditorHost(
                    bmp = previewBmp ?: bmp,
                    quadF = quad,
                    onMoveCorner = ::moveCorner,
                    dragIdx = dragIdx,
                    onDragIdx = { dragIdx = it },
                    loupeAt = loupeAt,
                    onLoupeAt = { loupeAt = it },
                    hint = stringResource(R.string.scan_hint),
                    modifier = Modifier.weight(1f),
                    controls = {
                        ScanControls(
                            filterIdx = filterIdx,
                            onFilter = { filterIdx = it },
                            noteName = noteName,
                            onName = { noteName = it },
                            onAuto = ::autoCorners,
                            onReset = {
                                quadF = DocScan.Quad.inset(bmp.width, bmp.height).toFractions(bmp.width, bmp.height)
                            },
                            error = error,
                        )
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { if (!busy) onRetake() }) { Text(stringResource(R.string.scan_retake)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirm(trace = false) }, enabled = !busy) { Text(stringResource(R.string.scan_save)) }
                    Button(onClick = { confirm(trace = true) }, enabled = !busy) { Text(stringResource(R.string.scan_trace)) }
                }
            }
        }
    }
}

/** Longest side of the photo decoded for corner editing. */
private const val SCAN_EDIT_SIDE = 1200

/** Screen width at or above which the editor and controls sit side by side. */
private const val SCAN_WIDE_DP = 720

/**
 * Preview + controls in one responsive block: side by side on wide screens,
 * stacked on narrow ones.
 */
@Composable
private fun QuadEditorHost(
    bmp: Bitmap,
    quadF: FloatArray,
    onMoveCorner: (Int, Float, Float) -> Unit,
    dragIdx: Int,
    onDragIdx: (Int) -> Unit,
    loupeAt: Offset?,
    onLoupeAt: (Offset?) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    controls: @Composable () -> Unit,
) {
    val dim = LocalPalette.current.textDim.toComposeColor()
    // Live reads for the gesture callbacks; the pointerInput keys stay stable mid-drag.
    val latestQuad by rememberUpdatedState(quadF)
    val latestDrag by rememberUpdatedState(dragIdx)
    if (LocalConfiguration.current.screenWidthDp >= SCAN_WIDE_DP) {
        Row(modifier.fillMaxWidth()) {
            Column(Modifier.weight(1.15f).fillMaxHeight()) {
                QuadEditor(
                    bmp = bmp,
                    latestQuad = latestQuad,
                    latestDrag = latestDrag,
                    onDragIdx = onDragIdx,
                    onMoveCorner = onMoveCorner,
                    loupeAt = loupeAt,
                    onLoupeAt = onLoupeAt,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 6.dp),
                )
                Text(
                    hint, color = dim, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(start = 6.dp, end = 16.dp),
            ) {
                controls()
            }
        }
    } else {
        Column(modifier.fillMaxWidth()) {
        QuadEditor(
            bmp = bmp,
            latestQuad = latestQuad,
            latestDrag = latestDrag,
            onDragIdx = onDragIdx,
            onMoveCorner = onMoveCorner,
            loupeAt = loupeAt,
            onLoupeAt = onLoupeAt,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
        )
        Text(
            hint, color = dim, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Column(Modifier.fillMaxWidth().weight(1.1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            controls()
        }
        }
    }
}

/**
 * The photo with the page quad over it: dimmed surround, white outline, four
 * draggable corner handles with a magnifier loupe while dragging. [bmp] fills
 * the box (Fit); corners are fractions of the bitmap.
 */
@Composable
private fun QuadEditor(
    bmp: Bitmap,
    latestQuad: FloatArray?,
    latestDrag: Int,
    onDragIdx: (Int) -> Unit,
    onMoveCorner: (Int, Float, Float) -> Unit,
    loupeAt: Offset?,
    onLoupeAt: (Offset?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)
    val lineColor = androidx.compose.ui.graphics.Color.White
    val accentColor = LocalPalette.current.accent.toComposeColor()
    BoxWithConstraints(modifier.clip(RoundedCornerShape(8.dp))) {
        val boxWpx = with(density) { maxWidth.toPx() }
        val boxHpx = with(density) { maxHeight.toPx() }
        val imgAspect = bmp.width.toFloat() / bmp.height
        val boxAspect = boxWpx / boxHpx
        val dispW = if (imgAspect > boxAspect) boxWpx else boxHpx * imgAspect
        val dispH = if (imgAspect > boxAspect) boxWpx / imgAspect else boxHpx
        val offX = (boxWpx - dispW) / 2f
        val offY = (boxHpx - dispH) / 2f
        val touchSlop = with(density) { 28.dp.toPx() }
        // Live reads for the gesture callbacks below. pointerInput restarts only
        // when its keys change (here: just [bmp]), so reading the params directly
        // would freeze the drag on the values from when the gesture block was
        // created — the handles would never move. These holders always read fresh.
        val liveQuad by rememberUpdatedState(latestQuad)
        val liveDrag by rememberUpdatedState(latestDrag)
        val liveMove by rememberUpdatedState(onMoveCorner)

        fun toFrac(at: Offset): Pair<Float, Float> =
            ((at.x - offX) / dispW).coerceIn(0f, 1f) to ((at.y - offY) / dispH).coerceIn(0f, 1f)

        fun handlePx(i: Int, q: FloatArray): Offset =
            Offset(offX + q[2 * i] * dispW, offY + q[2 * i + 1] * dispH)

        Canvas(
            Modifier.fillMaxSize()
                .background(LocalPalette.current.menuBg.toComposeColor())
                .border(1.dp, LocalPalette.current.border.toComposeColor(), RoundedCornerShape(8.dp))
                .pointerInput(bmp) {
                    detectDragGestures(
                        onDragStart = { at ->
                            val q = liveQuad ?: return@detectDragGestures
                            var best = -1
                            var bestD = Float.MAX_VALUE
                            for (i in 0 until 4) {
                                val d = (handlePx(i, q) - at).getDistance()
                                if (d < bestD) {
                                    bestD = d
                                    best = i
                                }
                            }
                            if (best >= 0 && bestD <= touchSlop) {
                                onDragIdx(best)
                                onLoupeAt(at)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val i = liveDrag
                            if (i in 0..3) {
                                val (x, y) = toFrac(change.position)
                                liveMove(i, x, y)
                                onLoupeAt(change.position)
                            }
                        },
                        onDragEnd = {
                            onDragIdx(-1)
                            onLoupeAt(null)
                        },
                        onDragCancel = {
                            onDragIdx(-1)
                            onLoupeAt(null)
                        },
                    )
                },
        ) {
            val img = bmp.asImageBitmap()
            drawImage(
                img,
                dstOffset = IntOffset(offX.roundToInt(), offY.roundToInt()),
                dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            val q = latestQuad ?: return@Canvas
            val quadPath = Path().apply {
                val p0 = handlePx(0, q)
                moveTo(p0.x, p0.y)
                for (i in 1 until 4) {
                    val p = handlePx(i, q)
                    lineTo(p.x, p.y)
                }
                close()
            }
            val outside = Path().apply {
                addRect(Rect(Offset.Zero, size))
                addPath(quadPath)
                fillType = PathFillType.EvenOdd
            }
            drawPath(outside, dimColor)
            drawPath(quadPath, lineColor, style = Stroke(width = 2.dp.toPx()))
            for (i in 0 until 4) {
                val p = handlePx(i, q)
                drawCircle(lineColor, radius = 11.dp.toPx(), center = p)
                drawCircle(
                    if (i == latestDrag) accentColor else lineColor,
                    radius = 11.dp.toPx(), center = p, style = Stroke(width = 2.5.dp.toPx()),
                )
            }
            // Magnifier loupe above the finger while a corner moves.
            val at = loupeAt
            if (latestDrag in 0..3 && at != null) {
                val hp = handlePx(latestDrag, q)
                val k = dispW / bmp.width // display px per image px
                val srcSize = (140f / k).coerceAtMost(minOf(bmp.width, bmp.height).toFloat())
                val sx = (hp.x - offX) / dispW * bmp.width
                val sy = (hp.y - offY) / dispH * bmp.height
                val srcX = (sx - srcSize / 2).coerceIn(0f, (bmp.width - srcSize).coerceAtLeast(0f))
                val srcY = (sy - srcSize / 2).coerceIn(0f, (bmp.height - srcSize).coerceAtLeast(0f))
                val loupeR = 64.dp.toPx()
                val center = Offset(
                    at.x.coerceIn(loupeR, size.width - loupeR),
                    (at.y - 150.dp.toPx()).coerceAtLeast(loupeR),
                )
                clipPath(
                    Path().apply {
                        addOval(Rect(center - Offset(loupeR, loupeR), center + Offset(loupeR, loupeR)))
                    },
                ) {
                    drawImage(
                        img,
                        srcOffset = IntOffset(srcX.roundToInt(), srcY.roundToInt()),
                        srcSize = IntSize(srcSize.roundToInt(), srcSize.roundToInt()),
                        dstOffset = IntOffset(
                            (center.x - loupeR).roundToInt(),
                            (center.y - loupeR).roundToInt(),
                        ),
                        dstSize = IntSize((loupeR * 2).roundToInt(), (loupeR * 2).roundToInt()),
                    )
                    // Crosshair on the exact corner.
                    val c = Offset(
                        center.x + (sx - (srcX + srcSize / 2)) * k * (loupeR * 2 / srcSize),
                        center.y + (sy - (srcY + srcSize / 2)) * k * (loupeR * 2 / srcSize),
                    )
                    drawLine(accentColor, c + Offset(-14.dp.toPx(), 0f), c + Offset(14.dp.toPx(), 0f), strokeWidth = 2.dp.toPx())
                    drawLine(accentColor, c + Offset(0f, -14.dp.toPx()), c + Offset(0f, 14.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
                drawCircle(lineColor, radius = loupeR, center = center, style = Stroke(width = 3.dp.toPx()))
            }
        }
    }
}

/** Filter picker, name field, auto/reset and errors for the scan dialog. */
@Composable
private fun ScanControls(
    filterIdx: Int,
    onFilter: (Int) -> Unit,
    noteName: String,
    onName: (String) -> Unit,
    onAuto: () -> Unit,
    onReset: () -> Unit,
    error: String?,
) {
    val palette = LocalPalette.current
    val dim = palette.textDim.toComposeColor()
    Text(stringResource(R.string.scan_filter), color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip(stringResource(R.string.scan_filter_original), filterIdx == 0) { onFilter(0) }
        ModeChip(stringResource(R.string.scan_filter_gray), filterIdx == 1) { onFilter(1) }
        ModeChip(stringResource(R.string.scan_filter_ink), filterIdx == 2) { onFilter(2) }
    }
    Text(stringResource(R.string.scan_filter_hint), color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        TextButton(onClick = onAuto) { Text(stringResource(R.string.scan_auto)) }
        TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
    }
    OutlinedTextField(
        value = noteName,
        onValueChange = onName,
        label = { Text(stringResource(R.string.trace_note_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    if (error != null) {
        Text(error, color = palette.danger.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
}
