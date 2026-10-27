package com.xnotes.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class PgpKeyStoreTest {

    private fun tempDir(): File = File.createTempFile("pgp", "dir").apply {
        delete()
        mkdirs()
    }

    @Test
    fun privateKeyExportStaysWrappedAndCanBeImported() {
        val sourceDir = tempDir()
        val targetDir = tempDir()
        try {
            val source = PgpKeyStore(sourceDir)
            val alice = source.generate("Alice <alice@example.com>", "alice-passphrase".toCharArray())
            val exported = source.exportSecret(alice.fingerprint)
            requireNotNull(exported)
            assertNull(source.exportSecret("0000000000000000"))
            val publicArmored = source.exportPublic(alice.fingerprint)

            val target = PgpKeyStore(targetDir)
            assertEquals(alice.fingerprint, target.importPublic(publicArmored.toByteArray()).fingerprint)
            assertNull(target.exportSecret(alice.fingerprint))
            val imported = target.importSecret(exported.toByteArray(), "alice-passphrase".toCharArray())
            assertEquals(alice.fingerprint, imported.fingerprint)
            assertEquals(alice.userId, imported.userId)
            assertThrows(PgpKeyStore.Failure::class.java) {
                target.importSecret(exported.toByteArray(), "wrong-passphrase".toCharArray())
            }
        } finally {
            sourceDir.deleteRecursively()
            targetDir.deleteRecursively()
        }
    }

    @Test
    fun signEncryptThenVerify() {
        val dir = File.createTempFile("pgp", "dir")
        dir.delete()
        dir.mkdirs()
        try {
            val store = PgpKeyStore(dir)
            val alice = store.generate("Alice <alice@example.com>", "alice-passphrase".toCharArray())
            val bob = store.generate("Bob <bob@example.com>", "bob-passphrase".toCharArray())
            val note = "sealed note".toByteArray()
            val message = store.encryptAndSign(note, "Note.xnote", bob.fingerprint, alice.fingerprint, "alice-passphrase".toCharArray())
            val opened = store.decryptAndVerify(message, bob.fingerprint, "bob-passphrase".toCharArray())
            assertArrayEquals(note, opened.plain)
            assertEquals("Note.xnote", opened.fileName)
            assertEquals(alice.fingerprint, opened.signerFingerprint)
            assertEquals("Alice <alice@example.com>", opened.signerUserId)

            // Flip a base64 character in the armored body (not the trailing CRC) so the
            // authenticated ciphertext itself changes.
            val armored = message.toString(Charsets.US_ASCII)
            val bodyStart = armored.indexOf("\n\n") + 2
            val bodyEnd = armored.lastIndexOf("\n=").let { if (it > bodyStart) it else armored.length }
            val midpoint = bodyStart + (bodyEnd - bodyStart) / 2
            val swapped = armored.toCharArray()
            val at = (midpoint until bodyEnd).first { armored[it].isLetterOrDigit() }
            swapped[at] = if (swapped[at] == 'A') 'B' else 'A'
            assertThrows(PgpKeyStore.Failure::class.java) {
                store.decryptAndVerify(String(swapped).toByteArray(Charsets.US_ASCII), bob.fingerprint, "bob-passphrase".toCharArray())
            }
            assertThrows(PgpKeyStore.Failure::class.java) {
                store.decryptAndVerify(message, bob.fingerprint, "nope-passphrase".toCharArray())
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
