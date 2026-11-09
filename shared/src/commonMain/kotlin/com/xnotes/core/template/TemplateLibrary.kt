package com.xnotes.core.template

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Rooms
import com.xnotes.core.model.resolvedAccentColor
import com.xnotes.core.model.resolvedColors
import com.xnotes.core.model.resolvedParams
import com.xnotes.core.model.resolvedPatternColor
import com.xnotes.core.model.resolvedSpacing
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.model.sourceHeight
import com.xnotes.core.model.sourceWidth
import com.xnotes.core.pal.Renderer
import com.xnotes.core.platform.File
import com.xnotes.core.platform.Lock
import com.xnotes.core.platform.ZipInputStream
import com.xnotes.core.platform.asInputStream
import com.xnotes.core.platform.asPath
import com.xnotes.core.platform.withLock
import com.xnotes.core.vector.GlyphOutliner
import com.xnotes.format.TemplatePackage
import com.xnotes.format.TemplateReader
import com.xnotes.format.readAtMost

/**
 * A template the library offers: its parsed JSON text, and/or its package file when it has
 * pictures. Plain templates (the bundled rulings and examples) carry text; packaged ones
 * (covers, user imports with pictures) carry a file the JSON is read from on demand.
 */
class LibraryTemplate(val text: String?, val packageFile: File?)

/** What a picker lists: every template by its stable key, with its display name, kind and origin. */
data class TemplateListEntry(val key: String, val name: String, val kind: TemplateKind, val source: TemplateSource)

/** Where a listed template comes from: the host's library, or the note's own embedded copy. */
enum class TemplateSource { LIBRARY, NOTE }

/**
 * The page templates a host knows: the parsed note's own ([Document.templates] and
 * [Document.templatePackages], which always win so a note draws the same everywhere) plus the
 * host's library (bundled examples on every device, and imports where the host offers them).
 * Paints through [TemplatePainter] with the shared layouts cache, so every host — Android,
 * companion, PDF export — draws a template's page identically.
 *
 * Built-in ruling ids ([PagePattern]) are NOT templates: they keep painting through the hosts'
 * ruling path, exactly as before.
 */
