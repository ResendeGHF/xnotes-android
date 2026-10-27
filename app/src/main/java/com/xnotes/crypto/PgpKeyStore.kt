package com.xnotes.crypto

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.Features
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPMarker
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.Date
import java.util.Locale

/**
 * OpenPGP keys for moving a note to someone else.
 *
 * A key is an Ed25519 signing key plus a Curve25519 encryption subkey. The note is signed, then
 * encrypted to the recipient with AES-256 and a modification-detection code. AES-256 is the
 * post-quantum part of the message. Curve25519 is the interoperable OpenPGP encryption key;
 * hybrid post-quantum OpenPGP keys still fail to open in most other software, so they are not
 * used for import and export.
 *
 * Secret keys are stored armored and wrapped with the passphrase (AES-256, iterated S2K). The
 * directory is excluded from backups.
 */
class PgpKeyStore(private val dir: File) {

    data class Identity(val fingerprint: String, val userId: String, val secret: Boolean)

    class Opened(val plain: ByteArray, val fileName: String, val signerUserId: String, val signerFingerprint: String)

    class Failure(message: String) : Exception(message)

    init {
        dir.mkdirs()
    }

    fun list(): List<Identity> {
        val secrets = secretFiles().mapNotNull { identityOf(it, secret = true) }
        val have = secrets.map { it.fingerprint }.toSet()
        val publics = publicFiles().mapNotNull { identityOf(it, secret = false) }.filter { it.fingerprint !in have }
        return (secrets + publics).sortedBy { it.userId.lowercase(Locale.US) }
    }

