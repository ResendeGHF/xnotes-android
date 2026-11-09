package com.xnotes.format

/**
 * A packaged `.xtemplate` (SPEC §1, Packages): a ZIP holding `template.json` and the pictures
 * its `image` nodes name. The file handling lives on each platform (Android's
 * `com.xnotes.format.TemplatePackage`, the companion's capi); this is the part every
 * platform — including the shared reader — agrees on. The bounded entry reader lives with
 * [DocumentCodec]'s own (same module).
 */
object TemplatePackage {

    /** The template JSON's entry inside a package. */
    const val JSON_ENTRY = "template.json"

    /** Whether [src] is a path an `image` may name: relative, with no `..` segment (SPEC §5.3). */
    fun validSrc(src: String): Boolean =
        src.isNotEmpty() && !src.startsWith("/") && src.split('/').none { it == ".." }
}
