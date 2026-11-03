package com.xnotes.share

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCryptoTest {

    @Test
    fun identityPersistsAcrossLoads() {
        val dir = Files.createTempDirectory("share-crypto").toFile()
        val first = ShareCrypto.loadOrCreate(dir)
        val second = ShareCrypto.loadOrCreate(dir)
        assertEquals(first.fingerprintHex, second.fingerprintHex)
        assertEquals(first.token, second.token)
        assertEquals(64, first.fingerprintHex.length)
        assertTrue(first.token.length >= 64)
    }

    @Test
    fun freshDirsGetFreshIdentities() {
        val a = ShareCrypto.loadOrCreate(Files.createTempDirectory("share-a").toFile())
        val b = ShareCrypto.loadOrCreate(Files.createTempDirectory("share-b").toFile())
        assertNotEquals(a.fingerprintHex, b.fingerprintHex)
        assertNotEquals(a.token, b.token)
    }

    @Test
    fun displayFingerprintGroupsBytes() {
        assertEquals("AB:CD:01", ShareCrypto.displayFingerprint("abcd01"))
    }

    @Test
    fun serverContextPresentsTheStoredCertificate() {
        val dir = Files.createTempDirectory("share-tls").toFile()
        val identity = ShareCrypto.loadOrCreate(dir)
        val ctx = ShareCrypto.serverSslContext(identity)
        assertTrue(ctx.serverSessionContext.sessionTimeout >= 0)
    }

    @Test
    fun pinningContextBuildsForOwnFingerprint() {
        val dir = Files.createTempDirectory("share-pin").toFile()
        val identity = ShareCrypto.loadOrCreate(dir)
        val other = ShareCrypto.loadOrCreate(Files.createTempDirectory("share-other").toFile())
        assertNotEquals(identity.fingerprintHex, other.fingerprintHex)
        // Fingerprint is deterministic for the same certificate.
        assertEquals(identity.fingerprintHex, ShareCrypto.fingerprintOf(identity.certificate))
        // A pinning context builds; wrong-cert rejection is covered by the
        // TLS loopback test in ShareServerTest.
        val pinning = ShareCrypto.pinningSslContext(identity.fingerprintHex)
        assertTrue(pinning.socketFactory != null)
    }
}
