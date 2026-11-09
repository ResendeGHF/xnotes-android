package com.xnotes.sync

import com.xnotes.core.model.TextMarkup

/**
 * What the companion engine needs from its host (the capi bridge on Linux):
 * transport, UI callbacks, and applying remote commits to open documents.
 * Every call is on the UI thread.
 */
interface SyncEngineHost {
    /** A frame is ready for the socket: write it at once (TCP_NODELAY). */
    fun onBytes(data: ByteArray)

    /** The desktop must approve pairing `deviceName` (fingerprint `fp`). */
    fun onPairRequest(deviceName: String, fp: String)

    /** Pairing finished: refresh the home screen. */
    fun onPaired(deviceName: String)

    /** The shared-file list changed: rebuild the file manager. */
    fun onIndexChanged()

    /** [id]'s bytes are complete: open (or reload) it. */
    fun onFileReady(id: String)

    /** [id] left the shared set: close it with a notice. */
    fun onFileGone(id: String)

    /** A file's small entries arrived ahead of its bytes: open the skeleton now. */
    fun onFileHeadReady(id: String)

    /** The host answered a delete request. */
    fun onDeleteResult(ok: Boolean, reason: String?)

    /** The host answered a create request (`id` set when approved). */
    fun onCreateResult(ok: Boolean, id: String?, reason: String?)

    /** The host answered a link-access request for [id]. */
    fun onLinkResult(id: String, ok: Boolean, reason: String?)

    /** Fatal: close the socket and show [reason]. */
    fun onError(reason: String)

    /** A live frame changed the overlay: repaint. */
    fun onLiveDirty()

    // --- open documents ---

    /** Apply a committed remote stroke to the open document for [fileId]. */
    fun applyRemoteStroke(fileId: String, page: Int, stroke: WireStroke)

    /** Apply a committed remote erase to the open document for [fileId]. */
    fun applyRemoteErase(fileId: String, page: Int, ids: List<String>)

    /** Insert a blank remote page at [index] ([width]/[height] in page px). */
    fun applyRemotePageAdd(fileId: String, index: Int, width: Double, height: Double)

    /** Remove the remote page at [index]. */
    fun applyRemotePageRemove(fileId: String, index: Int)

    /**
     * Apply a committed remote room edit on [page]: [open] opens room at [a]
     * (to [b]), else closes the strip [a] to [b]. No-op when the page or room
     * is gone; bypasses undo like all remote ops.
     */
    fun applyRemoteRoomEdit(fileId: String, page: Int, sideways: Boolean, open: Boolean, a: Double, b: Double)

    /** Show the far side's room drag on [fileId]'s page without touching the model. */
    fun previewRemoteRoom(fileId: String, page: Int, sideways: Boolean, split: Double, resume: Double, shift: Double)

    /** Drop the far side's room preview for [fileId]. */
    fun clearRemoteRoomPreview(fileId: String)

    /**
     * Place a committed remote image on [page]: [bytes] are its encoded file
     * (PNG/JPEG/SVG as sent). Applies to the open document, bypassing undo.
     */
    fun applyRemoteImage(fileId: String, page: Int, image: WireImage, bytes: ByteArray)

    /**
     * Apply committed remote text markups on [page]: add [added] (skipping
     * ids already present), remove [removedIds]. Bypasses undo like all
     * remote ops; the host rebakes the background and repaints.
     */
    fun applyRemoteMarkups(fileId: String, page: Int, added: List<TextMarkup>, removedIds: List<String>)

    /**
     * Apply a committed remote page-style change: [entries] assign style
     * and/or margins per page index; [docStyle]/[docMargins] replace the
     * document-wide defaults. Bypasses undo; the host rebakes backgrounds,
     * relayouts when margins move, and repaints.
     */
    fun applyRemotePageStyle(fileId: String, entries: List<PageStyleEntry>, docStyle: WirePageStyle?, docMargins: WireMargins?)

    /**
     * [fileId]'s head moved under us: drop the open document's state and
     * refetch. The engine already queued the fetch.
     */
    fun applyResync(fileId: String, version: Long)

    /** Upsert a committed remote text box on [page] (matched by uid). Bypasses undo. */
    fun applyRemoteText(fileId: String, page: Int, text: WireText)

    /** Take remote text boxes off [page] by uid. Bypasses undo. */
    fun applyRemoteTextRemove(fileId: String, page: Int, ids: List<String>)

    /** Upsert a committed remote table on [page] (matched by uid). Bypasses undo. */
    fun applyRemoteTable(fileId: String, page: Int, table: WireTable)

    /** Take remote tables off [page] by uid. Bypasses undo. */
    fun applyRemoteTableRemove(fileId: String, page: Int, ids: List<String>)

    /**
     * Place a committed remote audio pill on [page]: [bytes] are the encoded
     * audio, [peaks] its waveform bins. Applies to the open document,
     * bypassing undo; each side plays its own copy.
     */
    fun applyRemoteAudio(fileId: String, page: Int, audio: WireAudio, bytes: ByteArray, peaks: ByteArray)

    /** Take remote audio pills off [page] by audio id or uid. Bypasses undo. */
    fun applyRemoteAudioRemove(fileId: String, page: Int, ids: List<String>)

    /** Take remote image pills off [page] by image id or uid. Bypasses undo. */
    fun applyRemoteImageRemove(fileId: String, page: Int, ids: List<String>)

    /** Upsert a committed remote shape on [page] (matched by uid; drops [WireShape.liveId]'s live run). Bypasses undo. */
    fun applyRemoteShape(fileId: String, page: Int, shape: WireShape)

    /** Take remote shapes off [page] by uid. Bypasses undo. */
    fun applyRemoteShapeRemove(fileId: String, page: Int, ids: List<String>)
}
