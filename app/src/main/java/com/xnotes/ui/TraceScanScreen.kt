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
import com.xnotes.core.util.Paths
import com.xnotes.scan.InkVectorizer
import com.xnotes.scan.TraceDocument
import com.xnotes.scan.TracePreprocess
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    var denoise by remember { mutableFloatStateOf(8f) }
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

    // Debounced re-trace of the preview page on any param change.
    LaunchedEffect(
        sourceBmp, quarterTurns, fineDegrees, cropL, cropT, cropR, cropB,
        brightness, contrast, enhance, autoThreshold, threshold, invert, denoise,
    ) {
        val src = sourceBmp ?: return@LaunchedEffect
        delay(250)
        working = true
        outOfMemory = false
        val snapAuto = autoThreshold
        val snapLevel = threshold.toInt().coerceIn(1, 254)
        val snapEnhance = enhance
        val snapInvert = invert
        val snapDenoise = denoise.toInt()
        val result = traceMutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    runCatching {
                        val geo = TracePreprocess.adjustGeometry(src, crop(), quarterTurns, fineDegrees)
                        val photo = TracePreprocess.adjustPhoto(geo, brightness.toInt(), contrast.toInt())
                        if (photo !== geo) geo.recycle()
                        val raw = TracePreprocess.toLuminance(photo)
                        val lum = if (snapEnhance) InkVectorizer.autoEnhance(raw, photo.width, photo.height) else raw
                        val opts = InkVectorizer.TraceOptions(
                            threshold = if (snapAuto) null else snapLevel,
                            adaptive = snapAuto && !snapInvert,
                            invert = snapInvert,
                            denoisePx = snapDenoise,
                        )
                        val paths = InkVectorizer.vectorize(lum, photo.width, photo.height, opts)
                        val mask = InkVectorizer.applyThreshold(lum, photo.width, photo.height, opts)
                        val preview = TracePreprocess.maskPreview(mask, photo.width, photo.height)
                        photo.recycle()
                        // The level the auto mode settled on (-1 for local Sauvola).
                        val shown = if (!snapAuto) snapLevel else if (snapInvert) InkVectorizer.otsuThreshold(lum) else -1
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
        val snapQ = quarterTurns
        val snapFine = fineDegrees
        val snapBright = brightness.toInt()
        val snapContrast = contrast.toInt()
        val snapAuto = autoThreshold
        val snapLevel = threshold.toInt().coerceIn(1, 254)
        val snapEnhance = enhance
        val snapInvert = invert
        val snapDenoise = denoise.toInt()
        val snapInk = inks[inkIndex.coerceIn(inks.indices)]
        val snapWidth = inkWidth
        val snapName = noteName.ifBlank { Paths.stem(request.sourceName).ifEmpty { "Traced notes" } }
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
                        val src = withContext(Dispatchers.IO) {
                            when (k) {
                                TracePreprocess.TraceKind.IMAGE -> TracePreprocess.decodeBounded(file, side)
                                TracePreprocess.TraceKind.PDF -> TracePreprocess.renderPdfPage(file, p, side)
                            }
                        } ?: continue
                        val traced = runCatching {
                            val geo = TracePreprocess.adjustGeometry(src, snapCrop, snapQ, snapFine)
                            val photo = TracePreprocess.adjustPhoto(geo, snapBright, snapContrast)
                            if (photo !== geo) geo.recycle()
                            val raw = TracePreprocess.toLuminance(photo)
                            val lum = if (snapEnhance) InkVectorizer.autoEnhance(raw, photo.width, photo.height) else raw
                            val paths = InkVectorizer.vectorize(
                                lum, photo.width, photo.height,
                                InkVectorizer.TraceOptions(
                                    threshold = if (snapAuto) null else snapLevel,
                                    adaptive = snapAuto && !snapInvert,
                                    invert = snapInvert,
                                    denoisePx = snapDenoise,
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
                    Row(
                        modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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
                }

                @Composable
                fun ParamsContent() {
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
                                val on = quarterTurns == q
                                Text(
                                    stringResource(R.string.trace_degrees, q * 90), color = if (on) accent else dim,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(if (on) palette.selectionBackground.toComposeColor() else palette.menuBg.toComposeColor())
                                        .clickable { quarterTurns = q }.padding(horizontal = 12.dp, vertical = 8.dp),
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.trace_auto_enhance), color = text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(checked = enhance, onCheckedChange = { enhance = it })
                        }
                        Text(stringResource(R.string.trace_auto_enhance_hint), color = dim, fontSize = 12.sp)
                        TraceSlider(stringResource(R.string.trace_brightness), brightness, -100f, 100f, "%.0f") { brightness = it }
                        TraceSlider(stringResource(R.string.trace_contrast), contrast, 50f, 200f, "%.0f%%") { contrast = it }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.trace_auto_threshold), color = text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Switch(checked = autoThreshold, onCheckedChange = { autoThreshold = it })
                        }
                        if (!autoThreshold) {
                            TraceSlider(stringResource(R.string.trace_threshold), threshold, 1f, 254f, "%.0f") { threshold = it }
                        } else if (!invert) {
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
                            Switch(checked = invert, onCheckedChange = { invert = it })
                        }
                        Text(stringResource(R.string.trace_invert_hint), color = dim, fontSize = 12.sp)
                        TraceSlider(stringResource(R.string.trace_cleanup), denoise, 0f, 60f, "%.0f") { denoise = it }
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
