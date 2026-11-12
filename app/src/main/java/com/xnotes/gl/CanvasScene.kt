package com.xnotes.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.GlowSpec
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.Minimap
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.StickyNoteItem
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.floor

/** Which part of the item under the pen a batch of triangles is. */
enum class WetKind {
    /** Replaces the lot: a selection overlay, a shape being dragged out, or clearing everything. */
    WHOLE,

    /** Added to the run of the stroke that has stopped moving, and never rewritten. */
    SETTLED,

    /** Replaces the few points still moving under the nib. */
    TAIL,
}

/**
 * The GL-thread-owned mirror of the document: one record per committed item, holding where its
 * triangles live and what it takes to draw them. The model itself is never touched from here.
 * Edits arrive as messages, already tessellated on the thread that made them, so a frame never
 * races an edit and tessellation never blocks the render thread.
 */
class CanvasScene(private val store: GeometryStore = GeometryStore()) : GlScene {

    /** One run of triangles in one colour. An item is one or more, drawn in order. */
    private class Part(
        val slice: BufferSlice,
        val pass: InkPass,
        val coverColor: Rgba,
        val coverAlpha: Double,
        val glow: GlowSpec? = null,
        /** A covered tape's body, which the highlights beneath it are drawn round. */
        val covers: Boolean = false,
    )

    /** A highlight held back to composite last, and the bodies of the covered tape laid over it since. */
    private class Deferred(val record: Record, val part: Part) {
        var under: ArrayList<Part>? = null
    }

    private class Record(
        val item: CanvasItem,
        val parts: List<Part>,
        var bounds: Rect,
        var z: Int,
        /** Set for a placed image, which is drawn as a texture rather than as coloured triangles. */
        val image: ImageItem? = null,
        /** Text-only editable shape: rasterized once off the GL thread at the latest wrap width. */
        val textBitmap: Bitmap? = null,
        var textCorners: DoubleArray? = null,
        var textTexture: Int = 0,
    )

    /** An edit handed over from the main thread. */
    private sealed class Edit {
        class Upsert(
            val item: CanvasItem,
            val parts: List<MeshPart>,
            val bounds: Rect,
            /** Non-null for an image, which draws from a texture rather than from the parts. */
            val image: ImageItem? = null,
            /**
             * Set on the message that commits the stroke under the pen. Clearing the wet buffer
             * and adding the committed stroke have to land in the same message: as two, a frame
             * can fall between them and show one blank frame where the stroke should be.
             */
            val clearsWet: Boolean = false,
            val textBitmap: Bitmap? = null,
            val textCorners: DoubleArray? = null,
        ) : Edit()

        /**
         * The item under the pen. [WetKind] says whether these triangles replace everything, add
         * to the run that has stopped moving, or replace the run that has not.
         */
        class Wet(val parts: List<MeshPart>, val bounds: Rect, val kind: WetKind) : Edit()

        /** Items being dragged, and how far from where their triangles sit. Empty ends the drag. */
        class Lift(val items: List<CanvasItem>, val at: LiftTransform) : Edit()

        /** The same drag moved further, with the same items: only the transform changed. */
        class LiftAt(val at: LiftTransform) : Edit()

        /**
         * Decimated stand-ins for the lifted strokes, meshed on a worker while the drag runs.
         * [gen] is the editor's lift generation: anything at or below the watermark is stale.
         */
        class SetProxy(val parts: IdentityHashMap<CanvasItem, List<MeshPart>>, val zoom: Double, val gen: Long) : Edit()

        /** Drop the stand-ins; the watermark still retires any late background build. */
        class ClearProxy(val gen: Long) : Edit()

        /** Move a text quad while preserving its already uploaded texture. */
        class RelocateText(val item: CanvasItem, val corners: DoubleArray, val bounds: Rect) : Edit()
        class Remove(val item: CanvasItem) : Edit()
        class Order(val items: List<CanvasItem>) : Edit()

        /** Room for the batch queued right behind it, so the buffers grow once for all of it. */
        class Reserve(val vertices: Long, val indices: Long) : Edit()
        object Reset : Edit()
    }

    private val pending = ConcurrentLinkedQueue<Edit>()

    /** Edits held back by [batch], to be queued behind one reservation for the lot. */
    private var staged: ArrayList<Edit>? = null

    /**
     * The stroke under the pen lives in its own buffers, kept apart from the committed geometry so
     * growing it never disturbs that allocation. On pen up both are cleared and the finished stroke
     * arrives through the normal upsert path.
     *
     * There are two of them because the stroke has two halves. [wetStore] holds the runs that have
     * stopped moving: they are appended once each and never touched again, which is what keeps a
     * long stroke from re-uploading everything it has laid down on every sample. [tailStore] holds
     * the few points still in play, and is cleared and refilled each time — as its own buffer, so
     * that churn cannot leave a trail of dead allocations through the settled runs. Everything the
     * selection overlay and a dragged-out shape publish goes through the tail too: none of them can
     * be on screen while a stroke is being drawn.
     */
    private val wetStore = GeometryStore()
    private var wetParts = ArrayList<Part>()
    private val tailStore = GeometryStore()
    private var tailParts: List<Part> = emptyList()
    private var wetBounds = Rect(0.0, 0.0, 0.0, 0.0)
    private val records = IdentityHashMap<CanvasItem, Record>()

    /**
     * The selection under a drag. Its triangles stay exactly where they were tessellated and the
     * drag reaches the shader as a turn and a shift, so moving or rotating a selection costs a
     * couple of uniforms rather than a re-tessellation and a re-upload of every selected item on
     * every touch sample.
     *
     * A lifted item draws above the rest for the length of the drag, which is what the paged canvas
     * does with its own lifted selection.
     */
    private val lifted = ArrayList<CanvasItem>()
    private var liftAt = LiftTransform.NONE

    /** Identity set mirroring [lifted], so the per-frame cull tests membership in O(1). */
    private val liftedSet = IdentityHashMap<CanvasItem, Unit>()

    /**
     * The lifted items' records, resolved once per content change and drawn in z-order.
     * Resolving per item per frame cost a hash lookup each, and drawing in selection order
     * could stack overlaps differently from the settled canvas; z-order matches what the
     * commit will look like and keeps sequentially-created runs contiguous for batching.
     */
    private val liftedRecords = ArrayList<Record>()
    private var liftedRecordsRevision = -1
    private val glowingScratch = ArrayList<Record>()

    /**
     * Decimated stand-ins for lifted strokes, drawn in place of the full meshes while a big
     * selection moves. Slices live in the main store through the normal put/free path, so the
     * draw code below needs no second buffer; [proxyGen] retires late background builds.
     */
    private val proxySlices = IdentityHashMap<CanvasItem, List<Part>>()
    private var proxyZoom = Double.NaN
    private var proxyGen = 0L

    /** Records bucketed by content chunk, so a frame only visits what the viewport overlaps. */
    private val buckets = HashMap<Long, ArrayList<Record>>()
    private val oversized = ArrayList<Record>()

    /** Next unseen depth, for an item that arrives before its order message. */
    private var nextZ = 0

    /** Bumped by any change to the records, so the cached halo layer knows it is stale. */
    private var revision = 0

    /** Viewport and revision the visible set was last collected for; a match reuses it. */
    private var lastCullView: Rect? = null
    private var lastCullRevision = -1
    /**
     * Expanded cull bounds: the last collection covered this plus a margin, so small pans reuse
     * the set with only uniforms changing. Re-culls only when the view leaves the margin, the
     * zoom moves, or the content revision moves.
     */
    private var lastCullExpanded: Rect? = null
    private var lastCullZoom = Double.NaN

    /** Frames that reused the visible set, for the debug HUD. */
    private var cullSkippedTotal = 0

    /** Last wet mesh+upload cost in ms, published by whoever produced the tessellation. */
    @Volatile
    var notedWetMeshMs = 0.0
        private set

    /** Last minimap redraw cost in ms. */
    @Volatile
    var lastMinimapMs = 0.0
        private set

    /** What the cached halo layer was built for; a mismatch means rebuild it. */
    private var lastHaloRevision = -1
    private var lastHaloZoom = Double.NaN
    private var lastHaloScrollX = Double.NaN
    private var lastHaloScrollY = Double.NaN
    private var lastHaloW = -1
    private var lastHaloH = -1

    /** Glowing records for the cached halo layer, rebuilt when the revision moves. */
    private var lastGlowing: List<Record> = emptyList()
    private var lastGlowRevision = -1

    /** What the throttled minimap was last drawn for; a match skips redrawing it. */
    private var lastMinimapNs = 0L
    private var lastMinimapRevision = -1
    private var lastMinimapZoom = Double.NaN
    private var lastMinimapScrollX = Double.NaN
    private var lastMinimapScrollY = Double.NaN
    private var lastMinimapW = -1
    private var lastMinimapH = -1
    private var lastMinimapBounds: Rect? = null

    /** Cached minimap dots/panel/extent: recomputed throttled, replayed every frame. */
    private var minimapDots: List<Rect> = emptyList()
    private var minimapPanel: Rect = Rect(0.0, 0.0, 0.0, 0.0)
    private var minimapExtent: Rect? = null

    /** The same for the dragged selection's halos, plus the point in the drag they were built at. */
    private var liftHaloKey: String? = null
    private var liftHaloAt = LiftTransform.NONE

    private var ink: InkShader? = null
    private var cover: CoverShader? = null
    private var imageShader: ImageShader? = null
    private var minimapShader: MinimapShader? = null
    private var glowShader: GlowShader? = null
    private val glowTarget = GlowTarget()

