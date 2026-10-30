package com.xnotes.core.util

/**
 * Files the explorer lists and syncs but only opens in the read-only reader. Only [DocumentKind]
 * files are ever edited; these are shown as they are and never written back.
 */
enum class ReaderKind(val label: String, vararg val extensions: String) {
    MARKDOWN("MD", "md", "markdown"),
    DOCX("DOCX", "docx"),
    PPTX("PPTX", "pptx"),
    XLSX("XLSX", "xlsx"),
    CSV("CSV", "csv"),
    PDF("PDF", "pdf"),
    ;

    companion object {
        fun ofName(name: String): ReaderKind? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty() || ext == name.lowercase()) return null
            return entries.firstOrNull { ext in it.extensions }
        }

        fun isReadable(name: String): Boolean = ofName(name) != null

        /** Samsung Notes files: not rendered, but recognized so they can be imported. */
        fun isSdocx(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() == "sdocx"

        /** Whether the explorer lists [name] as a file at all: an editable document,
         *  a readable one, or an importable Samsung note. */
        fun isListed(name: String): Boolean =
            DocumentKind.isDocument(name) || isReadable(name) || isSdocx(name)
    }
}
