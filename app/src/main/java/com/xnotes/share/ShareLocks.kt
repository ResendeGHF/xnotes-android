package com.xnotes.share

import java.util.UUID

/**
 * Per-file exclusive edit leases for the LAN share server.
 *
 * Each shared file (`uri`) can be edited by at most one party at a time: the
 * device itself (`HOLDER_DEVICE`) or one remote client. Different files are
 * independent — editing note A on the desktop never blocks note B on the
 * device. Reads never need a lease.
 *
 * A lease expires after [leaseTtlMs] unless renewed via [renew]; expiry (not
 * explicit release) is what recovers from a client that disconnected without
 * unlocking, e.g. a laptop lid closed on a university network. All methods
 * are thread-safe and pure JVM (no Android dependency) so they unit test on
 * the host.
 */
class ShareLockManager(
    /** Lease lifetime; renewed by heartbeats while the editor stays open. */
    val leaseTtlMs: Long = 30_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The holder id the device itself uses when the open note is being edited locally. */
    companion object {
        const val HOLDER_DEVICE = "device"
    }

    /** A live lease on one file. */
    data class Lease(
        val leaseId: String,
        val uri: String,
        val holder: String,
        val expiresAtMs: Long,
    ) {
        fun isLive(now: Long): Boolean = now < expiresAtMs
    }

    private val byUri = HashMap<String, Lease>()
    private val byLeaseId = HashMap<String, Lease>()

    /**
     * Take (or, for the same [holder], renew) the edit lease on [uri].
     * Returns the lease, or null when a *different* live holder owns it.
     */
    @Synchronized
    fun acquire(uri: String, holder: String): Lease? {
        val now = nowMs()
        val current = byUri[uri]
        if (current != null) {
            if (current.isLive(now)) {
                if (current.holder != holder) return null
                return renewLocked(current, now)
            }
            dropLocked(current)
        }
        val lease = Lease(UUID.randomUUID().toString(), uri, holder, now + leaseTtlMs)
        byUri[uri] = lease
        byLeaseId[lease.leaseId] = lease
        return lease
    }

    /** Extend [leaseId] when it is still live and held by [holder]. */
    @Synchronized
    fun renew(leaseId: String, holder: String): Lease? {
        val now = nowMs()
        val current = byLeaseId[leaseId] ?: return null
        if (!current.isLive(now) || current.holder != holder) {
            if (!current.isLive(now)) dropLocked(current)
            return null
        }
        return renewLocked(current, now)
    }

    /** Give up [leaseId] when held by [holder]; true when anything was released. */
    @Synchronized
    fun release(leaseId: String, holder: String): Boolean {
        val current = byLeaseId[leaseId] ?: return false
        if (current.holder != holder) return false
        dropLocked(current)
        return true
    }

    /** The live lease on [uri], or null when it is free (or expired, which frees it). */
    @Synchronized
    fun holderOf(uri: String): Lease? {
        val now = nowMs()
        val current = byUri[uri] ?: return null
        if (!current.isLive(now)) {
            dropLocked(current)
            return null
        }
        return current
    }

    /** True when [uri] is leased to someone other than [holder]. */
    @Synchronized
    fun isLockedByOther(uri: String, holder: String): Boolean {
        val current = holderOf(uri) ?: return false
        return current.holder != holder
    }

    /** Drop every expired lease. Returns how many were dropped. */
    @Synchronized
    fun sweep(): Int {
        val now = nowMs()
        val dead = byUri.values.filter { !it.isLive(now) }
        dead.forEach { dropLocked(it) }
        return dead.size
    }

    private fun renewLocked(current: Lease, now: Long): Lease {
        val renewed = current.copy(expiresAtMs = now + leaseTtlMs)
        byUri[renewed.uri] = renewed
        byLeaseId[renewed.leaseId] = renewed
        return renewed
    }

    private fun dropLocked(lease: Lease) {
        byLeaseId.remove(lease.leaseId)
        if (byUri[lease.uri]?.leaseId == lease.leaseId) byUri.remove(lease.uri)
    }
}
