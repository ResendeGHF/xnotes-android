package com.xnotes.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.scan.DocScan
import com.xnotes.scan.InkVectorizer
import com.xnotes.scan.TracePreprocess
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Filter for one scanned page: colour, grey, thresholded ink, or auto-levelled. */
const val SCAN_FILTER_ORIGINAL = 0
const val SCAN_FILTER_GREY = 1
const val SCAN_FILTER_INK = 2
const val SCAN_FILTER_ENHANCED = 3

/** One page of a multi-page scan session: its photo plus per-page adjustments. */
class ScanPage(file: java.io.File, val id: Long) {
    var file: java.io.File = file
    /** Page corners as fractions of the photo (TL,TR,BR,BL), null = whole frame. */
    var quadF by mutableStateOf<FloatArray?>(null)
    var filter by mutableIntStateOf(SCAN_FILTER_ORIGINAL)
    /** -100..100, like the trace brightness. */
    var brightness by mutableIntStateOf(0)
    /** Percent of normal contrast, like the trace contrast. */
    var contrast by mutableIntStateOf(100)
    /** Quarter turns clockwise applied after de-warping. */
    var rotation by mutableIntStateOf(0)
}

/**
 * An in-memory multi-page scan session started from inside a note. [target]
 * receives the finished pages at [at] (null = default placement); [dir] holds
 * the staged page photos, deleted with [clearFiles].
 */
class ScanSession(val target: Editor, val at: Pt?, val dir: File) {
    val pages: SnapshotStateList<ScanPage> = mutableStateListOf()
    private var nextId = 0L

    fun addPage(file: File): ScanPage {
        val page = ScanPage(file, nextId++)
        pages.add(page)
        return page
    }

    fun move(page: ScanPage, delta: Int) {
        val i = pages.indexOf(page)
        val j = (i + delta).coerceIn(0, pages.size - 1)
        if (i < 0 || i == j) return
        pages.removeAt(i)
        pages.add(j, page)
    }

    fun remove(page: ScanPage) {
        pages.remove(page)
        page.file.delete()
    }

    fun clearFiles() {
        pages.forEach { runCatching { it.file.delete() } }
        pages.clear()
    }
}

/** Longest side of a photo decoded for corner editing in the scan dialog. */
private const val SCAN_PAGE_EDIT_SIDE = 1200

/** Longest side of the on-screen result preview (the insert renders full-size). */
private const val SCAN_RESULT_PREVIEW_SIDE = 800

/** Cascade offset between consecutive inserted scans, in content px. */
private const val SCAN_INSERT_CASCADE_PX = 40.0

/**
 * De-warp [photo] with [page]'s corners and adjustments at [maxSide]; null on
 * failure. IO/heavy — call off the main thread. The caller owns the result.
 */
internal fun renderScanPage(photo: File, page: ScanPage, maxSide: Int): Bitmap? {
    val full = TracePreprocess.decodeBounded(photo, maxSide) ?: return null
    val qpx = page.quadF?.let { DocScan.Quad.fromFractions(it, full.width, full.height).toPixels(full.width, full.height) }
        ?: floatArrayOf(
            0f, 0f, full.width.toFloat(), 0f,
            full.width.toFloat(), full.height.toFloat(), 0f, full.height.toFloat(),
        )
    val (ow, oh) = DocScan.quadOutputSize(qpx, maxSide)
    val warped = DocScan.warpPerspective(full, qpx, ow, oh)
    full.recycle()
    if (warped == null) return null
    return developWarped(warped, page.filter, page.brightness, page.contrast, page.rotation)
}

/**
 * Rotate, photometrically adjust and filter an already de-warped page.
 * Takes ownership of [warped] (recycled); returns the developed bitmap.
 */
internal fun developWarped(warped: Bitmap, filter: Int, brightness: Int, contrast: Int, rotation: Int): Bitmap {
    var bmp = warped
    if (rotation % 4 != 0) {
        val m = Matrix().apply { postRotate(((rotation % 4) * 90f) % 360f) }
        val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (turned !== bmp) bmp.recycle()
        bmp = turned
    }
    val adjusted = TracePreprocess.adjustPhoto(bmp, brightness, contrast)
    if (adjusted !== bmp) bmp.recycle()
    bmp = adjusted
    return when (filter) {
        SCAN_FILTER_GREY -> desaturatePage(bmp)
        SCAN_FILTER_INK -> binarizePage(bmp)
        SCAN_FILTER_ENHANCED -> autoLevelsPage(bmp)
        else -> bmp
    }
}

