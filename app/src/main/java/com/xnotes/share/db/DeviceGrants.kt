package com.xnotes.share.db

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The sync engine's view of sharing: which file/folder ids the ACTIVE
 * companion may see. Reads are a volatile in-memory snapshot (safe on any
 * thread, including the main-thread hot paths); every mutation also
 * persists through the store on IO.
 *
 * Process singleton like the old SharePolicy.of: the policy screen, the
 * host engine and the backend share one instance.
 */
class DeviceGrants internal constructor(private val store: GrantLookup) : GrantSource {
    /** Fingerprint of the connected companion; null while disconnected. */
    @Volatile var activeFp: String? = null

    @Volatile private var snap: Set<String> = emptySet()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Synchronous gate for the hot paths (op/live/fetch guards). */
    override fun isShared(uri: String): Boolean = uri in snap

    /** Synchronous snapshot, for seeding coverage before the first walk lands. */
    override fun sharedUris(): Set<String> = snap

    /** Grant or revoke for the active companion. */
    override fun setGranted(uri: String, granted: Boolean) = setGranted(activeFp, uri, granted)

    /** Blocking reload for threads that already hold IO. */
    suspend fun refreshBlocking(fp: String? = activeFp) {
        snap = if (fp == null) emptySet() else store.grantIds(fp).toSet()
    }

    /** Reload in the background (service start, active switch, external edits). */
    fun refresh(fp: String? = activeFp) {
        io.launch { refreshBlocking(fp) }
    }

    /** Synchronous refresh for callers that already hold a background thread. */
    fun refreshNow(fp: String? = activeFp) {
        kotlinx.coroutines.runBlocking { refreshBlocking(fp) }
    }

    /** Grant or revoke for one companion; snapshot updates at once when it is the active one. */
    fun setGranted(fp: String?, uri: String, granted: Boolean) {
        if (fp != null && fp == activeFp) {
            snap = if (granted) snap + uri else snap - uri
        }
        io.launch {
            if (fp == null) return@launch
            store.setGranted(fp, uri, granted)
        }
    }

    companion object {
        @Volatile private var instance: DeviceGrants? = null

        fun of(context: Context): DeviceGrants = instance ?: synchronized(this) {
            instance ?: DeviceGrants(ShareStore.of(context)).also {
                instance = it
                it.refresh(null)
            }
        }

        /** Test seam. */
        internal fun resetForTests() {
            instance = null
            ShareStore.resetForTests()
        }
    }
}
