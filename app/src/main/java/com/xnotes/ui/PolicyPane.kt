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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.snapshots.SnapshotStateSet
import android.content.Context
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Which files the companion may expose. Shared files list first; the rest
 * streams in as the tree walk proceeds, so the screen is usable at once.
 * A tick flips instantly (local state) and persists + notifies off the main
 * thread — toggling never walks the tree or touches disk on the UI thread.
 */
@Composable
fun PolicyPane(editor: Editor) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val policy = remember { SharePolicy.of(File(context.filesDir, "share")) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ShareServerEntry>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(0) }
    // The checkbox state lives here, not in the policy: a toggle flips one row
    // without refiltering the list or re-reading the policy file.
    val shared: SnapshotStateSet<String> = remember { mutableStateSetOf<String>().apply { addAll(policy.sharedUris()) } }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    fun notifyChanged() {
        // One notification per burst of taps: the engine rebuilds the index off
        // the main thread and coalesces rapid refreshes itself.
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(400)
            withContext(Dispatchers.IO) {
                com.xnotes.share.companion.CompanionService.refreshPolicy(context)
            }
        }
    }

    val root = editor.browseRoot
    LaunchedEffect(root) {
        if (root == null) return@LaunchedEffect
        scanning = true
        withContext(Dispatchers.IO) {
            runCatching {
                val backend = ShareBackendImpl(context.applicationContext)
                val sharedNow = policy.sharedUris()
                // Phase 1: the shared files, by direct stat — no tree walk.
                val first = sharedNow.mapNotNull { uri ->
                    backend.statEntry(uri)?.let { ShareServerEntry(it.uri, it.name, it.size, it.mtime, it.kind) }
                }.sortedBy { it.lower }
                entries = first
                scanned = first.size
                // Phase 2: everything else, published in chunks so the list
                // stays responsive while a big tree is walked.
                val seen = HashSet<String>(first.size * 2)
                first.forEach { seen.add(it.uri) }
                val rest = ArrayList<ShareServerEntry>()
                val queue = ArrayDeque<String?>(listOf(null))
                while (queue.isNotEmpty()) {
                    val dir = queue.removeFirst()
                    for (e in backend.listEntries(dir, onlyShared = false)) {
                        if (!seen.add(e.uri)) continue
                        if (e.isDir) {
                            queue.add(e.uri)
                        } else {
                            rest.add(ShareServerEntry(e.uri, e.name, e.size, e.mtime, e.kind))
                        }
                    }
                    if (rest.size >= 250) {
                        val snap = (first + rest).sortedWith(sharedFirst(sharedNow))
                        entries = snap
                        scanned = snap.size
                    }
                }
                val all = (first + rest).sortedWith(sharedFirst(sharedNow))
                entries = all
                scanned = all.size
                // Selections pointing at deleted files are dropped on sight.
                if (policy.pruneMissing(all.map { it.uri }.toSet()) > 0) {
                    val keep = policy.sharedUris()
                    shared.removeAll { it !in keep }
                }
            }
        }
        scanning = false
    }
    DisposableEffect(Unit) {
        onDispose {
            // Never lose the last tap in a burst to a cancelled debounce.
            refreshJob?.let { job ->
                if (job.isActive) {
                    job.cancel()
                    CompanionServiceFlush.refreshPolicyNow(context)
                }
            }
        }
    }

    val q = query.trim().lowercase()
    val shown = remember(entries, q) {
        if (q.isEmpty()) entries else entries.filter { it.lower.contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.policy),
            color = palette.text.toComposeColor(),
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.policy_help, shared.size),
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
        if (shown.isEmpty() && !scanning) {
            Text(
                stringResource(if (entries.isEmpty()) R.string.policy_empty else R.string.policy_no_match),
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shown, key = { it.uri }) { e ->
                    val isShared = e.uri in shared
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (isShared) shared.remove(e.uri) else shared.add(e.uri)
                            scope.launch(Dispatchers.IO) { policy.setShared(e.uri, !isShared) }
                            notifyChanged()
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isShared,
                            onCheckedChange = {
                                if (it) shared.add(e.uri) else shared.remove(e.uri)
                                scope.launch(Dispatchers.IO) { policy.setShared(e.uri, it) }
                                notifyChanged()
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
                if (scanning) {
                    item {
                        Text(
                            stringResource(R.string.share_starting) + " ($scanned)",
                            color = palette.textDim.toComposeColor(),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Shared files first (as selected when the list loaded), then by name. Order is fixed after load so rows never jump under the finger. */
internal fun sharedFirst(shared: Set<String>): Comparator<ShareServerEntry> =
    compareByDescending<ShareServerEntry> { it.uri in shared }.thenBy { it.lower }

/** A shareable file row: identity, display facts, no preview pixels (by design). */
internal data class ShareServerEntry(
    val uri: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val kind: String,
) {
    /** Lowercased once, so searching never re-lowercases the whole list per keystroke. */
    val lower: String = name.lowercase()
}

/** Flush a pending policy notification synchronously (pane teardown). */
private object CompanionServiceFlush {
    fun refreshPolicyNow(context: Context) {
        runCatching {
            com.xnotes.share.companion.CompanionService.refreshPolicy(context)
        }
    }
}

private fun formatBytes(size: Long): String = when {
    size < 0 -> "?"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%d KB".format(size / 1024)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}

private fun formatDate(mtime: Long): String =
    if (mtime <= 0) "?"
    else runCatching { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(mtime)) }.getOrDefault("?")