/** Greyscale version of [src]; recycles [src]. */
internal fun desaturatePage(src: Bitmap): Bitmap {
    val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val m = ColorMatrix().apply { setSaturation(0f) }
    android.graphics.Canvas(dst).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    src.recycle()
    return dst
}

/** Black-and-white (Otsu) version of [src]; recycles [src]. */
internal fun binarizePage(src: Bitmap): Bitmap {
    val lum = TracePreprocess.toLuminance(src)
    val dst = TracePreprocess.maskPreview(InkVectorizer.binarize(lum, InkVectorizer.otsuThreshold(lum).coerceIn(1, 254), false), src.width, src.height)
    src.recycle()
    return dst
}

/**
 * Auto-levelled version of [src]: each channel stretched from its 2nd to its
 * 98th percentile, evening out dull phone photos while keeping colour.
 * Works in place and returns [src].
 */
internal fun autoLevelsPage(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val histR = IntArray(256)
    val histG = IntArray(256)
    val histB = IntArray(256)
    var i = 0
    while (i < px.size) {
        val p = px[i]
        histR[(p shr 16) and 0xFF]++
        histG[(p shr 8) and 0xFF]++
        histB[p and 0xFF]++
        i += 4
    }
    fun level(hist: IntArray, total: Int): Pair<Int, Int> {
        val loTarget = (total * 0.02).toInt()
        val hiTarget = total - loTarget
        var acc = 0
        var lo = 0
        while (lo < 255) {
            acc += hist[lo]
            if (acc >= loTarget) break
            lo++
        }
        acc = 0
        var hi = 255
        while (hi > 0) {
            acc += hist[hi]
            if (acc >= total - hiTarget + 1) break
            hi--
        }
        return lo to hi
    }
    val total = (px.size + 3) / 4
    val (loR, hiR) = level(histR, total)
    val (loG, hiG) = level(histG, total)
    val (loB, hiB) = level(histB, total)
    fun stretch(v: Int, lo: Int, hi: Int): Int {
        if (hi - lo < 8) return v
        return ((v - lo) * 255.0 / (hi - lo)).roundToInt().coerceIn(0, 255)
    }
    for (k in px.indices) {
        val p = px[k]
        val a = (p shr 24) and 0xFF
        px[k] = (a shl 24) or (stretch((p shr 16) and 0xFF, loR, hiR) shl 16) or
            (stretch((p shr 8) and 0xFF, loG, hiG) shl 8) or stretch(p and 0xFF, loB, hiB)
    }
    src.setPixels(px, 0, w, 0, 0, w, h)
    return src
}

/**
 * Multi-page "Scan": as many camera photos (or imported images) as the user
 * wants, each with draggable page corners, four document filters, brightness,
 * contrast and rotation against a live result preview — then every page is
 * placed on the note's canvas (or paged note) at [ScanSession.at], cascading.
 *
 * Capture uses the dialog's own camera/gallery launchers; [onClose] (Backstage)
 * drops the session and deletes every staged photo.
 */
