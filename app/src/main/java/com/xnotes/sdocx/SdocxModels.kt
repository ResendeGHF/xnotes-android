package com.xnotes.sdocx

/** One entry from `media/mediaInfo.dat`. */
class SdocxMediaEntry(
    val bindId: Int,
    val fileName: String,
    var bytes: ByteArray? = null,
) {
    val isImage: Boolean
        get() {
            val lower = fileName.lowercase()
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp") ||
                lower.endsWith(".gif") || lower.endsWith(".bmp") ||
                lower.endsWith(".heic") || lower.endsWith(".heif") ||
                lower.endsWith(".avif") || lower.endsWith(".tif") ||
                lower.endsWith(".tiff") || lower.endsWith(".dng") ||
                lower.endsWith(".wbmp") || lower.endsWith(".ico")
        }
    val isPdf: Boolean get() = fileName.lowercase().endsWith(".pdf")
    val isAudio: Boolean
        get() {
            val lower = fileName.lowercase()
            return lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".wav") ||
                lower.endsWith(".ogg") || lower.endsWith(".3gp") || lower.endsWith(".amr")
        }

    /** Sticker sub-notes (.sdocx), .spi painting data, video and other attachments. */
    val isSkipped: Boolean get() = !isImage && !isPdf && !isAudio
}

/** A decoded pen stroke in Samsung page coordinates. */
class SdocxStroke(
    val points: List<SdocxPoint>,
    val pressures: List<Double>,
    val colorArgb: Int?,
    val penWidth: Double,
    val bbox: SdocxRect,
    val penName: String?,
    val penNameId: Int?,
    val advancedSettingId: Int?,
    val toolTypeRaw: Int?,
)

data class SdocxPoint(val x: Double, val y: Double)

/** One native path subpath decoded from a shape: move/line/close verbs only. */
class SdocxSubPath(
    val points: List<SdocxPoint>,
    val closed: Boolean,
)

data class SdocxRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

/** A placed raster image in Samsung page coordinates. */
class SdocxImage(
    val bbox: SdocxRect,
    val mediaId: Int?,
    val rotationDegrees: Double?,
)

/** PDF-backed paper: a Samsung page showing one page of a PDF asset. */
class SdocxPdfPaper(
    val mediaId: Int,
    val pageIndex: Int,
    val rect: SdocxRect,
)

/** A decoded Samsung text box: plain text plus whole-box styling. */
class SdocxTextBox(
    val bbox: SdocxRect,
    val text: String,
    val colorArgb: Int?,
    val fontSize: Float?,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strike: Boolean,
    /** Samsung paragraph alignment: 0 = left, 1 = right, 2 = center, 3 = both. */
    val align: Int,
    val rotationDegrees: Double?,
)

/**
 * A decoded Samsung shape (type 7): a basic template (rectangle, triangle,
 * ellipse, ...) drawn from [geoBox] (unturned frame; [rotationDegrees] turns
 * it about its centre), and/or a free outline in [subpaths] (exact native
 * move/line/close geometry when the shape carries a simple path; null when
 * it carries arcs, ovals or custom control points).
 */
class SdocxShape(
    val bbox: SdocxRect,
    val geoBox: SdocxRect,
    val shapeType: Int,
    val rotationDegrees: Double?,
    val flipH: Boolean,
    val flipV: Boolean,
    val subpaths: List<SdocxSubPath>?,
    val strokeColorArgb: Int?,
    val strokeWidth: Float?,
    val dashed: Boolean,
    val headStart: Boolean,
    val headEnd: Boolean,
    val label: SdocxTextBox?,
)

/** A decoded Samsung line (type 8): straight (0), elbow (1) or curve (2). */
class SdocxLine(
    val begin: SdocxPoint,
    val end: SdocxPoint,
    val controlPoints: List<SdocxPoint>,
    val lineType: Int,
    val bbox: SdocxRect,
    val strokeColorArgb: Int?,
    val strokeWidth: Float?,
    val dashed: Boolean,
    val headStart: Boolean,
    val headEnd: Boolean,
)

/** A decoded Samsung voice memo (type 10): an audio attachment with a title. */
class SdocxVoice(
    val bbox: SdocxRect,
    val mediaId: Int?,
    val title: String,
    /** Length in milliseconds, parsed from the memo's duration label. */
    val durationMs: Int,
)

/** One parsed `.page` record. */
class SdocxPage(
    val uuid: String,
    val width: Double,
    val height: Double,
    val backgroundColorArgb: Int? = null,
    val pdfPapers: List<SdocxPdfPaper> = emptyList(),
    val strokes: List<SdocxStroke> = emptyList(),
    val images: List<SdocxImage> = emptyList(),
    val textBoxes: List<SdocxTextBox> = emptyList(),
    val shapes: List<SdocxShape> = emptyList(),
    val lines: List<SdocxLine> = emptyList(),
    val voices: List<SdocxVoice> = emptyList(),
    val templateUri: String? = null,
    val templateType: Int? = null,
    val backgroundImageId: Int? = null,
    val backgroundImageMode: Int? = null,
    val backgroundWidth: Int? = null,
    val backgroundRotation: Int? = null,
) {
    val isEmpty: Boolean
        get() = strokes.isEmpty() && images.isEmpty() && pdfPapers.isEmpty() &&
            textBoxes.isEmpty() && shapes.isEmpty() && lines.isEmpty() && voices.isEmpty()
}

/** Whole document in Samsung page order (`pageIdInfo.dat`). */
class SdocxDocument(
    val pages: List<SdocxPage>,
    val mediaByBindId: Map<Int, SdocxMediaEntry>,
    val mediaByFileName: Map<String, SdocxMediaEntry>,
    val title: String? = null,
    val stringTable: Map<Int, String> = emptyMap(),
    val defaultTemplateUri: String? = null,
    /**
     * Photos embedded in `note.note`'s title/body text (object spans of type
     * 3, e.g. pictures pasted into Samsung text): they belong to no `.page`,
     * so they travel separately. Bboxes are in note text-flow coordinates.
     */
    val bodyImages: List<SdocxImage> = emptyList(),
)
