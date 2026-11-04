package com.xnotes.sync

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

    /** The host answered a delete request. */
    fun onDeleteResult(ok: Boolean, reason: String?)

    /** The host answered a create request (`id` set when approved). */
    fun onCreateResult(ok: Boolean, id: String?, reason: String?)

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
     * [fileId]'s head moved under us: drop the open document's state and
     * refetch. The engine already queued the fetch.
     */
    fun applyResync(fileId: String, version: Long)
}