@Composable
fun ScanSessionDialog(session: ScanSession, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    val accent = palette.accent.toComposeColor()

    var selId by remember { mutableStateOf<Long?>(null) }
    var cropping by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var insertProgress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var dragIdx by remember { mutableIntStateOf(-1) }
    var loupeAt by remember { mutableStateOf<Offset?>(null) }

    val selected = session.pages.firstOrNull { it.id == selId } ?: session.pages.firstOrNull()
    // The selection follows deletions; new pages select themselves on arrival.
    LaunchedEffect(session.pages.toList()) {
        if (session.pages.none { it.id == selId }) {
            selId = session.pages.lastOrNull()?.id
            cropping = false
        }
    }

    // --- capture: one TakePicture launcher + one gallery importer, both owned here ---
    var pendingShot by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = pendingShot
        pendingShot = null
        if (file == null) return@rememberLauncherForActivityResult
        if (!taken || file.length() == 0L) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        session.addPage(file)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            for (uri in uris) {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                if (bytes == null || bytes.isEmpty()) continue
                val ext = (context.contentResolver.getType(uri) ?: "").substringAfterLast('/', "jpg").take(4).ifEmpty { "jpg" }
                val file = runCatching {
                    File.createTempFile("page-import-", ".$ext", session.dir).also { it.writeBytes(bytes) }
                }.getOrNull() ?: continue
                withContext(Dispatchers.Main) { session.addPage(file) }
            }
        }
    }
    fun shoot() {
        if (busy) return
        error = null
        val file = runCatching { File.createTempFile("page-", ".jpg", session.dir.apply { mkdirs() }) }.getOrNull()
        if (file == null) {
            error = context.getString(R.string.err_create_note)
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            file.delete()
            error = context.getString(R.string.err_create_note)
            return
        }
        pendingShot = file
        runCatching { cameraLauncher.launch(uri) }.onFailure {
            pendingShot = null
            file.delete()
            error = context.getString(R.string.err_no_camera)
        }
    }

    // --- page thumbnails (small, recycled with the dialog) ---
    var thumbs by remember { mutableStateOf<Map<Long, Bitmap>>(emptyMap()) }
    LaunchedEffect(session.pages.toList()) {
        val wanted = session.pages.map { it.id }.toSet()
        val gone = thumbs.keys - wanted
        if (gone.isNotEmpty()) {
            gone.forEach { thumbs[it]?.recycle() }
            thumbs = thumbs - gone
        }
        val missing = session.pages.filter { !thumbs.containsKey(it.id) }
        if (missing.isNotEmpty()) {
            val fresh = withContext(Dispatchers.IO) {
                missing.mapNotNull { p ->
                    runCatching { TracePreprocess.decodeBounded(p.file, 256)?.let { p.id to it } }.getOrNull()
                }
            }
            thumbs = thumbs + fresh
        }
    }

    // --- selected page: edit-size photo, auto corners on first sight ---
    var editBmp by remember { mutableStateOf<Bitmap?>(null) }
    val editRetire = remember { com.xnotes.scan.BitmapRetire<Bitmap>(2) { it.recycle() } }
    LaunchedEffect(selected?.file) {
        val page = selected
        if (page == null) {
            val old = editBmp
            editBmp = null
            editRetire.retire(old)
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.IO) { TracePreprocess.decodeBounded(page.file, SCAN_PAGE_EDIT_SIDE) }
        if (bmp != null && page.quadF == null) {
            page.quadF = withContext(Dispatchers.Default) {
                val lum = TracePreprocess.toLuminance(bmp)
                val q = runCatching { DocScan.detectQuad(lum, bmp.width, bmp.height) }.getOrNull()
                (q ?: DocScan.Quad.inset(bmp.width, bmp.height)).toFractions(bmp.width, bmp.height)
            }
        }
        if (bmp == null) error = context.getString(R.string.err_read_image)
        val old = editBmp
        editBmp = bmp
        editRetire.retire(old)
        cropping = false
    }

    // --- debounced result preview for the selected page ---
    var resultBmp by remember { mutableStateOf<Bitmap?>(null) }
    val resultRetire = remember { com.xnotes.scan.BitmapRetire<Bitmap>(2) { it.recycle() } }
    val selQuad = selected?.quadF
    val selFilter = selected?.filter ?: SCAN_FILTER_ORIGINAL
    val selBright = selected?.brightness ?: 0
    val selContrast = selected?.contrast ?: 100
    val selRotation = selected?.rotation ?: 0
    LaunchedEffect(editBmp, selected?.id, selQuad, selFilter, selBright, selContrast, selRotation) {
        val bmp = editBmp ?: return@LaunchedEffect
        delay(200)
        val out = try {
            withContext(Dispatchers.Default) {
                runCatching {
                    val q = selQuad?.let { DocScan.Quad.fromFractions(it, bmp.width, bmp.height).toPixels(bmp.width, bmp.height) }
                        ?: floatArrayOf(
                            0f, 0f, bmp.width.toFloat(), 0f,
                            bmp.width.toFloat(), bmp.height.toFloat(), 0f, bmp.height.toFloat(),
                        )
                    val side = maxOf(bmp.width, bmp.height)
                    val (ow, oh) = DocScan.quadOutputSize(q, side)
                    val warped = DocScan.warpPerspective(bmp, q, ow, oh) ?: return@runCatching null
                    developWarped(warped, selFilter, selBright, selContrast, selRotation)
                }.getOrNull()
            }
        } catch (e: OutOfMemoryError) {
            error = context.getString(R.string.trace_too_big)
            null
        }
        val old = resultBmp
        resultBmp = out
        resultRetire.retire(old)
    }

    DisposableEffect(Unit) {
        onDispose {
            editRetire.clear()
            resultRetire.clear()
            editBmp?.recycle()
            resultBmp?.recycle()
            thumbs.values.forEach { runCatching { it.recycle() } }
            pendingShot?.delete()
        }
    }

    fun moveCorner(i: Int, x: Float, y: Float) {
        val page = selected ?: return
        val q = (page.quadF ?: return).copyOf()
        q[2 * i] = x.coerceIn(0f, 1f)
        q[2 * i + 1] = y.coerceIn(0f, 1f)
        page.quadF = q
    }

    fun insertAll() {
        if (busy || session.pages.isEmpty()) return
        busy = true
        error = null
        val target = session.target
        val origin = session.at
        val pages = session.pages.toList()
        scope.launch {
            var done = 0
            for ((i, page) in pages.withIndex()) {
                insertProgress = context.getString(R.string.scan_inserting, i + 1, pages.size)
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        val bmp = renderScanPage(page.file, page, TracePreprocess.cappedWorkSide()) ?: return@runCatching null
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        bmp.recycle()
                        out.toByteArray()
                    }.getOrNull()
                }
                if (bytes == null) continue
                val at = origin?.let { Pt(it.x + i * SCAN_INSERT_CASCADE_PX, it.y + i * SCAN_INSERT_CASCADE_PX) }
                if (target.canvasOpen) target.infinite.insertImage(bytes, at)
                else target.insertImageAt(bytes, at)
                done++
            }
            busy = false
            insertProgress = ""
            if (done == 0) {
                error = context.getString(R.string.err_create_note)
            } else {
                target.say(context.getString(R.string.scan_inserted, done))
                onClose()
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
                    Text(stringResource(R.string.scan_pages_title), color = text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = ::shoot, enabled = !busy) {
                        Icon(XnotesIcons.camera, stringResource(R.string.scan_add_photo), tint = text)
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("image/*")) }, enabled = !busy) {
                        Icon(XnotesIcons.importDoc, stringResource(R.string.scan_import), tint = text)
                    }
                    if (busy) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                // Page strip: thumbnails in order; tap to edit, "+" appends.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for ((i, page) in session.pages.withIndex()) {
                        val on = page.id == selected?.id
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                                .background(palette.menuBg.toComposeColor())
                                .border(if (on) 2.dp else 1.dp, if (on) accent else palette.border.toComposeColor(), RoundedCornerShape(8.dp))
                                .clickable { selId = page.id },
                            contentAlignment = Alignment.Center,
                        ) {
                            val tb = thumbs[page.id]
                            if (tb != null) {
                                Image(
                                    tb.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                                )
                            } else {
                                Text("${i + 1}", color = dim, fontSize = 16.sp)
                            }
                        }
                    }
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy) { shoot() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(XnotesIcons.plus, stringResource(R.string.scan_add_photo), tint = dim)
                    }
                }
                if (selected == null) {
                    Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.scan_empty), color = dim, fontSize = 14.sp)
                            if (error != null) {
                                Text(error ?: "", color = palette.danger.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                } else {
                    val page = selected
                    val quad = page.quadF
                    val eb = editBmp
                    if (eb == null || quad == null) {
                        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (cropping) {
                        QuadEditorHost(
                            bmp = eb,
                            quadF = quad,
                            onMoveCorner = ::moveCorner,
                            dragIdx = dragIdx,
                            onDragIdx = { dragIdx = it },
                            loupeAt = loupeAt,
                            onLoupeAt = { loupeAt = it },
                            hint = stringResource(R.string.scan_hint),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            controls = {
                                ScanPageControls(
                                    page = page,
                                    index = session.pages.indexOf(page),
                                    pageCount = session.pages.size,
                                    onDelete = { session.remove(page) },
                                    onMove = { session.move(page, it) },
                                    onAuto = {
                                        scope.launch {
                                            busy = true
                                            val q = withContext(Dispatchers.Default) {
                                                val lum = TracePreprocess.toLuminance(eb)
                                                runCatching { DocScan.detectQuad(lum, eb.width, eb.height) }.getOrNull()
                                            }
                                            busy = false
                                            if (q != null) {
                                                page.quadF = q.toFractions(eb.width, eb.height)
                                                error = null
                                            } else {
                                                error = context.getString(R.string.scan_no_edges)
                                            }
                                        }
                                    },
                                    onReset = {
                                        page.quadF = DocScan.Quad.inset(eb.width, eb.height).toFractions(eb.width, eb.height)
                                    },
                                    onCropToggle = { cropping = false },
                                    cropping = true,
                                    error = error,
                                )
                            },
                        )
                    } else {
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            Box(
                                Modifier.weight(1.15f).fillMaxSize().padding(start = 12.dp, end = 6.dp)
                                    .clip(RoundedCornerShape(8.dp)).background(palette.menuBg.toComposeColor())
                                    .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val rb = resultBmp
                                if (rb != null) {
                                    Image(
                                        rb.asImageBitmap(), contentDescription = null,
                                        modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit,
                                    )
                                } else {
                                    CircularProgressIndicator()
                                }
                            }
                            Column(
                                Modifier.weight(1f).fillMaxSize()
                                    .verticalScroll(rememberScrollState()).padding(start = 6.dp, end = 16.dp),
                            ) {
                                ScanPageControls(
                                    page = page,
                                    index = session.pages.indexOf(page),
                                    pageCount = session.pages.size,
                                    onDelete = { session.remove(page) },
                                    onMove = { session.move(page, it) },
                                    onAuto = {
                                        scope.launch {
                                            busy = true
                                            val q = withContext(Dispatchers.Default) {
                                                val lum = TracePreprocess.toLuminance(eb)
                                                runCatching { DocScan.detectQuad(lum, eb.width, eb.height) }.getOrNull()
                                            }
                                            busy = false
                                            if (q != null) {
                                                page.quadF = q.toFractions(eb.width, eb.height)
                                                error = null
                                            } else {
                                                error = context.getString(R.string.scan_no_edges)
                                            }
                                        }
                                    },
                                    onReset = {
                                        page.quadF = DocScan.Quad.inset(eb.width, eb.height).toFractions(eb.width, eb.height)
                                    },
                                    onCropToggle = { cropping = true },
                                    cropping = false,
                                    error = error,
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { if (!busy) onClose() }) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.weight(1f))
                    if (busy && insertProgress.isNotEmpty()) Text(insertProgress, color = dim, fontSize = 13.sp)
                    Button(onClick = ::insertAll, enabled = !busy && session.pages.isNotEmpty()) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(if (insertProgress.isNotEmpty()) insertProgress else stringResource(R.string.trace_working))
                        } else {
                            Text(context.getString(R.string.scan_insert, session.pages.size))
                        }
                    }
                }
            }
        }
    }
}

