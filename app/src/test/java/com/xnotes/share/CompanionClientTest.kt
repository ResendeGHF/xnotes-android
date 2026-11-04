package com.xnotes.share.companion

import com.xnotes.share.ShareCrypto
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback tests for the companion client handshake (SYNC.md v1). A minimal
 * TLS server stands in for the Linux companion: it presents its own identity,
 * requires no client cert, and speaks the hello / pair_request envelope.
 * Plain threads + latches, no Robolectric.
 */
class CompanionClientTest {
    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }

    /** Server identity + a listening TLS socket on 127.0.0.1:ephemeral. */
    private fun loopbackServer(): Triple<ShareCrypto.ServerIdentity, SSLServerSocket, Int> {
        val id = ShareCrypto.loadOrCreate(tempDir("srv"))
        val ctx = ShareCrypto.serverSslContext(id)
        val ss = ctx.serverSocketFactory.createServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        return Triple(id, ss, ss.localPort)
    }

    private fun readMsg(sock: javax.net.ssl.SSLSocket, dec: Frames.Decoder, timeoutMs: Long): JSONObject? {
        // Blocking reads (short soTimeout): polling available() on a TLS
        // socket never decrypts the waiting record, so poll-reads starve.
        sock.soTimeout = 500
        val end = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(64 * 1024)
        val input = sock.getInputStream()
        while (System.currentTimeMillis() < end) {
            try {
                val n = input.read(buf)
                if (n < 0) return null
                val out = dec.feed(buf, 0, n)
                if (out.isNotEmpty()) return out.first()
            } catch (e: java.net.SocketTimeoutException) {
                // No full frame yet; keep waiting until the overall timeout.
            }
        }
        return null
    }

    @Test fun helloAckPairRoundTripWithPinning() {
        val (serverId, ss, port) = loopbackServer()
        val clientId = ShareCrypto.loadOrCreate(tempDir("cli"))
        val serverDone = CountDownLatch(1)
        val serverError = AtomicReference<String>()
        val serverThread = Thread({
            try {
                val sock = ss.accept() as javax.net.ssl.SSLSocket
                sock.startHandshake()
                                val output = sock.getOutputStream()
                val dec = Frames.Decoder()
                // hello
                val hello = readMsg(sock, dec, 10_000)
                check(hello != null && hello.getString("t") == "hello") { "no hello" }
                check(hello.getString("app") == "xnotes-android") { "app" }
                check(hello.getInt("version") == 1) { "version" }
                check(hello.getString("device_name").isNotEmpty()) { "device_name" }
                check(hello.getString("host_cert_fp") == clientId.fingerprintHex) { "host_cert_fp" }
                // hello_ack: unpaired, code required
                output.write(
                    Frames.encode(
                        JSONObject().put("t", "hello_ack")
                            .put("seq", 1).put("in_reply_to", hello.optInt("seq", 0))
                            .put("companion_cert_fp", serverId.fingerprintHex)
                            .put("paired", false).put("code_required", true),
                    ),
                )
                output.flush()
                // pair_request
                val req = readMsg(sock, dec, 10_000)
                check(req != null && req.getString("t") == "pair_request") { "no pair_request" }
                check(req.getString("code").length == 6) { "code" }
                check(req.getString("host_cert_fp") == clientId.fingerprintHex) { "req fp" }
                output.write(
                    Frames.encode(
                        JSONObject().put("t", "pair_result").put("seq", 2).put("ok", true),
                    ),
                )
                output.flush()
                serverDone.countDown()
                // Hold the connection briefly so the client reads pair_result.
                Thread.sleep(1_000)
                sock.close()
            } catch (e: Exception) {
                serverError.set(e.message ?: e.javaClass.simpleName)
                serverDone.countDown()
            } finally {
                runCatching { ss.close() }
            }
        }, "test-companion-server")
        serverThread.isDaemon = true
        serverThread.start()

        val pinsDir = tempDir("pins")
        val pins = CompanionPins.of(pinsDir)
        val events = mutableListOf<String>()
        val paired = CountDownLatch(1)
        lateinit var pairing: PairingClient
        val ui = object : PairingUi {
            override fun showCodeEntry() {
                synchronized(events) { events += "code" }
                // Simulate the user typing the displayed code.
                Thread({ pairing.submitCode("654321") }, "test-type-code").start()
            }
            override fun showStatus(s: String) {
                synchronized(events) { events += "status:$s" }
            }
            override fun pairingDone(name: String) {
                synchronized(events) { events += "done:$name" }
                paired.countDown()
            }
            override fun pairingFailed(reason: String) {
                synchronized(events) { events += "failed:$reason" }
            }
        }
        val client = CompanionClient(
            host = "127.0.0.1",
            port = port,
            identity = clientId,
            pinnedFp = serverId.fingerprintHex, // correct pin
        )
        pairing = PairingClient("TestDevice", clientId.fingerprintHex, pins, ui, client::send)
        val errors = mutableListOf<String>()
        client.onConnected = { pairing.onConnected(it) }
        client.onMessage = { pairing.onMessage(it) }
        client.onError = { synchronized(errors) { errors += it } }
        client.connect()
        try {
            assertTrue("server finished", serverDone.await(15, TimeUnit.SECONDS))
            assertEquals(null, serverError.get())
            assertTrue("client paired", paired.await(15, TimeUnit.SECONDS))
            synchronized(events) {
                assertTrue("code entry shown", events.contains("code"))
                assertTrue("done fired", events.any { it == "done:companion" })
            }
            // pair_result ok pinned the live fingerprint under the default name.
            assertEquals(serverId.fingerprintHex, pins.fpFor("companion"))
        } finally {
            pairing.close()
            client.disconnect()
        }
    }

    @Test fun fullPairFlowViaSubmitCode() {
        val (serverId, ss, port) = loopbackServer()
        val clientId = ShareCrypto.loadOrCreate(tempDir("cli2"))
        val serverGotCode = AtomicReference<String>()
        val serverDone = CountDownLatch(1)
        val serverThread = Thread({
            try {
                val sock = ss.accept() as javax.net.ssl.SSLSocket
                sock.startHandshake()
                                val output = sock.getOutputStream()
                val dec = Frames.Decoder()
                val hello = readMsg(sock, dec, 10_000)!!
                output.write(
                    Frames.encode(
                        JSONObject().put("t", "hello_ack")
                            .put("seq", 1).put("in_reply_to", hello.optInt("seq", 0))
                            .put("companion_cert_fp", serverId.fingerprintHex)
                            .put("paired", false).put("code_required", true),
                    ),
                )
                output.flush()
                val req = readMsg(sock, dec, 10_000)!!
                check(req.getString("t") == "pair_request")
                serverGotCode.set(req.getString("code"))
                output.write(
                    Frames.encode(
                        JSONObject().put("t", "pair_result").put("seq", 2)
                            .put("ok", true).put("companion_name", "testbox"),
                    ),
                )
                output.flush()
                serverDone.countDown()
                Thread.sleep(1_000)
                sock.close()
            } catch (_: Exception) {
                serverDone.countDown()
            } finally {
                runCatching { ss.close() }
            }
        }, "test-companion-server2")
        serverThread.isDaemon = true
        serverThread.start()

        val pins = CompanionPins.of(tempDir("pins2"))
        val done = CountDownLatch(1)
        var doneName: String? = null
        val codeShown = CountDownLatch(1)
        val ui = object : PairingUi {
            override fun showCodeEntry() = codeShown.countDown()
            override fun showStatus(s: String) = Unit
            override fun pairingDone(name: String) {
                doneName = name
                done.countDown()
            }
            override fun pairingFailed(reason: String) = Unit
        }
        val client = CompanionClient("127.0.0.1", port, clientId, serverId.fingerprintHex)
        val pairing = PairingClient("Pixel", clientId.fingerprintHex, pins, ui, client::send)
        client.onConnected = { pairing.onConnected(it) }
        client.onMessage = { pairing.onMessage(it) }
        client.connect()
        try {
            assertTrue("code entry", codeShown.await(15, TimeUnit.SECONDS))
            pairing.submitCode("123456")
            assertTrue("paired", done.await(15, TimeUnit.SECONDS))
            assertTrue("server done", serverDone.await(15, TimeUnit.SECONDS))
            assertEquals("123456", serverGotCode.get())
            assertEquals("testbox", doneName)
            assertEquals(serverId.fingerprintHex, pins.fpFor("testbox"))
            assertNotNull(client.peerFingerprint())
        } finally {
            pairing.close()
            client.disconnect()
        }
    }

    @Test fun wrongPinFailsHandshake() {
        val (_, ss, port) = loopbackServer()
        val ssThread = Thread({
            try {
                // Accept then close: the client must fail before any message.
                val sock = ss.accept()
                Thread.sleep(2_000)
                runCatching { sock.close() }
            } catch (_: Exception) {
            } finally {
                runCatching { ss.close() }
            }
        }, "test-server-wrongpin")
        ssThread.isDaemon = true
        ssThread.start()

        val clientId = ShareCrypto.loadOrCreate(tempDir("cli3"))
        val failed = CountDownLatch(1)
        val client = CompanionClient(
            host = "127.0.0.1",
            port = port,
            identity = clientId,
            pinnedFp = "00".repeat(32), // wrong pin: handshake must abort
            autoReconnect = false,
        )
        client.onError = { failed.countDown() }
        client.onDisconnected = { failed.countDown() }
        client.connect()
        try {
            assertTrue("handshake rejected", failed.await(15, TimeUnit.SECONDS))
        } finally {
            client.disconnect()
        }
    }

    @Test fun reconnectsAfterDrop() {
        val (serverId, ss, port) = loopbackServer()
        val drops = CountDownLatch(1)
        val serverThread = Thread({
            try {
                repeat(2) {
                    val sock = ss.accept() as javax.net.ssl.SSLSocket
                    sock.startHandshake()
                    val output = sock.getOutputStream()
                    val dec = Frames.Decoder()
                    val hello = readMsg(sock, dec, 10_000)!!
                    output.write(
                        Frames.encode(
                            JSONObject().put("t", "hello_ack")
                                .put("seq", 1).put("in_reply_to", hello.optInt("seq", 0))
                                .put("companion_cert_fp", serverId.fingerprintHex)
                                .put("paired", true).put("code_required", false),
                        ),
                    )
                    output.flush()
                    if (it == 0) {
                        // Drop the first connection; the client must redial.
                        Thread.sleep(300)
                        sock.close()
                    } else {
                        drops.countDown()
                        Thread.sleep(1_000)
                        sock.close()
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { ss.close() }
            }
        }, "test-server-redial")
        serverThread.isDaemon = true
        serverThread.start()

        val pins = CompanionPins.of(tempDir("pins3"))
        pins.pin("testbox", serverId.fingerprintHex)
        val connects = java.util.concurrent.atomic.AtomicInteger(0)
        val ui = object : PairingUi {
            override fun showCodeEntry() = Unit
            override fun showStatus(s: String) = Unit
            override fun pairingDone(name: String) = Unit
            override fun pairingFailed(reason: String) = Unit
        }
        val client = CompanionClient("127.0.0.1", port, ShareCrypto.loadOrCreate(tempDir("cli4")), serverId.fingerprintHex)
        val pairing = PairingClient("Pixel", "fp", pins, ui, client::send)
        client.onConnected = {
            connects.incrementAndGet()
            pairing.onConnected(it)
        }
        client.onMessage = { pairing.onMessage(it) }
        client.connect()
        try {
            assertTrue("second hello answered", drops.await(25, TimeUnit.SECONDS))
            assertTrue("reconnected", connects.get() >= 2)
        } finally {
            pairing.close()
            client.disconnect()
        }
    }
}
