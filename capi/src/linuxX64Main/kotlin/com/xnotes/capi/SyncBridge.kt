@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.capi

import com.xnotes.capi.c.xn_host
import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.core.platform.fileOf
import com.xnotes.core.tools.ToolConfig
import com.xnotes.sync.CompanionEngine
import com.xnotes.sync.SyncEngineHost
import com.xnotes.sync.SyncFileEntry
import com.xnotes.sync.SyncFingerprint
import com.xnotes.sync.SyncOp
import com.xnotes.sync.WireSample
import com.xnotes.sync.WireStroke
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import platform.posix.memcpy
import platform.posix.strdup
import kotlin.random.Random

/**
 * The companion sync engine behind the C API (SYNC.md v1), living in the core.
 * Local gestures flow in through controller hooks (sample per sample, no
 * batching); remote commits apply straight to the bound open documents,
 * bypassing undo; remote live runs paint in the interaction overlay.
 */
internal class SyncBridge(private val host: xn_host, pinsDir: String, deviceName: String, ownFp: String) : SyncEngineHost {
    val engine = CompanionEngine(this, fileOf(pinsDir), deviceName, ownFp)

    /** Sync file id to the editor showing it. */
    private val bindings = HashMap<String, CEditor>()
    private val reverse = HashMap<CEditor, String>()
    private var dedupeCounter = 0

    /** The live stroke each editor is drawing now, if its file is shared. */
    private class ActiveStroke(
        val fileId: String,
        var page: Int,
        val id: String,
        val tool: String,
        val width: Double,
        val color: String,
    )
    private val active = HashMap<CEditor, ActiveStroke>()

    // --- binding ---

    fun bind(editor: CEditor, fileId: String) {
        unbindEditor(editor)
        bindings[fileId] = editor
        reverse[editor] = fileId
        val overlay = RemoteLiveStrokes(editor.state)
        overlay.showingFile = fileId
        editor.controller.remoteLive = overlay
        engine.liveOverlay = overlay
        attach(editor)
    }

    fun unbindEditor(editor: CEditor) {
        val fileId = reverse.remove(editor) ?: return
        bindings.remove(fileId)
        active.remove(editor)
        if (editor.controller.remoteLive === engine.liveOverlay) editor.controller.remoteLive = null
    }

    fun fileFor(editor: CEditor): String? = reverse[editor]
    fun editorFor(fileId: String): CEditor? = bindings[fileId]

    private fun attach(editor: CEditor) {
        val c = editor.controller
        c.onStrokeStarted = { page -> onStarted(editor, page) }
        c.onStrokeSample = { page, x, y, p -> onSample(editor, page, x, y, p) }
        c.onStrokeCommitted = { page, stroke -> onCommitted(editor, page, stroke) }
        c.onStrokesErased = { page, strokes -> onErased(editor, page, strokes) }
    }

    // --- local gestures out ---

    private fun headOf(fileId: String): Long? = engine.headVersion(fileId)

    private fun onStarted(editor: CEditor, page: Int) {
        val fileId = reverse[editor] ?: return
        val tool = editor.controller.tool
        if (!tool.isStroke) return
        val cfg = editor.controller.configFor(tool)
        active[editor] = ActiveStroke(
            fileId, page, "l${dedupeCounter++}-${Random.nextInt().toString(16)}",
            tool.id, cfg.baseWidth, hexColor(cfg.rgba),
        )
    }