/** Filters, tune sliders, rotation, crop toggle and page actions for one scan page. */
@Composable
private fun ScanPageControls(
    page: ScanPage,
    index: Int,
    pageCount: Int,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    onAuto: () -> Unit,
    onReset: () -> Unit,
    onCropToggle: () -> Unit,
    cropping: Boolean,
    error: String?,
) {
    val palette = LocalPalette.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    Text(
        stringResource(R.string.trace_page_of, index + 1, pageCount),
        color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        TextButton(onClick = { onMove(-1) }, enabled = index > 0) { Text("‹") }
        TextButton(onClick = { onMove(1) }, enabled = index < pageCount - 1) { Text("›") }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.scan_delete)) }
    }
    TextButton(onClick = onCropToggle) {
        Text(if (cropping) stringResource(R.string.scan_preview) else stringResource(R.string.scan_crop))
    }
    Text(stringResource(R.string.scan_filter), color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip(stringResource(R.string.scan_filter_original), page.filter == SCAN_FILTER_ORIGINAL) { page.filter = SCAN_FILTER_ORIGINAL }
        ModeChip(stringResource(R.string.scan_filter_gray), page.filter == SCAN_FILTER_GREY) { page.filter = SCAN_FILTER_GREY }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        ModeChip(stringResource(R.string.scan_filter_ink), page.filter == SCAN_FILTER_INK) { page.filter = SCAN_FILTER_INK }
        ModeChip(stringResource(R.string.scan_filter_enhanced), page.filter == SCAN_FILTER_ENHANCED) { page.filter = SCAN_FILTER_ENHANCED }
    }
    Text(stringResource(R.string.scan_filter_hint), color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    ScanSlider(stringResource(R.string.trace_brightness), page.brightness.toFloat(), -100f, 100f, "%.0f") { page.brightness = it.toInt() }
    ScanSlider(stringResource(R.string.trace_contrast), page.contrast.toFloat(), 50f, 200f, "%.0f%%") { page.contrast = it.toInt() }
    Text(stringResource(R.string.trace_rotation), color = dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (q in 0..3) {
            val on = page.rotation == q
            Text(
                stringResource(R.string.trace_degrees, q * 90), color = if (on) text else dim,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (on) palette.selectionBackground.toComposeColor() else palette.menuBg.toComposeColor())
                    .clickable { page.rotation = q }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        TextButton(onClick = onAuto) { Text(stringResource(R.string.scan_auto)) }
        TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
    }
    if (error != null) {
        Text(error, color = palette.danger.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ScanSlider(label: String, value: Float, min: Float, max: Float, format: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Slider(value = value, onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(format.format(value), fontSize = 12.sp, color = LocalPalette.current.textDim.toComposeColor(), modifier = Modifier.width(52.dp))
    }
}
