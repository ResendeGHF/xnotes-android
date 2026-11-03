package com.xnotes.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.canvas.InteractionController
import com.xnotes.handwrite.HandwriteText
import com.xnotes.handwrite.HBlock
import com.xnotes.handwrite.PdfPageContent
import com.xnotes.handwrite.blocksHaveContent
import com.xnotes.handwrite.blocksPreview
import com.xnotes.handwrite.parseLatex
import com.xnotes.handwrite.parseMarkdown
import com.xnotes.handwrite.plainBlocks
import com.xnotes.platform.ReaderPdfText
import com.xnotes.scan.InkVectorizer
import com.xnotes.handwrite.HandwriteTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Text-to-handwriting: typed text, the selected text box, an imported
 * .md/.tex/.txt file or a whole PDF becomes handwriting on the page — glyph
 * lines plus editable math and editable tables, flowing onto fresh pages so
 * entire documents fit. PDF pages without a text layer (scans) are traced to
 * strokes instead of skipped.
 *
 * Hosted from the Insert menu; everything it needs hangs off [controller], so
 * no editor state was added for it. File reads, parsing and tracing run off
 * the main thread; placement lands as one undoable edit (replacing the
 * selected box when asked).
 */
@Composable
fun TextToStrokesDialog(
    controller: InteractionController,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(controller.singleSelectedText()?.text ?: "") }
    var blocks by remember { mutableStateOf<List<HBlock>?>(null) }
    var sizePt by remember { mutableFloatStateOf(max(controller.textPointSize.toFloat(), 18f).coerceIn(12f, 32f)) }
    var replace by remember { mutableStateOf(controller.singleSelectedText() != null) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }

    fun convert() {
        val structured = blocks
        val input = if (structured != null && blocksHaveContent(structured)) structured
        else plainBlocks(HandwriteText.normalize(text))
        if (!blocksHaveContent(input)) {
            onMessage(context.getString(R.string.handwrite_empty))
            return
        }
        busy = true
        try {
            val sel = controller.singleSelectedText()
            val ok = if (replace && sel != null) {
                controller.replaceTextWithHandwritingBlocks(sel, input, sizePt.toDouble())
            } else {
                controller.insertHandwritingBlocks(input, sizePt.toDouble())
            }
            if (ok) onDismiss() else onMessage(context.getString(R.string.handwrite_failed))
        } catch (e: Exception) {
            onMessage(context.getString(R.string.handwrite_failed_text, e.message ?: ""))
        } finally {
            busy = false
        }
    }

    fun importTextFile(uri: Uri) {
        scope.launch {
            busy = true
            busyText = context.getString(R.string.handwrite_reading)
            try {
                val pair = withContext(Dispatchers.IO) { readUriText(context, uri) }
                if (pair == null) {
                    onMessage(context.getString(R.string.handwrite_failed))
                    return@launch
                }
                val parsed = withContext(Dispatchers.Default) {
                    val (name, content) = pair
                    when (name.substringAfterLast('.', "").substringAfterLast('/', "").lowercase()) {
                        "md", "markdown", "mdown", "mkd" -> parseMarkdown(content)
                        "tex", "latex", "sty", "cls", "dtx" -> parseLatex(content)
                        else -> plainBlocks(HandwriteText.normalize(content))
                    }
                }
                if (!blocksHaveContent(parsed)) {
                    onMessage(context.getString(R.string.handwrite_empty))
                    return@launch
                }
                blocks = parsed
                text = blocksPreview(parsed)
            } catch (e: Exception) {
                onMessage(context.getString(R.string.handwrite_failed_text, e.message ?: ""))
            } finally {
                busy = false
                busyText = ""
            }
        }
    }

    fun importPdf(uri: Uri) {
        scope.launch {
            busy = true
            busyText = context.getString(R.string.handwrite_reading)
            try {
                val result = withContext(Dispatchers.IO) { readPdfPages(context, uri, sizePt.toDouble()) { page, total ->
                    scope.launch(Dispatchers.Main) {
                        busyText = context.getString(R.string.handwrite_tracing, page, total)
                    }
                } }
                if (result == null) {
                    onMessage(context.getString(R.string.handwrite_failed))
                    return@launch
                }
                val (contents, skipped, total) = result
                if (contents.isEmpty()) {
                    onMessage(context.getString(R.string.handwrite_pdf_empty))
                    return@launch
                }
                busyText = context.getString(R.string.handwrite_placing)
                val placed = controller.insertPdfPages(contents, sizePt.toDouble())
                if (placed <= 0) {
                    onMessage(context.getString(R.string.handwrite_failed))
                    return@launch
                }
                onMessage(context.getString(R.string.handwrite_added, placed, total))
                if (skipped.isNotEmpty()) {
                    onMessage(context.getString(R.string.handwrite_pdf_skipped, skipped.joinToString(", ")))
                }
                onDismiss()
            } catch (e: Exception) {
                onMessage(context.getString(R.string.handwrite_failed_text, e.message ?: ""))
            } finally {
                busy = false
                busyText = ""
            }
        }
    }

    val pickTextFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importTextFile(uri)
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importPdf(uri)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.handwrite_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; blocks = null },
                    placeholder = { Text(stringResource(R.string.handwrite_hint)) },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        controller.singleSelectedText()?.let { text = it.text; blocks = null }
                    }) { Text(stringResource(R.string.handwrite_use_selected)) }
                    TextButton(enabled = !busy, onClick = { pickTextFile.launch("*/*") }) {
                        Text(stringResource(R.string.handwrite_import_file))
                    }
                    TextButton(enabled = !busy, onClick = { pickPdf.launch("application/pdf") }) {
                        Text(stringResource(R.string.handwrite_import_pdf))
                    }
                }
                Text(stringResource(R.string.handwrite_size, sizePt.toInt()), modifier = Modifier.fillMaxWidth())
                Slider(
                    value = sizePt,
                    onValueChange = { sizePt = it },
                    valueRange = 12f..32f,
                    steps = 19,
                    enabled = !busy,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = replace,
                        onCheckedChange = { replace = it },
                        enabled = !busy && controller.singleSelectedText() != null,
                    )
                    Text(stringResource(R.string.handwrite_replace))
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (busyText.isNotEmpty()) Text(busyText)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (text.isNotBlank() || (blocks != null && blocksHaveContent(blocks!!))), onClick = { convert() }) {
                Text(stringResource(R.string.handwrite_convert))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Display name + UTF-8 content of [uri], or null when unreadable/oversize. */
private fun readUriText(context: android.content.Context, uri: Uri): Pair<String, String>? {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: "note.txt"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > 8_000_000) return null
    return name to bytes.toString(Charsets.UTF_8)
}

