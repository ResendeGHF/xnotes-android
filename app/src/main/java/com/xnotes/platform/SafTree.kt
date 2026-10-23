package com.xnotes.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * A plain walk of a SAF tree, independent of the explorer's UI. Used by maintenance passes that
 * visit every note under the browse root without opening it.
 */
object SafTree {
    private val NOTE_EXTENSIONS = listOf(".xnote", ".xcanvas")

    data class LocalEntry(val relativePath: String, val documentUri: String, val size: Long, val modified: Long)

    fun isNote(name: String) = NOTE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    /** Every note and canvas under [treeUri], skipping hidden folders (the colour sidecar, Trash). */
    fun listNotes(context: Context, treeUri: String): List<LocalEntry> {
        val tree = Uri.parse(treeUri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val out = ArrayList<LocalEntry>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<Pair<String, String>>().apply { addLast(rootId to "") }
        while (stack.isNotEmpty()) {
            val (docId, prefix) = stack.removeLast()
            if (!seen.add(docId)) continue
            for (child in children(context, tree, docId)) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDir) {
                    if (!child.name.startsWith(".")) stack.addLast(child.docId to rel)
                } else if (isNote(child.name)) {
                    out.add(LocalEntry(rel, child.uri, child.size, child.modified))
                }
            }
        }
        return out
    }

    private data class Child(
        val name: String,
        val docId: String,
        val uri: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long,
    )

    private fun children(context: Context, tree: Uri, parentDocId: String): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<Child>()
        runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val id = c.getString(1) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    out.add(
                        Child(
                            name = name,
                            docId = id,
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                            isDir = isDir,
                            size = if (!c.isNull(3)) c.getLong(3) else 0L,
                            modified = if (!c.isNull(4)) c.getLong(4) else 0L,
                        ),
                    )
                }
            }
        }
        return out
    }
}