    /** Textures for placed images, decoded at the size the current zoom needs. */
    val textures = TextureCache()

    /** Reusable corner/clip scratch: the GL thread draws sequentially, so per-item
     *  FloatArray allocations per frame (one per visible text/image plus one per masked
     *  cover) become UI-thread churn during pan. Reused here; never retained by callees. */
    private val textCornersScratch = FloatArray(8)
    private val imageCornersScratch = FloatArray(8)
    private val imageXYScratchX = DoubleArray(4)
    private val imageXYScratchY = DoubleArray(4)
    private val clipRectScratch = FloatArray(4)

    /** Runs a decode off the render thread; installed by the host. */
    var decodeOn: (Runnable) -> Unit = { it.run() }

    /**
     * The ink a playback fades, set from the main thread and read each frame. A faded item draws
     * each of its runs stencilled and covered once at a quarter of its alpha, so it needs no new
     * triangles and never darkens where it crosses itself; its neon halo is left out.
     */
    @Volatile
    var fade: com.xnotes.core.audio.InkFade? = null

    /** Told on the render thread when committed items start or stop being left out for memory. */
    var onOutOfMemory: ((Boolean) -> Unit)? = null
    private var reportedOutOfMemory = false

    /** Publish a wet mesh+upload cost measured wherever the tessellation ran. */
    fun noteWetMesh(ms: Double) {
        notedWetMeshMs = ms
    }

    private var contextGen = -1

    /** Reused per frame so drawing allocates nothing. */
    private val visible = ArrayList<Record>()
    private val deferred = ArrayList<Deferred>()
    private val stickyDeferred = ArrayList<Deferred>()

    /** Items currently drawn, for the debug readout. */
    val itemCount: Int get() = records.size

    var lastDrawCalls: Int = 0
        private set

    var lastVisibleItems: Int = 0
        private set

    /** Milliseconds the newest tessellation took, published by whoever produced it. */
    @Volatile
    var lastTessellateMs: Double = 0.0

    /** Last frame's render-thread phase costs, for the debug HUD's lag triage. */
    @Volatile var lastCullMs: Double = 0.0
        private set
    @Volatile var lastCommitMs: Double = 0.0
        private set
    @Volatile var lastLiftMs: Double = 0.0
        private set
    @Volatile var lastWetMs: Double = 0.0
        private set

    override fun describe(into: GlStats): GlStats = into.copy(
        items = records.size,
        visibleItems = lastVisibleItems,
        drawCalls = lastDrawCalls,
        geometryBytes = store.gpuBytes + wetStore.gpuBytes + tailStore.gpuBytes,
        liveGeometryBytes = store.liveBytes + wetStore.liveBytes + tailStore.liveBytes,
        textures = textures.textureCount,
        textureBytes = textures.residentBytes,
        texturesPending = textures.pendingCount,
        lastTessellateMs = lastTessellateMs,
        wetMeshMs = notedWetMeshMs,
        cullSkipped = cullSkippedTotal,
        minimapMs = lastMinimapMs,
        cullMs = lastCullMs,
        commitMs = lastCommitMs,
        liftMs = lastLiftMs,
        wetMs = lastWetMs,
    )

    // --- main-thread API ---

    fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect, clearsWet: Boolean = false) {
        post(Edit.Upsert(item, parts, bounds, null, clearsWet))
    }

    /** File a placed image. It carries no geometry: the renderer draws it as a textured quad. */
    fun upsertImage(item: ImageItem, bounds: Rect) {
        post(Edit.Upsert(item, emptyList(), bounds, item, false))
    }

    /** A label rasterized on a worker: the GL thread only uploads and draws four corners. */
    fun upsertText(item: CanvasItem, bitmap: Bitmap, corners: DoubleArray, bounds: Rect) {
        post(Edit.Upsert(item, emptyList(), bounds, textBitmap = bitmap, textCorners = corners))
    }

    /** Rigid translation keeps the existing glyph layout, bitmap, and GPU texture intact. */
    fun relocateText(item: CanvasItem, corners: DoubleArray, bounds: Rect) {
        post(Edit.RelocateText(item, corners, bounds))
    }

    fun remove(item: CanvasItem) {
        post(Edit.Remove(item))
    }

    /** Publish the document's z order; the scene draws back to front by it. */
    fun setOrder(items: List<CanvasItem>) {
        post(Edit.Order(ArrayList(items)))
    }

    /** Publish the in-progress stroke's triangles, or clear it when [mesh] is null. */
    fun setWet(mesh: MeshData?, color: Rgba, pass: InkPass, bounds: Rect) {
        post(
            Edit.Wet(
                if (mesh == null) emptyList() else listOf(MeshPart(mesh, color, pass)),
                bounds,
                WetKind.WHOLE,
            ),
        )
    }

    /** Publish an in-progress item made of several runs, which is what a filled shape is. An empty
     *  list clears the whole wet buffer, settled runs included. */
    fun setWetParts(parts: List<MeshPart>, bounds: Rect) {
        post(Edit.Wet(parts, bounds, WetKind.WHOLE))
    }

    /** Add a run of the stroke under the pen that has stopped moving; it is never rewritten. */
    fun appendWetRun(parts: List<MeshPart>, bounds: Rect) {
        post(Edit.Wet(parts, bounds, WetKind.SETTLED))
    }

    /** Replace the run still moving under the nib, leaving the settled ones where they are. */
    fun setWetTail(parts: List<MeshPart>, bounds: Rect) {
        post(Edit.Wet(parts, bounds, WetKind.TAIL))
    }

    /** Draw [items] displaced by [at] until this is called again with an empty list. */
    fun setLift(items: List<CanvasItem>, at: LiftTransform) {
        post(Edit.Lift(ArrayList(items), at))
    }

    /** Move the current lifted drag to [at] without re-publishing (and re-comparing) its items. */
    fun setLiftAt(at: LiftTransform) {
        post(Edit.LiftAt(at))
    }

    /**
     * Show decimated stand-ins for the lifted strokes until [clearLiftProxy]. The queue is FIFO,
     * so a late build from an earlier drag always lands behind that drag's clear and is retired
     * by the watermark instead of leaking onto the next drag.
     */
    fun setLiftProxy(parts: Map<CanvasItem, List<MeshPart>>, zoom: Double, gen: Long) {
        val copy = IdentityHashMap<CanvasItem, List<MeshPart>>(parts.size * 2 + 1)
        copy.putAll(parts)
        post(Edit.SetProxy(copy, zoom, gen))
    }

    /** Drop the drag stand-ins; the watermark retires any late background build. */
    fun clearLiftProxy(gen: Long) {
        post(Edit.ClearProxy(gen))
    }

    fun reset() {
        post(Edit.Reset)
    }

    /** Queue what [block] publishes behind one reservation, so a whole load grows the buffers once. */
    fun batch(block: () -> Unit) {
        if (staged != null) return block()
        val edits = ArrayList<Edit>()
        staged = edits
        try {
            block()
        } finally {
            staged = null
            var vertices = 0L
            var indices = 0L
            for (edit in edits) {
                if (edit !is Edit.Upsert) continue
                for (part in edit.parts) {
                    vertices += part.mesh.vertexCount
                    indices += part.mesh.indices.size
                }
            }
            pending.add(Edit.Reserve(vertices, indices))
            pending.addAll(edits)
        }
    }

    private fun post(edit: Edit) {
        val batch = staged
        if (batch != null) batch.add(edit) else pending.add(edit)
    }

    // --- GL thread ---

    @Volatile var maxTextTextureSide: Double = 2048.0
        private set

    override fun onContextCreated(contextGen: Int) {
        this.contextGen = contextGen
        val textureLimit = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, textureLimit, 0)
        maxTextTextureSide = textureLimit[0].coerceAtLeast(2048).toDouble()
        ink = null
        cover = null
        imageShader = null
        minimapShader = null
        glowShader = null
        glowTarget.onContextCreated(contextGen)
        try {
            ink = InkShader(contextGen)
            cover = CoverShader(contextGen)
            imageShader = ImageShader(contextGen)
            minimapShader = MinimapShader(contextGen)
            glowShader = GlowShader(contextGen)
        } catch (e: GlShaderException) {
            Log.e(TAG, "ink shaders unavailable", e)
        }
        textures.onContextCreated(contextGen)
        // GL names do not survive context loss; retained text bitmaps are uploaded on demand.
        for (record in records.values) record.textTexture = 0
        // The mirrors survived, so the whole document re-uploads without re-tessellating anything.
        store.onContextCreated(contextGen)
        wetStore.onContextCreated(contextGen)
        tailStore.onContextCreated(contextGen)
    }

    override fun drawContent(frame: FrameState) {
        drainEdits()
        ensureLiftedRecords()
        val frameStartNs = System.nanoTime()
        textures.beginFrame()
        textures.uploadPending()
        glowTarget.resize(frame.widthPx, frame.heightPx, contextGen)
        val program = ink ?: return
        if (program.contextGen != contextGen) return
        if (records.isEmpty() && wetParts.isEmpty() && tailParts.isEmpty()) {
            lastDrawCalls = 0
            lastVisibleItems = 0
            return
        }

        val camChunkX = floor(frame.scrollX / GeometryStore.CHUNK_SIZE)
        val camChunkY = floor(frame.scrollY / GeometryStore.CHUNK_SIZE)
        program.begin(
            camChunkX, camChunkY,
            frame.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
            frame.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
            frame.zoom, frame.widthPx.toDouble(), frame.heightPx.toDouble(),
        )
        if (!store.bindForDraw(contextGen)) return
        store.bindAttributes(program)


        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // The visible set only depends on the viewport and the records: when neither
        // moved, reusing it skips a full chunk walk, sort and dedupe every static frame.
        // Hysteresis: the collection covers the view plus a margin, so small pans reuse the
        // superset with only shader uniforms changing. Re-culls only when the view leaves the
        // margin, the zoom moves, or the content changed.
        val view = viewRect(frame)
        val expanded = lastCullExpanded
        val zoomSame = frame.zoom == lastCullZoom
        if (revision == lastCullRevision && zoomSame && expanded != null &&
            expanded.left <= view.left && expanded.top <= view.top &&
            expanded.right >= view.right && expanded.bottom >= view.bottom
        ) {
            cullSkippedTotal++
        } else {
            val marginW = (view.w * CULL_MARGIN_FRACTION).coerceAtLeast(CULL_MARGIN_MIN_PX / frame.zoom)
            val marginH = (view.h * CULL_MARGIN_FRACTION).coerceAtLeast(CULL_MARGIN_MIN_PX / frame.zoom)
            val collectRect = Rect(view.x - marginW, view.y - marginH, view.w + marginW * 2, view.h + marginH * 2)
            collectVisible(collectRect)
            lastCullView = view
            lastCullExpanded = collectRect
            lastCullRevision = revision
            lastCullZoom = frame.zoom
            lastVisibleItems = visible.size
            lastCullMs = (System.nanoTime() - frameStartNs) / 1_000_000.0
        }
        lastDrawCalls = 0
        val cullDone = System.nanoTime()

        // Halos of everything already committed go into a layer that is rebuilt only when the view
        // or the content moves. Re-blurring each of them every frame made the cost of neon scale
        // with how much of it was on screen: nine strokes took the canvas to a fifth of the
        // refresh rate, while none of them had actually changed.
        drawCommittedHalos(program, frame, camChunkX, camChunkY)

        // Sticky notes and Math Studio instances always sit in front of canvas ink, whatever the document order says:
        // everything else first, then the notes/instances (with their own ink) over it.
        val fade = fade
        deferred.clear()
        stickyDeferred.clear()
        drawCommittedBatch(program, frame, camChunkX, camChunkY, fade, stickyOnly = false, deferred)
        // A sticky's paper covers the plain highlighters beneath it, like a tape does.
        for (record in visible) {
            if (record.item !is StickyNoteItem && record.item !is com.xnotes.core.model.MathStudioInstanceItem) continue
            for (part in record.parts) {
                if (!part.covers) continue
                for (d in deferred) {
                    if (d.record !== record && d.record.bounds.intersects(record.bounds)) {
                        (d.under ?: ArrayList<Part>().also { d.under = it }).add(part)
                    }
                }
            }
        }
        for (d in deferred) {
            drawMasked(store, d.part.slice, d.record.bounds, d.part.coverColor, d.part.coverAlpha, frame, d.part.pass, d.under)
            rebind(program, frame, camChunkX, camChunkY)
        }
        drawCommittedBatch(program, frame, camChunkX, camChunkY, fade, stickyOnly = true, stickyDeferred)
        for (d in stickyDeferred) {
            drawMasked(store, d.part.slice, d.record.bounds, d.part.coverColor, d.part.coverAlpha, frame, d.part.pass, d.under)
            rebind(program, frame, camChunkX, camChunkY)
        }
        val commitDone = System.nanoTime()
        lastCommitMs = (commitDone - cullDone) / 1_000_000.0

        drawLifted(program, frame, camChunkX, camChunkY)
        val liftDone = System.nanoTime()
        lastLiftMs = (liftDone - commitDone) / 1_000_000.0
        drawWet(program, frame, camChunkX, camChunkY)
        if (frame.minimapVisible) {
            // The minimap must draw *every* frame it is visible: it paints straight into the
            // framebuffer, so skipping a frame reads as flicker. What is throttled is the
            // *recomputation* of the cached dots (the O(N) walk over every record): edits
            // refresh it promptly, camera-only moves at most twice a second. The per-frame
            // draw replays the cache plus a live viewport outline, so the marker never lags
            // the fingers even while the dots underneath are a few frames old.
            val nowNs = System.nanoTime()
            val revChanged = revision != lastMinimapRevision || frame.contentBounds != lastMinimapBounds
            val viewChanged = frame.zoom != lastMinimapZoom ||
                frame.scrollX != lastMinimapScrollX || frame.scrollY != lastMinimapScrollY ||
                frame.widthPx != lastMinimapW || frame.heightPx != lastMinimapH ||
                frame.contentBounds != lastMinimapBounds
            if (minimapExtent == null || revChanged ||
                (viewChanged && nowNs - lastMinimapNs >= MINIMAP_REDRAW_NS_PAN)
            ) {
                refreshMinimapCache(frame)
                lastMinimapNs = nowNs
                lastMinimapRevision = revision
                lastMinimapZoom = frame.zoom
                lastMinimapScrollX = frame.scrollX
                lastMinimapScrollY = frame.scrollY
                lastMinimapW = frame.widthPx
                lastMinimapH = frame.heightPx
                lastMinimapBounds = frame.contentBounds
            }
            val miniStart = System.nanoTime()
            drawMinimapCached(frame, view)
            lastMinimapMs = (System.nanoTime() - miniStart) / 1_000_000.0
        }
        lastWetMs = (System.nanoTime() - liftDone) / 1_000_000.0
        lastCommitMs = (commitDone - cullDone) / 1_000_000.0

        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.disableAttributes()
    }

    /**
     * One half of the committed pass: either everything but the sticky notes, or just the
     * notes. The two halves share the visible list (already back-to-front) so each keeps its
     * own z order; the caller draws the first half's held-back highlighters before the second
     * half, which is what keeps the notes in front of page ink.
     */
    private fun drawCommittedBatch(
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
        fade: com.xnotes.core.audio.InkFade?,
        stickyOnly: Boolean,
        deferred: MutableList<Deferred>,
    ) {
        // Back to front, batching every run of opaque items that happens to be contiguous in the
        // buffer into one call. Freshly loaded content is laid down in z order, so a screenful of
        // it is usually a single draw.
        var runStart = -1
        var runCount = 0
        for (record in visible) {
            val isStickyLike = record.item is StickyNoteItem || record.item is com.xnotes.core.model.MathStudioInstanceItem
            if (isStickyLike != stickyOnly) continue
            val faded = fade != null && fade.fades(record.item)
            if (record.textBitmap != null) {
                flushRun(runStart, runCount)
                runStart = -1
                runCount = 0
                drawText(record, frame, alpha = if (faded) com.xnotes.core.audio.InkFade.ALPHA else 1.0)
                rebind(program, frame, camChunkX, camChunkY)
                store.bindForDraw(contextGen)
                store.bindAttributes(program)
                continue
            }
            if (record.image != null) {
                flushRun(runStart, runCount)
                runStart = -1
                runCount = 0
                drawImage(record, record.image, frame, alpha = if (faded) com.xnotes.core.audio.InkFade.ALPHA else 1.0)
                rebind(program, frame, camChunkX, camChunkY)
                store.bindForDraw(contextGen)
                store.bindAttributes(program)
                continue
            }
            if (faded) {
                flushRun(runStart, runCount)
                runStart = -1
                runCount = 0
                for (part in record.parts) {
                    val pass = when (part.pass) {
                        InkPass.MULTIPLY, InkPass.SCREEN, InkPass.EVEN_ODD, InkPass.NONZERO -> part.pass
                        else -> InkPass.TRANSLUCENT
                    }
                    drawMasked(store, part.slice, record.bounds, part.coverColor, part.coverAlpha * com.xnotes.core.audio.InkFade.ALPHA, frame, pass)
                }
                rebind(program, frame, camChunkX, camChunkY)
                continue
            }
            for (part in record.parts) {
                // Tape laid over a held-back highlight hides it, so the highlight will be drawn round it.
                if (part.covers && deferred.isNotEmpty()) {
                    for (d in deferred) {
                        if (d.record !== record && d.record.bounds.intersects(record.bounds)) {
                            (d.under ?: ArrayList<Part>().also { d.under = it }).add(part)
                        }
                    }
                }
                @Suppress("UNUSED_EXPRESSION")
                when (part.pass) {
                    InkPass.MULTIPLY, InkPass.SCREEN -> {
                        // Highlighters composite over the finished picture, matching the paged
                        // canvas, so their blend acts on what is beneath instead of washing it out.
                        deferred.add(Deferred(record, part))
                    }
                    InkPass.OPAQUE -> {
                        if (runStart >= 0 && part.slice.indexOffset == runStart + runCount) {
                            runCount += part.slice.indexCount
                        } else {
                            flushRun(runStart, runCount)
                            runStart = part.slice.indexOffset
                            runCount = part.slice.indexCount
                        }
                    }
                    InkPass.TRANSLUCENT, InkPass.EVEN_ODD, InkPass.NONZERO -> {
                        flushRun(runStart, runCount)
                        runStart = -1
                        runCount = 0
                        drawMasked(record, part, frame, part.pass)
                        rebind(program, frame, camChunkX, camChunkY)
                    }
                    InkPass.GLOW -> {
                        // The halo is already on screen from the cached layer; this draws the lit
                        // body and the white core over it, from the very same triangles.
                        flushRun(runStart, runCount)
                        runStart = -1
                        runCount = 0
                        drawGlowBody(program, store, part)
                    }
                }
            }
        }
        flushRun(runStart, runCount)
    }

    /**
     * The minimap: a panel, a dot per item, and the viewport's own outline.
     *
     * It is drawn from item bounds rather than by running the scene through a second transform,
     * which would cost a second full pass of everything visible. At this scale a stroke is a dot
     * either way, so the cheap version conveys exactly as much.
     *
     * Split in two: [refreshMinimapCache] walks every record (throttled by the caller) while
     * [drawMinimapCached] replays the cache every frame, so the panel never flickers mid-gesture.
     */
    private fun refreshMinimapCache(frame: FrameState) {
        val panel = Minimap.panel(frame.widthPx, frame.heightPx, frame.insetRight, frame.insetBottom)
        val visible = Rect(frame.scrollX, frame.scrollY, frame.widthPx / frame.zoom, frame.heightPx / frame.zoom)
        val extent = Minimap.mappedExtent(frame.contentBounds, visible)
        // Hoisted out of the per-dot loop: one division per refresh instead of per record.
        val scale = Minimap.scaleFor(extent, panel)
        val originX = panel.centerX - extent.centerX * scale
        val originY = panel.centerY - extent.centerY * scale
        // Bounded draws per refresh: past the cap every k-th record stands in for its
        // neighbours (hash order, stable while panning). A thousand overlapping 1.5px dots
        // read the same as 250 of them at this scale, for a quarter of the draw calls.
        val total = records.size
        val stride = if (total > MINIMAP_MAX_DOTS) (total + MINIMAP_MAX_DOTS - 1) / MINIMAP_MAX_DOTS else 1
        val dots = ArrayList<Rect>(minOf(total, MINIMAP_MAX_DOTS) + 1)
        var i = 0
        for (record in records.values) {
            if ((i++ % stride) != 0) continue
            val b = record.bounds
            val w = (b.w * scale).coerceAtLeast(MINIMAP_DOT_PX)
            val h = (b.h * scale).coerceAtLeast(MINIMAP_DOT_PX)
            dots.add(Rect(originX + b.left * scale, originY + b.top * scale, w, h))
        }
        minimapDots = dots
        minimapPanel = panel
        minimapExtent = extent
    }

    /** Replay the cached panel and dots plus a live viewport outline: cheap enough for every frame. */
    private fun drawMinimapCached(frame: FrameState, view: Rect) {
        val shader = minimapShader ?: return
        val extent = minimapExtent ?: return
        val panel = minimapPanel
        val paperDim = Rgba(frame.paper.r, frame.paper.g, frame.paper.b, 210)
        shader.fill(panel, paperDim, frame.widthPx, frame.heightPx)
        shader.outline(panel, 1.0, frame.accent.withAlpha(90), frame.widthPx, frame.heightPx)

        val dot = frame.accent.withAlpha(150)
        for (mapped in minimapDots) {
            shader.fill(mapped, dot, frame.widthPx, frame.heightPx)
        }
        shader.outline(
            Minimap.toPanel(view, extent, panel), 1.5, frame.accent,
            frame.widthPx, frame.heightPx,
        )
        lastDrawCalls += minimapDots.size + 6
    }

    /**
     * The selection under a drag, drawn above the rest with the drag pushed in as a uniform.
     *
     * Nothing is re-tessellated and nothing is re-uploaded, which is the whole point: the cost of
     * dragging is the same for one stroke and for a hundred. Contiguous opaque runs batch into a
     * single draw like the committed pass does, so a large selection of sequentially-created
     * strokes still moves in a handful of calls rather than one per item.
     */
    private fun drawLifted(program: InkShader, frame: FrameState, camChunkX: Double, camChunkY: Double) {
        if (liftedRecords.isEmpty()) return
        val view = viewRect(frame)
        drawLiftedHalos(program, frame, camChunkX, camChunkY)
        applyView(program, frame, camChunkX, camChunkY, liftAt)
        if (!store.bindForDraw(contextGen)) return
        store.bindAttributes(program)
        var runStart = -1
        var runCount = 0
        fun flushRun() {
            if (runStart >= 0 && runCount > 0) {
                store.drawRange(runStart, runCount)
                lastDrawCalls++
            }
            runStart = -1
            runCount = 0
        }
        for (record in liftedRecords) {
            // A proxy is a decimated stand-in within a pixel of the full mesh; outside a small
            // zoom drift it would show, so fall back to the full mesh instead of showing that.
            val useProxy = proxyZoom.isFinite() &&
                kotlin.math.abs(frame.zoom - proxyZoom) <= 0.25 * proxyZoom
            val drawParts = if (useProxy) proxySlices[record.item] ?: record.parts else record.parts
            val bounds = liftAt.bounds(record.bounds)
            if (!bounds.intersects(view)) {
                flushRun()
                continue
            }
            if (record.textBitmap != null) {
                flushRun()
                drawText(record, frame, liftAt)
                applyView(program, frame, camChunkX, camChunkY, liftAt)
                store.bindForDraw(contextGen)
                store.bindAttributes(program)
                continue
            }
            if (record.image != null) {
                flushRun()
                drawImage(record, record.image, frame, liftAt)
                applyView(program, frame, camChunkX, camChunkY, liftAt)
                store.bindForDraw(contextGen)
                store.bindAttributes(program)
                continue
            }
            for (part in drawParts) {
                when (part.pass) {
                    InkPass.OPAQUE -> {
                        if (runStart >= 0 && part.slice.indexOffset == runStart + runCount) {
                            runCount += part.slice.indexCount
                        } else {
                            flushRun()
                            runStart = part.slice.indexOffset
                            runCount = part.slice.indexCount
                        }
                    }
                    InkPass.TRANSLUCENT, InkPass.EVEN_ODD, InkPass.NONZERO, InkPass.MULTIPLY, InkPass.SCREEN -> {
                        flushRun()
                        drawMasked(
                            store, part.slice, bounds, part.coverColor, part.coverAlpha,
                            frame, part.pass,
                        )
                        applyView(program, frame, camChunkX, camChunkY, liftAt)
                        store.bindForDraw(contextGen)
                        store.bindAttributes(program)
                    }
                    // The halo is already down from the layer above; this is the lit body and core.
                    InkPass.GLOW -> {
                        flushRun()
                        drawGlowBody(program, store, part)
                    }
                }
            }
        }
        flushRun()
        rebind(program, frame, camChunkX, camChunkY)
    }

    /**
     * The dragged selection's halos, blurred once and then slid and turned to follow the finger.
     *
     * A blur commutes with a translation, and an isotropic Gaussian commutes with a rotation too, so
     * a drag never needs a second one: the layer is built with the selection where it was when the
     * drag began, and every frame after that is one textured quad read at an offset and an angle.
     * Re-blurring per frame is what made dragging neon crawl, the same way it once made inking with
     * it crawl.
     *
     * It is rebuilt when the drag has travelled or turned far enough that content could have entered
     * from off screen, which a screen-sized buffer cannot have captured.
     */
    private fun drawLiftedHalos(
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
    ) {
        val shader = glowShader ?: return
        if (!glowTarget.ready) return
        glowingScratch.clear()
        for (record in liftedRecords) {
            for (part in record.parts) {
                if (part.pass == InkPass.GLOW) {
                    glowingScratch.add(record)
                    break
                }
            }
        }
        val glowing = glowingScratch
        if (glowing.isEmpty()) {
            liftHaloKey = null
            return
        }
        val key = "$revision|${frame.zoom}|${frame.scrollX}|${frame.scrollY}|${frame.widthPx}x${frame.heightPx}"
        val since = liftAt.since(liftHaloAt)
        val driftPx = kotlin.math.hypot(since.dx, since.dy) * frame.zoom
        val turned = kotlin.math.abs(kotlin.math.atan2(since.b, since.a))
        // A scale is only exactly a read of the blurred picture while it stays uniform, so a
        // stretched halo is re-blurred once it has strayed far enough to show.
        val stretched = since.linearScale !in LIFT_HALO_KEEP_SCALE..(1.0 / LIFT_HALO_KEEP_SCALE)
        if (key != liftHaloKey || driftPx > LIFT_HALO_REBUILD_PX ||
            turned > LIFT_HALO_REBUILD_RAD || stretched
        ) {
            liftHaloAt = liftAt
            glowTarget.bind(glowTarget.liftLayerIndex)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            for (record in glowing) {
                for (part in record.parts) {
                    if (part.pass != InkPass.GLOW) continue
                    drawGlow(
                        program, store, record, part, frame, camChunkX, camChunkY,
                        targetLayer = glowTarget.liftLayerIndex, lift = liftHaloAt,
                    )
                }
            }
            glowTarget.unbind(frame.widthPx, frame.heightPx)
            liftHaloKey = key
        }
        // What is left of the drag since the layer was built, as a slide and a turn of the finished
        // picture. Device y runs down and the buffer's runs up, so the vertical term changes sign.
        // The turn needs no sign flip: reading the source at the inverse angle and the axis flip
        // cancel, leaving the drag's own angle.
        val rest = liftAt.since(liftHaloAt)
        val slideX = (rest.dx * frame.zoom / frame.widthPx).toFloat()
        val slideY = (rest.dy * frame.zoom / frame.heightPx).toFloat()
        val pivotDeviceX = (rest.pivot.x - frame.scrollX) * frame.zoom
        val pivotDeviceY = (rest.pivot.y - frame.scrollY) * frame.zoom
        // A texture read runs destination to source, so the map goes in inverted. The buffer's v
        // axis runs up while the device's y runs down, which flips the two off-diagonal terms.
        val inv = rest.inverseLinear()
        shader.compositeOver(
            glowTarget.texture(glowTarget.liftLayerIndex), 1.0,
            uvOffsetX = -slideX, uvOffsetY = slideY,
            uvMapA = inv[0].toFloat(), uvMapB = (-inv[1]).toFloat(),
            uvMapC = (-inv[2]).toFloat(), uvMapD = inv[3].toFloat(),
            uvPivotX = (pivotDeviceX / frame.widthPx).toFloat(),
            uvPivotY = (1.0 - pivotDeviceY / frame.heightPx).toFloat(),
            uvSizeX = frame.widthPx.toFloat(), uvSizeY = frame.heightPx.toFloat(),
        )
        lastDrawCalls++
    }

    private fun viewRect(frame: FrameState) = Rect(
        frame.scrollX, frame.scrollY, frame.widthPx / frame.zoom, frame.heightPx / frame.zoom,
    )

    /**
     * The stroke under the pen, drawn last because it is the newest thing on the canvas. It lives
     * in its own buffer, so it is bound over the committed one for these few triangles and the
     * committed geometry's allocation is never disturbed while a stroke grows.
     */
    private fun drawWet(program: InkShader, frame: FrameState, camChunkX: Double, camChunkY: Double) {
        // Settled runs first, then the one still under the nib over it, so the two overlap the way
        // they were meshed to.
        drawWetBuffer(wetStore, wetParts, program, frame, camChunkX, camChunkY)
        drawWetBuffer(tailStore, tailParts, program, frame, camChunkX, camChunkY)
        if (wetParts.isEmpty() && tailParts.isEmpty()) return
        // Leave the committed buffers bound for the next frame's first draw.
        store.bindForDraw(contextGen)
        store.bindAttributes(program)
    }

    private fun drawWetBuffer(
        buffer: GeometryStore,
        parts: List<Part>,
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
    ) {
        if (parts.isEmpty()) return
        if (!buffer.bindForDraw(contextGen)) return
        buffer.bindAttributes(program)
        // The settled runs are put into a fresh buffer in order, so they land back to back and a
        // whole stroke's worth of them collapses into one call rather than one per run.
        var runStart = -1
        var runCount = 0
        fun flush() {
            if (runStart >= 0 && runCount > 0) {
                buffer.drawRange(runStart, runCount)
                lastDrawCalls++
            }
            runStart = -1
            runCount = 0
        }
        for (part in parts) {
            if (part.pass == InkPass.OPAQUE) {
                if (runStart >= 0 && part.slice.indexOffset == runStart + runCount) {
                    runCount += part.slice.indexCount
                } else {
                    flush()
                    runStart = part.slice.indexOffset
                    runCount = part.slice.indexCount
                }
                continue
            }
            flush()
            if (part.pass == InkPass.GLOW) {
                drawGlow(program, buffer, wetRecord(), part, frame, camChunkX, camChunkY)
            } else {
                drawMasked(
                    buffer, part.slice, wetBounds, part.coverColor, part.coverAlpha,
                    frame, part.pass,
                )
            }
            rebind(program, frame, camChunkX, camChunkY)
            buffer.bindForDraw(contextGen)
            buffer.bindAttributes(program)
        }
        flush()
    }

    /** Upload one ready-to-draw text bitmap and reuse its GL texture until the text changes. */
    private fun textTexture(record: Record): Int {
        if (record.textTexture != 0) return record.textTexture
        val bitmap = record.textBitmap ?: return 0
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        if (ids[0] == 0) return 0
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        record.textTexture = ids[0]
        return ids[0]
    }

    /** A native-laid-out editable label is just four vertices on the GL thread, even while resizing. */
    private fun drawText(
        record: Record,
        frame: FrameState,
        lift: LiftTransform = LiftTransform.NONE,
        alpha: Double = 1.0,
    ) {
        val shader = imageShader ?: return
        val points = record.textCorners ?: return
        val texture = textTexture(record)
        if (texture == 0) return
        val corners = textCornersScratch
        for (i in 0 until 4) {
            val p = lift.apply(com.xnotes.core.geometry.Pt(points[2 * i], points[2 * i + 1]))
            val dx = (p.x - frame.scrollX) * frame.zoom
            val dy = (p.y - frame.scrollY) * frame.zoom
            corners[2 * i] = (dx / frame.widthPx * 2.0 - 1.0).toFloat()
            corners[2 * i + 1] = (1.0 - dy / frame.heightPx * 2.0).toFloat()
        }
        shader.draw(corners, texture, 0, alpha, invert = frame.background.invertColors)
        lastDrawCalls++
    }

    /**
     * A placed image as one textured quad. The corners are worked out in double precision here and
     * handed over in clip space, so an image a long way from the origin is placed exactly.
     *
     * Only the drag's shift is honoured, never its turn: an image's angle lives in the model, so a
     * selection holding one is turned by the model rather than lifted. The stored angle is folded
     * into the corners here, which is all a rotation costs on a quad.
     */
    private fun drawImage(
        record: Record,
        image: ImageItem,
        frame: FrameState,
        lift: LiftTransform = LiftTransform.NONE,
        alpha: Double = 1.0,
    ) {
        val shader = imageShader ?: return
        val rect = image.rect.translate(lift.dx, lift.dy)
        // Decode for the size the image actually occupies on screen right now; a slice of it, that much larger.
        val share = image.part?.let { minOf(it.w, it.h) }?.takeIf { it > 0.0 } ?: 1.0
        val onScreenEdge = (maxOf(rect.w, rect.h) / share * frame.zoom).toInt().coerceAtLeast(1)
        val texture = textures.textureFor(image.image, onScreenEdge, decodeOn)
        if (texture == 0) return
        val corners = imageCornersScratch
        // A mirrored picture lays each corner's texels on the opposite corner.
        val (l, r) = if (image.flip.x) rect.right to rect.left else rect.left to rect.right
        val (t, b) = if (image.flip.y) rect.bottom to rect.top else rect.top to rect.bottom
        val xs = imageXYScratchX
        val ys = imageXYScratchY
        xs[0] = l; xs[1] = r; xs[2] = l; xs[3] = r
        ys[0] = t; ys[1] = t; ys[2] = b; ys[3] = b
        val turn = image.angle
        val co = kotlin.math.cos(turn)
        val sn = kotlin.math.sin(turn)
        for (i in 0 until 4) {
            var wx = xs[i]
            var wy = ys[i]
            if (turn != 0.0) {
                val ox = wx - rect.centerX
                val oy = wy - rect.centerY
                wx = rect.centerX + ox * co - oy * sn
                wy = rect.centerY + ox * sn + oy * co
            }
            val dx = (wx - frame.scrollX) * frame.zoom
            val dy = (wy - frame.scrollY) * frame.zoom
            corners[2 * i] = (dx / frame.widthPx * 2.0 - 1.0).toFloat()
            corners[2 * i + 1] = (1.0 - dy / frame.heightPx * 2.0).toFloat()
        }
        // Placed images keep their pixels unless both inversion and "invert images" are on;
        // rasterized text labels are content ink and follow the main inversion flag.
        shader.draw(corners, texture, image.orientation / 90, alpha, image.part,
            invert = frame.background.invertColors && frame.background.invertImages)
        lastDrawCalls++
    }

    /**
     * Neon's two halos: the geometry into an offscreen buffer, blurred across and back, then
     * composited under the item at the halo's own brightness. Twice, wide and faint under tight and
     * bright, which is the layering the paged renderer paints.
     *
     * The buffers are a fraction of the viewport, since a blur hides the resolution it was computed
     * at, and each halo composites once rather than per overlap, so a self-crossing scribble cannot
     * pile up into a brighter blob.
     */
    /**
     * Composite every committed item's halo, rebuilding the layer they share only when the view or
     * the content has actually moved. While a stroke is being drawn neither has, so this is one
     * textured quad however much neon is on screen.
     *
     * The halos land under all the ink rather than each under its own item. They are soft and
     * diffuse, so the difference shows only where a neon stroke sits beneath other content, which
     * is a small price for not paying a blur per item per frame.
     */
    private fun drawCommittedHalos(
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
    ) {
        val shader = glowShader ?: return
        if (!glowTarget.ready) return
        // Pan slides the shared layer instead of re-blurring it: a blur commutes with a
        // translation, so the same uvOffset trick the lifted halos use applies here. Re-blur
        // only on content/zoom/size change, or once the pan drifted far enough that strokes
        // could have entered from off screen (the layer only holds what was visible at build).
        val driftX = (frame.scrollX - lastHaloScrollX) * frame.zoom
        val driftY = (frame.scrollY - lastHaloScrollY) * frame.zoom
        val driftPx = if (lastHaloScrollX.isNaN()) Double.MAX_VALUE
            else kotlin.math.hypot(driftX, driftY)
        val needsRebuild = revision != lastHaloRevision || frame.zoom != lastHaloZoom ||
            frame.widthPx != lastHaloW || frame.heightPx != lastHaloH ||
            driftPx > HALO_PAN_REBUILD_PX
        if (needsRebuild) {
            lastGlowing = visible.filter { record -> record.parts.any { it.pass == InkPass.GLOW } }
            lastGlowRevision = revision
        } else if (revision != lastGlowRevision) {
            // Content changed but the camera did not move far: refresh the set cheaply from the
            // reused visible list instead of paying a blur for an unchanged view.
            lastGlowing = visible.filter { record -> record.parts.any { it.pass == InkPass.GLOW } }
            lastGlowRevision = revision
        }
        val glowing = lastGlowing
        if (glowing.isEmpty()) return
        if (needsRebuild) {
            buildHaloLayer(program, glowing, frame, camChunkX, camChunkY)
            lastHaloRevision = revision
            lastHaloZoom = frame.zoom
            lastHaloScrollX = frame.scrollX
            lastHaloScrollY = frame.scrollY
            lastHaloW = frame.widthPx
            lastHaloH = frame.heightPx
            rebind(program, frame, camChunkX, camChunkY)
            store.bindForDraw(contextGen)
            store.bindAttributes(program)
            shader.compositeOver(glowTarget.texture(glowTarget.layerIndex), 1.0)
        } else {
            // Slide the previously blurred picture with the pan. Device y runs down while the
            // buffer's v runs up, hence the sign flip — same convention as the lifted halos.
            val slideX = ((frame.scrollX - lastHaloScrollX) * frame.zoom / frame.widthPx).toFloat()
            val slideY = ((frame.scrollY - lastHaloScrollY) * frame.zoom / frame.heightPx).toFloat()
            shader.compositeOver(
                glowTarget.texture(glowTarget.layerIndex), 1.0,
                uvOffsetX = -slideX, uvOffsetY = slideY,
            )
        }
        lastDrawCalls++
        rebind(program, frame, camChunkX, camChunkY)
        store.bindForDraw(contextGen)
        store.bindAttributes(program)
    }

    /** Blur every committed halo into the shared layer, once, for this view. */
    private fun buildHaloLayer(
        program: InkShader,
        glowing: List<Record>,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
    ) {
        glowTarget.bind(glowTarget.layerIndex)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        for (record in glowing) {
            for (part in record.parts) {
                if (part.pass != InkPass.GLOW) continue
                drawGlow(
                    program, store, record, part, frame, camChunkX, camChunkY,
                    targetLayer = glowTarget.layerIndex,
                )
            }
        }
        glowTarget.unbind(frame.widthPx, frame.heightPx)
    }

    /** Neon's lit body and white core, both from the same triangles the halo came from. */
    private fun drawGlowBody(program: InkShader, from: GeometryStore, part: Part) {
        val spec = part.glow ?: return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program.setOverride(spec.bodyColor)
        from.drawRange(part.slice.indexOffset, part.slice.indexCount)
        program.setOverride(spec.coreColor)
        program.setWidthScale(spec.coreScale)
        from.drawRange(part.slice.indexOffset, part.slice.indexCount)
        program.setWidthScale(1.0)
        program.clearOverride()
        lastDrawCalls += 2
    }

    private fun drawGlow(
        program: InkShader,
        from: GeometryStore,
        record: Record,
        part: Part,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
        /** Glow buffer to accumulate into, or -1 to composite straight onto the screen. */
        targetLayer: Int = -1,
        lift: LiftTransform = LiftTransform.NONE,
    ) {
        val shader = glowShader ?: return
        val spec = part.glow ?: return
        if (!glowTarget.ready) return
        val bounds = lift.bounds(record.bounds)

        for (halo in 0 until 2) {
            val radius = if (halo == 0) spec.wideRadius else spec.tightRadius
            val alpha = if (halo == 0) spec.wideAlpha else spec.tightAlpha
            if (alpha <= 0.0 || radius <= 0.0) continue
            // The blur works in the buffer's own pixels, so the content radius comes through the
            // zoom and then the buffer's own ratio to the screen.
            val radiusPx = (radius * frame.zoom * bufferRatioX(frame))
                .coerceAtMost(GlowShader.MAX_TAPS.toDouble() * 2.0)
            if (radiusPx < 0.4) continue

            // Only the stroke's own patch of screen is touched, grown by the blur's reach. A halo
            // is a small thing on a large display, and clearing and blurring the whole buffer for
            // each one is what made inking with neon crawl.
            val patch = glowPatch(bounds, radiusPx / bufferRatioX(frame), frame) ?: continue
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)

            scissorInBuffer(patch, frame)
            glowTarget.bind(0)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            // Exactly the projection the screen pass uses, with nothing divided by anything. The
            // downscale is already done: glViewport maps clip space onto the smaller buffer, so
            // rendering the same picture into it renders it smaller. Shrinking the zoom as well
            // applied the downscale twice and put the halo down at half size in the corner, which
            // is what a halo sitting away from its stroke looks like. It also means the whole
            // buffer is exactly the whole screen, so nothing has to be cropped on the way back.
            applyView(program, frame, camChunkX, camChunkY, lift)
            from.bindForDraw(contextGen)
            from.bindAttributes(program)
            GLES30.glDisable(GLES30.GL_BLEND)
            from.drawRange(part.slice.indexOffset, part.slice.indexCount)
            program.disableAttributes()

            glowTarget.bind(1)
            shader.blur(glowTarget.texture(0), radiusPx, true, glowTarget.bufferWidth, glowTarget.bufferHeight)
            glowTarget.bind(0)
            shader.blur(glowTarget.texture(1), radiusPx, false, glowTarget.bufferWidth, glowTarget.bufferHeight)

            // The buffer is the screen, so every hop samples it one to one.
            if (targetLayer >= 0) {
                glowTarget.bind(targetLayer)
                scissorInBuffer(patch, frame)
            } else {
                glowTarget.unbind(frame.widthPx, frame.heightPx)
                GLES30.glScissor(patch[0], frame.heightPx - patch[1] - patch[3], patch[2], patch[3])
            }
            shader.compositeOver(glowTarget.texture(0), alpha)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            lastDrawCalls += 4
        }
        if (targetLayer >= 0) return

        // The lit body and the white core, both from the very same triangles: the shader overrides
        // the colour and scales the stored spine offset, so neon costs one tessellation and one
        // copy in the buffer rather than three of each.
        applyView(program, frame, camChunkX, camChunkY, lift)
        from.bindForDraw(contextGen)
        from.bindAttributes(program)
        drawGlowBody(program, from, part)
    }

    /**
     * The item's patch of screen, in device pixels with y down, grown by the blur's reach and
     * clipped to the viewport. Null when it falls entirely off screen.
     */
    private fun glowPatch(bounds: Rect, reachPx: Double, frame: FrameState): IntArray? {
        val pad = reachPx * 2.0 + 4.0
        val x0 = ((bounds.left - frame.scrollX) * frame.zoom - pad).toInt().coerceIn(0, frame.widthPx)
        val y0 = ((bounds.top - frame.scrollY) * frame.zoom - pad).toInt().coerceIn(0, frame.heightPx)
        val x1 = ((bounds.right - frame.scrollX) * frame.zoom + pad).toInt().coerceIn(0, frame.widthPx)
        val y1 = ((bounds.bottom - frame.scrollY) * frame.zoom + pad).toInt().coerceIn(0, frame.heightPx)
        if (x1 <= x0 || y1 <= y0) return null
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    /** Buffer pixels per screen pixel. The buffer covers the whole screen, so this is its ratio. */
    private fun bufferRatioX(frame: FrameState): Double =
        if (frame.widthPx <= 0) 1.0 else glowTarget.bufferWidth.toDouble() / frame.widthPx

    private fun bufferRatioY(frame: FrameState): Double =
        if (frame.heightPx <= 0) 1.0 else glowTarget.bufferHeight.toDouble() / frame.heightPx

    /** Set the scissor for a glow-buffer pass, converting the device patch into buffer pixels. */
    private fun scissorInBuffer(patch: IntArray, frame: FrameState) {
        val rx = bufferRatioX(frame)
        val ry = bufferRatioY(frame)
        val x = kotlin.math.floor(patch[0] * rx).toInt().coerceIn(0, glowTarget.bufferWidth)
        val yTop = kotlin.math.floor(patch[1] * ry).toInt().coerceIn(0, glowTarget.bufferHeight)
        val w = (kotlin.math.ceil(patch[2] * rx).toInt() + 1).coerceAtMost(glowTarget.bufferWidth - x)
        val h = (kotlin.math.ceil(patch[3] * ry).toInt() + 1).coerceAtMost(glowTarget.bufferHeight - yTop)
        // The buffer shares the framebuffer's y-up convention, so the patch flips into it.
        val y = (glowTarget.bufferHeight - yTop - h).coerceAtLeast(0)
        if (w <= 0 || h <= 0) return
        GLES30.glScissor(x, y, w, h)
    }

    private fun flushRun(start: Int, count: Int) {
        if (start < 0 || count <= 0) return
        store.drawRange(start, count)
        lastDrawCalls++
    }

    /**
     * Stencil the stroke, then paint its colour through the mask exactly once. The cover zeroes the
     * stencil as it draws, so consecutive strokes need no clear between them.
     */
    /** Re-point the ink program after a cover draw took the program and buffer bindings away. */
    private fun rebind(program: InkShader, frame: FrameState, camChunkX: Double, camChunkY: Double) =
        applyView(program, frame, camChunkX, camChunkY)

    /** Bind the ink program for this frame's view, optionally displaced for a dragged selection. */
    private fun applyView(
        program: InkShader,
        frame: FrameState,
        camChunkX: Double,
        camChunkY: Double,
        lift: LiftTransform = LiftTransform.NONE,
    ) {
        program.begin(
            camChunkX, camChunkY,
            frame.scrollX - camChunkX * GeometryStore.CHUNK_SIZE,
            frame.scrollY - camChunkY * GeometryStore.CHUNK_SIZE,
            frame.zoom, frame.widthPx.toDouble(), frame.heightPx.toDouble(),
        )
        if (lift.isIdentity) return
        // The pivot goes in the camera's own chunk frame, the frame the vertices rebuild themselves
        // in, so nothing large is ever subtracted from anything large.
        program.setLift(
            lift.pivot.x - camChunkX * GeometryStore.CHUNK_SIZE,
            lift.pivot.y - camChunkY * GeometryStore.CHUNK_SIZE,
            lift.a, lift.b, lift.c, lift.d, lift.linearScale, lift.dx, lift.dy,
        )
    }

    private fun drawMasked(record: Record, part: Part, frame: FrameState, pass: InkPass) =
        drawMasked(store, part.slice, record.bounds, part.coverColor, part.coverAlpha, frame, pass)

    private fun drawMasked(
        from: GeometryStore,
        slice: BufferSlice,
        bounds: Rect,
        coverColor: Rgba,
        coverAlpha: Double,
        frame: FrameState,
        pass: InkPass,
        under: List<Part>? = null,
    ) {
        val quad = cover ?: return
        // An even-odd fill inverts instead of replacing, so the parity of how many times a pixel
        // was covered is the fill rule itself: a hole cancels, a self-crossing cancels, and the
        // cover then paints exactly what the outline encloses without a single triangle of setup.
        val invert = pass == InkPass.EVEN_ODD
        val winding = pass == InkPass.NONZERO
        GLES30.glEnable(GLES30.GL_STENCIL_TEST)
        GLES30.glStencilFunc(GLES30.GL_ALWAYS, if (invert || winding) 0 else 1, 0xFF)
        if (winding) {
            GLES30.glStencilOpSeparate(GLES30.GL_FRONT, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_INCR_WRAP)
            GLES30.glStencilOpSeparate(GLES30.GL_BACK, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_DECR_WRAP)
        } else {
            GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP,
                if (invert) GLES30.GL_INVERT else GLES30.GL_REPLACE)
        }
        GLES30.glStencilMask(0xFF)
        GLES30.glColorMask(false, false, false, false)
        from.drawRange(slice.indexOffset, slice.indexCount)
        lastDrawCalls++
        // Covered tape over the highlight takes its body back out of the mask, so the cover skips it.
        if (under != null) {
            GLES30.glStencilFunc(GLES30.GL_ALWAYS, 0, 0xFF)
            GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_REPLACE)
            for (p in under) {
                from.drawRange(p.slice.indexOffset, p.slice.indexCount)
                lastDrawCalls++
            }
        }

        GLES30.glColorMask(true, true, true, true)
        GLES30.glStencilFunc(if (invert || winding) GLES30.GL_NOTEQUAL else GLES30.GL_EQUAL, if (invert || winding) 0 else 1, 0xFF)
        GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_ZERO)
        when (pass) {
            // Exact multiply: the destination is scaled by the source and nothing is added.
            InkPass.MULTIPLY -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ZERO)
            // Exact screen: src + dst - src*dst, the multiply reflected about white.
            InkPass.SCREEN -> GLES30.glBlendFunc(GLES30.GL_ONE_MINUS_DST_COLOR, GLES30.GL_ONE)
            else -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        val r = clipRect(bounds, frame)
        // A multiply/screen cover must present the ink already faded by its own alpha (toward white
        // and toward black respectively), since the blend function cannot apply an alpha of its own.
        val color = when (pass) {
            InkPass.MULTIPLY -> ItemMesher.multiplyColor(coverColor, coverAlpha)
            InkPass.SCREEN -> ItemMesher.screenColor(coverColor, coverAlpha)
            else -> coverColor
        }
        val alpha = if (pass == InkPass.MULTIPLY || pass == InkPass.SCREEN) 1.0 else coverAlpha
        quad.draw(r[0], r[1], r[2], r[3], color, alpha)
        lastDrawCalls++

        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** Content bounds to clip space, grown a couple of pixels so the mask is never clipped short. */
    private fun clipRect(bounds: Rect, frame: FrameState): FloatArray {
        val pad = 2.0
        val x0 = (bounds.left - frame.scrollX) * frame.zoom - pad
        val y0 = (bounds.top - frame.scrollY) * frame.zoom - pad
        val x1 = (bounds.right - frame.scrollX) * frame.zoom + pad
        val y1 = (bounds.bottom - frame.scrollY) * frame.zoom + pad
        val w = frame.widthPx.toDouble()
        val h = frame.heightPx.toDouble()
        val out = clipRectScratch
        out[0] = (x0 / w * 2.0 - 1.0).coerceIn(-1.0, 1.0).toFloat()
        out[1] = (1.0 - y0 / h * 2.0).coerceIn(-1.0, 1.0).toFloat()
        out[2] = (x1 / w * 2.0 - 1.0).coerceIn(-1.0, 1.0).toFloat()
        out[3] = (1.0 - y1 / h * 2.0).coerceIn(-1.0, 1.0).toFloat()
        return out
    }

    // --- record bookkeeping ---

    private fun drainEdits() {
        // Coalesce bursts first: a fast drag posts several transform/ tail edits per frame and
        // only the last of each consecutive run can survive it; the rest only churn the buffers.
        // Runs are same-kind and adjacent, so dropping all but the last changes nothing that a
        // frame could have observed between them; every other kind keeps its exact order.
        var batch: ArrayList<Edit>? = null
        while (true) {
            val edit = pending.poll() ?: break
            if (batch == null) batch = ArrayList(8)
            batch.add(edit)
        }
        if (batch != null) {
            var i = 0
            while (i < batch.size) {
                val edit = batch[i]
                if (i + 1 < batch.size && isCoalescible(edit) && sameCoalescible(edit, batch[i + 1])) {
                    i++
                    continue
                }
                applyEdit(edit)
                i++
            }
        }
        reportMemory()
    }

    /** Edits a later same-kind edit fully supersedes: a transform, or a tail-buffer replace. */
    private fun isCoalescible(edit: Edit): Boolean =
        edit is Edit.LiftAt || (edit is Edit.Wet && edit.kind == WetKind.TAIL)

    private fun sameCoalescible(a: Edit, b: Edit): Boolean =
        (a is Edit.LiftAt && b is Edit.LiftAt) ||
            (a is Edit.Wet && b is Edit.Wet && a.kind == WetKind.TAIL && b.kind == WetKind.TAIL)

    private fun applyEdit(edit: Edit) {
        when (edit) {
            is Edit.Upsert -> applyUpsert(edit)
            is Edit.RelocateText -> applyRelocateText(edit)
            is Edit.Wet -> applyWet(edit)
            is Edit.Lift -> applyLift(edit)
            is Edit.LiftAt -> liftAt = edit.at
            is Edit.SetProxy -> applySetProxy(edit)
            is Edit.ClearProxy -> applyClearProxy(edit)
            is Edit.Remove -> applyRemove(edit.item)
            is Edit.Order -> applyOrder(edit.items)
            is Edit.Reserve -> store.reserve(edit.vertices, edit.indices)
            Edit.Reset -> applyReset()
        }
    }

    private fun reportMemory() {
        val short = store.outOfMemory
        if (short == reportedOutOfMemory) return
        reportedOutOfMemory = short
        if (short) Log.w(TAG, "geometry buffers could not grow; some items are not drawn")
        onOutOfMemory?.invoke(short)
    }

    private fun applyUpsert(edit: Edit.Upsert) {
        revision++
        // Committing the wet stroke is one step, not two: the buffer is released and the finished
        // stroke takes its place within a single frame's drain, so nothing can be drawn between.
        if (edit.clearsWet) clearWetBuffer()
        // An item edited in place keeps its depth: only a structural change reorders, and that
        // arrives as its own message.
        val previousZ = records[edit.item]?.z
        applyRemove(edit.item)
        val parts = ArrayList<Part>(edit.parts.size)
        for (part in edit.parts) {
            // A translucent run accumulates at full alpha and gets its alpha back at cover time,
            // which is what stops its own overlaps from compounding.
            val baked = if (part.pass == InkPass.OPAQUE) part.color else part.color.withAlpha(255)
            val slice = store.put(part.mesh, baked) ?: continue
            parts.add(Part(slice, part.pass, part.color.withAlpha(255), part.color.a / 255.0, part.glow, part.covers))
        }
        if (parts.isEmpty() && edit.image == null && edit.textBitmap == null) return
        val record = Record(edit.item, parts, edit.bounds, previousZ ?: nextZ++, edit.image,
            edit.textBitmap, edit.textCorners)
        records[edit.item] = record
        fileRecord(record)
    }

    /** Move the already rasterized text in the spatial index without touching its GL texture. */
    private fun applyRelocateText(edit: Edit.RelocateText) {
        val record = records[edit.item] ?: return
        if (record.textBitmap == null) return
        unfileRecord(record)
        record.textCorners = edit.corners
        record.bounds = edit.bounds
        fileRecord(record)
        revision++
    }

    private fun applyWet(edit: Edit.Wet) {
        when (edit.kind) {
            WetKind.WHOLE -> {
                clearWetBuffer()
                if (edit.parts.isEmpty()) return
                wetBounds = edit.bounds
                tailParts = build(edit.parts, tailStore)
            }
            WetKind.SETTLED -> {
                // Appended, never rewritten: this run is ink that can no longer change.
                wetBounds = edit.bounds
                if (edit.parts.isEmpty()) return
                wetParts.addAll(build(edit.parts, wetStore))
            }
            WetKind.TAIL -> {
                // Its own buffer, cleared whole, so the churn under the nib cannot fragment the
                // settled runs sitting next to it.
                wetBounds = edit.bounds
                tailStore.clear()
                tailParts = if (edit.parts.isEmpty()) emptyList() else build(edit.parts, tailStore)
            }
        }
    }

    private fun build(parts: List<MeshPart>, into: GeometryStore): List<Part> {
        val built = ArrayList<Part>(parts.size)
        for (part in parts) {
            // A translucent run accumulates at full alpha and gets its alpha back at cover time,
            // which is what stops its own overlaps from compounding.
            val baked = if (part.pass == InkPass.OPAQUE) part.color else part.color.withAlpha(255)
            val slice = into.put(part.mesh, baked) ?: continue
            built.add(Part(slice, part.pass, part.color.withAlpha(255), part.color.a / 255.0, part.glow))
        }
        return built
    }

    private fun applyLift(edit: Edit.Lift) {
        // Only which items are lifted can stale the shared halo layer, since a lifted item is left
        // out of it. How far the drag has got cannot: that is drawn separately. Bumping the
        // revision for the offset too rebuilt every committed halo on screen on every touch
        // sample, which is the cost the layer exists to avoid.
        if (!sameItems(lifted, edit.items)) {
            revision++
            lifted.clear()
            lifted.addAll(edit.items)
            liftedSet.clear()
            for (item in lifted) liftedSet[item] = Unit
        }
        liftAt = edit.at
    }

    /** Resolve the lifted items to records once per content change, in z-order. */
    private fun ensureLiftedRecords() {
        if (liftedRecordsRevision == revision) return
        liftedRecords.clear()
        for (item in lifted) {
            records[item]?.let { liftedRecords.add(it) }
        }
        liftedRecords.sortBy { it.z }
        liftedRecordsRevision = revision
    }

    /**
     * Swap in decimated drag stand-ins. Sorted by z before uploading so the proxy slices land
     * back-to-back in draw order and keep batching; a late build from an earlier drag retires
     * against the watermark instead of leaking onto this one. No revision bump: the committed
     * halo layer never contained the lifted items, and the lifted halos keep reading the exact
     * meshes (a pixel of body mismatch under a soft halo is invisible, while re-blurring on
     * every swap would defeat the optimisation).
     */
    private fun applySetProxy(edit: Edit.SetProxy) {
        if (edit.gen <= proxyGen) return
        clearProxySlices()
        val ordered = edit.parts.entries.sortedBy { records[it.key]?.z ?: Int.MAX_VALUE }
        for ((item, parts) in ordered) {
            // The model stands still while lifted, but never trust a background thread for that:
            // anything gone since is simply left on its full mesh.
            if (!records.containsKey(item)) continue
            val built = build(parts, store)
            if (built.isNotEmpty()) proxySlices[item] = built
        }
        proxyZoom = edit.zoom
        proxyGen = edit.gen
    }

    private fun clearProxySlices() {
        for (parts in proxySlices.values) for (part in parts) store.free(part.slice)
        proxySlices.clear()
        proxyZoom = Double.NaN
    }

    private fun applyClearProxy(edit: Edit.ClearProxy) {
        proxyGen = maxOf(proxyGen, edit.gen)
        clearProxySlices()
    }

    private fun sameItems(a: List<CanvasItem>, b: List<CanvasItem>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    /** A stand-in record for the wet item, so the glow pass can read its bounds like any other. */
    private fun wetRecord(): Record = Record(WET_KEY, emptyList(), wetBounds, 0)

    private fun clearWetBuffer() {
        wetStore.clear()
        wetParts.clear()
        tailStore.clear()
        tailParts = emptyList()
    }

    private fun freeText(record: Record) {
        if (record.textTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(record.textTexture), 0)
            record.textTexture = 0
        }
        record.textBitmap?.recycle()
    }

    private fun applyRemove(item: CanvasItem) {
        revision++
        val record = records.remove(item) ?: return
        unfileRecord(record)
        for (part in record.parts) store.free(part.slice)
        freeText(record)
    }

    private fun applyOrder(items: List<CanvasItem>) {
        revision++
        for (i in items.indices) records[items[i]]?.z = i
    }

    private fun applyReset() {
        revision++
        nextZ = 0
        lifted.clear()
        liftedSet.clear()
        proxySlices.clear()
        proxyZoom = Double.NaN
        for (record in records.values) freeText(record)
        records.clear()
        buckets.clear()
        oversized.clear()
        store.clear()
    }

    // --- culling ---

    private fun collectVisible(view: Rect) {
        visible.clear()
        for (record in oversized) if (record.bounds.intersects(view)) visible.add(record)
        val x0 = chunkOf(view.left)
        val y0 = chunkOf(view.top)
        val x1 = chunkOf(view.right)
        val y1 = chunkOf(view.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_QUERY_CELLS) {
            // Zoomed so far out that walking cells costs more than testing everything.
            for (record in records.values) if (record.bounds.intersects(view)) visible.add(record)
            visible.sortBy { it.z }
            dedupeSorted()
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                val bucket = buckets[key(cx, cy)] ?: continue
                for (record in bucket) if (record.bounds.intersects(view)) visible.add(record)
            }
        }
        visible.sortBy { it.z }
        dedupeSorted()
    }

    /**
     * Drop the repeats a record filed in several chunks produced, and anything a drag has lifted;
     * the list is already z sorted. A lifted record draws in its own pass, from where it is being
     * dragged to rather than from where its triangles sit.
     */
    private fun dedupeSorted() {
        if (visible.isEmpty()) return
        var write = 0
        for (read in visible.indices) {
            val record = visible[read]
            if (write > 0 && record === visible[write - 1]) continue
            if (isLifted(record.item)) continue
            visible[write] = record
            write++
        }
        // Bulk-trim the tail: removeAt in a loop is O(n^2) on an ArrayList.
        if (visible.size > write) visible.subList(write, visible.size).clear()
    }

    private fun isLifted(item: CanvasItem): Boolean = liftedSet.containsKey(item)

    private fun fileRecord(record: Record) {
        val x0 = chunkOf(record.bounds.left)
        val y0 = chunkOf(record.bounds.top)
        val x1 = chunkOf(record.bounds.right)
        val y1 = chunkOf(record.bounds.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_FILE_CELLS) {
            oversized.add(record)
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) buckets.getOrPut(key(cx, cy)) { ArrayList(4) }.add(record)
        }
    }

    private fun unfileRecord(record: Record) {
        val x0 = chunkOf(record.bounds.left)
        val y0 = chunkOf(record.bounds.top)
        val x1 = chunkOf(record.bounds.right)
        val y1 = chunkOf(record.bounds.bottom)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong() > MAX_FILE_CELLS) {
            oversized.removeAll { it === record }
            return
        }
        for (cy in y0..y1) {
            for (cx in x0..x1) {
                val k = key(cx, cy)
                val bucket = buckets[k] ?: continue
                bucket.removeAll { it === record }
                if (bucket.isEmpty()) buckets.remove(k)
            }
        }
    }

    private fun chunkOf(v: Double): Int {
        if (!v.isFinite()) return 0
        return floor(v / GeometryStore.CHUNK_SIZE).coerceIn(-32768.0, 32767.0).toInt()
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    companion object {
        private const val TAG = "xnotes.gl"

        /** Widest cell span a record is binned across before it is just tested on every frame. */
        private const val MAX_FILE_CELLS = 256L

        /** Cells a cull will walk before it gives up and scans every record instead. */
        private const val MAX_QUERY_CELLS = 4096L

        /** Cull hysteresis: the collected set covers the view plus this fraction per side, so
         *  small pans reuse it with only uniforms changing instead of re-walking chunks. */
        private const val CULL_MARGIN_FRACTION = 0.25
        /** Floor for the cull margin, in device px, so deep zoom-outs still get room. */
        private const val CULL_MARGIN_MIN_PX = 256.0

        /** Smallest a minimap marker is drawn, so one thin stroke is still visible. */
        private const val MINIMAP_DOT_PX = 1.5

        /** Slowest the minimap redraws while nothing it shows changed. */
        private const val MINIMAP_REDRAW_NS = 120_000_000L
        /** Slower cap while only the camera moves (no edits): the dots refresh at 2Hz, while the
         *  panel and the viewport outline still draw every frame from the cache. */
        private const val MINIMAP_REDRAW_NS_PAN = 500_000_000L
        /** Most dots a minimap refresh keeps: past this every k-th record stands in. */
        private const val MINIMAP_MAX_DOTS = 250
        /** Pan distance in device px before a panned halo layer is re-blurred to pick up newcomers. */
        private const val HALO_PAN_REBUILD_PX = 96.0

        /**
         * Device pixels a drag may slide its halo layer before it is blurred again. The layer only
         * holds what was on screen when it was built, so a long drag has to refresh it to pick up
         * anything that has since come into view.
         */
        private const val LIFT_HALO_REBUILD_PX = 96.0

        /** And how far it can turn, for the same reason: about a sixth of a full turn. */
        private const val LIFT_HALO_REBUILD_RAD = 1.0

        /** Smallest residual scale a stretched halo is still read rather than blurred again. */
        private const val LIFT_HALO_KEEP_SCALE = 0.85

        /** Identity for the wet item's stand-in record; never filed or drawn as itself. */
        private val WET_KEY: CanvasItem = object : CanvasItem {
            override val kind = "wet"
            override val resizable = false
            override var locked = false
            override var link: String? = null
            override fun paint(r: com.xnotes.core.pal.Renderer) = Unit
            override fun bounds() = Rect(0.0, 0.0, 0.0, 0.0)
            override fun translate(dx: Double, dy: Double) = Unit
            override fun contains(p: com.xnotes.core.geometry.Pt) = false
            override fun centroid() = com.xnotes.core.geometry.Pt.ZERO
            override fun intersectsCircle(cx: Double, cy: Double, radius: Double) = false
            override fun snapshotGeometry(): com.xnotes.core.model.GeometrySnapshot =
                throw UnsupportedOperationException()
            override fun restoreGeometry(snap: com.xnotes.core.model.GeometrySnapshot) = Unit
            override fun applyTransform(t: com.xnotes.core.geometry.Affine) = Unit
        }
    }
}
