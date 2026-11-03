package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.share.ShareBackendImpl
import com.xnotes.share.SharePolicy
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Which files the LAN server may expose. Lists every file under the granted
 * explorer root (name + metadata only, no thumbnails) with a search box; a
 * ticked file becomes visible to the paired desktop, an unticked one behaves
 * as if it did not exist (the server answers 404). Nothing is shared until
 * the user ticks it here.
 */
@Composable
fun PolicyPane(editor: Editor) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val policy = remember { SharePolicy.of(File(context.filesDir, "share")) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ShareServerEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var ticks by remember { mutableStateOf(0) } // bump to re-read the policy after a toggle

    val root = editor.browseRoot
    LaunchedEffect(root) {
        loading = true
        entries = withContext(Dispatchers.IO) {
            if (root == null) {
                emptyList()
            } else {
                runCatching {
                    val backend = ShareBackendImpl(context.applicationContext)
                    val out = ArrayList<ShareServerEntry>()
                    val queue = ArrayDeque<String?>(listOf(null))
                    val seen = HashSet<String>()
                    while (queue.isNotEmpty()) {
                        val dir = queue.removeFirst()
                        for (e in backend.listEntries(dir, onlyShared = false)) {
                            if (!seen.add(e.uri)) continue
                            if (e.isDir) {
                                queue.add(e.uri)
                            } else {
                                out.add(ShareServerEntry(e.uri, e.name, e.size, e.mtime, e.kind))
                            }
                        }
                    }
                    out.sortedBy { it.name.lowercase() }
                }.getOrDefault(emptyList())
            }
        }
        // Selections pointing at deleted files are dropped on sight.
        if (root != null) policy.pruneMissing(entries.map { it.uri }.toSet())
        ticks++
        loading = false
    }

    val q = query.trim().lowercase()
    val shown = remember(entries, q, ticks) {
        entries.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    }
    val sharedCount = remember(entries, ticks) { entries.count { policy.isShared(it.uri) } }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.policy),
            color = palette.text.toComposeColor(),
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.policy_help, sharedCount),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (root == null) {
            Text(
                stringResource(R.string.policy_no_root),
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.policy_search), fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        if (loading) {
            Text(stringResource(R.string.share_starting), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        } else if (shown.isEmpty()) {
            Text(
                stringResource(if (entries.isEmpty()) R.string.policy_empty else R.string.policy_no_match),
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shown, key = { it.uri }) { e ->
                    val shared = policy.isShared(e.uri)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            policy.setShared(e.uri, !shared)
                            ticks++
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = shared,
                            onCheckedChange = {
                                policy.setShared(e.uri, it)
                                ticks++
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1)
                            Text(
                                "${e.kind} · ${formatBytes(e.size)} · ${formatDate(e.mtime)}",
                                color = palette.textDim.toComposeColor(),
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A shareable file row: identity, display facts, no preview pixels (by design). */
private data class ShareServerEntry(
    val uri: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val kind: String,
)

private fun formatBytes(size: Long): String = when {
    size < 0 -> "?"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%d KB".format(size / 1024)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}

private fun formatDate(mtime: Long): String =
    if (mtime <= 0) "?"
    else runCatching { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(mtime)) }.getOrDefault("?")
