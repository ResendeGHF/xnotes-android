package com.xnotes.core.template

import com.xnotes.core.model.ImageData
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.platform.File
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.ZipInputStream
import com.xnotes.core.platform.asInputStream
import com.xnotes.core.platform.asPath
import com.xnotes.core.platform.createTempFile
import com.xnotes.core.platform.pathString
import com.xnotes.core.platform.withLock
import com.xnotes.format.TemplatePackage
import com.xnotes.format.copyToFile
import com.xnotes.format.deleteQuietly
import okio.FileSystem

/**
 * A template picture supply: extracts `image` pictures out of their package zips on first use
 * and serves them as [ImageData] after that. One per host (Android, companion); the cache
 * directory keeps extracted pictures across notes, and [probe] reads a picture's dimensions
 * (Android decodes bounds, the companion asks its host).
 *
 * Everything here is shared so every device paints the same pixels from the same package.
 */
class TemplatePictures(
    private val cacheDir: File?,
    private val probe: (File) -> ImageSize?,
) {
    private val lock = Lock()
    private val pictures = HashMap<String, ImageData>()
    private val missing = HashSet<String>()

    /** The picture `src` of template [key], from [pkg] (the note's copy wins, then the library's). */
    fun picture(pkg: File?, key: String, src: String): ImageData? {
        if (pkg == null || !TemplatePackage.validSrc(src)) return null
        val id = "$key/$src"
        val miss = "${pkg.pathString}|$src"
        // Fast path under lock; extraction and probing run outside it, then publish once.
        withLock(lock) {
            pictures[id]?.let { return it }
            if (miss in missing) return null
        }
        val dir = cacheDir ?: return null
        val out = extract(pkg, src, dir, key)
        if (out == null) {
            withLock(lock) { missing += miss }
            return null
        }
        val size = runCatching { probe(out) }.getOrNull()
        if (size == null || size.width <= 0 || size.height <= 0) {
            withLock(lock) { missing += miss }
            return null
        }
        return ImageData(out, size.width, size.height).also { withLock(lock) { pictures[id] = it } }
    }

    fun clear() = withLock(lock) {
        pictures.clear()
        missing.clear()
    }

    private fun extract(pkg: File, src: String, dir: File, key: String): File? = runCatching {
        // Closing the zip releases the file underneath on every platform; the raw stream
        // itself has no closer in common code.
        val raw = FileSystem.SYSTEM.source(pkg.asPath()).asInputStream()
        ZipInputStream(raw).use { zis ->
            while (true) {
                val entry = zis.getNextEntry() ?: return@runCatching null
                if (!entry.isDirectory() && entry.getName() == src) {
                    val ext = src.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }
                    val out = createTempFile("pic-$key-", ext, dir)
                    try {
                        copyToFile(zis, out)
                    } catch (_: Exception) {
                        deleteQuietly(out)
                        return@runCatching null
                    } finally {
                        zis.closeEntry()
                    }
                    return@runCatching out
                }
                zis.closeEntry()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }.getOrNull()
}