private const val MAX_PDF_PAGES = 100
private const val SCAN_RENDER_W = 1400

private data class PdfRead(val contents: List<PdfPageContent>, val skipped: List<Int>, val total: Int)

/**
 * Every page of the PDF at [uri]: pages with a text layer become structured
 * text blocks, pages without one are rendered and traced to strokes. IO.
 * [onTrace] reports scan progress. Null on failure.
 */
private fun readPdfPages(
    context: android.content.Context,
    uri: Uri,
    pointSize: Double,
    onTrace: (page: Int, total: Int) -> Unit,
): PdfRead? {
    val tmp = java.io.File(context.cacheDir, "hw-import-${System.currentTimeMillis()}.pdf")
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return null
        val src = com.xnotes.platform.PdfSource.open(tmp)
        try {
            val total = src.pageCount.coerceAtMost(MAX_PDF_PAGES).coerceAtLeast(0)
            if (total <= 0) return null
            val texts = ReaderPdfText.pages(context, "hw:$uri") { tmp.inputStream() }
            val contents = ArrayList<PdfPageContent>()
            val skipped = ArrayList<Int>()
            for (i in 0 until total) {
                val body = HandwriteText.normalize(texts.getOrNull(i)?.text.orEmpty())
                if (body.isNotBlank()) {
                    contents.add(PdfPageContent.Text(plainBlocks(body)))
                    continue
                }
                val scan = tracePdfPage(src, i, total, onTrace) ?: run { skipped.add(i + 1); continue }
                contents.add(PdfPageContent.Scan(scan))
            }
            return PdfRead(contents, skipped, total)
        } finally {
            src.close()
        }
    } catch (_: Exception) {
        return null
    } finally {
        tmp.delete()
    }
}

/** Render PDF page [i] and trace its print to strokes; null when it holds no ink. IO. */
private fun tracePdfPage(
    src: com.xnotes.platform.PdfSource,
    i: Int,
    total: Int,
    onTrace: (page: Int, total: Int) -> Unit,
): HandwriteTrace.ScanPage? {
    onTrace(i + 1, total)
    val (pw, ph) = src.pageSizePoints(i) ?: (612f to 792f)
    if (pw <= 0f || ph <= 0f || !pw.isFinite() || !ph.isFinite()) return null
    val w = SCAN_RENDER_W
    val h = (w * ph / pw).toInt().coerceIn(1, 2400)
    val bmp = src.renderPage(i, w, h)?.bitmap ?: return null
    if (bmp.width <= 0 || bmp.height <= 0) {
        bmp.recycle()
        return null
    }
    val bw = bmp.width
    val bh = bmp.height
    val px = IntArray(bw * bh)
    bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
    bmp.recycle()
    // getPixels honours the real size when the backend clamps the request.
    val lum = IntArray(bw * bh) { k ->
        val p = px[k]
        (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
    }
    val paths = InkVectorizer.vectorize(
        lum, bw, bh,
        InkVectorizer.TraceOptions(threshold = 128, printedText = true, removeRulings = false, closeGaps = false),
    )
    if (paths.isEmpty()) return null
    return HandwriteTrace.ScanPage(paths, bw, bh)
}
