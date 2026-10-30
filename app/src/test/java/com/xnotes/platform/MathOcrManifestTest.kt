package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MathOcrManifestTest {

    @Test fun urlsPointAtPinnedUpstreamRevision() {
        for (f in MathOcrManifest.FILES) {
            assertTrue(
                "${f.name} should resolve from the pinned Hugging Face revision",
                f.url == MathOcrManifest.HF_BASE + f.name &&
                    f.url.startsWith("https://huggingface.co/breezedeus/pix2text-mfr-1.5/resolve/") &&
                    MathOcrManifest.REVISION in f.url,
            )
        }
    }

    @Test fun downloadIsModelOnly() {
        // Encoder + decoder + tokenizer; the runtime ships in the APK.
        assertEquals(87_510_770L + 32_026_253L + 113_168L, MathOcrManifest.DOWNLOAD_BYTES)
        assertEquals(3, MathOcrManifest.FILES.size)
        assertTrue(MathOcrManifest.FILES.none { it.name.endsWith(".so") || it.name.endsWith(".gz") })
    }

    @Test fun completeDirNeedsMarkerAndExactSizes() {
        val dir = Files.createTempDirectory("mathocr").toFile()
        try {
            assertFalse(MathOcrManifest.isCompleteDir(dir))
            for (f in MathOcrManifest.FILES) {
                File(dir, f.name).writeBytes(ByteArray(f.size.toInt()))
            }
            // Sizes alone are not enough without the revision marker.
            assertFalse(MathOcrManifest.isCompleteDir(dir))
            File(dir, MathOcrManifest.VERSION_FILE).writeText(MathOcrManifest.REVISION)
            assertTrue(MathOcrManifest.isCompleteDir(dir))
            // A truncated file breaks completeness.
            File(dir, MathOcrManifest.FILES.first().name).writeBytes(ByteArray(10))
            assertFalse(MathOcrManifest.isCompleteDir(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun sha256HexMatchesKnownDigest() {
        // Sanity-check the helper against the empty input vector.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            MathOcrManifest.sha256Hex(ByteArray(0)),
        )
    }
}
