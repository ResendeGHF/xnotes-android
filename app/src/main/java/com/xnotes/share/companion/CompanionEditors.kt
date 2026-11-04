package com.xnotes.share.companion

import com.xnotes.ui.Editor

/**
 * Open editors by shared file URI, for the sync host to route remote ops,
 * live frames and presence. Editors register on every document swap.
 */
object CompanionEditors {
    private val lock = Any()
    private val open = HashMap<Editor, String?>()
    var onTracked: ((Editor, String?) -> Unit)? = null

    fun track(editor: Editor, uri: String?) {
        synchronized(lock) { open[editor] = uri }
        onTracked?.invoke(editor, uri)
    }

    fun forget(editor: Editor) {
        synchronized(lock) { open.remove(editor) }
    }

    fun editorFor(uri: String): Editor? = synchronized(lock) {
        open.entries.firstOrNull { it.value == uri }?.key
    }

    fun uriFor(editor: Editor): String? = synchronized(lock) { open[editor] }

    fun editors(): List<Editor> = synchronized(lock) { open.keys.toList() }
}
