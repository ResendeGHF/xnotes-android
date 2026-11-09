package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xnotes.core.util.ThumbnailCacheFiles
import java.io.File
import java.io.FileOutputStream

// Notes and canvases share a bounded disk cache, invalidated by content or the rendered theme.
class NoteThumbnailCache(dir: File, maxFiles: Int = 256) {
    private val files = ThumbnailCacheFiles(dir, FORMAT, maxFiles)

    fun useTheme(theme: String): Long = files.useTheme(theme)

    fun isCurrent(generation: Long): Boolean = files.isCurrent(generation)

    fun load(uri: String, generation: Long): Bitmap? = files.load(uri, generation) { jpg ->
        // Tiles render at 600 px and are shown at ~that size: decode whole, RGB_565 (opaque,
        // 2 B/px). A single pass — the old PNG path decoded twice (bounds, then downsampled).
        BitmapFactory.decodeFile(jpg.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    fun store(uri: String, bitmap: Bitmap, generation: Long) = files.store(uri, generation) { jpg ->
        FileOutputStream(jpg).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
    }

    fun remove(uri: String) = files.remove(uri)

    fun prune(keep: Set<String>) = files.prune(keep)

    private companion object {
        const val JPEG_QUALITY = 88

        /** Bumped when the disk encoding changed (PNG→JPEG): old tiles are wiped on sight. */
        const val FORMAT = 5
    }
}
