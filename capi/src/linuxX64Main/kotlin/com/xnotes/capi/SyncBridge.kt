@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.capi

import com.xnotes.capi.c.xn_host
import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.canvas.RoomEdit
import com.xnotes.canvas.StrokeReplacement
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.platform.File
import com.xnotes.core.platform.asPath
import com.xnotes.core.platform.fileOf
import com.xnotes.core.platform.pathString
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.sync.CompanionEngine
import com.xnotes.sync.SyncEngineHost
import com.xnotes.sync.SyncFileEntry
import com.xnotes.sync.SyncFingerprint
import com.xnotes.sync.SyncOp
import com.xnotes.sync.PageStyleEntry
import com.xnotes.sync.WireImage
import com.xnotes.sync.WireMargins
import com.xnotes.sync.WirePageStyle
import com.xnotes.sync.toPageStyle
import com.xnotes.sync.toPageMargins
import com.xnotes.sync.toWireMarkup
import com.xnotes.sync.toWirePageStyle
import com.xnotes.sync.toWireMargins
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
    val engine = CompanionEngine(this, fileOf(pinsDir), deviceName, ownFp, fileOf(fileOf(pinsDir), "file-cache"))

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
        val style: com.xnotes.sync.WireStrokeStyle? = null,
    )
    private val active = HashMap<CEditor, ActiveStroke>()

    /** The live room drag each editor has going, if its file is shared. */
    private class ActiveRoom(val fileId: String, val page: Int, val id: String)
    private var activeRoom: ActiveRoom? = null

    /** Received image files by sync file id, so unshare/close can drop them. */
    private val imageFiles = HashMap<String, ArrayList<File>>()
    private val imageDir: File = fileOf(fileOf(pinsDir), "sync-images").also {
        // Stale from a previous run: nothing is bound this early, so nothing can reference them.
        runCatching {
            val fs = okio.FileSystem.SYSTEM
            if (fs.exists(it.asPath())) {
                fs.list(it.asPath()).forEach { p -> runCatching { fs.deleteRecursively(p) } }
            } else {
                fs.createDirectories(it.asPath())
            }
        }
    }

    // --- binding ---

    fun bind(editor: CEditor, fileId: String) {
        unbindEditor(editor)
        bindings[fileId] = editor
        reverse[editor] = fileId
        historyBefore[editor] = snapshotOf(editor)
        val overlay = RemoteLiveStrokes(editor.state)
        overlay.showingFile = fileId
        editor.controller.remoteLive = overlay
        engine.liveOverlay = overlay
        attach(editor)
    }

    fun unbindEditor(editor: CEditor) {
        val fileId = reverse.remove(editor) ?: return
        editor.editor.onPageInserted = null
        editor.editor.onPageRemoved = null
        editor.editor.onImageInserted = null
        editor.editor.onMarkupsAdded = null
        editor.editor.onPageStyleChanged = null
        editor.onViewInvertChanged = null
        editor.onNoteLinkTap = null
        editor.onLocalMarkups = null
        editor.controller.onStrokesReplaced = null
        editor.controller.onMarkupsErased = null
        dropImageFiles(fileId)
        bindings.remove(fileId)
        active.remove(editor)
        if (activeRoom?.fileId == fileId) activeRoom = null
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
        c.onStrokesReplaced = { changes -> onStrokesReplaced(editor, changes) }
        c.onMarkupsErased = { page, ids -> onMarkupsRemoved(editor, page, ids) }
        editor.onViewInvertChanged = { onViewInvertChanged(editor) }
        editor.onNoteLinkTap = { targetId -> onNoteLinkTap(editor, targetId) }
        editor.onLocalMarkups = { added, removedIds -> onLocalMarkups(editor, added, removedIds) }
        c.onRoomPreview = { page, sideways, split, resume, shift -> onRoomPreview(editor, page, sideways, split, resume, shift) }
        c.onRoomCommitted = { edit -> onRoomCommitted(editor, edit) }
        c.onRoomPreviewDone = { onRoomPreviewDone(editor) }
        // Explicit local page edits submit matching ops; remote ones never fire these.
        editor.editor.onPageInserted = { index, w, h -> onPageInserted(editor, index, w, h) }
        editor.editor.onPageRemoved = { index -> onPageRemoved(editor, index) }
        editor.editor.onImageInserted = { page, item -> onImageInserted(editor, page, item) }
        editor.editor.onMarkupsAdded = { page, markups -> onLocalMarkupsAdded(editor, page, markups) }
        editor.editor.onPageStyleChanged = { entries, docStyle, docMargins -> onLocalPageStyle(editor, entries, docStyle, docMargins) }
        // Undo/redo converges via the same op paths (no new wire type): snapshot
        // before the step, diff after, and submit stroke/markup/page deltas.
        editor.editor.onHistoryStep = { onHistoryStep(editor) }
    }

    private var historyBefore: HashMap<CEditor, HistorySnapshot> = HashMap()

    private data class HistorySnapshot(
        val pages: List<String>,
        val items: List<List<String>>,
        val markups: List<List<String>>,
    )

    private fun snapshotOf(editor: CEditor): HistorySnapshot {
        val doc = editor.state.document
        return HistorySnapshot(
            pages = doc.pages.map { "${it.width}x${it.height}" },
            items = doc.pages.map { pg ->
                pg.items.filterIsInstance<Stroke>().map {
                    com.xnotes.sync.SyncFingerprint.of(it.tool.id, it.config.baseWidth, hexColor(it.config.rgba), wireSamplesOf(it)) + "|" + it.config.hashCode()
                }
            },
            markups = doc.pages.map { pg -> pg.markups.map { it.id } },
        )
    }

    fun armHistory(editor: CEditor) {
        historyBefore[editor] = snapshotOf(editor)
    }

    private fun onHistoryStep(editor: CEditor) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val before = historyBefore.remove(editor)
        // Arm for the next step (redo chain, or next undo).
        historyBefore[editor] = snapshotOf(editor)
        if (before == null) return
        val doc = editor.state.document
        // Page add/remove: single-index ops (reorder falls back to nothing —
        // page order has no op; a full refetch converges it).
        if (doc.pages.size != before.pages.size) {
            if (doc.pages.size < before.pages.size) {
                for (i in before.pages.indices) {
                    if (i >= doc.pages.size || doc.pages[i].let { "${it.width}x${it.height}" } != before.pages[i]) {
                        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.RemovePage(i)))
                        return
                    }
                }
            } else {
                for (i in doc.pages.indices) {
                    if (i >= before.pages.size || doc.pages[i].let { "${it.width}x${it.height}" } != before.pages[i]) {
                        val pg = doc.pages[i]
                        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.AddPage(i, pg.width, pg.height)))
                        return
                    }
                }
            }
            return
        }
        // Stroke diff (fingerprint+config, catches moves/restyles).
        val ops = ArrayList<SyncOp>()
        for (pi in doc.pages.indices) {
            val b = before.items.getOrNull(pi) ?: emptyList()
            val a = doc.pages[pi].items.filterIsInstance<Stroke>().map {
                com.xnotes.sync.SyncFingerprint.of(it.tool.id, it.config.baseWidth, hexColor(it.config.rgba), wireSamplesOf(it)) + "|" + it.config.hashCode()
            }
            if (b != a) {
                // Coarse but convergent: erase removed fingerprints, add current strokes.
                // Fingerprints alone identify removals; adds carry full wire strokes.
                val bSet = b.toSet()
                val aSet = a.toSet()
                val removed = b.filter { it.substringBefore("|") !in aSet.map { x -> x.substringBefore("|") } }
                // Map fingerprints back to wire strokes for adds.
                val afterStrokes = doc.pages[pi].items.filterIsInstance<Stroke>()
                val addedWires = ArrayList<SyncOp>()
                // Simplified: send full page strokes as adds for new fingerprints.
                // Erase by fingerprint, add by wire (upsert-safe on the host).
                if (removed.isNotEmpty()) {
                    ops += SyncOp.EraseStrokes(pi, removed.map { it.substringBefore("|") })
                }
                val newKeys = a.filter { it !in bSet }
                if (newKeys.isNotEmpty()) {
                    val byKey = HashMap<String, Stroke>()
                    for (st in afterStrokes) {
                        val k = com.xnotes.sync.SyncFingerprint.of(st.tool.id, st.config.baseWidth, hexColor(st.config.rgba), wireSamplesOf(st)) + "|" + st.config.hashCode()
                        if (k in newKeys) byKey.putIfAbsent(k, st)
                    }
                    for (k in newKeys) {
                        byKey[k]?.let { ops += SyncOp.AddStroke(pi, wireOf(it)) }
                    }
                }
            }
            val bm = before.markups.getOrNull(pi) ?: emptyList()
            val am = doc.pages[pi].markups.map { it.id }
            if (bm != am) {
                val removedIds = bm.filter { it !in am }
                if (removedIds.isNotEmpty()) ops += SyncOp.RemoveMarkup(pi, removedIds)
                val added = doc.pages[pi].markups.filter { it.id !in bm }
                for (m in added) ops += SyncOp.AddMarkup(pi, m.toWireMarkup())
            }
        }
        if (ops.isNotEmpty()) {
            engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", ops)
        }
    }

    // --- local gestures out ---

    private fun headOf(fileId: String): Long? = engine.headVersion(fileId)

    private fun styleOf(tool: Tool, cfg: ToolConfig): com.xnotes.sync.WireStrokeStyle {
        return com.xnotes.sync.WireStrokeStyle(
            pmin = cfg.pressureMinFactor,
            dir = cfg.directionStrength,
            speed = cfg.speedStrength,
            taper = cfg.taperEnabled.takeIf { it },
            taperMin = cfg.taperMinFactor,
            neon = cfg.neon.takeIf { it },
            neonS = cfg.neonStrength,
            dashL = cfg.dashLength,
            dashG = cfg.dashGap,
            hlAlpha = cfg.highlighterAlpha,
            hlInv = cfg.highlighterInverse.takeIf { it },
            pencilP = cfg.pencilPressure,
            pencilD = cfg.pencilDensity,
            pencilG = cfg.pencilGrainSize,
            pencilS = cfg.pencilShadeResponse,
        )
    }

    private fun onStarted(editor: CEditor, page: Int) {
        val fileId = reverse[editor] ?: return
        val tool = editor.controller.tool
        if (!tool.isStroke) return
        val cfg = editor.controller.resolvedStrokeConfig(tool)
        active[editor] = ActiveStroke(
            fileId, page, "l${dedupeCounter++}-${Random.nextInt().toString(16)}",
            tool.id, cfg.baseWidth, hexColor(cfg.rgba), styleOf(tool, cfg),
        )
    }

    private fun onSample(editor: CEditor, page: Int, x: Double, y: Double, p: Double) {
        var a = active[editor] ?: return
        if (a.page != page) {
            // Crossed onto another page mid-stroke: close this run, open the next.
            engine.finishStroke(a.fileId, a.page, a.id, a.tool, a.width, a.color, a.style)
            val tool = editor.controller.tool
            val cfg = editor.controller.resolvedStrokeConfig(tool)
            a = ActiveStroke(a.fileId, page, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", tool.id, cfg.baseWidth, hexColor(cfg.rgba), styleOf(tool, cfg))
            active[editor] = a
        }
        engine.liveSample(a.fileId, page, a.id, a.tool, a.width, a.color, x, y, p, a.style)
    }

    private fun onCommitted(editor: CEditor, page: Int, stroke: Stroke) {
        val a = active.remove(editor)
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        if (a != null && a.fileId == fileId) {
            engine.finishStroke(fileId, page, a.id, a.tool, a.width, a.color, a.style)
            engine.submitOp(fileId, base, a.id, listOf(SyncOp.AddStroke(page, wireOf(stroke))))
        } else {
            engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.AddStroke(page, wireOf(stroke))))
        }
        historyBefore[editor] = snapshotOf(editor)
    }

    private fun onErased(editor: CEditor, page: Int, strokes: List<Stroke>) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val ids = strokes.map { SyncFingerprint.of(it.tool.id, it.config.baseWidth, hexColor(it.config.rgba), wireSamplesOf(it)) }
        if (ids.isEmpty()) return
        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.EraseStrokes(page, ids)))
    }

    private fun onStrokesReplaced(editor: CEditor, changes: List<StrokeReplacement>) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val ops = ArrayList<SyncOp>()
        for (c in changes) {
            if (c.removed.isNotEmpty()) {
                ops += SyncOp.EraseStrokes(
                    c.erasePage,
                    c.removed.map { SyncFingerprint.of(it.tool.id, it.config.baseWidth, hexColor(it.config.rgba), wireSamplesOf(it)) },
                )
            }
            for (s in c.added) ops += SyncOp.AddStroke(c.addPage, wireOf(s))
        }
        if (ops.isEmpty()) return
        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", ops)
    }

    private fun onMarkupsRemoved(editor: CEditor, page: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.RemoveMarkup(page, ids)))
    }

    private fun onNoteLinkTap(editor: CEditor, targetId: String) {
        if (targetId.isEmpty()) return
        if (engine.files().any { it.id == targetId }) {
            engine.requestOpen(targetId)
        } else {
            engine.requestLinkAccess(targetId)
        }
    }

    private fun onViewInvertChanged(editor: CEditor) {        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val ce = bindings[fileId] ?: return
        // Synthesize a doc-level snapshot carrying the toggle; the host
        // honors explicit invert flags (nulls keep its own).
        val docStyle = ce.state.document.style.toWirePageStyle()
            .copy(invertColors = ce.viewInvert, invertImages = ce.viewInvertImages)
        engine.submitOp(
            fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}",
            listOf(SyncOp.PageStyle(emptyList(), docStyle, null)),
        )
    }

    private fun onLocalMarkups(editor: CEditor, added: List<Pair<Int, TextMarkup>>, removedIds: List<Pair<Int, String>>) {
        if (added.isEmpty() && removedIds.isEmpty()) return
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val ops = ArrayList<SyncOp>()
        // Removes first: same-id recolor/retype converges (add upserts).
        for ((page, ids) in removedIds.groupBy({ it.first }, { it.second })) {
            ops += SyncOp.RemoveMarkup(page, ids)
        }
        for ((page, m) in added) ops += SyncOp.AddMarkup(page, m.toWireMarkup())
        if (ops.isEmpty()) return
        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", ops)
    }

    private fun onLocalMarkupsAdded(editor: CEditor, page: Int, markups: List<TextMarkup>) {
        onLocalMarkups(editor, markups.map { page to it }, emptyList())
    }

    private fun onLocalPageStyle(
        editor: CEditor,
        entries: List<Triple<Int, PageStyle?, PageMargins?>>,
        docStyle: PageStyle?,
        docMargins: PageMargins?,
    ) {
        if (entries.isEmpty() && docStyle == null && docMargins == null) return
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        engine.submitOp(
            fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}",
            listOf(
                SyncOp.PageStyle(
                    entries.map { (index, style, margins) ->
                        PageStyleEntry(index, style?.toWirePageStyle(), margins?.toWireMargins())
                    },
                    docStyle?.toWirePageStyle(),
                    docMargins?.toWireMargins(),
                ),
            ),
        )
    }

    private fun onPageInserted(editor: CEditor, index: Int, w: Double, h: Double) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.AddPage(index, w, h)))
    }

    private fun onPageRemoved(editor: CEditor, index: Int) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.RemovePage(index)))
    }

    private fun onRoomPreview(editor: CEditor, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double) {
        val fileId = reverse[editor] ?: return
        var r = activeRoom
        if (r == null || r.fileId != fileId || r.page != page) {
            r = ActiveRoom(fileId, page, "r${dedupeCounter++}-${Random.nextInt().toString(16)}")
            activeRoom = r
        }
        engine.previewRoom(fileId, page, r.id, sideways, split, resume, shift)
    }

    private fun onRoomCommitted(editor: CEditor, edit: RoomEdit) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val r = activeRoom?.takeIf { it.fileId == fileId && it.page == edit.page }
        activeRoom = null
        val id = r?.id ?: "r${dedupeCounter++}-${Random.nextInt().toString(16)}"
        val (split, resume, shift) = if (edit.open) Triple(edit.a, edit.a, edit.b - edit.a) else Triple(edit.a, edit.b, edit.a - edit.b)
        engine.finishRoom(fileId, edit.page, id, edit.sideways, split, resume, shift)
        val op = if (edit.open) SyncOp.OpenRoom(edit.page, edit.sideways, edit.a, edit.b - edit.a)
        else SyncOp.CloseRoom(edit.page, edit.sideways, edit.a, edit.b)
        engine.submitOp(fileId, base, id, listOf(op))
    }

    private fun onRoomPreviewDone(editor: CEditor) {
        val r = activeRoom ?: return
        if (reverse[editor] != r.fileId) return
        activeRoom = null
        engine.finishRoom(r.fileId, r.page, r.id, false, 0.0, 0.0, 0.0)
    }

    private fun onImageInserted(editor: CEditor, page: Int, item: ImageItem) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val bytes = runCatching {
            okio.FileSystem.SYSTEM.read(item.image.file.asPath()) { readByteArray() }
        }.getOrNull() ?: return
        val imageId = "i${dedupeCounter++}-${Random.nextInt().toString(16)}"
        engine.sendImageData(fileId, imageId, bytes)
        val part = item.part?.let { listOf(it.x, it.y, it.right, it.bottom) }
        engine.submitOp(
            fileId, base, imageId,
            listOf(
                SyncOp.AddImage(
                    page,
                    WireImage(
                        imageId, item.image.width, item.image.height,
                        item.rect.x, item.rect.y, item.rect.w, item.rect.h,
                        item.orientation, item.angle, item.flip.x, item.flip.y, part,
                    ),
                ),
            ),
        )
    }

    /** Drop received image files of [fileId] (unshare/close); the model owns applied ones. */
    private fun dropImageFiles(fileId: String) {
        val files = imageFiles.remove(fileId) ?: return
        val fs = okio.FileSystem.SYSTEM
        for (f in files) runCatching { fs.delete(f.asPath()) }
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
        bindings.remove(id)?.let {
            reverse.remove(it)
            it.editor.onPageInserted = null
            it.editor.onPageRemoved = null
            it.editor.onImageInserted = null
            activeRoom?.takeIf { r -> r.fileId == id }?.let { activeRoom = null }
        }
        dropImageFiles(id)
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
        if (bindings.isEmpty()) {
            // Live frames for a file that is not open here: notable (the far
            // side is drawing into the void), but routine when idle, so whisper.
            whisper("live for an unbound file")
        }
        for (e in bindings.values) e.requestOverlayRender()
        host.sync_live_dirty?.invoke(host.ctx)
    }

    /** Rate-limited stdout note for silent-drop states (release builds keep qInfo). */
    private var lastWhisperMs = 0L
    private fun whisper(what: String) {
        val now = com.xnotes.core.platform.monotonicNanos() / 1_000_000L
        if (now - lastWhisperMs < 5000) return
        lastWhisperMs = now
        println("sync: ${what} (no file bound; open a shared file to see live strokes)")
    }

    override fun applyRemoteStroke(fileId: String, page: Int, stroke: WireStroke) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: run {
            // The host moved on (pages added): refetch the whole file.
            engine.requestOpen(fileId)
            return
        }
        val native = strokeToNative(stroke)
        pg.items.add(native)
        doc.dirty = true
        // Bake into the warm cache in place: dropping it blanks the page for a
        // frame (the commit blink). Falls back to a rebuild when uncached.
        ce.state.appendToCache(pg, native)
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
        val dirty = targets.map { it.paintBounds() }.reduce { a, b -> a.union(b) }.outset(2.0)
        pg.items.removeAll(targets)
        doc.dirty = true
        // Patch just the erased area in the warm cache, like the local eraser.
        if (!ce.state.repairRegion(pg, dirty)) ce.state.invalidatePage(pg)
        ce.requestRender()
    }

    override fun applyRemoteMarkups(fileId: String, page: Int, added: List<TextMarkup>, removedIds: List<String>) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        if (removedIds.isNotEmpty()) {
            pg.markups = pg.markups.filter { it.id !in removedIds }
        }
        val fresh = added.filter { a -> pg.markups.none { it.id == a.id } }
        if (removedIds.isEmpty() && fresh.isEmpty()) return
        if (fresh.isNotEmpty()) pg.markups = pg.markups + fresh
        doc.dirty = true
        ce.state.rebakeBackground(pg, fresh)
        ce.requestRender()
    }

    override fun applyRemotePageStyle(fileId: String, entries: List<PageStyleEntry>, docStyle: WirePageStyle?, docMargins: WireMargins?) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        // Linux has no per-page invert model: the note-level flags drive the
        // view override the frame inverter reads; the rest applies as usual.
        val invert = docStyle?.invertColors ?: entries.firstNotNullOfOrNull { it.style?.invertColors }
        if (invert != null && ce.viewInvert != invert) {
            ce.viewInvert = invert
            ce.viewInvertImages = docStyle?.invertImages
                ?: entries.firstNotNullOfOrNull { it.style?.invertImages }
            ce.requestRender()
        }
        if (docStyle != null) doc.style = docStyle.toPageStyle()
        if (docMargins != null) doc.margins = docMargins.toPageMargins()
        var relayout = docMargins != null
        for (e in entries) {
            val pg = doc.pages.getOrNull(e.index) ?: continue
            e.style?.let { pg.style = it.toPageStyle() }
            e.margins?.let { pg.margins = it.toPageMargins(); relayout = true }
            ce.state.invalidateBackground(pg)
        }
        doc.dirty = true
        if (relayout) {
            ce.state.invalidatePageGeometry()
            ce.state.relayout()
        }
        ce.requestRender()
    }

    override fun applyRemotePageAdd(fileId: String, index: Int, width: Double, height: Double) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val ref = doc.pages.getOrNull(index.coerceIn(0, doc.pages.size))
            ?: doc.pages.lastOrNull()
        val w = if (width > 0) width else ref?.width ?: 595.0
        val h = if (height > 0) height else ref?.height ?: 842.0
        val at = index.coerceIn(0, doc.pages.size)
        ce.controller.clearSelection()
        doc.pages.add(at, Page(w, h))
        doc.dirty = true
        ce.state.relayout()
        ce.state.clampScroll()
        host.content_changed?.invoke(host.ctx)
        ce.requestRender()
    }

    override fun applyRemotePageRemove(fileId: String, index: Int) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        if (doc.pages.size <= 1 || index !in doc.pages.indices) return
        val page = doc.pages.removeAt(index)
        ce.controller.clearSelection()
        doc.dirty = true
        ce.state.invalidatePage(page)
        ce.state.relayout()
        ce.state.clampScroll()
        host.content_changed?.invoke(host.ctx)
        ce.requestRender()
    }

    override fun applyRemoteRoomEdit(fileId: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double) {
        val ce = bindings[fileId] ?: return
        if (!ce.controller.applyRemoteRoomEdit(page, sideways, open, a, b)) {
            // Page or room gone (concurrent edit): refetch the whole file.
            engine.requestOpen(fileId)
        } else {
            host.content_changed?.invoke(host.ctx)
        }
    }

    override fun applyRemoteImage(fileId: String, page: Int, image: WireImage, bytes: ByteArray) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: run {
            // The host moved on: refetch the whole file (it carries the image in its assets).
            engine.requestOpen(fileId)
            return
        }
        if (image.w <= 0 || image.h <= 0) return
        val safeId = image.imageId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "img" }
        val safeFile = fileId.filter { it.isLetterOrDigit() }.take(24) + fileId.hashCode().toString(16)
        val dest = fileOf(fileOf(imageDir, safeFile), "$safeId.bin")
        runCatching {
            val fs = okio.FileSystem.SYSTEM
            fs.createDirectories(fileOf(imageDir, safeFile).asPath())
            fs.write(dest.asPath()) { write(bytes) }
        }.getOrNull() ?: return
        imageFiles.getOrPut(fileId) { ArrayList() } += dest
        val part = image.part?.takeIf { it.size == 4 }?.let { Rect(it[0], it[1], it[2] - it[0], it[3] - it[1]) }
        val native = ImageItem(
            ImageData(dest, image.width, image.height),
            Rect(image.x, image.y, image.w, image.h),
            orientation = image.orientation,
            angle = image.angle,
            flip = Flip.of(image.flipX, image.flipY),
            part = part,
        )
        pg.items.add(native)
        doc.dirty = true
        ce.state.appendToCache(pg, native)
        ce.requestRender()
    }

    override fun previewRemoteRoom(fileId: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double) {
        val ce = bindings[fileId] ?: return
        if (ce.controller.roomGestureActive) return // a local drag owns the preview
        ce.controller.previewRemoteRoom(page, sideways, split, resume, shift)
    }

    override fun clearRemoteRoomPreview(fileId: String) {
        bindings[fileId]?.controller?.clearRemoteRoom()
    }

    override fun onLinkResult(id: String, ok: Boolean, reason: String?) {
        if (!ok) {
            // The host shows the denial; the tap already explained itself.
            return
        }
        // Granted: the index now covers the target, so fetch and open it.
        engine.requestOpen(id)
    }

    override fun applyResync(fileId: String, version: Long) {
        memScoped {
            host.sync_resync?.invoke(host.ctx, fileId.cstr.ptr, version)
        }
    }

    // --- conversion ---

    private fun wireOf(s: Stroke): WireStroke {
        val c = s.config
        return WireStroke(
            s.tool.id, c.baseWidth, hexColor(c.rgba), c.pressureEnabled,
            s.samples.map { WireSample(it.x, it.y, it.pressure) },
            com.xnotes.sync.WireStrokeStyle(
                pmin = c.pressureMinFactor,
                dir = c.directionStrength,
                speed = c.speedStrength,
                taper = c.taperEnabled.takeIf { it },
                taperMin = c.taperMinFactor,
                neon = c.neon.takeIf { it },
                neonS = c.neonStrength,
                dashL = c.dashLength,
                dashG = c.dashGap,
                hlAlpha = c.highlighterAlpha,
                hlInv = c.highlighterInverse.takeIf { it },
                pencilP = c.pencilPressure,
                pencilD = c.pencilDensity,
                pencilG = c.pencilGrainSize,
                pencilS = c.pencilShadeResponse,
            ),
        )
    }

    private fun wireSamplesOf(s: Stroke): List<WireSample> =
        s.samples.map { WireSample(it.x, it.y, it.pressure) }

    private fun strokeToNative(w: WireStroke): Stroke {
        val color = RemoteLiveStrokes.parseColor(w.color) ?: Rgba(20, 20, 20, 255)
        val tool = Tool.fromId(w.tool) ?: Tool.PEN
        val base = ToolDefaults.configFor(tool).copy(baseWidth = w.width, rgba = color, pressureEnabled = w.pressure)
        val st = w.style
        val cfg = if (st == null) base else base.copy(
            pressureMinFactor = st.pmin ?: base.pressureMinFactor,
            directionStrength = st.dir ?: base.directionStrength,
            speedStrength = st.speed ?: base.speedStrength,
            taperEnabled = st.taper ?: base.taperEnabled,
            taperMinFactor = st.taperMin ?: base.taperMinFactor,
            neon = st.neon ?: base.neon,
            neonStrength = st.neonS ?: base.neonStrength,
            dashLength = st.dashL ?: base.dashLength,
            dashGap = st.dashG ?: base.dashGap,
            highlighterAlpha = st.hlAlpha ?: base.highlighterAlpha,
            highlighterInverse = st.hlInv ?: base.highlighterInverse,
        )
        val stroke = Stroke(tool, cfg, emptyList(), 1.0, false, 1.0)
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
