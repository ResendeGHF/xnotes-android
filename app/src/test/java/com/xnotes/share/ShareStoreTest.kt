package com.xnotes.share

import com.xnotes.share.companion.CompanionPins
import com.xnotes.share.db.DeviceGrants
import com.xnotes.share.db.GrantLookup
import com.xnotes.share.db.migrationPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareStoreTest {
    private fun pin(name: String, suffix: String) =
        CompanionPins.Pin(name, suffix.padStart(64, 'a').takeLast(64))

    @Test fun emptyLegacyWorldNeedsNoMigration() {
        assertEquals(null, migrationPlan(emptyList(), emptySet(), "127.0.0.1", 42317))
    }

    @Test fun singlePinKeepsAddressAndBecomesActive() {
        val plan = migrationPlan(listOf(pin("Desktop", "1")), setOf("u1", "u2"), "192.168.1.5", 42317)!!
        assertEquals(1, plan.devices.size)
        val d = plan.devices.single()
        assertEquals("192.168.1.5", d.host)
        assertEquals(42317, d.port)
        assertEquals(d.fp, plan.makeActiveFp)
        assertEquals(
            setOf("u1", "u2"),
            plan.grants.filter { it.deviceFp == d.fp }.map { it.fileId }.toSet(),
        )
    }

    @Test fun multiPinDropsAmbiguousAddressButKeepsGrants() {
        val plan = migrationPlan(listOf(pin("A", "1"), pin("B", "2")), setOf("u9"), "192.168.1.5", 42317)!!
        assertEquals(2, plan.devices.size)
        assertTrue(plan.devices.all { it.host.isEmpty() && it.port == 0 })
        assertEquals(2, plan.grants.size)
        assertTrue(plan.grants.all { it.fileId == "u9" })
        assertEquals(plan.devices.first().fp, plan.makeActiveFp)
    }

    private class FakeGrants(val map: MutableMap<String, MutableSet<String>> = HashMap()) : GrantLookup {
        override suspend fun grantIds(fp: String): List<String> = map[fp]?.toList() ?: emptyList()
        override suspend fun setGranted(fp: String, uri: String, granted: Boolean) {
            val set = map.getOrPut(fp) { HashSet() }
            if (granted) set.add(uri) else set.remove(uri)
        }
    }

    @Test fun deviceGatesArePerCompanionAndSynchronous() {
        val fake = FakeGrants(mutableMapOf("fpA" to mutableSetOf("u1"), "fpB" to mutableSetOf("u2")))
        val gates = DeviceGrants(fake)
        runBlocking {
            gates.refreshBlocking("fpA")
            assertTrue(gates.isShared("u1"))
            assertFalse(gates.isShared("u2"))
            gates.refreshBlocking("fpB")
            assertFalse(gates.isShared("u1"))
            assertTrue(gates.isShared("u2"))
            gates.refreshBlocking(null)
            assertTrue(gates.sharedUris().isEmpty())
        }
    }

    @Test fun setGrantedFlipsTheActiveSnapshotAtOnce() {
        val fake = FakeGrants(mutableMapOf("fpA" to mutableSetOf()))
        val gates = DeviceGrants(fake)
        gates.activeFp = "fpA"
        runBlocking {
            gates.refreshBlocking()
            assertFalse(gates.isShared("u7"))
            // Another device's toggle must not leak into the active snapshot.
            fake.setGranted("fpB", "u7", true)
            gates.setGranted("fpB", "u7", true)
            assertFalse(gates.isShared("u7"))
            gates.setGranted("fpA", "u7", true)
            assertTrue(gates.isShared("u7"))
            gates.setGranted("fpA", "u7", false)
            assertFalse(gates.isShared("u7"))
        }
    }
}
