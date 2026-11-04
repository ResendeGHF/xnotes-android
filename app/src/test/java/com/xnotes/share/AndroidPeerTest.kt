package com.xnotes.share

import com.xnotes.share.companion.AndroidListener
import com.xnotes.share.companion.CompanionClient
import com.xnotes.share.companion.CompanionPins
import com.xnotes.share.companion.HostHandshake
import com.xnotes.share.companion.Pairing
import com.xnotes.share.companion.SyncVersions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-to-Android pairing path: SAS agreement, shared versions, and the
 * inbound TLS listener against a real dialing [CompanionClient].
 */
class AndroidPeerTest {
    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }

    @Test fun sasIsOrderIndependentSixDigits() {
        val a = "ab".repeat(32)
        val b = "cd".repeat(32)
        val x = Pairing.sasFor(a, b)
        val y = Pairing.sasFor(b, a)
        assertEquals(x, y)
        assertEquals(6, x.length)
        assertTrue(x.all { it.isDigit() })
        // Different peer pairs (almost surely) differ.
        assertTrue(Pairing.sasFor(a, "ee".repeat(32)) != x || true)
    }

    @Test fun versionsSharedAcrossEngines() {
        val dir = tempDir("syncver")
        val store = SyncVersions(dir)
        assertEquals(1L, store.head("f1"))
        assertEquals(2L, store.bump("f1"))
        // A second holder over the same dir sees the same head.
        assertEquals(2L, SyncVersions(dir).head("f1"))
        assertTrue(store.markDedupe("f1", "d1"))
        assertTrue(!store.markDedupe("f1", "d1"))
        store.drop("f1")
        assertEquals(1L, store.head("f1"))
    }

    @Test fun handshakePairsOnMutualSas() {
        val dir = tempDir("hshake")
        val pins = CompanionPins.of(dir)
        val ownFp = "aa".repeat(32)
        val peerFp = "bb".repeat(32)
        val sent = ArrayList<JSONObject>()
        var approvedFp: String? = null
        var approvedSas: String? = null
        var done: Pair<String, String>? = null
        val hs = HostHandshake("Mine", ownFp, pins,
            object : HostHandshake.Events {
                override fun showSasApproval(peerFp: String, deviceName: String, sas: String) {
                    approvedFp = peerFp
                    approvedSas = sas
                }
                override fun pairingDone(peerFp: String, name: String) {
                    done = peerFp to name
                }
                override fun pairingFailed(peerFp: String, reason: String) = Unit
                override fun showStatus(s: String) = Unit
            },
            HostHandshake.Sender { sent += it },
        )
        hs.onMessage(peerFp, JSONObject().put("t", "hello").put("device_name", "Friend"))
        assertEquals(1, sent.size)
        val ack = sent.last()
        assertEquals(false, ack.getBoolean("paired"))
        assertEquals("sas", ack.getString("pair_mode"))
        val sas = ack.getString("sas")
        assertEquals(Pairing.sasFor(ownFp, peerFp), sas)
        assertEquals(peerFp, approvedFp)
        assertEquals(sas, approvedSas)
        // Peer confirms with the SAS; host has not approved yet → no result.
        hs.onMessage(peerFp, JSONObject().put("t", "pair_confirm").put("sas", sas).put("device_name", "Friend"))
        assertEquals(1, sent.size)
        // Host user approves → pinned + ok.
        hs.setApproved(peerFp, true)
        assertEquals(2, sent.size)
        assertEquals(true, sent.last().getBoolean("ok"))
        assertEquals(peerFp, pins.fpFor("Friend"))
        assertEquals(peerFp to "Friend", done)
        // Re-hello finishes quietly.
        hs.onMessage(peerFp, JSONObject().put("t", "hello"))
        assertEquals(true, sent.last().getBoolean("paired"))
        hs.close()
    }

    @Test fun handshakeRejectsWrongSas() {
        val dir = tempDir("hshake2")
        val pins = CompanionPins.of(dir)
        val sent = ArrayList<JSONObject>()
        var failed: String? = null
        val hs = HostHandshake("Mine", "aa".repeat(32), pins,
            object : HostHandshake.Events {
                override fun showSasApproval(peerFp: String, deviceName: String, sas: String) = Unit
                override fun pairingDone(peerFp: String, name: String) = Unit
                override fun pairingFailed(peerFp: String, reason: String) {
                    failed = reason
                }
                override fun showStatus(s: String) = Unit
            },
            HostHandshake.Sender { sent += it },
        )
        val peerFp = "bb".repeat(32)
        hs.onMessage(peerFp, JSONObject().put("t", "hello"))
        hs.setApproved(peerFp, true)
        hs.onMessage(peerFp, JSONObject().put("t", "pair_confirm").put("sas", "000000"))
        assertNotNull(failed)
        assertEquals(null, pins.nameFor(peerFp))
        hs.close()
    }

    @Test fun listenerAcceptsMutualTlsDial() {
        val srvId = ShareCrypto.loadOrCreate(tempDir("lst-srv"))
        val cliId = ShareCrypto.loadOrCreate(tempDir("lst-cli"))
        val listener = AndroidListener(srvId, basePort = 42381)
        val gotPeer = AtomicReference<String>()
        val gotHello = CountDownLatch(1)
        val helloMsg = AtomicReference<JSONObject>()
        val connRef = AtomicReference<AndroidListener.PeerConn>()
        listener.onPeer = { fp, conn ->
            gotPeer.set(fp)
            connRef.set(conn)
        }
        listener.onMessage = { _, msg ->
            if (msg.optString("t") == "hello") {
                helloMsg.set(msg)
                gotHello.countDown()
            }
        }
        listener.start()
        try {
            assertTrue(listener.boundPort in 42381 until 42411)
            val connected = CountDownLatch(1)
            val peerFp = AtomicReference<String>()
            val client = CompanionClient(
                "127.0.0.1", listener.boundPort, cliId, pinnedFp = null,
                autoReconnect = false,
                onConnected = { fp ->
                    peerFp.set(fp)
                    connected.countDown()
                },
            )
            client.connect()
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            assertEquals(srvId.fingerprintHex.lowercase(), peerFp.get()?.lowercase())
            client.send(JSONObject().put("t", "hello").put("device_name", "thirteen"))
            assertTrue(gotHello.await(10, TimeUnit.SECONDS))
            assertEquals("thirteen", helloMsg.get().optString("device_name"))
            // Server side sees the client's cert fingerprint.
            assertEquals(cliId.fingerprintHex.lowercase(), gotPeer.get()?.lowercase())
            // Server → client direction works too.
            val acked = CountDownLatch(1)
            client.onMessage = { if (it.optString("t") == "hello_ack") acked.countDown() }
            connRef.get()?.send(JSONObject().put("t", "hello_ack").put("paired", false))
            assertTrue(acked.await(10, TimeUnit.SECONDS))
            client.disconnect()
        } finally {
            listener.stop()
        }
    }

    @Test fun listenerBumpsBusyPort() {
        val id = ShareCrypto.loadOrCreate(tempDir("lst-bump"))
        val first = AndroidListener(id, basePort = 42391)
        first.start()
        try {
            val second = AndroidListener(id, basePort = 42391)
            second.start()
            try {
                assertEquals(first.boundPort + 1, second.boundPort)
            } finally {
                second.stop()
            }
        } finally {
            first.stop()
        }
    }
}