    private fun onSample(editor: CEditor, page: Int, x: Double, y: Double, p: Double) {
        var a = active[editor] ?: return
        if (a.page != page) {
            // Crossed onto another page mid-stroke: close this run, open the next.
            engine.finishStroke(a.fileId, a.page, a.id, a.tool, a.width, a.color)
            val tool = editor.controller.tool
            val cfg = editor.controller.configFor(tool)
            a = ActiveStroke(a.fileId, page, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", tool.id, cfg.baseWidth, hexColor(cfg.rgba))
            active[editor] = a
        }
        engine.liveSample(a.fileId, page, a.id, a.tool, a.width, a.color, x, y, p)
    }

    private fun onCommitted(editor: CEditor, page: Int, stroke: Stroke) {
        val a = active.remove(editor)
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        if (a != null && a.fileId == fileId) {
            engine.finishStroke(fileId, page, a.id, a.tool, a.width, a.color)
            engine.submitOp(fileId, base, a.id, listOf(SyncOp.AddStroke(page, wireOf(stroke))))
        } else {
            engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.AddStroke(page, wireOf(stroke))))
        }
    }

    private fun onErased(editor: CEditor, page: Int, strokes: List<Stroke>) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val ids = strokes.map { SyncFingerprint.of(it.tool.id, it.config.baseWidth, hexColor(it.config.rgba), wireSamplesOf(it)) }
        if (ids.isEmpty()) return
        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.EraseStrokes(page, ids)))
    }

    // --- SyncEngineHost (remote in) ---

    override fun onBytes(data: ByteArray) {
        val send = host.sync_send ?: return
        memScoped {
            val buf = allocArray<UByteVar>(data.size)
            for (i in data.indices) buf[i] = data[i].toUByte()
            send.invoke(host.ctx, buf, data.size)
        }
    }

    override fun onPairRequest(deviceName: String, fp: String) {
        memScoped {
            host.sync_pair_request?.invoke(host.ctx, deviceName.cstr.ptr, fp.cstr.ptr)
        }
    }

    override fun onPaired(deviceName: String) {
        memScoped {
            host.sync_paired?.invoke(host.ctx, deviceName.cstr.ptr)
        }
    }

    override fun onIndexChanged() {
        host.sync_index_changed?.invoke(host.ctx)
    }

    override fun onFileReady(id: String) {
        memScoped {
            host.sync_file_changed?.invoke(host.ctx, id.cstr.ptr)
        }
    }

    override fun onFileGone(id: String) {
        bindings.remove(id)?.let { reverse.remove(it) }
        memScoped {
            host.sync_file_gone?.invoke(host.ctx, id.cstr.ptr)
        }
    }

    override fun onDeleteResult(ok: Boolean, reason: String?) {
        memScoped {
            host.sync_delete_result?.invoke(host.ctx, if (ok) 1 else 0, reason?.cstr?.ptr)
        }
    }

    override fun onCreateResult(ok: Boolean, id: String?, reason: String?) {
        memScoped {
            host.sync_create_result?.invoke(host.ctx, if (ok) 1 else 0, id?.cstr?.ptr, reason?.cstr?.ptr)
        }
    }

    override fun onError(reason: String) {
        memScoped {
            host.sync_error?.invoke(host.ctx, reason.cstr.ptr)
        }
    }

    override fun onLiveDirty() {
        for (e in bindings.values) e.requestRender()
        host.sync_live_dirty?.invoke(host.ctx)
    }

    override fun applyRemoteStroke(fileId: String, page: Int, stroke: WireStroke) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: run {
            // The host moved on (pages added): refetch the whole file.
            engine.requestOpen(fileId)
            return
        }
        pg.items.add(strokeToNative(stroke))
        doc.dirty = true
        ce.state.invalidatePage(pg)
        ce.requestRender()
    }

    override fun applyRemoteErase(fileId: String, page: Int, ids: List<String>) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        val targets = pg.items.filterIsInstance<Stroke>().filter {
            SyncFingerprint.of(it.tool.id, it.config.baseWidth, hexColor(it.config.rgba), wireSamplesOf(it)) in ids
        }
        if (targets.isEmpty()) return
        pg.items.removeAll(targets)
        doc.dirty = true
        ce.state.invalidatePage(pg)
        ce.requestRender()
    }

    override fun applyResync(fileId: String, version: Long) {
        memScoped {
            host.sync_resync?.invoke(host.ctx, fileId.cstr.ptr, version)
        }
    }

    // --- conversion ---

    private fun wireOf(s: Stroke): WireStroke = WireStroke(
        s.tool.id, s.config.baseWidth, hexColor(s.config.rgba), s.config.pressureEnabled,
        s.samples.map { WireSample(it.x, it.y, it.pressure) },
    )

    private fun wireSamplesOf(s: Stroke): List<WireSample> =
        s.samples.map { WireSample(it.x, it.y, it.pressure) }

    private fun strokeToNative(w: WireStroke): Stroke {
        val color = RemoteLiveStrokes.parseColor(w.color) ?: Rgba(20, 20, 20, 255)
        val cfg = ToolConfig(baseWidth = w.width, rgba = color, pressureEnabled = w.pressure)
        val stroke = Stroke(Tool.fromId(w.tool) ?: Tool.PEN, cfg, emptyList(), 1.0, false, 1.0)
        val n = w.samples.size
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        val ps = DoubleArray(n)
        val ts = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = w.samples[i].x
            ys[i] = w.samples[i].y
            ps[i] = w.samples[i].p
        }
        stroke.setSamples(xs, ys, ps, ts, n)
        return stroke
    }

    companion object {
        private fun hex2(v: Int): String {
            val h = "0123456789abcdef"
            return "${h[(v shr 4) and 0xF]}${h[v and 0xF]}"
        }

        fun hexColor(c: Rgba): String = "#${hex2(c.r)}${hex2(c.g)}${hex2(c.b)}"
    }
}
