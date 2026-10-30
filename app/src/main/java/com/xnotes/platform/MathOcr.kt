package com.xnotes.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Stroke
import com.xnotes.core.text.RecognizedLatex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Handwritten maths to LaTeX with the formula model ([MathOcrManifest]), fully on
 * device once its files are downloaded. The model reads whole expressions at once —
 * stacked fractions, integrals with limits, sums, roots, multi-variable terms,
 * derivatives and Greek-letter physics notation — where the built-in structural
 * fallback ([InkMathOcr]) only reassembles what handwriting recognition spells out.
 *
 * Files come from the model's canonical upstream at a pinned revision with a
 * per-file SHA-256 check, resume across attempts, and retries with backoff. ONNX
 * Runtime ships inside the APK, so the model files are the only download.
 *
 * Recognition renders the selected strokes to an image and runs the model's encoder
 * once, then its decoder greedily, one token at a time.
 */
object MathOcr {

    sealed interface State {
        data object Missing : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    private const val INPUT = 384
    private const val BOS = 1L
    private const val EOS = 2
    private const val MAX_TOKENS = 256
    private const val MAX_ATTEMPTS = 4

    val DOWNLOAD_BYTES: Long get() = MathOcrManifest.DOWNLOAD_BYTES

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var download: Job? = null

    fun dir(context: Context) = File(context.filesDir, "addons/math-ocr")

    fun isInstalled(context: Context): Boolean = MathOcrManifest.isCompleteDir(dir(context))

    /** Sync [state] with what is on disk; a download in flight is left to report itself. */
    fun refresh(context: Context) {
        if (_state.value is State.Downloading) return
        _state.value = if (isInstalled(context)) State.Installed else State.Missing
    }

