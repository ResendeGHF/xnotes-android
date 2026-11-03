package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.text.VlmLatex
import com.xnotes.core.text.VlmTraceTune
import com.xnotes.core.util.Paths
import com.xnotes.platform.VlmClient
import com.xnotes.scan.InkVectorizer
import com.xnotes.scan.TraceDocument
import com.xnotes.scan.TracePreprocess
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * "Trace scan into strokes": turn a photo/scan of handwritten paper (or a PDF of
 * such pages) into an editable note of real strokes, discarding the background.
 *
 * The dialog stages the pick into a temp file, shows the page, and lets the user
 * tune geometry (page selector for PDFs, crop margins, quarter + fine rotation)
 * and filters (brightness, contrast, threshold with auto mode, invert for
 * light-on-dark sources, speckle cleanup) against a live black-on-white preview
 * of what the tracer sees. Convert vectorizes every page (PDFs, capped) with the
 * same settings and writes a new note of pen strokes via
 * [Editor.createTracedNoteFile].
 */
@Composable
fun TraceScanDialog(
    editor: Editor,
    request: PendingTrace,
    root: String,
    parentDocId: String,
    onClose: () -> Unit,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    val accent = palette.accent.toComposeColor()

    var staged by remember { mutableStateOf<File?>(null) }
    var kind by remember { mutableStateOf<TracePreprocess.TraceKind?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // --- tunable params ---
    var pageIndex by remember { mutableIntStateOf(0) }
    var quarterTurns by remember { mutableIntStateOf(0) }
    var fineDegrees by remember { mutableFloatStateOf(0f) }
    var cropL by remember { mutableFloatStateOf(0f) }
    var cropT by remember { mutableFloatStateOf(0f) }
    var cropR by remember { mutableFloatStateOf(0f) }
    var cropB by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(100f) }
    var autoThreshold by remember { mutableStateOf(true) }
    var threshold by remember { mutableFloatStateOf(128f) }
    var enhance by remember { mutableStateOf(true) }
    var invert by remember { mutableStateOf(false) }
    var denoise by remember { mutableFloatStateOf(4f) }
    var removeLines by remember { mutableStateOf(true) }
    var printedText by remember { mutableStateOf(false) }
    var inkWidth by remember { mutableFloatStateOf(3f) }
    var inkIndex by remember { mutableIntStateOf(0) }
    var showBinary by remember { mutableStateOf(true) }
    var noteName by remember(request) { mutableStateOf(Paths.stem(request.sourceName).ifEmpty { "Traced notes" }) }

    // --- derived work ---
    var sourceBmp by remember { mutableStateOf<Bitmap?>(null) }
    var binaryBmp by remember { mutableStateOf<Bitmap?>(null) }
    var estimate by remember { mutableStateOf<TraceDocument.TracedPage?>(null) }
    var usedLevel by remember { mutableIntStateOf(128) }
    var working by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf(false) }
    var convertProgress by remember { mutableStateOf("") }
    var convertError by remember { mutableStateOf<String?>(null) }
    // Vision-model auto-tune: suggests the filter settings for this page.
    var tuning by remember { mutableStateOf(false) }
    var tuneMessage by remember { mutableStateOf<String?>(null) }
    var tuneIsError by remember { mutableStateOf(false) }
    // Per-page custom settings: page index → its full recipe. A page follows
    // the dialog sliders unless it has a recipe (stored by AI tune or by
    // switching the page to custom), so each PDF page can be tuned alone.
    val pageCustom = remember { mutableStateMapOf<Int, EffectiveTraceParams>() }

    // Serializes the preview pipeline against source-bitmap replacement, so a
    // trace never reads a bitmap the page flip just recycled.
    val traceMutex = remember { Mutex() }
    // Bitmaps leave composition a couple of generations late: the render thread
    // can still be mid-draw when a replacement lands, and recycling immediately
    // is a native crash. Retired generations are freed once no frame can hold them.
    val sourceRetire = remember { com.xnotes.scan.BitmapRetire<Bitmap>(2) { it.recycle() } }
    val binaryRetire = remember { com.xnotes.scan.BitmapRetire<Bitmap>(2) { it.recycle() } }
    // An OOM anywhere in the pipeline becomes this message instead of a crash.
    var outOfMemory by remember { mutableStateOf(false) }

    val inks = remember {
        listOf(
            Rgba(35, 35, 45),
            Rgba(35, 70, 170),
            Rgba(170, 40, 40),
            Rgba(30, 120, 70),
            Rgba(235, 235, 240),
        )
    }

    fun crop(): TracePreprocess.CropFrac {
        val l = (cropL / 100f).coerceIn(0f, 0.4f)
        val t = (cropT / 100f).coerceIn(0f, 0.4f)
        val r = 1f - (cropR / 100f).coerceIn(0f, 0.4f)
        val b = 1f - (cropB / 100f).coerceIn(0f, 0.4f)
        return TracePreprocess.CropFrac(l.coerceAtMost(r - 0.05f), t.coerceAtMost(b - 0.05f), r, b)
    }

    /**
     * The filter settings in force for page [p]: its custom recipe when the
     * page is switched to custom, the dialog sliders otherwise. Geometry
     * (crop, fine angle) stays global; the quarter turn rides with the recipe
     * since page orientation is genuinely per page.
     */
    fun effectiveFor(p: Int): EffectiveTraceParams {
        pageCustom[p]?.let { return it }
        return EffectiveTraceParams(
            autoThreshold = autoThreshold,
            threshold = threshold.toInt().coerceIn(1, 254),
            brightness = brightness.toInt(),
            contrast = contrast.toInt().coerceIn(50, 200),
            denoise = denoise.toInt().coerceIn(0, 60),
            removeLines = removeLines,
            printedText = printedText,
            invert = invert,
            enhance = enhance,
            quarterTurns = quarterTurns.coerceIn(0, 3),
        )
    }

    /** True when page [p] carries its own recipe instead of the globals. */
    fun isCustom(p: Int): Boolean = pageCustom.containsKey(p)

    /** Human-readable summary of the settings in force, sent along for the model. */
    fun currentSettingsSummary(): String {
        val eff = effectiveFor(pageIndex)
        val on = context.getString(R.string.trace_ai_on)
        val off = context.getString(R.string.trace_ai_off)
        return listOf(
            "${context.getString(R.string.trace_auto_threshold)} ${if (eff.autoThreshold) on else "${context.getString(R.string.trace_threshold)} ${eff.threshold}"}",
            "${context.getString(R.string.trace_brightness)} ${eff.brightness}",
            "${context.getString(R.string.trace_contrast)} ${eff.contrast}%",
            "${context.getString(R.string.trace_cleanup)} ${eff.denoise}px",
            "${context.getString(R.string.trace_remove_lines)} ${if (eff.removeLines) on else off}",
            "${context.getString(R.string.trace_content)} ${context.getString(if (eff.printedText) R.string.trace_content_print else R.string.trace_content_hand)}",
            "${context.getString(R.string.trace_invert)} ${if (eff.invert) on else off}",
            "${context.getString(R.string.trace_auto_enhance)} ${if (eff.enhance) on else off}",
            "${context.getString(R.string.trace_rotation)} ${context.getString(R.string.trace_degrees, eff.quarterTurns * 90)}",
        ).joinToString("; ")
    }

    /**
     * Build the image the model judges: the adjusted photo beside the current
     * edges preview, labelled, so it can see which ink survives and which
     * artifacts remain. Takes ownership of [photo] and [mask] (recycled).
     */
    fun labeledTuneImage(photo: Bitmap, mask: Bitmap): Bitmap {
        val labelH = (maxOf(photo.height, mask.height) / 14).coerceIn(28, 72)
        fun labeled(src: Bitmap, caption: String): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height + labelH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            canvas.drawColor(0xFFFFFFFF.toInt())
            canvas.drawBitmap(src, 0f, labelH.toFloat(), null)
            val paint = android.graphics.Paint().apply {
                color = 0xFF000000.toInt()
                textSize = labelH * 0.55f
                isAntiAlias = true
            }
            canvas.drawText(caption, labelH * 0.35f, labelH * 0.72f, paint)
            return out
        }
        val left = labeled(photo, "PHOTO")
        val right = labeled(mask, "EDGES")
        // Same height halves keep thin strokes legible on both sides.
        val h = maxOf(left.height, right.height)
        fun fit(src: Bitmap): Bitmap {
            if (src.height == h) return src
            val w = (src.width * (h.toDouble() / src.height)).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            if (scaled !== src) src.recycle()
            return scaled
        }
        val l = fit(left)
        val r = fit(right)
        val out = Bitmap.createBitmap(l.width + r.width, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(0xFFFFFFFF.toInt())
        canvas.drawBitmap(l, 0f, 0f, null)
        canvas.drawBitmap(r, l.width.toFloat(), 0f, null)
        l.recycle()
        r.recycle()
        return out
    }

    /**
     * Ask the configured vision model for this page's best filter settings and
     * apply whatever it returns. The model sees the photo beside the current
     * edges preview and must answer with explicit values (automatic modes are
     * normalized away); the full recipe is stored for this page alone, which
     * switches it to custom mode, so the preview updates at once and the
     * conversion reuses it. Globals stay untouched.
     */
    fun autoTune() {
        val src = sourceBmp ?: return
        if (tuning || converting) return
        editor.aiExportBlocker()?.let {
            tuneMessage = it
            tuneIsError = true
            return
        }
        tuning = true
        tuneMessage = null
        val page = pageIndex
        val summary = currentSettingsSummary()
        val snapCrop = crop()
        val snapFine = fineDegrees
        // Copy synchronously: the preview pipeline may retire this generation
        // while the model call is in flight; everything below works on the copy.
        val snap = runCatching { src.copy(src.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
        scope.launch {
            try {
                if (snap == null) throw IllegalStateException(context.getString(R.string.trace_ai_failed))
                val eff = effectiveFor(page)
                // Photo + edges preview with the settings in force, built fresh
                // so the model judges exactly what the tracer sees.
                val built = withContext(Dispatchers.Default) {
                    runCatching {
                        val geo = TracePreprocess.adjustGeometry(snap, snapCrop, eff.quarterTurns, snapFine)
                        val photo = TracePreprocess.adjustPhoto(geo, eff.brightness, eff.contrast)
                        if (photo !== geo) geo.recycle()
                        val raw = TracePreprocess.toLuminance(photo)
                        val lum = if (eff.enhance) InkVectorizer.autoEnhance(raw, photo.width, photo.height) else raw
                        val opts = InkVectorizer.TraceOptions(
                            threshold = if (eff.autoThreshold) null else eff.threshold,
                            adaptive = eff.autoThreshold && !eff.invert,
                            invert = eff.invert,
                            denoisePx = eff.denoise,
                            removeRulings = eff.removeLines,
                            printedText = eff.printedText,
                        )
                        val mask = InkVectorizer.inkMask(lum, photo.width, photo.height, opts)
                        val edges = TracePreprocess.maskPreview(mask, photo.width, photo.height)
                        val composed = labeledTuneImage(photo, edges)
                        photo.recycle()
                        edges.recycle()
                        snap.recycle()
                        Triple(composed, InkVectorizer.otsuThreshold(raw), opts)
                    }.getOrNull()
                } ?: throw IllegalStateException(context.getString(R.string.trace_ai_failed))
                val png = withContext(Dispatchers.Default) {
                    val bmp = built.first
                    val (tw, th) = VlmLatex.rasterSize(bmp.width.toFloat(), bmp.height.toFloat())
                    val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                    bmp.recycle()
                    val out = ByteArrayOutputStream()
                    val ok = scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                    scaled.recycle()
                    if (!ok) null else out.toByteArray()
                } ?: throw IllegalStateException(context.getString(R.string.trace_ai_failed))
                val config = editor.vlmConfig()
                val raw = withContext(Dispatchers.IO) {
                    VlmClient.askRaw(png, VlmTraceTune.promptFor(summary), config)
                }
                val parsed = VlmTraceTune.parseRecommendation(raw)
                    ?: throw IllegalStateException(context.getString(R.string.trace_ai_failed))
                // No automatic modes, ever: stray `true` values fall back to an
                // explicit recipe (Otsu level for the threshold).
                val sparse = VlmTraceTune.normalizeManual(parsed, built.second)
                // Materialize the full recipe: model-set fields win, anything it
                // omitted keeps following the dialog. Stored for this page only —
                // globals stay untouched, and the page switches to custom mode.
                val full = EffectiveTraceParams(
                    autoThreshold = sparse.autoThreshold ?: eff.autoThreshold,
                    threshold = (sparse.threshold ?: eff.threshold).coerceIn(1, 254),
                    brightness = sparse.brightness ?: eff.brightness,
                    contrast = (sparse.contrast ?: eff.contrast).coerceIn(50, 200),
                    denoise = (sparse.denoise ?: eff.denoise).coerceIn(0, 60),
                    removeLines = sparse.removeLines ?: eff.removeLines,
                    printedText = sparse.printedText ?: eff.printedText,
                    invert = sparse.invert ?: eff.invert,
                    enhance = sparse.enhance ?: eff.enhance,
                    quarterTurns = (sparse.quarterTurns ?: eff.quarterTurns).coerceIn(0, 3),
                )
                pageCustom[page] = full
                val on = context.getString(R.string.trace_ai_on)
                val off = context.getString(R.string.trace_ai_off)
                val changes = ArrayList<String>()
                sparse.threshold?.let { changes.add("${context.getString(R.string.trace_threshold)} $it") }
                sparse.brightness?.let { changes.add("${context.getString(R.string.trace_brightness)} $it") }
                sparse.contrast?.let { changes.add("${context.getString(R.string.trace_contrast)} $it%") }
                sparse.denoise?.let { changes.add("${context.getString(R.string.trace_cleanup)} ${it}px") }
                sparse.removeLines?.let { changes.add("${context.getString(R.string.trace_remove_lines)} ${if (it) on else off}") }
                sparse.printedText?.let {
                    changes.add("${context.getString(R.string.trace_content)} ${context.getString(if (it) R.string.trace_content_print else R.string.trace_content_hand)}")
                }
                sparse.invert?.let { changes.add("${context.getString(R.string.trace_invert)} ${if (it) on else off}") }
                sparse.quarterTurns?.let {
                    changes.add("${context.getString(R.string.trace_rotation)} ${context.getString(R.string.trace_degrees, it * 90)}")
                }
                tuneMessage = context.getString(R.string.trace_ai_applied, changes.joinToString(", "))
                tuneIsError = false
            } catch (e: Exception) {
                tuneMessage = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.trace_ai_failed)
                tuneIsError = true
            }
            tuning = false
        }
    }

    // Stage the pick once: copy the content URI aside, classify, count PDF pages.
    LaunchedEffect(request) {
        withContext(Dispatchers.IO) {
            val uri = android.net.Uri.parse(request.uri)
            val bytes = runCatching {
                val staged = request.file
                if (staged != null && staged.exists()) staged.readBytes()
                else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                loadError = context.getString(R.string.err_read_file)
                return@withContext
            }
            val k = TracePreprocess.classify(request.sourceName, request.mime)
            if (k == null) {
                loadError = context.getString(R.string.err_read_file)
                return@withContext
            }
            val suffix = when (k) {
                TracePreprocess.TraceKind.PDF -> ".pdf"
                TracePreprocess.TraceKind.IMAGE -> "." + request.sourceName.substringAfterLast('.', "img").take(4)
            }
            val file = TracePreprocess.stageBytes(bytes, suffix, context.cacheDir) ?: run {
                loadError = context.getString(R.string.err_read_file)
                return@withContext
            }
            if (k == TracePreprocess.TraceKind.PDF) {
                val n = TracePreprocess.pdfPageCount(file)
                if (n == 0) {
                    file.delete()
                    loadError = context.getString(R.string.err_read_file)
                    return@withContext
                }
                pageCount = n.coerceAtMost(MAX_TRACE_PAGES)
            } else {
                pageCount = 1
            }
            staged = file
            kind = k
            // A new document means new pages: drop any per-page recipes.
            pageCustom.clear()
        }
    }

    // Decode/render the current page at preview size.
    LaunchedEffect(staged, kind, pageIndex) {
        val file = staged ?: return@LaunchedEffect
        val k = kind ?: return@LaunchedEffect
        working = true
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                when (k) {
                    TracePreprocess.TraceKind.IMAGE -> TracePreprocess.decodeBounded(file, PREVIEW_MAX_SIDE)
                    TracePreprocess.TraceKind.PDF -> TracePreprocess.renderPdfPage(file, pageIndex, PREVIEW_MAX_SIDE)
                }
            }.getOrNull()
        }
        traceMutex.withLock {
            val old = sourceBmp
            sourceBmp = bmp
            sourceRetire.retire(old)
        }
        if (bmp == null) loadError = context.getString(R.string.err_read_image)
        working = false
    }

    // Debounced re-trace of the preview page on any param change. The page's
    // stored AI recipe wins over the dialog sliders (see [effectiveFor]), so
    // every PDF page previews exactly what the conversion will trace for it.
    LaunchedEffect(
        sourceBmp, pageIndex, quarterTurns, fineDegrees, cropL, cropT, cropR, cropB,
        brightness, contrast, enhance, autoThreshold, threshold, invert, denoise, removeLines, printedText,
        pageCustom[pageIndex],
    ) {
        val src = sourceBmp ?: return@LaunchedEffect
        delay(250)
        working = true
        outOfMemory = false
        val eff = effectiveFor(pageIndex)
        val result = traceMutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val geo = TracePreprocess.adjustGeometry(src, crop(), eff.quarterTurns, fineDegrees)
                        val photo = TracePreprocess.adjustPhoto(geo, eff.brightness, eff.contrast)
                        if (photo !== geo) geo.recycle()
                        val raw = TracePreprocess.toLuminance(photo)
                        val lum = if (eff.enhance) InkVectorizer.autoEnhance(raw, photo.width, photo.height) else raw
                        val opts = InkVectorizer.TraceOptions(
                            threshold = if (eff.autoThreshold) null else eff.threshold,
                            adaptive = eff.autoThreshold && !eff.invert,
                            invert = eff.invert,
                            denoisePx = eff.denoise,
                            removeRulings = eff.removeLines,
                            printedText = eff.printedText,
                        )
                        val paths = InkVectorizer.vectorize(lum, photo.width, photo.height, opts)
                        // The cleaned mask (denoise + ruling removal + gap closing),
                        // so the preview is exactly what the tracer skeletonizes.
                        val mask = InkVectorizer.inkMask(lum, photo.width, photo.height, opts)
                        val preview = TracePreprocess.maskPreview(mask, photo.width, photo.height)
                        photo.recycle()
                        // The level the auto mode settled on (-1 for local Sauvola).
                        val shown = if (!eff.autoThreshold) eff.threshold else if (eff.invert) InkVectorizer.otsuThreshold(lum) else -1
                        Triple(TraceDocument.TracedPage(paths, photo.width, photo.height), preview, shown)
                    }.getOrNull()
                }
            } catch (e: OutOfMemoryError) {
                outOfMemory = true
                null
            }
        }
        // Publish on the main thread; the previous preview retires a generation late.
        val old = binaryBmp
        if (result != null) {
            estimate = result.first
            binaryBmp = result.second
            usedLevel = result.third
        } else {
            estimate = null
        }
        binaryRetire.retire(old)
        working = false
    }

    // Temp files + bitmaps never outlive the dialog. A staged camera file handed
    // in via [PendingTrace.file] is owned from here: it goes once staged.
    // Displayed bitmaps are safe to free outright: the composition is gone too.
    DisposableEffect(Unit) {
        onDispose {
            sourceRetire.clear()
            binaryRetire.clear()
            sourceBmp?.recycle()
            binaryBmp?.recycle()
            staged?.delete()
            request.file?.takeIf { it.exists() }?.delete()
        }
    }

    fun convert() {
        val file = staged ?: return
        val k = kind ?: return
        if (converting) return
        converting = true
        convertError = null
        val snapCrop = crop()
        val snapFine = fineDegrees
        val snapBright = brightness.toInt()
        val snapContrast = contrast.toInt()
        val snapAuto = autoThreshold
        val snapLevel = threshold.toInt().coerceIn(1, 254)
        val snapEnhance = enhance
        val snapInvert = invert
        val snapDenoise = denoise.toInt()
        val snapLines = removeLines
        val snapPrint = printedText
        val snapQ = quarterTurns
        val snapInk = inks[inkIndex.coerceIn(inks.indices)]
        val snapWidth = inkWidth
        val snapName = noteName.ifBlank { Paths.stem(request.sourceName).ifEmpty { "Traced notes" } }
        // Freeze every page's recipe up front: flipping pages or dragging
        // sliders mid-run must not change what later pages trace with.
        val frozenCustom = HashMap(pageCustom)
        val globalFallback = EffectiveTraceParams(
            autoThreshold = snapAuto,
            threshold = snapLevel,
            brightness = snapBright,
            contrast = snapContrast,
            denoise = snapDenoise,
            removeLines = snapLines,
            printedText = snapPrint,
            invert = snapInvert,
            enhance = snapEnhance,
            quarterTurns = snapQ,
        )
        fun effFor(p: Int): EffectiveTraceParams = frozenCustom[p] ?: globalFallback
        scope.launch {
            // An OOM anywhere below becomes a message, never a crash: nothing has
            // been written yet at this point, so there is no partial note to clean.
            var oom = false
            val pages = try {
                withContext(Dispatchers.Default) {
                    val out = ArrayList<TraceDocument.TracedPage>()
                    val side = TracePreprocess.cappedWorkSide()
                    val n = if (k == TracePreprocess.TraceKind.PDF) pageCount else 1
                    for (p in 0 until n) {
                        convertProgress = if (n > 1) context.getString(R.string.trace_progress_pages, p + 1, n) else ""
                        val eff = effFor(p)
                        val src = withContext(Dispatchers.IO) {
                            when (k) {
                                TracePreprocess.TraceKind.IMAGE -> TracePreprocess.decodeBounded(file, side)
                                TracePreprocess.TraceKind.PDF -> TracePreprocess.renderPdfPage(file, p, side)
                            }
                        } ?: continue
                        val traced = runCatching {
                            val geo = TracePreprocess.adjustGeometry(src, snapCrop, eff.quarterTurns, snapFine)
                            val photo = TracePreprocess.adjustPhoto(geo, eff.brightness, eff.contrast)
                            if (photo !== geo) geo.recycle()
                            val raw = TracePreprocess.toLuminance(photo)
                            val lum = if (eff.enhance) InkVectorizer.autoEnhance(raw, photo.width, photo.height) else raw
                            val paths = InkVectorizer.vectorize(
                                lum, photo.width, photo.height,
                                InkVectorizer.TraceOptions(
                                    threshold = if (eff.autoThreshold) null else eff.threshold,
                                    adaptive = eff.autoThreshold && !eff.invert,
                                    invert = eff.invert,
                                    denoisePx = eff.denoise,
                                    removeRulings = eff.removeLines,
                                    printedText = eff.printedText,
                                ),
                            )
                            val page = TraceDocument.TracedPage(paths, photo.width, photo.height)
                            photo.recycle()
                            page
                        }.getOrNull()
                        src.recycle()
                        if (traced != null) out.add(traced)
                    }
                    out
                }
            } catch (e: OutOfMemoryError) {
                oom = true
                emptyList()
            }
            if (oom) {
                converting = false
                convertProgress = ""
                convertError = context.getString(R.string.trace_too_big)
                return@launch
            }
            if (pages.isEmpty() || pages.all { it.paths.isEmpty() }) {
                converting = false
                convertProgress = ""
                convertError = context.getString(R.string.trace_nothing_found)
                return@launch
            }
            val uri = withContext(Dispatchers.IO) {
                editor.createTracedNoteFile(
                    root, request.parentDocId ?: parentDocId, snapName, pages, snapInk, snapWidth.toDouble(),
                )
            }
            converting = false
            convertProgress = ""
            if (uri != null) onDone(uri) else convertError = context.getString(R.string.err_create_note)
        }
    }

    Dialog(onDismissRequest = { if (!converting) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxSize().background(palette.panel.toComposeColor()),
            color = palette.panel.toComposeColor(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (!converting) onClose() }) {
                        Icon(XnotesIcons.close, stringResource(R.string.close), tint = text)
                    }
                    Text(stringResource(R.string.trace_scan_title), color = text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    if (working) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                if (loadError != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(loadError ?: "", color = palette.danger.toComposeColor())
                    }
                    return@Column
                }
                if (staged == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Column
                }
                // Wide screens (landscape/tablet): the preview sits on the left and the
                // parameters on the right, so every slider drag shows at full size.
                // Narrow screens keep the stacked layout: preview above, parameters below.
                val wide = LocalConfiguration.current.screenWidthDp >= WIDE_TRACE_DP

                @Composable
                fun PreviewBox(modifier: Modifier) {
                    // Preview: binary (what the tracer sees) or the adjusted photo.
                    val shown: Bitmap? = if (showBinary) binaryBmp ?: sourceBmp else sourceBmp
                    Box(
                        modifier.clip(RoundedCornerShape(8.dp)).background(palette.menuBg.toComposeColor())
                            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (shown != null) {
                            Image(
                                shown.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit,
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }

                @Composable
                fun PreviewBar(modifier: Modifier) {
                    Column(modifier) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (kind == TracePreprocess.TraceKind.PDF && pageCount > 1) {
                                TextButton(onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) }, enabled = pageIndex > 0) { Text("‹") }
                                Text(
                                    context.getString(R.string.trace_page_of, pageIndex + 1, pageCount),
                                    color = dim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { pageIndex = (pageIndex + 1).coerceAtMost(pageCount - 1) }, enabled = pageIndex < pageCount - 1) { Text("›") }
                            } else {
                                val n = estimate?.paths?.size ?: 0
                                Text(
                                    if (showBinary) context.getString(R.string.trace_strokes_found, n)
                                    else stringResource(R.string.trace_photo_preview),
                                    color = dim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showBinary = !showBinary }) {
                                Switch(checked = showBinary, onCheckedChange = { showBinary = it })
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.trace_show_edges), color = dim, fontSize = 13.sp)
                            }
                        }
                        // Per-page scope: on = this page follows the dialog sliders
                        // like every image; off = it keeps its own recipe.
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.trace_apply_all),
                                color = text, fontSize = 13.sp, modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = !isCustom(pageIndex),
                                onCheckedChange = { global ->
                                    if (global) pageCustom.remove(pageIndex)
                                    else if (!isCustom(pageIndex)) pageCustom[pageIndex] = effectiveFor(pageIndex)
                                },
                            )
                        }
                    }
                }

                @Composable
                fun ParamsContent() {
                    // Displayed values resolve through the page recipe; writes go
                    // to the recipe in custom mode, to the globals otherwise.
                    val eff = effectiveFor(pageIndex)
                    val custom = isCustom(pageIndex)
                    TraceSection(stringResource(R.string.trace_section_paper)) {
                        if (kind == TracePreprocess.TraceKind.PDF && pageCount > 1) {
                            Text(
                                context.getString(R.string.trace_all_pages_note, pageCount),
                                color = dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        Text(stringResource(R.string.trace_rotation), color = dim, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (q in 0..3) {
                                val on = eff.quarterTurns == q
                                Text(
                                    stringResource(R.string.trace_degrees, q * 90), color = if (on) accent else dim,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(if (on) palette.selectionBackground.toComposeColor() else palette.menuBg.toComposeColor())
                                        .clickable {
                                            if (custom) pageCustom[pageIndex] = eff.copy(quarterTurns = q)
                                            else quarterTurns = q
                                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        TraceSlider(stringResource(R.string.trace_fine_angle), fineDegrees, -15f, 15f, "%.0f°") { fineDegrees = it }
                        TraceSlider(stringResource(R.string.trace_margin_left), cropL, 0f, 25f, "%.0f%%") { cropL = it }
                        TraceSlider(stringResource(R.string.trace_margin_top), cropT, 0f, 25f, "%.0f%%") { cropT = it }
                        TraceSlider(stringResource(R.string.trace_margin_right), cropR, 0f, 25f, "%.0f%%") { cropR = it }
                        TraceSlider(stringResource(R.string.trace_margin_bottom), cropB, 0f, 25f, "%.0f%%") { cropB = it }
                    }
                    TraceSection(stringResource(R.string.trace_section_filters)) {
                        Button(
                            onClick = ::autoTune,
                            enabled = !tuning && !converting && sourceBmp != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (tuning) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.trace_ai_tuning))
                            } else {
                                Icon(XnotesIcons.magicWand, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.trace_ai_tune))
                            }
                        }
                        Text(stringResource(R.string.trace_ai_hint), color = dim, fontSize = 12.sp)
                        if (tuneMessage != null) {
                            Text(
                                tuneMessage ?: "",
                                color = if (tuneIsError) palette.danger.toComposeColor() else dim,
                                fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        Text(stringResource(R.string.trace_content), color = dim, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((label, print) in listOf(
                                stringResource(R.string.trace_content_hand) to false,
                                stringResource(R.string.trace_content_print) to true,
                            )) {
                                val on = eff.printedText == print
                                Text(
                                    label, color = if (on) accent else dim,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(if (on) palette.selectionBackground.toComposeColor() else palette.menuBg.toComposeColor())
                                        .clickable {
                                            if (custom) pageCustom[pageIndex] = eff.copy(printedText = print)
                                            else printedText = print
                                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Text(stringResource(R.string.trace_content_hint), color = dim, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.trace_auto_enhance), color = text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = eff.enhance,
                                onCheckedChange = { if (custom) pageCustom[pageIndex] = eff.copy(enhance = it) else enhance = it },
                            )
                        }
                        Text(stringResource(R.string.trace_auto_enhance_hint), color = dim, fontSize = 12.sp)
                        TraceSlider(stringResource(R.string.trace_brightness), eff.brightness.toFloat(), -100f, 100f, "%.0f") {
                            if (custom) pageCustom[pageIndex] = eff.copy(brightness = it.toInt()) else brightness = it
                        }
                        TraceSlider(stringResource(R.string.trace_contrast), eff.contrast.toFloat(), 50f, 200f, "%.0f%%") {
                            if (custom) pageCustom[pageIndex] = eff.copy(contrast = it.toInt()) else contrast = it
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.trace_auto_threshold), color = text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = eff.autoThreshold,
                                onCheckedChange = { if (custom) pageCustom[pageIndex] = eff.copy(autoThreshold = it) else autoThreshold = it },
                            )
                        }
                        if (!eff.autoThreshold) {
                            TraceSlider(stringResource(R.string.trace_threshold), eff.threshold.toFloat(), 1f, 254f, "%.0f") {
                                if (custom) pageCustom[pageIndex] = eff.copy(threshold = it.toInt()) else threshold = it
                            }
                        } else if (!eff.invert) {
                            Text(
                                stringResource(R.string.trace_auto_local),
                                color = dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp),
                            )
                        } else {
                            Text(
                                context.getString(R.string.trace_auto_level, usedLevel),
                                color = dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.trace_invert), color = text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = eff.invert,
                                onCheckedChange = { if (custom) pageCustom[pageIndex] = eff.copy(invert = it) else invert = it },
                            )
                        }
                        Text(stringResource(R.string.trace_invert_hint), color = dim, fontSize = 12.sp)
                        TraceSlider(stringResource(R.string.trace_cleanup), eff.denoise.toFloat(), 0f, 60f, "%.0f") {
                            if (custom) pageCustom[pageIndex] = eff.copy(denoise = it.toInt()) else denoise = it
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.trace_remove_lines), color = text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = eff.removeLines,
                                onCheckedChange = { if (custom) pageCustom[pageIndex] = eff.copy(removeLines = it) else removeLines = it },
                            )
                        }
                        Text(stringResource(R.string.trace_remove_lines_hint), color = dim, fontSize = 12.sp)
                    }
                    TraceSection(stringResource(R.string.trace_section_ink)) {
                        TraceSlider(stringResource(R.string.trace_stroke_width), inkWidth, 1f, 12f, "%.1f") { inkWidth = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.trace_ink_colour), color = dim, fontSize = 13.sp)
                            for ((i, c) in inks.withIndex()) {
                                val cc = c.toComposeColor()
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape)
                                        .background(cc)
                                        .border(if (i == inkIndex) 2.dp else 1.dp, if (i == inkIndex) accent else dim, CircleShape)
                                        .clickable { inkIndex = i },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = noteName,
                            onValueChange = { noteName = it },
                            label = { Text(stringResource(R.string.trace_note_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (convertError != null) {
                        Text(convertError ?: "", color = palette.danger.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                    } else if (outOfMemory) {
                        Text(stringResource(R.string.trace_too_big), color = palette.danger.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (wide) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.weight(1.15f).fillMaxHeight()) {
                            PreviewBox(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 6.dp))
                            PreviewBar(Modifier.fillMaxWidth())
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .verticalScroll(rememberScrollState()).padding(start = 6.dp, end = 16.dp),
                        ) {
                            ParamsContent()
                        }
                    }
                } else {
                    PreviewBox(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp))
                    PreviewBar(Modifier.fillMaxWidth())
                    Column(Modifier.fillMaxWidth().weight(1.4f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        ParamsContent()
                    }
                }
                val total = estimate?.paths?.size ?: 0
                Button(
                    onClick = ::convert,
                    enabled = !converting && estimate != null,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    if (converting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (convertProgress.isNotEmpty()) convertProgress else stringResource(R.string.trace_working))
                    } else {
                        Text(context.getString(R.string.trace_convert, total))
                    }
                }
            }
        }
    }
}

