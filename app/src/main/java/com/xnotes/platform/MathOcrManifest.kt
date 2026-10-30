package com.xnotes.platform

/**
 * What the handwritten-maths model is and where it comes from.
 *
 * Pure Kotlin (no Android imports) so unit tests can pin it on the JVM.
 *
 * The model is pix2text-mfr 1.5 (MIT), a TrOCR-based formula recognizer trained on
 * printed and handwritten mathematical expressions: fractions, integrals, sums,
 * roots, multi-variable expressions, derivatives and Greek-letter physics notation.
 * It is fetched straight from its canonical upstream on Hugging Face at a pinned
 * revision — no app project's release artifacts are involved. ONNX Runtime itself
 * ships inside the APK, so only these three weight/tokenizer files are downloaded,
 * once, into app storage; inference then runs fully offline.
 */
object MathOcrManifest {

    /** Upstream revision every file below is pinned to. Bump to update the model. */
    const val REVISION = "1cef9f0bdcd6a4c63df7de1311fb0894593340cc"

    const val HF_BASE = "https://huggingface.co/breezedeus/pix2text-mfr-1.5/resolve/$REVISION/"

    /** One file of the model: [size] bytes at [url], SHA-256 [sha256]. */
    data class AddonFile(val name: String, val url: String, val size: Long, val sha256: String)

    val FILES: List<AddonFile> = listOf(
        AddonFile(
            "encoder_model.onnx",
            HF_BASE + "encoder_model.onnx",
            87_510_770,
            "080a3f660f08bc9ebcacdd96e34be6b6400f8c7e62d7cd0dd8251badc37f610b",
        ),
        AddonFile(
            "decoder_model.onnx",
            HF_BASE + "decoder_model.onnx",
            32_026_253,
            "917deb98e91a0453c5f234f58a0f32f9fb037de8527c7eb4ed394daf9e692f2a",
        ),
        AddonFile(
            "tokenizer.json",
            HF_BASE + "tokenizer.json",
            113_168,
            "4ffbeb2143e6a38324bb6111b7a8109530d38a076a8439aa5777535f0a32758a",
        ),
    )

    /** Total bytes fetched on a fresh download. Shown in settings before downloading. */
    val DOWNLOAD_BYTES: Long = FILES.sumOf { it.size }

    /** Marker written next to the files on a successful download; a revision bump orphans old files. */
    const val VERSION_FILE = "version.txt"

    fun fileNamed(name: String): AddonFile? = FILES.firstOrNull { it.name == name }

    /** True when [dir] holds exactly this revision's files at their expected sizes. */
    fun isCompleteDir(dir: java.io.File): Boolean {
        if (dir.resolve(VERSION_FILE).takeIf { it.isFile }?.readText()?.trim() != REVISION) return false
        return FILES.all { dir.resolve(it.name).length() == it.size }
    }

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
