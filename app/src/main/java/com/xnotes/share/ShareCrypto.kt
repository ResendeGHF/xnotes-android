package com.xnotes.share

import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo

/**
 * TLS identity and bearer token for the share server. Pure JVM so the
 * handshake, persistence and fingerprint logic unit test on the host; the
 * Android service only hands it a private directory.
 *
 * Threat model: the server may run on hostile LANs (university, cafe), so it
 * listens on TLS *only* — there is deliberately no plaintext socket — with a
 * device-generated self-signed certificate plus a 256-bit bearer token. The
 * first connection is trust-on-first-use: the client shows the certificate's
 * SHA-256 fingerprint and the user confirms it matches the one shown on the
 * device. Every request also carries the token, so a network observer that
 * somehow bypasses certificate verification still cannot call the API.
 */
object ShareCrypto {
    private const val CERT_FILE = "cert.pem"
    private const val KEY_FILE = "key.pem"
    private const val TOKEN_FILE = "token.txt"

    /** What the service persists under its private dir (`files/share/`). */
    data class ServerIdentity(
        val certificate: X509Certificate,
        val privateKey: PrivateKey,
        /** Lowercase hex SHA-256 of the certificate DER; shown on both ends for TOFU. */
        val fingerprintHex: String,
        /** Raw bearer token the client sends as `Authorization: Bearer`. */
        val token: String,
    )

    /**
     * Load the persisted identity, or generate (RSA-2048 + self-signed X.509
     * + 256-bit token) and store it. Callers hold no lock; worst case two
     * racing callers write the same shape twice and the last full set wins
     * (each file is tmp+rename, and all three are re-read when complete).
     */
    fun loadOrCreate(baseDir: File): ServerIdentity {
        val dir = File(baseDir, "share").apply { mkdirs() }
        val certFile = File(dir, CERT_FILE)
        val keyFile = File(dir, KEY_FILE)
        val tokenFile = File(dir, TOKEN_FILE)
        readIdentity(certFile, keyFile, tokenFile)?.let { return it }
        val random = SecureRandom()
        val keyPair = generateRsa(random)
        val cert = selfSigned(keyPair, random)
        val token = randomHex(random, 32)
        writeAtomically(certFile) { f -> pemWrite(f, cert) }
        writeAtomically(keyFile) { f -> pemWrite(f, keyPair.private) }
        writeAtomically(tokenFile) { f -> f.writeText(token, Charsets.UTF_8) }
        return readIdentity(certFile, keyFile, tokenFile)
            ?: ServerIdentity(cert, keyPair.private, fingerprintOf(cert), token)
    }

    /** `SSLContext` that presents [identity]'s certificate. PKCS12 is the
     *  portable store: Android has no JKS provider, so JKS (which the host
     *  JVM accepts) would fail on every real device. */
    fun serverSslContext(identity: ServerIdentity): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("share", identity.privateKey, CharArray(0), arrayOf(identity.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    /**
     * A client-side context that trusts *only* the server whose certificate
     * fingerprints to [expectedFingerprintHex]. Used by tests and as the
     * reference for the Linux companion's pinning.
     */
    fun pinningSslContext(expectedFingerprintHex: String): SSLContext {
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw java.security.cert.CertificateException("empty chain")
                val got = fingerprintOf(leaf)
                if (!ShareProtocol.tokensEqual(got, expectedFingerprintHex.lowercase())) {
                    throw java.security.cert.CertificateException("certificate fingerprint mismatch")
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tm), SecureRandom())
        }
    }

    /** Default client context from the device-persisted certificate file (TOFU store). */
    fun pinningSslContextFor(identity: ServerIdentity): SSLContext =
        pinningSslContext(identity.fingerprintHex)

    /** Trust managers of the platform default (for tests that need a baseline). */
    fun platformTrustManagers(): Array<X509TrustManager> {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().toTypedArray()
    }

    fun fingerprintOf(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Human form of [fingerprintHex]: `AB:CD:…`, grouped for read-out comparison. */
    fun displayFingerprint(fingerprintHex: String): String =
        fingerprintHex.chunked(2).joinToString(":").uppercase()

    fun randomHex(random: SecureRandom = SecureRandom(), bytes: Int = 32): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }

    private fun generateRsa(random: SecureRandom): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048, random)
        return gen.generateKeyPair()
    }

    internal fun selfSigned(keyPair: KeyPair, random: SecureRandom): X509Certificate {
        val provider = BouncyCastleProvider()
        val now = System.currentTimeMillis()
        // A day of backdate tolerates client clocks that lag; ten years avoids expiry churn.
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 3600 * 1000)
        val name = X500Name("CN=xnotes-share")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, random).abs(),
            notBefore,
            notAfter,
            name,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(
                arrayOf(
                    GeneralName(GeneralName.dNSName, "localhost"),
                    GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                ),
            ),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keyPair.private)
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
    }

    private fun readIdentity(certFile: File, keyFile: File, tokenFile: File): ServerIdentity? {
        return try {
        if (!certFile.exists() || !keyFile.exists() || !tokenFile.exists()) return null
        val token = tokenFile.readText(Charsets.UTF_8).trim()
        if (token.length < 32) return null
        val cert = pemRead(certFile)?.let { toCertificate(it) } ?: return null
        val key = pemRead(keyFile)?.let { pemToPrivateKey(it) } ?: return null
        // The pair must belong together, or a half-written rotation would serve a
        // certificate the stored key cannot prove ownership of.
        if (!keyPairMatches(cert, key)) return null
        ServerIdentity(cert, key, fingerprintOf(cert), token)
        } catch (_: Exception) {
            null
        }
    }

    private fun keyPairMatches(cert: X509Certificate, key: PrivateKey): Boolean = try {
        val test = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        test.init(javax.crypto.Cipher.ENCRYPT_MODE, cert.publicKey)
        val blob = test.doFinal("x".toByteArray(Charsets.UTF_8))
        val back = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        back.init(javax.crypto.Cipher.DECRYPT_MODE, key)
        back.doFinal(blob).toString(Charsets.UTF_8) == "x"
    } catch (_: Exception) {
        false
    }

    private fun pemWrite(file: File, obj: Any) {
        FileWriter(file).use { w -> JcaPEMWriter(w).use { it.writeObject(obj) } }
    }

    private fun pemRead(file: File): Any? {
        FileReader(file).use { r -> PEMParser(r).use { return it.readObject() } }
    }

    private fun toCertificate(obj: Any): X509Certificate? = try {
        when (obj) {
            is X509Certificate -> obj
            is org.bouncycastle.cert.X509CertificateHolder ->
                JcaX509CertificateConverter().getCertificate(obj)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun pemToPrivateKey(obj: Any): PrivateKey? = try {
        when (obj) {
            is PrivateKey -> obj
            is org.bouncycastle.asn1.pkcs.PrivateKeyInfo ->
                JcaPEMKeyConverter().getPrivateKey(obj)
            is org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo ->
                throw IllegalArgumentException("encrypted keys unsupported")
            is org.bouncycastle.openssl.PEMKeyPair ->
                JcaPEMKeyConverter().getPrivateKey(obj.privateKeyInfo)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun writeAtomically(file: File, write: (File) -> Unit) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        write(tmp)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
