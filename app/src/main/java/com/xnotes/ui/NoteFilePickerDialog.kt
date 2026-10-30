package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pick a note from the app's own storage as a link target: folder navigation
 * with breadcrumbs, search within the folder, and note rows to tap. Only `.xnote`
 * files are offered (links point at note pages); the note the link is created
 * from is left out via [excludeUri].
 */
@Composable
fun NoteFilePickerDialog(
    editor: Editor,
    excludeUri: String? = null,
    onPick: (uri: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val root = editor.browseRoot
    var trail by remember(root) { mutableStateOf(listOf<String>()) } // folder docIds below the root
    var query by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_link_pick_title)) },
        text = {
            if (root == null) {
                Text(stringResource(R.string.graph_no_folder), color = palette.textDim.toComposeColor())
            } else {
                val currentId = trail.lastOrNull()
                val children by produceState<List<BrowseEntry>?>(null, root, currentId, refreshTick) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val parent = currentId ?: editor.browseRootDocId(root)
                            editor.browseChildren(root, parent)
                        }.getOrNull()
                    }
                }
                Column {
                    // Breadcrumbs: root plus each descended folder.
                    val crumbNames by produceState<List<String>?>(null, root, trail) {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                // Names resolve through the same listing the explorer shows.
                                val names = ArrayList<String>()
                                var parent = editor.browseRootDocId(root)
                                for (id in trail) {
                                    val entry = editor.browseChildren(root, parent).firstOrNull {
                                        runCatching { editor.browseDocId(it.documentUri) }.getOrNull() == id
                                    }
                                    names.add(entry?.name ?: "…")
                                    parent = id
                                }
                                names.toList()
                            }.getOrNull()
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { trail = emptyList(); query = "" }) {
                            Text(stringResource(R.string.note_link_pick_root), fontWeight = if (trail.isEmpty()) FontWeight.Bold else FontWeight.Normal)
                        }
                        val names = crumbNames.orEmpty()
                        for ((i, id) in trail.withIndex()) {
                            Text("›", color = palette.textDim.toComposeColor())
                            TextButton(onClick = { trail = trail.take(i + 1); query = "" }) {
                                Text(
                                    names.getOrElse(i) { "…" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (i == trail.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    ExplorerSearchField(query, { query = it }, expandedWidth = 320.dp)
                    Spacer(Modifier.height(4.dp))
                    when (val list = children) {
                        null -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        else -> {
                            val q = query.trim().lowercase()
                            val folders = list.filter { it.isDir && (q.isEmpty() || it.name.lowercase().contains(q)) }
                                .sortedBy { it.name.lowercase() }
                            val notes = list.filter {
                                !it.isDir && DocumentKind.ofName(it.name) == DocumentKind.NOTE &&
                                    it.documentUri != excludeUri &&
                                    (q.isEmpty() || DocumentKind.stripSuffix(it.name).lowercase().contains(q))
                            }.sortedBy { DocumentKind.stripSuffix(it.name).lowercase() }
                            if (folders.isEmpty() && notes.isEmpty()) {
                                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.note_link_pick_empty), color = palette.textDim.toComposeColor())
                                }
                            } else {
                                LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                                    items(folders, key = { it.documentUri }) { e ->
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                                .clickable { trail = trail + runCatching { editor.browseDocId(e.documentUri) }.getOrDefault("") ; query = "" }
                                                .padding(horizontal = 8.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(XnotesIcons.folder, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(10.dp))
                                            Text(e.name, color = palette.text.toComposeColor(), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    items(notes, key = { it.documentUri }) { e ->
                                        val stem = DocumentKind.stripSuffix(e.name)
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                                .clickable { onPick(e.documentUri, stem) }
                                                .padding(horizontal = 8.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(XnotesIcons.note, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(10.dp))
                                            Text(stem, color = palette.text.toComposeColor(), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { refreshTick++ }) { Text(stringResource(R.string.graph_refresh)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
