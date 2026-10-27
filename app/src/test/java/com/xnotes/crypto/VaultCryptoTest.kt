package com.xnotes.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VaultCryptoTest {

    private val params = VaultCrypto.Params(8 * 1024, 1, 1)

    @Test
    fun roundTripKeepsPlaintextOffTheCiphertext() {
        val password = "correct horse".toCharArray()
        val created = VaultCrypto.create(password, params)
        val key = VaultCrypto.open(created.header, password.copyOf())
        assertArrayEquals(created.dataKey, key)

        val plain = "a note that must not land on disk".toByteArray()
        val cipher = ByteArrayOutputStream()
        VaultCrypto.encryptFile(key, cipher) { it.write(plain) }
        val stored = cipher.toByteArray()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("must not land"))

        val opened = VaultCrypto.decryptFile(key, ByteArrayInputStream(stored)).readBytes()
        assertArrayEquals(plain, opened)
        password.fill('\u0000')
        created.dataKey.fill(0)
        key.fill(0)
        opened.fill(0)
    }

    @Test
    fun hostileCostParametersAreRejectedBeforeArgon2() {
        val created = VaultCrypto.create("right-password".toCharArray(), params)
        // Header layout: magic(4), version(1), lanes(1), memoryKiB(4), iterations(4), ...
        val header = created.header.copyOf()
        header[6] = 0x7f
        header[7] = 0xff.toByte()
        header[8] = 0xff.toByte()
        header[9] = 0xff.toByte()
        assertThrows(VaultCrypto.Rejected::class.java) {
            VaultCrypto.open(header, "right-password".toCharArray())
        }
        created.dataKey.fill(0)
    }

    @Test
    fun wrongPasswordAndTamperAreRejected() {
        val created = VaultCrypto.create("right-password".toCharArray(), params)
        assertThrows(VaultCrypto.Rejected::class.java) {
            VaultCrypto.open(created.header, "wrong-password".toCharArray())
        }
        val cipher = ByteArrayOutputStream()
        VaultCrypto.encryptFile(created.dataKey, cipher) { it.write(byteArrayOf(1, 2, 3, 4)) }
        val stored = cipher.toByteArray()
        stored[stored.lastIndex] = (stored[stored.lastIndex].toInt() xor 0x01).toByte()
        assertThrows(VaultCrypto.Rejected::class.java) {
            VaultCrypto.decryptFile(created.dataKey, ByteArrayInputStream(stored)).readBytes()
        }
    }
}