class TemplateRenderer(
    outliner: GlyphOutliner? = null,
    private val pictures: TemplatePictures,
) {
    private val lock = Lock()

    /**
     * Library templates by key (content hashes, so the same file is the same key on every
     * device). Set whole by the host; reads see one consistent map.
     */
    var library: Map<String, LibraryTemplate>
        get() = withLock(lock) { libraryBacking }
        set(value) = withLock(lock) { libraryBacking = value }
    private var libraryBacking: Map<String, LibraryTemplate> = emptyMap()

    private val eval = TemplateEval(outliner)
    private val layouts = TemplateLayoutCache(eval)

    private data class Parsed(val text: String?, val file: File?, val template: Template)

    /** Parsed templates by key; re-parsed when the text or package behind the key changes. */
    private val parsed = HashMap<String, Parsed>()

    fun clear() = withLock(lock) {
        parsed.clear()
        pictures.clear()
    }

    /**
     * The template [key] names for [doc]: the note's own embedded copy first (so a note keeps
     * drawing what it was drawn with), then its packaged copy, then the library's. Null for
     * [PageTemplates.NONE], the built-ins (the ruling path paints those), and unknown keys.
     * Parsing happens off the lock; the cache publish is locked, so parallel paints may parse
     * the same template twice but never observe a torn map.
     */
    fun template(doc: Document, key: String): Template? {
        if (key == PageTemplates.NONE || PagePattern.fromId(key) != null) return null
        doc.templates[key]?.let { text ->
            withLock(lock) { parsed[key]?.takeIf { it.text === text }?.let { return it.template } }
            return TemplateReader.read(text).also { withLock(lock) { parsed[key] = Parsed(text, null, it) } }
        }
        val pkg = doc.templatePackages[key] ?: withLock(lock) { libraryBacking[key]?.packageFile }
        if (pkg != null) {
            withLock(lock) { parsed[key]?.takeIf { it.file == pkg }?.let { return it.template } }
            val text = readPackageJson(pkg) ?: return null
            return TemplateReader.read(text).also { withLock(lock) { parsed[key] = Parsed(null, pkg, it) } }
        }
        val text = withLock(lock) { libraryBacking[key]?.text } ?: return null
        withLock(lock) { parsed[key]?.takeIf { it.text === text }?.let { return it.template } }
        return TemplateReader.read(text).also { withLock(lock) { parsed[key] = Parsed(text, null, it) } }
    }

    /** Every template on offer for [doc]: the library's, plus the note's own embedded ones. */
    fun entries(doc: Document?): List<TemplateListEntry> {
        val libs = withLock(lock) { libraryBacking.toList() }
        val out = LinkedHashMap<String, TemplateListEntry>()
        for ((key, lib) in libs) {
            val t = runCatching {
                if (lib.text != null) TemplateReader.read(lib.text)
                else if (lib.packageFile != null) readPackageJson(lib.packageFile)?.let(TemplateReader::read) else null
            }.getOrNull() ?: continue
            if (t.kind != TemplateKind.PAGE && t.kind != TemplateKind.COVER) continue
            out[key] = TemplateListEntry(key, t.name.ifEmpty { key }, t.kind, TemplateSource.LIBRARY)
        }
        if (doc != null) {
            for (key in doc.templates.keys + doc.templatePackages.keys) {
                val t = runCatching { template(doc, key) }.getOrNull() ?: continue
                out[key] = TemplateListEntry(key, t.name.ifEmpty { key }, t.kind, TemplateSource.NOTE)
            }
        }
        return out.values.toList()
    }

    /**
     * Paint [page]'s template into [r] in page-local content space, over [cover] (the page's
     * whole paper, margins included) clipped to [region]. A page over an imported PDF is ruled
     * in the margins and the room strips only, never over the page itself. True when a custom
     * template painted (built-ins and none paint elsewhere and return false). [minLine] is the
     * thinnest line in content px, so thumbnails keep their ruling visible.
     */
    fun paint(
        r: Renderer,
        doc: Document,
        page: Page,
        cover: Rect,
        region: Rect,
        minLine: Double = 0.0,
    ): Boolean {
        val key = page.resolvedTemplate(doc)
        if (key == PageTemplates.NONE || PagePattern.fromId(key) != null) return false
        val t = template(doc, key) ?: return false
        val mm = doc.dpi / 25.4
        val papers =
            if (page.pdfPage == null) listOf(cover)
            else Rooms.paper(cover, page.rooms, page.sourceWidth, page.sourceHeight)
        val ink = page.resolvedPatternColor(doc)
        val accent = page.resolvedAccentColor(doc)
        val values = values(t, doc, page)
        for (paper in papers) {
            if (!paper.intersects(region)) continue
            val tp = TemplatePage(
                page.width / mm, page.height / mm,
                Rect(paper.x / mm, paper.y / mm, paper.w / mm, paper.h / mm),
                ink, accent, values,
            )
            TemplatePainter.paint(r, layouts.layout(t, tp), mm, region, minLine, pictures.picturesFor(doc, key))
        }
        return true
    }

    /** [page]'s parameter values for [t]: resolved params, lengths converted to template mm. */
    fun values(t: Template, doc: Document, page: Page): TemplateValues {
        val numbers = HashMap(page.resolvedParams(doc))
        val spacingParam = t.spacingParam
        page.resolvedSpacing(doc)?.let { px ->
            if (spacingParam != null) numbers[spacingParam.name] = px * 25.4 / doc.dpi
        }
        return TemplateValues(numbers, page.resolvedColors(doc))
    }

    /** The `picture` lookup for one paint: the note's package first, then the library's. */
    private fun TemplatePictures.picturesFor(doc: Document, key: String): (String) -> com.xnotes.core.model.ImageData? {
        val pkg = doc.templatePackages[key] ?: withLock(lock) { libraryBacking[key]?.packageFile }
        return { src -> picture(pkg, key, src) }
    }

    private fun readPackageJson(pkg: File): String? = runCatching {
        // Closing the zip releases the file underneath on every platform; the raw stream
        // itself has no closer in common code.
        val raw = okio.FileSystem.SYSTEM.source(pkg.asPath()).asInputStream()
        ZipInputStream(raw).use { zis ->
            while (true) {
                val entry = zis.getNextEntry() ?: return@runCatching null
                if (!entry.isDirectory() && entry.getName() == TemplatePackage.JSON_ENTRY) {
                    return@runCatching readAtMost(zis, TemplateReader.MAX_BYTES)
                        ?.decodeToString()
                }
                zis.closeEntry()
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }.getOrNull()
}
