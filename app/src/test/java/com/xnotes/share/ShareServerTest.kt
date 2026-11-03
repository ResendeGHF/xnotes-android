package com.xnotes.share

import java.io.File
import java.net.URL
import java.nio.file.Files
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShareServerTest {

    private var server: ShareServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private class FakeBackend(
        val tree: Map<String?, List<ShareServer.ShareBackend.Entry>>? = null,
    ) : ShareServer.ShareBackend {
        data class Doc(var pdf: ByteArray, var version: String)

        val docs = HashMap<String, Doc>()
        val monitorObj = Object()

        var canvasResult: ShareServer.ShareBackend.CanvasInfo? = null
        var pagePngResult: ShareServer.ShareBackend.PagePng? = null
        var strokesResult: ShareServer.ShareBackend.StrokesPage? = null
        var appendFn: (String, Int, String, List<ShareStrokes.WireStroke>) -> ShareServer.ReplaceResult =
            { _, _, _, _ -> ShareServer.ReplaceResult.Rejected(501, "x") }
        val appendedPosts = ArrayList<Triple<String, Int, List<ShareStrokes.WireStroke>>>()

        override fun canvasInfo(uri: String) = canvasResult
        override fun pagePng(uri: String, page: Int, dpi: Int) = pagePngResult
        override fun pageStrokes(uri: String, page: Int) = strokesResult
        override fun appendStrokes(uri: String, page: Int, baseVersion: String, strokes: List<ShareStrokes.WireStroke>) =
            appendFn(uri, page, baseVersion, strokes).also { appendedPosts.add(Triple(uri, page, strokes)) }

        override fun listEntries(dirUri: String?, onlyShared: Boolean): List<ShareServer.ShareBackend.Entry> =
            tree?.get(dirUri) ?: docs.map { (uri, d) ->
                ShareServer.ShareBackend.Entry(uri, uri.substringAfterLast('/'), d.pdf.size.toLong(), 1L, "note")
            }

        override fun versionOf(uri: String): String? = docs[uri]?.version

        override fun pdfBytes(uri: String): ShareServer.PdfSnapshot? {
            val d = docs[uri] ?: return null
            return ShareServer.PdfSnapshot(d.pdf.copyOf(), d.version, "$uri.pdf")
        }

        override fun rawBytes(uri: String): ShareServer.RawSnapshot? {
            val d = docs[uri] ?: return null
            return ShareServer.RawSnapshot(d.pdf.copyOf(), d.version, uri, "application/octet-stream")
        }

        override fun replacePdf(uri: String, bytes: ByteArray, baseVersion: String): ShareServer.ReplaceResult {
            val d = docs[uri] ?: return ShareServer.ReplaceResult.Rejected(404, "no such file")
            if (d.version != baseVersion) return ShareServer.ReplaceResult.Rejected(409, "stale base")
            d.pdf = bytes.copyOf()
            d.version = ShareProtocol.etagFor(bytes.size.toLong(), System.nanoTime())
            synchronized(monitorObj) { (monitorObj as Object).notifyAll() }
            return ShareServer.ReplaceResult.Ok(d.version)
        }

        override fun monitor(): Any = monitorObj
    }

    private data class Fixture(
        val base: String,
        val token: String,
        val backend: FakeBackend,
        val identity: ShareCrypto.ServerIdentity,
    )

    private fun fixture(): Fixture {
        val dir = Files.createTempDirectory("share-srv").toFile()
        val identity = ShareCrypto.loadOrCreate(dir)
        val backend = FakeBackend()
        backend.docs["notes/a"] = FakeBackend.Doc("PDFDATA-0123456789".toByteArray(), "\"1-1\"")
        val locks = ShareLockManager(leaseTtlMs = 60_000L)
        server = ShareServer(0, ShareCrypto.serverSslContext(identity), backend, locks, identity.token)
        return Fixture("https://127.0.0.1:${server!!.boundPort}", identity.token, backend, identity)
    }

    private fun headersOf(conn: HttpsURLConnection): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        for ((k, v) in conn.headerFields) if (k != null) out[k] = v
        return out
    }

    private fun get(f: Fixture, path: String, token: String? = f.token, headers: Map<String, String> = emptyMap()): Triple<Int, Map<String, List<String>>, ByteArray> {
        val conn = URL(f.base + path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ShareCrypto.pinningSslContext(f.identity.fingerprintHex).socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val code = conn.responseCode
        val body = try {
            conn.inputStream.readBytes()
        } catch (_: Exception) {
            conn.errorStream?.readBytes() ?: ByteArray(0)
        }
        return Triple(code, headersOf(conn), body)
    }

    private fun put(f: Fixture, path: String, body: ByteArray, lease: String, token: String? = f.token): Triple<Int, Map<String, List<String>>, ByteArray> {
        val conn = URL(f.base + path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ShareCrypto.pinningSslContext(f.identity.fingerprintHex).socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty(ShareProtocol.HEADER_LEASE, lease)
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val resp = try {
            conn.inputStream.readBytes()
        } catch (_: Exception) {
            conn.errorStream?.readBytes() ?: ByteArray(0)
        }
        return Triple(code, headersOf(conn), resp)
    }

    private fun post(f: Fixture, path: String, body: String = ""): Triple<Int, Map<String, List<String>>, ByteArray> {
        val conn = URL(f.base + path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ShareCrypto.pinningSslContext(f.identity.fingerprintHex).socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        val bytes = body.toByteArray()
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.setRequestProperty("Authorization", "Bearer ${f.token}")
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        val resp = try {
            conn.inputStream.readBytes()
        } catch (_: Exception) {
            conn.errorStream?.readBytes() ?: ByteArray(0)
        }
        return Triple(code, headersOf(conn), resp)
    }

    private fun delete(f: Fixture, path: String): Int {
        val conn = URL(f.base + path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ShareCrypto.pinningSslContext(f.identity.fingerprintHex).socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.requestMethod = "DELETE"
        conn.setRequestProperty("Authorization", "Bearer ${f.token}")
        return conn.responseCode
    }

    @Test
    fun canvasAndStrokesRoundTrip() {
        val f = fixture()
        f.backend.canvasResult = ShareServer.ShareBackend.CanvasInfo("\"1-1\"", "Note", 160, listOf(ShareServer.ShareBackend.PageSpec(595.0, 842.0)))
        f.backend.strokesResult = ShareServer.ShareBackend.StrokesPage(
            listOf(ShareStrokes.WireStroke("pen", "#ff0000", 2.5, listOf(10.0, 20.0, 30.0, 40.0))), "\"1-1\"",
        )
        f.backend.pagePngResult = ShareServer.ShareBackend.PagePng(byteArrayOf(1, 2, 3), "\"1-1\"", 0, 110)
        val (_, _, canvas) = get(f, "/api/canvas?uri=notes/a")
        assertTrue(canvas.toString(Charsets.UTF_8).contains("\"dpi\":160"))
        val (pcode, pheaders, _) = get(f, "/api/page?uri=notes/a&page=0&dpi=110")
        assertEquals(200, pcode)
        assertTrue((pheaders["Content-Type"]?.firstOrNull() ?: "").startsWith("image/png"))
        val (_, _, fresh) = get(f, "/api/strokes?uri=notes/a&page=0&since=%22old%22")
        val freshBody = fresh.toString(Charsets.UTF_8)
        assertTrue(freshBody.contains("\"changed\":true"))
        assertTrue(freshBody.contains("#ff0000"))
        val (_, _, same) = get(f, "/api/strokes?uri=notes/a&page=0&since=%221-1%22")
        val sameBody = same.toString(Charsets.UTF_8)
        assertTrue(sameBody.contains("\"changed\":false"))
        assertTrue(!sameBody.contains("#ff0000"))

        val (_, _, lockBody) = post(f, "/api/lock?uri=notes/a&holder=laptop")
        val lease = Regex("\"lease\":\"([^\"]+)\"").find(lockBody.toString(Charsets.UTF_8))!!.groupValues[1]
        f.backend.appendFn = { _, _, base, _ ->
            if (base == "\"1-1\"") ShareServer.ReplaceResult.Ok("\"2-2\"")
            else ShareServer.ReplaceResult.Rejected(409, "stale base")
        }
        val strokeBody = "{\"base\":\"\\\"1-1\\\"\",\"strokes\":[{\"tool\":\"pen\",\"color\":\"#00ff00\",\"width\":3.0,\"pts\":[1,2,3,4]}]}"
        val (ok, _, okBody) = postBytes(f, "/api/strokes?uri=notes/a&page=0", strokeBody.toByteArray(), lease)
        assertEquals(200, ok)
        assertTrue(okBody.toString(Charsets.UTF_8).contains("2-2"))
        assertEquals(1, f.backend.appendedPosts.size)
        assertEquals("#00ff00", f.backend.appendedPosts[0].third[0].color)
        val (bad, _, _) = postBytes(f, "/api/strokes?uri=notes/a&page=0", "not json".toByteArray(), lease)
        assertEquals(400, bad)
        val (stale, _, _) = postBytes(
            f, "/api/strokes?uri=notes/a&page=0",
            "{\"base\":\"\\\"old\\\"\",\"strokes\":[{\"tool\":\"pen\",\"color\":\"#00ff00\",\"width\":3.0,\"pts\":[1,2,3,4]}]}".toByteArray(), lease,
        )
        assertEquals(409, stale)
        val (nolease, _, _) = postBytes(f, "/api/strokes?uri=notes/a&page=0", strokeBody.toByteArray(), "bogus")
        assertEquals(423, nolease)
    }

    private fun postBytes(f: Fixture, path: String, body: ByteArray, lease: String): Triple<Int, Map<String, List<String>>, ByteArray> {
        val conn = URL(f.base + path).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ShareCrypto.pinningSslContext(f.identity.fingerprintHex).socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Authorization", "Bearer ${f.token}")
        conn.setRequestProperty(ShareProtocol.HEADER_LEASE, lease)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val resp = try {
            conn.inputStream.readBytes()
        } catch (_: Exception) {
            conn.errorStream?.readBytes() ?: ByteArray(0)
        }
        return Triple(code, headersOf(conn), resp)
    }

    @Test
    fun filesNavigatesIntoSubdirectories() {
        val dir = Files.createTempDirectory("share-tree").toFile()
        val identity = ShareCrypto.loadOrCreate(dir)
        val E = { uri: String, name: String, kind: String, isDir: Boolean ->
            ShareServer.ShareBackend.Entry(uri, name, 10L, 1L, kind, isDir)
        }
        val backend = FakeBackend(
            mapOf(
                null to listOf(E("d1", "Sub", "dir", true), E("notes/a", "a", "note", false)),
                "d1" to listOf(E("notes/b", "b", "pdf", false)),
            ),
        )
        val locks = ShareLockManager(leaseTtlMs = 60_000L)
        ShareServer(0, ShareCrypto.serverSslContext(identity), backend, locks, identity.token).use { srv ->
            val f = Fixture("https://127.0.0.1:${srv.boundPort}", identity.token, backend, identity)
            val (_, _, root) = get(f, "/api/files")
            val rootBody = root.toString(Charsets.UTF_8)
            assertTrue(rootBody.contains("\"dir\":true"))
            assertTrue(rootBody.contains("\"name\":\"Sub\""))
            val (_, _, sub) = get(f, "/api/files?uri=d1")
            val subBody = sub.toString(Charsets.UTF_8)
            assertTrue(subBody.contains("\"name\":\"b\""))
            assertTrue(subBody.contains("\"dir\":\"d1\""))
        }
    }

    @Test
    fun filesReportsMissingRoot() {
        val dir = Files.createTempDirectory("share-noroot").toFile()
        val identity = ShareCrypto.loadOrCreate(dir)
        val backend = FakeBackend()
        val noRoot =
            object : ShareServer.ShareBackend by backend {
                override fun browseRootSet(): Boolean = false
            }
        val locks = ShareLockManager(leaseTtlMs = 60_000L)
        ShareServer(0, ShareCrypto.serverSslContext(identity), noRoot, locks, identity.token).use { srv ->
            val f = Fixture("https://127.0.0.1:${srv.boundPort}", identity.token, backend, identity)
            val (code, _, body) = get(f, "/api/files")
            assertEquals(200, code)
            assertTrue(body.toString(Charsets.UTF_8).contains("\"root\":false"))
        }
    }

    @Test
    fun rejectsUnauthenticatedAndUnknownPaths() {
        val f = fixture()
        assertEquals(401, get(f, "/api/status", token = null).first)
        assertEquals(401, get(f, "/api/status", token = "wrong").first)
        assertEquals(200, get(f, "/api/status").first)
        assertEquals(404, get(f, "/nope").first)
    }

    @Test
    fun filesVersionAndPdfRoundTrip() {
        val f = fixture()
        val (code, _, body) = get(f, "/api/files")
        assertEquals(200, code)
        assertTrue(body.toString(Charsets.UTF_8).contains("notes/a"))

        val (_, _, vbody) = get(f, "/api/version?uri=notes/a")
        assertTrue(vbody.toString(Charsets.UTF_8).contains("1-1"))

        val (pcode, pheaders, pbody) = get(f, "/api/pdf?uri=notes/a")
        assertEquals(200, pcode)
        assertEquals("PDFDATA-0123456789", pbody.toString(Charsets.UTF_8))
        val etag = pheaders["ETag"]?.firstOrNull() ?: error("missing ETag")

        val (ncode, _, _) = get(f, "/api/pdf?uri=notes/a", headers = mapOf("If-None-Match" to etag))
        assertEquals(304, ncode)

        val (rcode, rheaders, rbody) = get(f, "/api/pdf?uri=notes/a", headers = mapOf("Range" to "bytes=0-6"))
        assertEquals(206, rcode)
        assertEquals("PDFDATA", rbody.toString(Charsets.UTF_8))
        assertTrue((rheaders["Content-Range"]?.firstOrNull() ?: "").startsWith("bytes 0-6/"))

        assertEquals(404, get(f, "/api/pdf?uri=notes/missing").first)
    }

    @Test
    fun largePutRoundTrip() {
        val f = fixture()
        val (_, _, lockBody) = post(f, "/api/lock?uri=notes/a&holder=laptop")
        val lease = Regex("\"lease\":\"([^\"]+)\"").find(lockBody.toString(Charsets.UTF_8))!!.groupValues[1]
        // A real annotated PDF is megabytes; the server must drain it whole before routing.
        val big = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        big[0] = '%'.code.toByte()
        big[1] = 'P'.code.toByte()
        big[2] = 'D'.code.toByte()
        big[3] = 'F'.code.toByte()
        val (ok, _, okBody) = put(f, "/api/pdf?uri=notes/a&base=%221-1%22", big, lease)
        assertEquals(200, ok)
        assertTrue(okBody.toString(Charsets.UTF_8).contains("version"))
        val (_, _, pdf) = get(f, "/api/pdf?uri=notes/a")
        assertEquals(big.size, pdf.size)
        assertTrue(pdf.contentEquals(big))
    }

    @Test
    fun lockLifecycleAndPut() {
        val f = fixture()
        // PUT without a lease is rejected.
        assertEquals(423, put(f, "/api/pdf?uri=notes/a&base=%221-1%22", "NEW".toByteArray(), lease = "bogus").first)

        val (_, _, lockBody) = post(f, "/api/lock?uri=notes/a&holder=laptop")
        val lease = Regex("\"lease\":\"([^\"]+)\"").find(lockBody.toString(Charsets.UTF_8))!!.groupValues[1]

        // Second holder is locked out.
        val (conflict, _, conflictBody) = post(f, "/api/lock?uri=notes/a&holder=other")
        assertEquals(423, conflict)
        assertTrue(conflictBody.toString(Charsets.UTF_8).contains("laptop"))

        // Stale base is rejected; fresh base commits.
        assertEquals(409, put(f, "/api/pdf?uri=notes/a&base=%22stale%22", "NEW".toByteArray(), lease).first)
        val (ok, _, okBody) = put(f, "/api/pdf?uri=notes/a&base=%221-1%22", "NEWDATA".toByteArray(), lease)
        assertEquals(200, ok)
        assertTrue(okBody.toString(Charsets.UTF_8).contains("version"))

        val (_, _, pdf) = get(f, "/api/pdf?uri=notes/a")
        assertEquals("NEWDATA", pdf.toString(Charsets.UTF_8))

        assertEquals(200, delete(f, "/api/lock?lease=$lease&holder=laptop"))
    }

    @Test
    fun watchReportsCommittedVersionChange() {
        val f = fixture()
        // Stale `since` answers immediately.
        val (code, _, body) = get(f, "/api/watch?uri=notes/a&since=%22old%22")
        assertEquals(200, code)
        assertTrue(body.toString(Charsets.UTF_8).contains("\"changed\":true"))

        // Current `since` long-polls; a commit wakes it promptly.
        var watchResult = ""
        val t = Thread {
            val (_, _, b) = get(f, "/api/watch?uri=notes/a&since=%221-1%22")
            watchResult = b.toString(Charsets.UTF_8)
        }
        t.isDaemon = true
        t.start()
        Thread.sleep(500)
        synchronized(f.backend.monitorObj) {
            f.backend.docs["notes/a"]!!.version = "\"2-2\""
            (f.backend.monitorObj as Object).notifyAll()
        }
        t.join(10_000)
        assertTrue(watchResult.contains("\"changed\":true"))
        assertTrue(watchResult.contains("2-2"))
    }

    @Test
    fun wrongFingerprintFailsHandshake() {
        val f = fixture()
        val otherDir: File = Files.createTempDirectory("share-evil").toFile()
        val other = ShareCrypto.loadOrCreate(otherDir)
        val conn = URL(f.base + "/api/status").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = ShareCrypto.pinningSslContext(other.fingerprintHex).socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Bearer ${f.token}")
        try {
            conn.responseCode
            fail("expected handshake failure")
        } catch (e: Exception) {
            assertTrue(e is javax.net.ssl.SSLHandshakeException || e.cause is javax.net.ssl.SSLHandshakeException)
        }
    }
}
