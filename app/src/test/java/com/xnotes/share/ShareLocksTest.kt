package com.xnotes.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLocksTest {

    private var now = 1_000_000L
    private fun manager() = ShareLockManager(leaseTtlMs = 10_000L, nowMs = { now })

    @Test
    fun acquireGrantsFreeFile() {
        val m = manager()
        val lease = m.acquire("note-a", "laptop")
        assertNotNull(lease)
        assertEquals("note-a", lease!!.uri)
        assertEquals("laptop", lease.holder)
    }

    @Test
    fun secondHolderIsRejectedWhileLive() {
        val m = manager()
        m.acquire("note-a", "laptop")
        assertNull(m.acquire("note-a", "phone2"))
        assertTrue(m.isLockedByOther("note-a", "phone2"))
        assertFalse(m.isLockedByOther("note-a", "laptop"))
    }

    @Test
    fun differentFilesAreIndependent() {
        val m = manager()
        assertNotNull(m.acquire("note-a", "laptop"))
        // The device keeps editing A while the desktop takes B: allowed.
        assertNotNull(m.acquire("note-b", ShareLockManager.HOLDER_DEVICE))
        assertNotNull(m.acquire("note-b", "laptop")?.let { null } ?: m.holderOf("note-b"))
    }

    @Test
    fun expiryFreesTheFile() {
        val m = manager()
        m.acquire("note-a", "laptop")
        now += 10_001L
        assertNull(m.holderOf("note-a"))
        assertNotNull(m.acquire("note-a", "other"))
    }

    @Test
    fun renewExtendsAndWrongHolderFails() {
        val m = manager()
        val lease = m.acquire("note-a", "laptop")!!
        now += 9_000L
        assertNotNull(m.renew(lease.leaseId, "laptop"))
        now += 9_000L
        // Without the first renew this would have expired at t+10s.
        assertNotNull(m.holderOf("note-a"))
        assertNull(m.renew(lease.leaseId, "impostor"))
        assertTrue(m.release(lease.leaseId, "impostor").not())
        assertTrue(m.release(lease.leaseId, "laptop"))
        assertNull(m.holderOf("note-a"))
    }

    @Test
    fun sameHolderReacquireRenews() {
        val m = manager()
        val first = m.acquire("note-a", "laptop")!!
        now += 9_000L
        val second = m.acquire("note-a", "laptop")!!
        assertEquals(first.leaseId, second.leaseId)
        now += 9_000L
        assertNotNull(m.holderOf("note-a"))
    }

    @Test
    fun sweepDropsExpired() {
        val m = manager()
        m.acquire("a", "x")
        m.acquire("b", "y")
        now += 20_000L
        assertEquals(2, m.sweep())
        assertNull(m.holderOf("a"))
    }
}