    fun generate(userId: String, passphrase: CharArray): Identity {
        val now = Date()
        val random = SecureRandom()
        val signGen = Ed25519KeyPairGenerator()
        signGen.init(Ed25519KeyGenerationParameters(random))
        val sign = BcPGPKeyPair(PublicKeyAlgorithmTags.Ed25519, signGen.generateKeyPair(), now)
        val x25519Gen = X25519KeyPairGenerator()
        x25519Gen.init(X25519KeyGenerationParameters(random))
        val enc = BcPGPKeyPair(PublicKeyAlgorithmTags.ECDH, x25519Gen.generateKeyPair(), now)
        val digests = BcPGPDigestCalculatorProvider()
        val signPackets = PGPSignatureSubpacketGenerator()
        signPackets.setKeyFlags(false, KeyFlags.SIGN_DATA or KeyFlags.CERTIFY_OTHER)
        signPackets.setPreferredSymmetricAlgorithms(false, intArrayOf(SymmetricKeyAlgorithmTags.AES_256))
        signPackets.setPreferredHashAlgorithms(false, intArrayOf(HashAlgorithmTags.SHA512))
        signPackets.setPreferredCompressionAlgorithms(false, intArrayOf(org.bouncycastle.bcpg.CompressionAlgorithmTags.ZIP))
        signPackets.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION)
        val encPackets = PGPSignatureSubpacketGenerator()
        encPackets.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
        val ring = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            sign,
            userId,
            // BC only supports SHA-1 for the secret-key checksum packet, even for modern keys.
            digests.get(HashAlgorithmTags.SHA1),
            signPackets.generate(),
            null,
            BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.Ed25519, HashAlgorithmTags.SHA512),
            BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, digests.get(HashAlgorithmTags.SHA256), S2K_COUNT)
                .build(passphrase),
        )
        ring.addSubKey(enc, encPackets.generate(), null)
        val secret = ring.generateSecretKeyRing()
        // Confirm the passphrase actually unwraps the key we just wrote.
        unlock(secret.secretKey, passphrase)
        val fp = fingerprint(secret.publicKey)
        writeArmored(secretFile(fp)) { secret.encode(it) }
        writeArmored(publicFile(fp)) { publicRingOf(secret).encode(it) }
        return Identity(fp, userId(secret.publicKey), secret = true)
    }

    fun importPublic(armored: ByteArray): Identity {
        val ring = readPublic(armored)
        val fp = fingerprint(ring.publicKey)
        writeArmored(publicFile(fp)) { ring.encode(it) }
        return Identity(fp, userId(ring.publicKey), secret = secretFile(fp).isFile)
    }

    fun importSecret(armored: ByteArray, passphrase: CharArray): Identity {
        val ring = readSecret(armored)
        unlock(ring.secretKey, passphrase)
        val fp = fingerprint(ring.publicKey)
        writeArmored(secretFile(fp)) { ring.encode(it) }
        writeArmored(publicFile(fp)) { publicRingOf(ring).encode(it) }
        return Identity(fp, userId(ring.publicKey), secret = true)
    }

    fun delete(fingerprint: String) {
        VaultCrypto.secureDelete(secretFile(fingerprint))
        VaultCrypto.secureDelete(publicFile(fingerprint))
    }

    fun exportPublic(fingerprint: String): String {
        val ring = publicRing(fingerprint) ?: throw Failure("missing key")
        return armor { ring.encode(it) }
    }

    /** The stored, passphrase-wrapped secret key as armored text, or null when it is not here. */
    fun exportSecret(fingerprint: String): String? {
        val ring = secretRing(fingerprint) ?: return null
        return armor { ring.encode(it) }
    }

    /**
     * Sign [plain] with [signerFingerprint] and encrypt it to [recipientFingerprint]. The returned
     * bytes are an armored OpenPGP message. [plain] is not retained.
     */
    fun encryptAndSign(
        plain: ByteArray,
        fileName: String,
        recipientFingerprint: String,
        signerFingerprint: String,
        passphrase: CharArray,
    ): ByteArray {
        val recipient = publicRing(recipientFingerprint) ?: throw Failure("missing recipient")
        val encKey = encryptionKey(recipient) ?: throw Failure("recipient cannot encrypt")
        val secret = secretRing(signerFingerprint) ?: throw Failure("missing signing key")
        val signer = unlock(secret.secretKey, passphrase)
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armored ->
            val encGen = PGPEncryptedDataGenerator(
                BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(SecureRandom()),
            )
            encGen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(encKey))
            encGen.open(armored, ByteArray(1 shl 16)).use { encOut ->
                val comp = PGPCompressedDataGenerator(org.bouncycastle.bcpg.CompressionAlgorithmTags.ZIP)
                comp.open(encOut).use { compOut ->
                    val sigGen = PGPSignatureGenerator(
                        BcPGPContentSignerBuilder(secret.publicKey.algorithm, HashAlgorithmTags.SHA512),
                    )
                    sigGen.init(PGPSignature.BINARY_DOCUMENT, signer)
                    sigGen.generateOnePassVersion(false).encode(compOut)
                    val literal = PGPLiteralDataGenerator()
                    literal.open(compOut, PGPLiteralData.BINARY, fileName, plain.size.toLong(), Date()).use { lit ->
                        lit.write(plain)
                    }
                    // The signature covers the literal data bytes, which must also be fed to the
                    // signature generator explicitly, not just written to the packet stream.
                    sigGen.update(plain)
                    sigGen.generate().encode(compOut)
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Decrypt a message for [secretFingerprint] and require a valid signature from an imported key.
     * The returned plaintext is owned by the caller, who must wipe it when finished.
     */
    fun decryptAndVerify(message: ByteArray, secretFingerprint: String, passphrase: CharArray): Opened {
        val secret = secretRing(secretFingerprint) ?: throw Failure("missing private key")
        val encryptedList = try {
            val top = PGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(message)), BcKeyFingerprintCalculator())
            findEncrypted(top) ?: throw Failure("not an encrypted message")
        } catch (t: Throwable) {
            if (t is Failure) throw t
            throw Failure("damaged note")
        }
        val decryptable = findDecryptable(secret, encryptedList, passphrase)
            ?: throw Failure("message is for a different key")

        var factory: PGPObjectFactory
        var literal: PGPLiteralData
        var onePass: PGPOnePassSignatureList? = null
        val plain: ByteArray
        try {
            factory = PGPObjectFactory(decryptable.stream, BcKeyFingerprintCalculator())
            var obj = nextMeaningful(factory)
            // A message may be wrapped in one or more compression layers.
            while (obj is PGPCompressedData) {
                factory = PGPObjectFactory(obj.dataStream, BcKeyFingerprintCalculator())
                obj = nextMeaningful(factory)
            }
            if (obj is PGPOnePassSignatureList) {
                onePass = obj
                obj = nextMeaningful(factory)
            }
            literal = obj as? PGPLiteralData ?: throw Failure("message has no note")
            plain = literal.inputStream.readBytes()
        } catch (t: Throwable) {
            if (t is Failure) throw t
            // Tampered ciphertext and damaged compression layers fail at different stages of
            // the packet parser; the UI only needs to know the message cannot be trusted.
            throw Failure("damaged note")
        }
        return try {
            val signatures = ArrayList<PGPSignature>()
            while (true) {
                val next = nextMeaningful(factory) ?: break
                if (next is PGPSignatureList) {
                    for (i in 0 until next.size()) signatures.add(next[i])
                }
            }
            val intact = try {
                decryptable.data.verify()
            } catch (_: Exception) {
                false
            }
            if (!intact) throw Failure("message was modified")
            val signer = verifySignatures(plain, onePass, signatures)
                ?: throw Failure("signer is not a trusted key")
            Opened(plain, literal.fileName.ifBlank { "Imported.xnote" }, signer.userId, signer.fingerprint)
        } catch (t: Throwable) {
            plain.fill(0)
            if (t is Failure) throw t
            throw Failure("damaged note")
        }
    }

    private class Decryptable(val data: PGPPublicKeyEncryptedData, val stream: InputStream)

    private class Verified(val userId: String, val fingerprint: String)

    private fun findEncrypted(factory: PGPObjectFactory): PGPEncryptedDataList? {
        while (true) {
            when (val obj = factory.nextObject() ?: return null) {
                is PGPEncryptedDataList -> return obj
                else -> Unit // markers/padding before the encrypted data
            }
        }
    }

    private fun findDecryptable(
        secret: PGPSecretKeyRing,
        list: PGPEncryptedDataList,
        passphrase: CharArray,
    ): Decryptable? {
        val packets = list.encryptedDataObjects
        while (packets.hasNext()) {
            val data = packets.next() as? PGPPublicKeyEncryptedData ?: continue
            val keyId = data.keyID
            if (keyId != 0L) {
                val key = secret.getSecretKey(keyId) ?: continue
                // The message names this key: a bad passphrase must surface as such, not as
                // "message is for a different key".
                val privateKey = unlock(key, passphrase)
                val stream = runCatching {
                    data.getDataStream(BcPublicKeyDataDecryptorFactory(privateKey))
                }.getOrNull() ?: continue
                return Decryptable(data, stream)
            }
            // Anonymous recipient: try every secret key in the ring.
            val keys = secret.secretKeys
            while (keys.hasNext()) {
                val candidate = keys.next()
                val privateKey = runCatching { unlock(candidate, passphrase) }.getOrNull() ?: continue
                val stream = runCatching {
                    data.getDataStream(BcPublicKeyDataDecryptorFactory(privateKey))
                }.getOrNull() ?: continue
                return Decryptable(data, stream)
            }
        }
        return null
    }

    private fun verifySignatures(
        plain: ByteArray,
        onePass: PGPOnePassSignatureList?,
        signatures: List<PGPSignature>,
    ): Verified? {
        if (signatures.isEmpty()) return null
        val provider = BcPGPContentVerifierBuilderProvider()
        var verified: Verified? = null
        var checked = 0

        fun remember(pub: PGPPublicKey) {
            if (verified == null) verified = Verified(userId(masterOf(pub)), fingerprint(masterOf(pub)))
        }

        if (onePass != null) {
            val paired = minOf(onePass.size(), signatures.size)
            for (i in 0 until paired) {
                val pass = onePass[i]
                val signature = signatures[i]
                val pub = findSigningKey(pass.keyID) ?: continue
                if (pass.signatureType != PGPSignature.BINARY_DOCUMENT) return null
                try {
                    pass.init(provider, pub)
                    pass.update(plain)
                    if (!pass.verify(signature)) return null
                } catch (_: Exception) {
                    return null
                }
                checked++
                remember(pub)
            }
            // Signatures not paired with an onepass packet still have to be checked.
            for (i in paired until signatures.size) {
                val signature = signatures[i]
                val pub = findSigningKey(signature.keyID) ?: continue
                if (!verifyDirect(signature, pub, plain, provider)) return null
                checked++
                remember(pub)
            }
        } else {
            for (signature in signatures) {
                val pub = findSigningKey(signature.keyID) ?: continue
                if (!verifyDirect(signature, pub, plain, provider)) return null
                checked++
                remember(pub)
            }
        }
        return if (checked > 0) verified else null
    }

    private fun verifyDirect(
        signature: PGPSignature,
        pub: PGPPublicKey,
        plain: ByteArray,
        provider: BcPGPContentVerifierBuilderProvider,
    ): Boolean = try {
        if (signature.signatureType != PGPSignature.BINARY_DOCUMENT) false
        else {
            signature.init(provider, pub)
            signature.update(plain)
            signature.verify()
        }
    } catch (_: Exception) {
        false
    }

    private fun nextMeaningful(factory: PGPObjectFactory): Any? {
        while (true) {
            val obj = factory.nextObject() ?: return null
            if (obj is PGPMarker) continue
            return obj
        }
    }

    private fun findSigningKey(keyId: Long): PGPPublicKey? {
        for (file in publicFiles() + secretFiles()) {
            val bytes = file.readBytes()
            runCatching { readPublic(bytes).getPublicKey(keyId) }.getOrNull()?.let { return it }
            runCatching { readSecret(bytes).getPublicKey(keyId) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Fingerprint of the certification key that owns [key], so a subkey verifies as its owner. */
    private fun masterOf(key: PGPPublicKey): PGPPublicKey {
        for (file in publicFiles() + secretFiles()) {
            val bytes = file.readBytes()
            val ring = runCatching { readPublic(bytes) }.getOrNull()
                ?: runCatching { publicRingOf(readSecret(bytes)) }.getOrNull()
            if (ring?.getPublicKey(key.keyID) != null) return ring.publicKey
        }
        return key
    }

    private fun encryptionKey(ring: PGPPublicKeyRing): PGPPublicKey? {
        val it = ring.publicKeys
        while (it.hasNext()) {
            val key = it.next()
            if (key.isEncryptionKey) return key
        }
        return null
    }

    private fun unlock(secret: PGPSecretKey, passphrase: CharArray): PGPPrivateKey =
        try {
            secret.extractPrivateKey(BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(passphrase))
        } catch (_: Exception) {
            throw Failure("wrong passphrase")
        }

    private fun publicRing(fingerprint: String): PGPPublicKeyRing? {
        publicFile(fingerprint).takeIf { it.isFile }?.let { file ->
            runCatching { readPublic(file.readBytes()) }.getOrNull()?.let { return it }
        }
        secretFile(fingerprint).takeIf { it.isFile }?.let { file ->
            return runCatching { publicRingOf(readSecret(file.readBytes())) }.getOrNull()
        }
        return null
    }

    private fun secretRing(fingerprint: String): PGPSecretKeyRing? =
        secretFile(fingerprint).takeIf { it.isFile }?.let { file ->
            runCatching { readSecret(file.readBytes()) }.getOrNull()
        }

    private fun identityOf(file: File, secret: Boolean): Identity? = runCatching {
        val bytes = file.readBytes()
        val key = if (secret) readSecret(bytes).publicKey else readPublic(bytes).publicKey
        Identity(fingerprint(key), userId(key), secret)
    }.getOrNull()

    private fun readPublic(bytes: ByteArray): PGPPublicKeyRing {
        val collection = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(bytes)),
            BcKeyFingerprintCalculator(),
        )
        val rings = collection.keyRings
        if (!rings.hasNext()) throw Failure("no public key")
        return rings.next()
    }

    private fun readSecret(bytes: ByteArray): PGPSecretKeyRing {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(bytes)),
            BcKeyFingerprintCalculator(),
        )
        val rings = collection.keyRings
        if (!rings.hasNext()) throw Failure("no private key")
        return rings.next()
    }

    private fun publicRingOf(secret: PGPSecretKeyRing): PGPPublicKeyRing {
        val keys = ArrayList<PGPPublicKey>()
        val it = secret.publicKeys
        while (it.hasNext()) keys.add(it.next())
        return PGPPublicKeyRing(keys)
    }

    private fun userId(key: PGPPublicKey): String {
        val it = key.userIDs
        return if (it.hasNext()) it.next() else fingerprint(key).take(16)
    }

    private fun fingerprint(key: PGPPublicKey): String =
        key.fingerprint.joinToString("") { "%02X".format(Locale.US, it) }

    private fun publicFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("pub-") && f.name.endsWith(".asc") }?.toList().orEmpty()

    private fun secretFiles(): List<File> =
        dir.listFiles { f -> f.name.startsWith("sec-") && f.name.endsWith(".asc") }?.toList().orEmpty()

    private fun publicFile(fingerprint: String) = File(dir, "pub-${normalize(fingerprint)}.asc")

    private fun secretFile(fingerprint: String) = File(dir, "sec-${normalize(fingerprint)}.asc")

    private fun normalize(fingerprint: String): String =
        fingerprint.filter { it.isLetterOrDigit() }.uppercase(Locale.US)

    private fun armor(encode: (java.io.OutputStream) -> Unit): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { encode(it) }
        return out.toString(Charsets.US_ASCII.name())
    }

    /** Write through a sibling temp so a crash cannot leave a half-written key file. */
    private fun writeArmored(file: File, encode: (java.io.OutputStream) -> Unit) {
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.outputStream().use { raw -> ArmoredOutputStream(raw).use { encode(it) } }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw Failure("could not save key")
            }
        } finally {
            if (tmp.exists()) VaultCrypto.secureDelete(tmp)
        }
    }

    private companion object {
        /** ~720k SHA-256 S2K iterations: strong for a passphrase, still quick on a phone. */
        const val S2K_COUNT = 0x96
    }
}
