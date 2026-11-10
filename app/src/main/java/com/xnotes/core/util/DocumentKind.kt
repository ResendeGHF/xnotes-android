package com.xnotes.core.util

/**
 * The document types the app stores, and the file extension each uses.
 *
 * The extension is spelled once here rather than at the two dozen sites that used to hard-code
 * `.xnote`. That matters now there are two: every place that lists, names, renames or opens a file
 * has to agree on which extensions are documents, and a place that forgets one silently hides the
 * user's files from the explorer.
 *
 * Renamed from `.xnote`/`.xcanvas` to `.pnotes`/`.pcanvas`; the old extensions stay readable
 * (see [legacyExtensions]) so existing libraries keep opening, but everything the app
 * writes uses the new ones.
 */
enum class DocumentKind(val extension: String, private val legacyExtensions: List<String> = emptyList()) {
    /** The paged notebook. */
    NOTE("pnotes", listOf("xnote")),

    /** The infinite canvas. */
    CANVAS("pcanvas", listOf("xcanvas")),
    ;

    /** The extension with its dot, as it appears at the end of a file name. */
    val suffix: String get() = ".$extension"

    companion object {
        /** Every suffix [kind] is recognised by, new first. */
        private fun DocumentKind.allSuffixes(): List<String> =
            listOf(suffix) + legacyExtensions.map { ".$it" }

        /** The kind [name] denotes, or null when it is not a document at all. */
        fun ofName(name: String): DocumentKind? =
            entries.firstOrNull { kind -> kind.allSuffixes().any { name.endsWith(it, ignoreCase = true) } }

        fun isDocument(name: String): Boolean = ofName(name) != null

        /** [name] without its document extension (old or new), or unchanged when it has none. */
        fun stripSuffix(name: String): String {
            val kind = ofName(name) ?: return name
            val hit = kind.allSuffixes().firstOrNull { name.endsWith(it, ignoreCase = true) } ?: return name
            return name.dropLast(hit.length)
        }

        /** [name] with [kind]'s extension, added only if it is not already the right
         *  one (a legacy extension of the same kind migrates to the new one; another
         *  kind's is left alone). */
        fun withSuffix(name: String, kind: DocumentKind): String {
            if (name.endsWith(kind.suffix, ignoreCase = true)) return name
            val base = if (ofName(name) == kind) stripSuffix(name) else name
            return "$base${kind.suffix}"
        }
    }
}
