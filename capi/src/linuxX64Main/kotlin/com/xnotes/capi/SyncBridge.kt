@file:OptIn(ExperimentalForeignApi::class)

package com.xnotes.capi

import com.xnotes.capi.c.xn_host
import com.xnotes.canvas.RemoteLiveStrokes
import com.xnotes.canvas.RoomEdit
import com.xnotes.canvas.StrokeReplacement
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.HAlign
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.sync.WireShape
import com.xnotes.sync.bytesOfBase64
import com.xnotes.sync.toWireShape
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TextItem
import com.xnotes.sync.WireAudio
import com.xnotes.sync.WireTable
import com.xnotes.sync.WireText
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.platform.File
import com.xnotes.core.platform.asFile
import com.xnotes.core.platform.asPath
import com.xnotes.core.platform.fileOf
import com.xnotes.core.platform.pathString
import com.xnotes.core.platform.asInputStream
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
    private val audioFiles = HashMap<String, ArrayList<File>>()

    /**
     * The last (id, version) handed to Qt as a temp note file. Reopening the same bytes
     * rewrote a full temp copy every time (a 100 MB PDF note paid a full write + read per
     * open); the path is reused while the version — and the file behind it — is unchanged.
     */
    private var lastNotePathId: String? = null
    private var lastNotePathVersion = -1L
    private var lastNotePath: String? = null

    /** Temp path holding the cached bytes of [id] at [version], reused when still current. */
    fun cachedNotePath(id: String, version: Long, bytes: ByteArray): String {
        lastNotePath
            ?.takeIf { lastNotePathId == id && lastNotePathVersion == version }
            ?.let { path ->
                if (runCatching { okio.FileSystem.SYSTEM.exists(fileOf(path).asPath()) }.getOrDefault(false)) return path
            }
        lastNotePath?.let { old ->
            runCatching { okio.FileSystem.SYSTEM.delete(fileOf(old).asPath()) }
        }
        val tmp = com.xnotes.core.platform.createTempFile("sync", ".xnote", okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY.asFile())
        okio.FileSystem.SYSTEM.write(tmp.asPath()) { write(bytes) }
        lastNotePathId = id
        lastNotePathVersion = version
        lastNotePath = tmp.pathString
        return tmp.pathString
    }

    /** Temp skeleton note files handed to Qt, at most one pending; replaced on every head. */
    private var lastHeadPath: String? = null

    /**
     * Files opened from a skeleton head, by sync id: their images/audio/pdf are still
     * streaming in. A full fetch completes them in place (no reopen).
     */
    private val skeletonVersions = HashMap<String, Long>()

    /**
     * The head's entries as an openable skeleton bundle, or null when no head is
     * waiting for [id]. The bytes go to a temp file the Qt side opens with
     * placeholders; every item slot stays stable so the full fetch fills in place.
     */
    fun cachedHeadPath(id: String): String? {
        val version = engine.fileVersion(id) ?: return null
        val head = engine.takeFileHead(id, version) ?: return null
        val packages = HashMap<String, ByteArray>()
        for ((k, v) in head.packages) {
            runCatching { packages[k] = com.xnotes.sync.bytesOfBase64(v) ?: error("bad package") }
        }
        val codec = codecFor(null)
        val bytes = codec.skeletonBytes(
            head.manifest.encodeToByteArray(),
            head.flow?.encodeToByteArray(),
            head.markups?.encodeToByteArray(),
            head.templates,
            packages,
        )
        lastHeadPath?.let { old ->
            runCatching { okio.FileSystem.SYSTEM.delete(fileOf(old).asPath()) }
        }
        val tmp = com.xnotes.core.platform.createTempFile("synch", ".xnote", okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY.asFile())
        okio.FileSystem.SYSTEM.write(tmp.asPath()) { write(bytes) }
        lastHeadPath = tmp.pathString
        skeletonVersions[id] = version
        return tmp.pathString
    }

    /**
     * Fill a skeleton-open document with its full bytes: pdf, images, audio and
     * missing packages swap in; items, history and the view survive. True when
     * a skeleton was completed (the caller repaints and reloads the outline).
     */
    fun transplantFull(id: String, workDir: File?): Boolean {
        val ce = bindings[id] ?: return false
        if (!skeletonVersions.containsKey(id)) return false
        val bytes = engine.fileBytes(id) ?: return false
        val dir = workDir ?: return false
        val full = try {
            codecFor(null).read(okio.Buffer().write(bytes).asInputStream(), dir, dir)
        } catch (_: Exception) {
            return false
        }
        codecFor(null).absorbAssets(ce.state.document, full)
        skeletonVersions.remove(id)
        ce.state.invalidateAllCaches()
        ce.requestRender()
        return true
    }
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
        engine.setLiveOverlay(fileId, overlay)
        engine.noteOpened(fileId, engine.fileVersion(fileId) ?: -1L)
        attach(editor)
    }

    fun unbindEditor(editor: CEditor) {
        val fileId = reverse.remove(editor) ?: return
        editor.editor.history.onPush = null
        editor.editor.onHistoryStep = null
        historyBefore.remove(editor)
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
        dropAudioFiles(fileId)
        bindings.remove(fileId)
        active.remove(editor)
        if (activeRoom?.fileId == fileId) activeRoom = null
        engine.noteClosed(fileId)
        engine.clearLiveOverlay(fileId)
        skeletonVersions.remove(fileId)
        if (editor.controller.remoteLive?.showingFile == fileId) editor.controller.remoteLive = null
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
        editor.editor.history.onPush = { cmd -> onHistoryPushed(editor, cmd) }
        editor.editor.onPageInserted = { index, w, h -> onPageInserted(editor, index, w, h) }
        editor.editor.onPageRemoved = { index -> onPageRemoved(editor, index) }
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
        val content: List<List<String>>,
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
            content = doc.pages.map { pg -> pg.items.mapNotNull { contentKey(it) } },
        )
    }

    /** Stable snapshot string for a non-stroke item: uid + full wire form. */
    private fun contentKey(item: CanvasItem): String? = when (item) {
        is TextItem -> "t|" + item.uid + "|" + wireTextOf(item).toString()
        is TableItem -> "b|" + item.uid + "|" + wireTableOf(item).toString()
        is ImageItem -> if (item.uid.isEmpty()) null else "i|" + item.uid + "|" +
            listOf(item.rect, item.orientation, item.angle, item.flip, item.part).joinToString("|")
        is AudioItem -> if (item.uid.isEmpty()) null else "a|" + item.uid + "|" +
            listOf(item.rect, item.name, item.rid, item.audio.durationMs, item.audio.ext).joinToString("|")
        is ShapeItem -> if (item.uid.isEmpty()) null else "s|" + item.uid + "|" + item.toWireShape().toString()
        else -> null
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
                        if (k in newKeys && !byKey.containsKey(k)) byKey[k] = st
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
            // Content items (text/table/image/audio): uid-keyed diff; edits and
            // moves upsert the current snapshot, deletes remove by uid.
            val bc = before.content.getOrNull(pi) ?: emptyList()
            val ac = doc.pages[pi].items.mapNotNull { contentKey(it) }
            if (bc != ac) {
                val bUids = bc.map { it.substringAfter("|").substringBefore("|") }.toSet()
                val aUids = ac.map { it.substringAfter("|").substringBefore("|") }.toSet()
                for (key in bc) {
                    val uid = key.substringAfter("|").substringBefore("|")
                    if (uid !in aUids) {
                        when (key[0]) {
                            't' -> ops += SyncOp.RemoveText(pi, listOf(uid))
                            'b' -> ops += SyncOp.RemoveTable(pi, listOf(uid))
                            'i' -> ops += SyncOp.RemoveImage(pi, listOf(uid))
                            'a' -> ops += SyncOp.RemoveAudio(pi, listOf(uid))
                            's' -> ops += SyncOp.RemoveShape(pi, listOf(uid))
                        }
                    }
                }
                val bMap = bc.associateBy { it.substringAfter("|").substringBefore("|") }
                for (key in ac) {
                    val uid = key.substringAfter("|").substringBefore("|")
                    if (bMap[uid] != key) {
                        val item = doc.pages[pi].items.firstOrNull {
                            (it as? TextItem)?.uid == uid || (it as? TableItem)?.uid == uid ||
                                (it as? ImageItem)?.uid == uid || (it as? AudioItem)?.uid == uid ||
                                (it as? ShapeItem)?.uid == uid
                        } ?: continue
                        when (item) {
                            is TextItem -> ops += SyncOp.AddText(pi, wireTextOf(item))
                            is TableItem -> ops += SyncOp.AddTable(pi, wireTableOf(item))
                            is ShapeItem -> ops += SyncOp.AddShape(pi, item.toWireShape())
                            is ImageItem -> {
                                val (imageId, bytes) = readImageBytes(item) ?: continue
                                engine.sendImageData(fileId, imageId, bytes)
                                ops += SyncOp.AddImage(
                                    pi,
                                    WireImage(
                                        imageId, item.image.width, item.image.height,
                                        item.rect.x, item.rect.y, item.rect.w, item.rect.h,
                                        item.orientation, item.angle, item.flip.x, item.flip.y,
                                        item.part?.let { listOf(it.x, it.y, it.right, it.bottom) },
                                        uid = item.uid,
                                    ),
                                )
                            }
                            is AudioItem -> {
                                val bytes = readAudioBytes(item) ?: continue
                                val audioId = "aud-" + item.uid
                                engine.sendAudioData(fileId, audioId, bytes)
                                engine.sendAudioPeaks(fileId, audioId, item.audio.peaks)
                                ops += SyncOp.AddAudio(pi, wireAudioOf(item, pi, audioId))
                            }
                            is ShapeItem -> ops += SyncOp.AddShape(pi, item.toWireShape())
                            else -> Unit
                        }
                    }
                }
            }
        }
        if (ops.isNotEmpty()) {
            engine.submitOp(fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", ops)
        }
    }

    // --- local gestures out ---

    private fun headOf(fileId: String): Long? = engine.openVersion(fileId) ?: engine.headVersion(fileId)

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
            submit(editor, fileId, base, a.id, listOf(SyncOp.AddStroke(page, wireOf(stroke))))
        } else {
            submit(editor, fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.AddStroke(page, wireOf(stroke))))
        }
        historyBefore[editor] = snapshotOf(editor)
    }

    private fun onErased(editor: CEditor, page: Int, strokes: List<Stroke>) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        val ids = strokes.map { SyncFingerprint.of(it.tool.id, it.config.baseWidth, hexColor(it.config.rgba), wireSamplesOf(it)) }
        if (ids.isEmpty()) return
        submit(editor, fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.EraseStrokes(page, ids)))
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
        submit(editor, fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", ops)
    }

    private fun onMarkupsRemoved(editor: CEditor, page: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        submit(editor, fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.RemoveMarkup(page, ids)))
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
        submit(editor, 
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
        submit(editor, fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", ops)
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
        submit(editor, 
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
        submit(editor, fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.AddPage(index, w, h)))
    }

    private fun onPageRemoved(editor: CEditor, index: Int) {
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        submit(editor, fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", listOf(SyncOp.RemovePage(index)))
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
        submit(editor, fileId, base, id, listOf(op))
    }

    private fun onRoomPreviewDone(editor: CEditor) {
        val r = activeRoom ?: return
        if (reverse[editor] != r.fileId) return
        activeRoom = null
        engine.finishRoom(r.fileId, r.page, r.id, false, 0.0, 0.0, 0.0)
    }

    private fun wireTextOf(t: TextItem): WireText = WireText(
        uid = t.uid,
        x = t.pos.x, y = t.pos.y, width = t.width, height = t.height,
        text = t.text, color = hexColor(t.rgba), pointSize = t.pointSize,
        face = t.face.id, bold = t.bold, italic = t.italic,
        underline = t.underline, strike = t.strike,
        align = t.align.name.lowercase(), math = t.math, link = t.link,
    )

    private fun wireTableOf(t: TableItem): WireTable = WireTable(
        uid = t.uid, x = t.rect.x, y = t.rect.y, w = t.rect.w, h = t.rect.h,
        cols = t.colFractions.toList(), rows = t.rowFractions.toList(),
        strokeColor = hexColor(t.strokeRgba), strokeWidth = t.strokeWidth,
        cells = t.cellGrid(), link = t.link,
    )

    private fun wireAudioOf(a: AudioItem, page: Int, audioId: String): WireAudio = WireAudio(
        audioId = audioId, uid = a.uid, page = page,
        x = a.rect.x, y = a.rect.y, w = a.rect.w, h = a.rect.h,
        name = a.name, rid = a.rid, ext = a.audio.ext, durationMs = a.audio.durationMs,
    )

    /** Submit [ops] and re-arm the undo/redo snapshot: every local commit path funnels here. */
    private fun submit(editor: CEditor, fileId: String, base: Long, dedupe: String, ops: List<SyncOp>) {
        engine.submitOp(fileId, base, dedupe, ops)
        historyBefore[editor] = snapshotOf(editor)
    }

    private fun submitContentOps(editor: CEditor, ops: List<SyncOp>) {
        if (ops.isEmpty()) return
        val fileId = reverse[editor] ?: return
        val base = headOf(fileId) ?: return
        submit(editor, fileId, base, "l${dedupeCounter++}-${Random.nextInt().toString(16)}", ops)
    }

    /**
     * Non-stroke local edits (text/table/image/audio create, move, restyle,
     * delete) have no dedicated controller hook; translate the history
     * command instead. Strokes are skipped (dedicated hooks own them).
     * Erases become removes by uid; everything else upserts the current
     * snapshot (same uid replaces in place on every peer).
     */
    private fun onHistoryPushed(editor: CEditor, cmd: com.xnotes.core.history.Command) {
        val fileId = reverse[editor] ?: return
        val doc = editor.state.document
        val touched = try {
            cmd.touched { item ->
                doc.pages.firstOrNull { p -> p.items.any { it === item } }
            }
        } catch (_: Exception) { null } ?: return
        // Items a dedicated hook just fanned out are skipped exactly once here
        // (their later moves/deletes arrive fresh).
        val items = touched.mapNotNull { (page, item) ->
            if (item is Stroke) null
            else {
                val pi = doc.pages.indexOf(page)
                if (pi < 0) null else pi to item
            }
        }
        if (items.isEmpty()) return
        val isErase = cmd is com.xnotes.core.history.EraseItems
        val ops = ArrayList<SyncOp>()
        for ((page, item) in items) {
            when (item) {
                is TextItem -> if (isErase) {
                    if (item.uid.isNotEmpty()) ops += SyncOp.RemoveText(page, listOf(item.uid))
                } else {
                    if (item.uid.isEmpty()) item.uid = newUid()
                    ops += SyncOp.AddText(page, wireTextOf(item))
                }
                is TableItem -> if (isErase) {
                    if (item.uid.isNotEmpty()) ops += SyncOp.RemoveTable(page, listOf(item.uid))
                } else {
                    if (item.uid.isEmpty()) item.uid = newUid()
                    ops += SyncOp.AddTable(page, wireTableOf(item))
                }
                is ImageItem -> {
                    val key = item.uid.ifEmpty { null } ?: continue
                    if (isErase) ops += SyncOp.RemoveImage(page, listOf(key))
                    else {
                        val (audioId, bytes) = readImageBytes(item) ?: continue
                        engine.sendImageData(fileId, audioId, bytes)
                        ops += SyncOp.AddImage(
                            page,
                            WireImage(
                                audioId, item.image.width, item.image.height,
                                item.rect.x, item.rect.y, item.rect.w, item.rect.h,
                                item.orientation, item.angle, item.flip.x, item.flip.y,
                                item.part?.let { listOf(it.x, it.y, it.right, it.bottom) },
                                uid = key,
                            ),
                        )
                    }
                }
                is AudioItem -> {
                    val key = item.uid.ifEmpty { null } ?: continue
                    if (isErase) ops += SyncOp.RemoveAudio(page, listOf(key))
                    else {
                        val bytes = readAudioBytes(item) ?: continue
                        val audioId = "a${dedupeCounter++}-${Random.nextInt().toString(16)}"
                        engine.sendAudioData(fileId, audioId, bytes)
                        engine.sendAudioPeaks(fileId, audioId, item.audio.peaks)
                        ops += SyncOp.AddAudio(page, wireAudioOf(item, page, audioId).copy(uid = key))
                    }
                }
                else -> Unit
            }
        }
        // Shapes have no dedicated hook on this side: every local shape commit funnels here.
        for ((page, item) in items) {
            if (item !is ShapeItem) continue
            if (isErase) {
                if (item.uid.isNotEmpty()) ops += SyncOp.RemoveShape(page, listOf(item.uid))
            } else {
                if (item.uid.isEmpty()) item.uid = newUid()
                ops += SyncOp.AddShape(page, item.toWireShape())
            }
        }
        submitContentOps(editor, ops)
    }

    private fun readImageBytes(item: ImageItem): Pair<String, ByteArray>? {
        if (item.uid.isEmpty()) return null
        val bytes = runCatching {
            okio.FileSystem.SYSTEM.read(item.image.file.asPath()) { readByteArray() }
        }.getOrNull() ?: return null
        return "img-" + item.uid to bytes
    }

    private fun readAudioBytes(item: AudioItem): ByteArray? {
        if (item.uid.isEmpty()) return null
        return runCatching {
            okio.FileSystem.SYSTEM.read(item.audio.file.asPath()) { readByteArray() }
        }.getOrNull()
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun newUid(): String = kotlin.uuid.Uuid.random().toString()

    private fun snapshotText(t: TextItem): String =
        listOf(t.uid, t.pos.x, t.pos.y, t.width, t.height, t.text, t.rgba, t.pointSize, t.face, t.bold, t.italic, t.underline, t.strike, t.align, t.math, t.link).joinToString("|")

    private fun snapshotTable(t: TableItem): String =
        (listOf(t.uid, t.rect, t.strokeRgba, t.strokeWidth, t.link) + t.colFractions + t.rowFractions + t.cellGrid().flatten()).joinToString("|")

    /** Drop received image files of [fileId] (unshare/close); the model owns applied ones. */
    private fun dropImageFiles(fileId: String) {
        val files = imageFiles.remove(fileId) ?: return
        val fs = okio.FileSystem.SYSTEM
        for (f in files) runCatching { fs.delete(f.asPath()) }
    }

    /** Drop received audio files of [fileId] (unshare/close); the model owns applied ones. */
    private fun dropAudioFiles(fileId: String) {
        val files = audioFiles.remove(fileId) ?: return
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

    override fun onFileHeadReady(id: String) {
        memScoped {
            host.sync_file_head_ready?.invoke(host.ctx, id.cstr.ptr)
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
        engine.noteClosed(id)
        engine.clearLiveOverlay(id)
        skeletonVersions.remove(id)
        dropImageFiles(id)
        dropAudioFiles(id)
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

    private fun findByUid(pg: Page, uid: String): CanvasItem? {
        if (uid.isEmpty()) return null
        for (it in pg.items) {
            val u = (it as? TextItem)?.uid ?: (it as? TableItem)?.uid
                ?: (it as? ImageItem)?.uid ?: (it as? AudioItem)?.uid
                ?: (it as? ShapeItem)?.uid ?: continue
            if (u == uid) return it
        }
        return null
    }

    override fun applyRemoteText(fileId: String, page: Int, text: WireText) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        val existing = findByUid(pg, text.uid) as? TextItem
        if (existing != null) {
            existing.pos = Pt(text.x, text.y)
            existing.width = text.width
            existing.height = text.height
            existing.text = text.text
            existing.rgba = RemoteLiveStrokes.parseColor(text.color) ?: existing.rgba
            existing.pointSize = text.pointSize
            existing.face = FontFace.fromId(text.face)
            existing.bold = text.bold
            existing.italic = text.italic
            existing.underline = text.underline
            existing.strike = text.strike
            existing.align = HAlign.entries.firstOrNull { it.name.lowercase() == text.align } ?: HAlign.LEFT
            existing.math = text.math
            existing.link = text.link
        } else {
            pg.items.add(
                TextItem(
                    pos = Pt(text.x, text.y), width = text.width, height = text.height,
                    text = text.text, rgba = RemoteLiveStrokes.parseColor(text.color) ?: Rgba(20, 20, 20, 255),
                    pointSize = text.pointSize, face = FontFace.fromId(text.face),
                    measurer = ce.measurer, bold = text.bold, italic = text.italic,
                    underline = text.underline, strike = text.strike,
                    align = HAlign.entries.firstOrNull { it.name.lowercase() == text.align } ?: HAlign.LEFT,
                    math = text.math, uid = text.uid, link = text.link,
                ),
            )
        }
        doc.dirty = true
        ce.state.invalidatePage(pg)
        ce.requestRender()
    }

    override fun applyRemoteTextRemove(fileId: String, page: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        if (pg.items.removeAll { (it as? TextItem)?.uid in ids }) {
            doc.dirty = true
            ce.state.invalidatePage(pg)
            ce.requestRender()
        }
    }

    override fun applyRemoteTable(fileId: String, page: Int, table: WireTable) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        val existing = findByUid(pg, table.uid) as? TableItem
        if (existing != null) {
            existing.rect = Rect(table.x, table.y, table.w, table.h)
            existing.colFractions.clear()
            existing.colFractions.addAll(table.cols)
            existing.rowFractions.clear()
            existing.rowFractions.addAll(table.rows)
            existing.strokeRgba = RemoteLiveStrokes.parseColor(table.strokeColor) ?: existing.strokeRgba
            existing.strokeWidth = table.strokeWidth
            existing.setCells(table.cells)
            existing.link = table.link
        } else {
            pg.items.add(
                TableItem(
                    Rect(table.x, table.y, table.w, table.h),
                    table.cols.toMutableList(), table.rows.toMutableList(),
                    RemoteLiveStrokes.parseColor(table.strokeColor) ?: Rgba(20, 20, 20, 255),
                    table.strokeWidth, table.cells.map { it.toMutableList() }.toMutableList(),
                ).also { it.uid = table.uid; it.link = table.link },
            )
        }
        doc.dirty = true
        ce.state.invalidatePage(pg)
        ce.requestRender()
    }

    override fun applyRemoteTableRemove(fileId: String, page: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        if (pg.items.removeAll { (it as? TableItem)?.uid in ids }) {
            doc.dirty = true
            ce.state.invalidatePage(pg)
            ce.requestRender()
        }
    }

    override fun applyRemoteShape(fileId: String, page: Int, shape: WireShape) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: run {
            // The host moved on (pages added): refetch the whole file.
            engine.requestOpen(fileId)
            return
        }
        val native = shapeToNative(shape, ce) ?: return
        val existing = findByUid(pg, shape.uid) as? ShapeItem
        if (existing != null) {
            val i = pg.items.indexOf(existing)
            if (i >= 0) pg.items[i] = native
        } else {
            pg.items.add(native)
        }
        doc.dirty = true
        ce.state.appendToCache(pg, native)
        ce.requestRender()
    }

    override fun applyRemoteShapeRemove(fileId: String, page: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        if (pg.items.removeAll { (it as? ShapeItem)?.uid in ids }) {
            doc.dirty = true
            ce.state.invalidatePage(pg)
            ce.requestRender()
        }
    }

    private fun shapeToNative(shape: WireShape, ce: CEditor): ShapeItem? {
        val kind = ShapeKind.fromIdOrNull(shape.kind) ?: return null
        val stroke = RemoteLiveStrokes.parseColor(shape.strokeColor) ?: return null
        val item = ShapeItem(
            shape = kind,
            start = Pt(shape.start.getOrElse(0) { 0.0 }, shape.start.getOrElse(1) { 0.0 }),
            end = Pt(shape.end.getOrElse(0) { 0.0 }, shape.end.getOrElse(1) { 0.0 }),
            strokeRgba = stroke,
            strokeWidth = shape.strokeWidth,
            fillRgba = shape.fillColor?.let { RemoteLiveStrokes.parseColor(it) },
            neon = shape.neon,
            neonStrength = shape.neonStrength,
            dashed = shape.dashed,
            dashLength = shape.dashLength,
            dashGap = shape.dashGap,
        )
        item.uid = shape.uid
        item.rotation = shape.rotation
        item.flip = Flip.fromId(shape.flip)
        if (shape.adj.isNotEmpty()) item.adj = shape.adj.toDoubleArray()
        if (shape.n > 0) item.n = shape.n
        item.headStart = Head.fromId(shape.headStart)
        item.headEnd = Head.fromId(shape.headEnd)
        shape.path?.let { item.path = FreePath.fromSvg(it) }
        shape.function?.let { f ->
            if (!item.setFunction(FunctionSpec(f.expr, f.from, f.to, f.yMin, f.yMax))) return null
        }
        shape.label?.let { l ->
            val rgba = RemoteLiveStrokes.parseColor(l.color) ?: stroke
            item.label = ShapeLabel(l.text, TextStyle(rgba, l.pointSize, FontFace.fromId(l.face)), ce.measurer)
            item.labelAt = l.at.coerceIn(0.0, 1.0)
        }
        item.locked = shape.locked
        return item
    }

    override fun applyRemoteAudio(fileId: String, page: Int, audio: WireAudio, bytes: ByteArray, peaks: ByteArray) {
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        val safeId = audio.audioId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "aud" }
        val safeFile = fileId.filter { it.isLetterOrDigit() }.take(24) + fileId.hashCode().toString(16)
        val dest = fileOf(fileOf(audioDir(), safeFile), "$safeId.${audioExt(audio.ext)}")
        runCatching {
            val fs = okio.FileSystem.SYSTEM
            fs.createDirectories(fileOf(audioDir(), safeFile).asPath())
            fs.write(dest.asPath()) { write(bytes) }
        }.getOrNull() ?: return
        audioFiles.getOrPut(fileId) { ArrayList() } += dest
        val native = AudioItem(
            AudioData(dest, audio.ext, audio.durationMs, peaks),
            Rect(audio.x, audio.y, audio.w, audio.h),
            audio.name.ifEmpty { "audio" }, audio.rid, ce.measurer,
        ).also { it.uid = audio.uid.ifEmpty { audio.audioId } }
        // Same audio re-sent (a move): replace in place so z-order holds.
        val at = pg.items.indexOfFirst { (it as? AudioItem)?.uid == native.uid && native.uid.isNotEmpty() }
        if (at >= 0) pg.items[at] = native else pg.items.add(native)
        doc.dirty = true
        ce.state.invalidatePage(pg)
        ce.requestRender()
    }

    override fun applyRemoteAudioRemove(fileId: String, page: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        if (pg.items.removeAll { (it as? AudioItem)?.uid in ids }) {
            doc.dirty = true
            ce.state.invalidatePage(pg)
            ce.requestRender()
        }
    }

    override fun applyRemoteImageRemove(fileId: String, page: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        val ce = bindings[fileId] ?: return
        val doc = ce.state.document
        val pg = doc.pages.getOrNull(page) ?: return
        if (pg.items.removeAll { (it as? ImageItem)?.uid in ids }) {
            doc.dirty = true
            ce.state.invalidatePage(pg)
            ce.requestRender()
        }
    }

    /** Audio/image bytes root (mirrors the image dir; temp files owned by the bridge). */
    private fun audioDir(): File = imageDir

    private fun audioExt(ext: String): String =
        ext.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "m4a"

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
            pencilPressure = st.pencilP ?: base.pencilPressure,
            pencilDensity = st.pencilD ?: base.pencilDensity,
            pencilGrainSize = st.pencilG ?: base.pencilGrainSize,
            pencilShadeResponse = st.pencilS ?: base.pencilShadeResponse,
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
