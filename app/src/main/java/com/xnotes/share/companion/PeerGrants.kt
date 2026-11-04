package com.xnotes.share.companion

import com.xnotes.share.db.GrantLookup
import com.xnotes.share.db.GrantSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The sync engine's view of one Android peer's grants: the files/folders this
 * device shares with the peer [fp].
 *
 * The Linux-companion path keeps using the [com.xnotes.share.db.DeviceGrants]
 * active-companion singleton; every inbound Android peer gets its own
 * [PeerGrants] so several friends can hold different grants (and edit
 * different files) at the same time. Reads are a volatile snapshot, safe on
 * any thread; [refresh] reloads it from Room after connects and policy edits.
 */
class PeerGrants(
    private val store: GrantLookup,
    val fp: String,
) : GrantSource {
    @Volatile private var snap: Set<String> = emptySet()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Synchronous gate for the hot paths (op/live/fetch guards). */
    override fun isShared(uri: String): Boolean = uri in snap

    override fun sharedUris(): Set<String> = snap

    override fun setGranted(uri: String, granted: Boolean) {
        io.launch {
            runCatching { store.setGranted(fp, uri, granted) }
            refresh()
        }
    }

    /** Reload the snapshot; called on connect and after policy changes. */
    suspend fun refresh() {
        snap = runCatching { store.grantIds(fp).toSet() }.getOrDefault(emptySet())
    }

    fun refreshAsync() {
        io.launch { refresh() }
    }
}