    fun startDownload(context: Context) {
        if (download?.isActive == true) return
        val app = context.applicationContext
        download = scope.launch {
            val target = dir(app).apply { mkdirs() }
            // Enough free bytes for the missing tail plus headroom; else fail fast
            // with a message instead of dying mid-file.
            val missing = MathOcrManifest.FILES.sumOf { f ->
                val part = File(target, f.name + ".part").length()
                val done = File(target, f.name).let { if (it.length() == f.size) f.size else minOf(part, f.size) }
                (f.size - done).coerceAtLeast(0)
            }
            val free = runCatching { target.freeSpace }.getOrDefault(Long.MAX_VALUE)
            if (missing > 0 && free < missing + 32 * 1024 * 1024) {
                _state.value = State.Failed("not enough storage (needs ${missing / 1_000_000} MB free)")
                return@launch
            }
            var done = MathOcrManifest.FILES.sumOf { f ->
                val out = File(target, f.name)
                if (out.length() == f.size) f.size else minOf(File(target, f.name + ".part").length(), f.size)
            }
            _state.value = State.Downloading(done.coerceAtMost(DOWNLOAD_BYTES), DOWNLOAD_BYTES)
            try {
                for (f in MathOcrManifest.FILES) {
                    val out = File(target, f.name)
                    if (out.length() == f.size && shaMatches(out, f.sha256)) {
                        continue
                    }
                    if (out.length() == f.size) {
                        // Right size but wrong bytes: it counts in [done] above, take it back.
                        done -= f.size
                        out.delete()
                    } else {
                        out.delete()
                    }
                    downloadFile(f, File(target, f.name + ".part")) { n ->
                        done += n
                        _state.value = State.Downloading(done.coerceAtMost(DOWNLOAD_BYTES), DOWNLOAD_BYTES)
                    }
                    if (!isActive) return@launch
                    val part = File(target, f.name + ".part")
                    if (part.length() != f.size || !shaMatches(part, f.sha256)) {
                        part.delete()
                        error("${f.name} is corrupt, try again")
                    }
                    part.renameTo(out)
                }
                File(target, MathOcrManifest.VERSION_FILE).writeText(MathOcrManifest.REVISION)
                _state.value = State.Installed
            } catch (e: Exception) {
                if (!isActive) {
                    _state.value = if (isInstalled(app)) State.Installed else State.Missing
                } else {
                    _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun cancelDownload(context: Context) {
        download?.cancel()
        download = null
        // Keep .part files so the next attempt resumes instead of restarting.
        _state.value = if (isInstalled(context)) State.Installed else State.Missing
    }

    fun delete(context: Context) {
        cancelDownload(context)
        dir(context).deleteRecursively()
        _state.value = State.Missing
    }

    /**
     * Download [f] into [part], resuming from its current length, calling [onBytes]
     * for each fresh byte. Retries with backoff; throws on persistent failure.
     */
    private suspend fun downloadFile(f: MathOcrManifest.AddonFile, part: File, onBytes: (Long) -> Unit) {
        var attempt = 0
        var last: Exception? = null
        while (attempt < MAX_ATTEMPTS) {
            try {
                fetchOnce(f, part, onBytes)
                return
            } catch (e: Exception) {
                last = e
                // Range-not-satisfiable means the part overran (stale); restart it.
                if (e.message?.contains("416") == true) part.delete()
                attempt++
                if (attempt < MAX_ATTEMPTS) delay(1_000L shl attempt)
            }
        }
        throw last ?: IllegalStateException("download failed")
    }

    private fun fetchOnce(f: MathOcrManifest.AddonFile, part: File, onBytes: (Long) -> Unit) {
        val have = part.length().coerceAtMost(f.size)
        if (have >= f.size) return
        val conn = URL(f.url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            conn.connect()
            val code = conn.responseCode
            if (have > 0 && code == 416) {
                error("HTTP 416")
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                error("HTTP $code")
            }
            // A server that ignores Range restarts from zero: drop the part tail.
            val fromZero = have > 0 && code == HttpURLConnection.HTTP_OK
            RandomAccessFile(part, "rw").use { raf ->
                if (fromZero) raf.setLength(0)
                raf.seek(raf.length())
                conn.inputStream.use { src ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        onBytes(n.toLong())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun shaMatches(file: File, expected: String): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { src ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }.getOrDefault(false)

    /**
     * A loaded formula model, held for a whole export: opening it reads ~120 MB of weights,
     * so callers open one [Session], recognize every formula with it, and close it.
     * Not thread-safe; use from one thread at a time.
     */
    class Session internal constructor(
        private val env: OrtEnvironment,
        private val encoder: OrtSession,
        private val decoder: OrtSession,
        private val vocab: Map<Int, String>,
    ) : AutoCloseable {

        /** Recognize [strokes] (content px, inside [bounds]) as LaTeX, or "" when the ink is blank. */
        fun recognize(strokes: List<Stroke>, bounds: Rect): String {
            val pixels = render(strokes, bounds)
            // White pixels normalize to +1, so an image with no ink is all ones.
            if (pixels.all { it >= 0.999f }) return ""
            val ids = ArrayList<Long>().apply { add(BOS) }
            OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(pixels), longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { image ->
                encoder.run(mapOf("pixel_values" to image)).use { encoded ->
                    val hidden = encoded[0] as OnnxTensor
                    while (ids.size < MAX_TOKENS) {
                        val next = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(ids.toLongArray()), longArrayOf(1, ids.size.toLong())).use { input ->
                            decoder.run(mapOf("input_ids" to input, "encoder_hidden_states" to hidden)).use { out ->
                                lastArgmax(out[0] as OnnxTensor, ids.size)
                            }
                        }
                        if (next == EOS) break
                        ids.add(next.toLong())
                    }
                }
            }
            return RecognizedLatex.clean(decode(ids, vocab))
        }

        override fun close() {
            encoder.close()
            decoder.close()
        }
    }

    /** Load the model once for a batch of recognitions. Throws when it is not installed or will not load. */
    fun open(context: Context): Session {
        if (!isInstalled(context)) {
            throw IllegalStateException("math model is not downloaded — fetch it in Settings first")
        }
        val d = dir(context)
        val vocab = loadVocab(File(d, "tokenizer.json"))
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            // KleidiAI's SME kernels trap (SIGILL) where a CPU reports SME it cannot run, as
            // emulators on SME hosts do; the plain kernels are plenty for a model this small.
            addConfigEntry("mlas.disable_kleidiai", "1")
        }
        val encoder = env.createSession(File(d, "encoder_model.onnx").path, opts)
        val decoder = try {
            env.createSession(File(d, "decoder_model.onnx").path, opts)
        } catch (e: Throwable) {
            encoder.close()
            throw e
        }
        return Session(env, encoder, decoder, vocab)
    }

    /** One-off recognition: opens and closes the model around a single call. Prefer [open] for batches. */
    fun recognize(context: Context, strokes: List<Stroke>, bounds: Rect): String =
        open(context).use { it.recognize(strokes, bounds) }

    private fun lastArgmax(logits: OnnxTensor, steps: Int): Int {
        val buf = logits.floatBuffer
        val vocab = (logits.info.shape[2]).toInt()
        val base = (steps - 1) * vocab
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        for (k in 0 until vocab) {
            val v = buf.get(base + k)
            if (v > bestV) { bestV = v; best = k }
        }
        return best
    }

    /**
     * The strokes drawn black on white, scaled to fit the 384 px square the model takes
     * WITHOUT distorting them (a wide formula keeps its shape and is centred, with white
     * around it), as a normalized CHW float image.
     */
    private fun render(strokes: List<Stroke>, bounds: Rect): FloatArray {
        val w = bounds.w.coerceAtLeast(1.0)
        val h = bounds.h.coerceAtLeast(1.0)
        // Glyphs about 160 px tall at the natural scale, capped so a very long line stays bounded.
        val scale = minOf(160.0 / maxOf(h, w / 10.0, 1.0), 2400.0 / maxOf(w, 1.0))
        val pad = 24.0
        val drawW = w * scale + 2 * pad
        val drawH = h * scale + 2 * pad
        val fit = minOf(INPUT / drawW, INPUT / drawH)
        val out = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.translate(((INPUT - drawW * fit) / 2).toFloat(), ((INPUT - drawH * fit) / 2).toFloat())
        canvas.scale(fit.toFloat(), fit.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (3.0 / fit).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            val n = s.sampleCount
            if (n == 0) continue
            fun x(i: Int) = ((s.xAt(i) - bounds.left) * scale + pad).toFloat()
            fun y(i: Int) = ((s.yAt(i) - bounds.top) * scale + pad).toFloat()
            if (n == 1) {
                canvas.drawPoint(x(0), y(0), paint)
                continue
            }
            val path = Path().apply { moveTo(x(0), y(0)) }
            for (i in 1 until n) path.lineTo(x(i), y(i))
            canvas.drawPath(path, paint)
        }
        val px = IntArray(INPUT * INPUT)
        out.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        out.recycle()
        val plane = INPUT * INPUT
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            chw[i] = ((c shr 16 and 0xFF) / 255f - 0.5f) / 0.5f
            chw[plane + i] = ((c shr 8 and 0xFF) / 255f - 0.5f) / 0.5f
            chw[2 * plane + i] = ((c and 0xFF) / 255f - 0.5f) / 0.5f
        }
        return chw
    }

    private fun loadVocab(file: File): Map<Int, String> {
        val root = JSONObject(file.readText())
        val out = HashMap<Int, String>()
        val vocab = root.getJSONObject("model").getJSONObject("vocab")
        for (k in vocab.keys()) out[vocab.getInt(k)] = k
        return out
    }

    /** Byte-level BPE: each token character stands for one byte of UTF-8 (GPT-2's table). */
    private fun decode(ids: List<Long>, vocab: Map<Int, String>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id <= 4) continue
            val token = vocab[id.toInt()] ?: continue
            for (ch in token) BYTE_OF[ch]?.let { bytes.write(it) }
        }
        return bytes.toString("UTF-8").trim()
    }

    private val BYTE_OF: Map<Char, Int> by lazy {
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code)
        bs.addAll(0xA1..0xAC)
        bs.addAll(0xAE..0xFF)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        bs.indices.associate { cs[it].toChar() to bs[it] }
    }
}
