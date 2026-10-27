package com.xnotes.crypto

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a folder of notes with a password.
 *
 * AES-256-GCM is the content cipher. Each file is authenticated, so a flipped bit or a truncated
 * write is rejected before any plaintext is released. AES-XTS is the wrong tool here: it is a
 * sector cipher for disks, it does not authenticate, and equal plaintext blocks stay visible.
 * AES-256 keeps a 128-bit margin under Grover's algorithm, which is the practical post-quantum
 * level for a password-derived key.
 *
 * The password is stretched with Argon2id (memory-hard). The parameters travel in the folder
 * header so a later unlock uses the same cost the folder was created with. A random 256-bit data
 * key is what actually seals the files; the password only wraps that key.
 */
object VaultCrypto {

    /** Hidden file written inside an encrypted folder. Never listed in the explorer. */
    const val MARKER_NAME = ".xnotes-vault"

    private val HEADER_MAGIC = byteArrayOf('X'.code.toByte(), 'N'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private val FILE_MAGIC = byteArrayOf('X'.code.toByte(), 'N'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    private val HEADER_AAD = "xnotes-vault-header-v1".toByteArray(Charsets.US_ASCII)
    private val FILE_AAD = "xnotes-vault-file-v1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Int = 1
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val KEY_LEN = 32
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** Argon2id cost stored with the folder. [memoryKiB] is kibibytes. */
    data class Params(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
        companion object {
            /** RFC 9106's memory-constrained recommendation: 64 MiB, 3 passes, one lane. */
            val DEFAULT = Params(64 * 1024, 3, 1)

            fun sanitize(memoryKiB: Int, iterations: Int, parallelism: Int) = Params(
                memoryKiB.coerceIn(8 * 1024, 256 * 1024),
                iterations.coerceIn(1, 16),
                parallelism.coerceIn(1, 4),
            )
        }
    }

    class Rejected(message: String) : Exception(message)

    /** Fresh header bytes plus the data key they wrap. The caller wipes [dataKey] after copying it. */
    class Created(val header: ByteArray, val dataKey: ByteArray)

    fun create(password: CharArray, params: Params): Created {
        // Callers may hand over values typed into a field; store only the bounded form.
        val cost = Params.sanitize(params.memoryKiB, params.iterations, params.parallelism)
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val dataKey = ByteArray(KEY_LEN).also { random.nextBytes(it) }
        val kek = stretch(password, salt, cost)
        try {
            val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
            val wrapped = aes(kek, nonce, HEADER_AAD, encrypt = true, dataKey)
            val out = ByteArrayOutputStream(4 + 1 + 1 + 4 + 4 + SALT_LEN + NONCE_LEN + wrapped.size)
            out.write(HEADER_MAGIC)
            out.write(VERSION)
            out.write(cost.parallelism)
            writeInt(out, cost.memoryKiB)
            writeInt(out, cost.iterations)
            out.write(salt)
            out.write(nonce)
            out.write(wrapped)
            return Created(out.toByteArray(), dataKey)
        } finally {
            kek.fill(0)
        }
    }

    /** The data key inside [header], or a [Rejected] when the password or the header is wrong. */
    fun open(header: ByteArray, password: CharArray): ByteArray {
        if (header.size < HEADER_MAGIC.size + 1 + 1 + 8 + SALT_LEN + NONCE_LEN + KEY_LEN + 16) throw Rejected("short header")
        if (!header.copyOfRange(0, 4).contentEquals(HEADER_MAGIC)) throw Rejected("not a vault")
        var o = 4
        val version = header[o].toInt() and 0xff
        if (version != VERSION) throw Rejected("unknown vault")
        o += 1
        val parallelism = header[o].toInt() and 0xff
        o += 1
        val memoryKiB = readInt(header, o)
        o += 4
        val iterations = readInt(header, o)
        o += 4
        // The header comes from a file that may have been modified. Refuse costs outside the
        // range this app writes, so a hostile header cannot ask for gigabytes of Argon2 memory
        // or hours of hashing before authentication fails.
        if (parallelism !in 1..4 ||
            memoryKiB !in (8 * 1024)..(256 * 1024) ||
            iterations !in 1..16
        ) {
            throw Rejected("bad vault parameters")
        }
        val salt = header.copyOfRange(o, o + SALT_LEN)
        o += SALT_LEN
        val nonce = header.copyOfRange(o, o + NONCE_LEN)
        o += NONCE_LEN
        val wrapped = header.copyOfRange(o, header.size)
        val kek = stretch(password, salt, Params(memoryKiB, iterations, parallelism))
        try {
            return aes(kek, nonce, HEADER_AAD, encrypt = false, wrapped)
        } catch (_: Exception) {
            throw Rejected("wrong password")
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Writes an authenticated ciphertext of whatever [produce] emits. Plaintext stays in the cipher
     * buffer; only the magic, nonce and ciphertext reach [dest].
     */
    fun encryptFile(dataKey: ByteArray, dest: OutputStream, produce: (OutputStream) -> Unit) {
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        dest.write(FILE_MAGIC)
        dest.write(nonce)
        val cipher = gcm(dataKey, nonce, Cipher.ENCRYPT_MODE)
        cipher.updateAAD(FILE_AAD)
        val pipe = object : OutputStream() {
            override fun write(b: Int) {
                val one = byteArrayOf(b.toByte())
                write(one, 0, 1)
                one.fill(0)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len <= 0) return
                val out = cipher.update(b, off, len) ?: return
                dest.write(out)
                out.fill(0)
            }
        }
        produce(pipe)
        val tail = cipher.doFinal()
        dest.write(tail)
        tail.fill(0)
    }

    /**
     * Decrypts a vault file. The authentication tag is checked before any plaintext is returned, so
     * a damaged file never reaches the editor. A file that is not a vault envelope is returned
     * unchanged (a note copied in from outside, still sealed on the next save).
     */
    fun decryptFile(dataKey: ByteArray, raw: InputStream): InputStream {
        val push = PushbackInputStream(raw, FILE_MAGIC.size)
        val magic = ByteArray(FILE_MAGIC.size)
        val n = push.read(magic)
        if (n != FILE_MAGIC.size || !magic.contentEquals(FILE_MAGIC)) {
            if (n > 0) push.unread(magic, 0, n)
            return push
        }
        val nonce = ByteArray(NONCE_LEN)
        if (readFully(push, nonce) != NONCE_LEN) {
            push.close()
            throw Rejected("truncated note")
        }
        val body = push.readBytes()
        push.close()
        val plain = try {
            val cipher = gcm(dataKey, nonce, Cipher.DECRYPT_MODE)
            cipher.updateAAD(FILE_AAD)
            cipher.doFinal(body)
        } catch (_: Exception) {
            body.fill(0)
            throw Rejected("damaged note")
        } finally {
            body.fill(0)
        }
        return ByteArrayInputStream(plain)
    }

    /** Overwrite a file with zeros, then delete it. Used for ciphertext scratch and unlocked working files. */
    fun secureDelete(file: File) {
        if (file.isFile && file.length() > 0L) {
            runCatching {
                java.io.RandomAccessFile(file, "rws").use { raf ->
                    val zeros = ByteArray(8192)
                    var left = raf.length()
                    raf.seek(0)
                    while (left > 0) {
                        val n = minOf(zeros.size.toLong(), left).toInt()
                        raf.write(zeros, 0, n)
                        left -= n
                    }
                }
            }
        }
        file.delete()
    }

    fun secureDeleteTree(root: File) {
        if (!root.exists()) return
        root.walkBottomUp().forEach { child ->
            if (child.isFile) secureDelete(child) else if (child != root) child.delete()
        }
        root.delete()
    }

    private fun stretch(password: CharArray, salt: ByteArray, params: Params): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKiB)
            .withParallelism(params.parallelism)
            .withSalt(salt)
        val gen = Argon2BytesGenerator()
        gen.init(builder.build())
        val out = ByteArray(KEY_LEN)
        val utf8 = encodeUtf8(password)
        try {
            gen.generateBytes(utf8, out, 0, out.size)
        } finally {
            utf8.fill(0)
        }
        return out
    }

    private fun aes(key: ByteArray, nonce: ByteArray, aad: ByteArray, encrypt: Boolean, input: ByteArray): ByteArray {
        val cipher = gcm(key, nonce, if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE)
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    private fun gcm(key: ByteArray, nonce: ByteArray, mode: Int): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key.copyOf(), "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher
    }

    private fun encodeUtf8(chars: CharArray): ByteArray {
        val out = ByteArrayOutputStream(chars.size)
        var i = 0
        while (i < chars.size) {
            val c = chars[i].code
            when {
                c < 0x80 -> out.write(c)
                c < 0x800 -> {
                    out.write(0xc0 or (c shr 6))
                    out.write(0x80 or (c and 0x3f))
                }
                c in 0xD800..0xDBFF && i + 1 < chars.size && chars[i + 1].code in 0xDC00..0xDFFF -> {
                    val u = 0x10000 + ((c and 0x3ff) shl 10) + (chars[i + 1].code and 0x3ff)
                    out.write(0xf0 or (u shr 18))
                    out.write(0x80 or ((u shr 12) and 0x3f))
                    out.write(0x80 or ((u shr 6) and 0x3f))
                    out.write(0x80 or (u and 0x3f))
                    i++
                }
                else -> {
                    out.write(0xe0 or (c shr 12))
                    out.write(0x80 or ((c shr 6) and 0x3f))
                    out.write(0x80 or (c and 0x3f))
                }
            }
            i++
        }
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or
            ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or
            (b[o + 3].toInt() and 0xff)

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var o = 0
        while (o < buf.size) {
            val n = input.read(buf, o, buf.size - o)
            if (n < 0) return o
            o += n
        }
        return o
    }
}

/**
 * Data keys for unlocked folders, held only in this process. Locking zeroes the key. Nothing here
 * is written to disk.
 */
object VaultSession {
    private val keys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    var epoch by androidx.compose.runtime.mutableStateOf(0)
        private set

    fun isUnlocked(folderId: String): Boolean = keys.containsKey(folderId)

    fun key(folderId: String): ByteArray? = keys[folderId]

    fun unlock(folderId: String, dataKey: ByteArray) {
        lock(folderId, bump = false)
        keys[folderId] = dataKey.copyOf()
        epoch++
    }

    fun lock(folderId: String, bump: Boolean = true) {
        keys.remove(folderId)?.fill(0)
        if (bump) epoch++
    }
}