/**
 * The filter settings in force for one page: plain values (no nulls), resolved
 * from a stored AI recipe over the dialog sliders by [TraceScanDialog].
 */
private data class EffectiveTraceParams(
    val autoThreshold: Boolean,
    val threshold: Int,
    val brightness: Int,
    val contrast: Int,
    val denoise: Int,
    val removeLines: Boolean,
    val printedText: Boolean,
    val invert: Boolean,
    val enhance: Boolean,
    val quarterTurns: Int,
)

/** Max PDF pages traced in one run (memory + note-size bound). */
private const val MAX_TRACE_PAGES = 30

/** Screen width at or above which the preview and parameters sit side by side. */
private const val WIDE_TRACE_DP = 720

/** Longest side of the on-screen preview + estimate (convert re-renders full-size). */
private const val PREVIEW_MAX_SIDE = 1100

@Composable
private fun TraceSection(title: String, content: @Composable () -> Unit) {
    Text(title, color = LocalPalette.current.textDim.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    content()
}

@Composable
private fun TraceSlider(label: String, value: Float, min: Float, max: Float, format: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Slider(value = value, onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(1f))
        Text(format.format(value), fontSize = 12.sp, color = LocalPalette.current.textDim.toComposeColor(), modifier = Modifier.width(52.dp))
    }
}
