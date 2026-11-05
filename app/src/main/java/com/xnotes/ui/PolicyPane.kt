package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateSet
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.share.companion.CompanionService
import com.xnotes.share.ShareBackendImpl
import com.xnotes.share.db.DeviceEntity
import com.xnotes.share.db.DeviceGrants
import com.xnotes.share.db.ShareStore
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Which files and folders each paired companion may see. The device picker at
 * the top selects whose grants are edited; shared entries list first, then
 * everything else streams in as the tree walk proceeds. Folders grant whole
 * subtrees (tri-state shows partial shares). Ticks flip instantly from local
 * state; persistence and companion notification happen off the main thread.
 */
@Composable
fun PolicyPane(editor: Editor) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val store = remember { ShareStore.of(context.applicationContext) }
    // All grant writes go through here so the engine's in-memory gate sees them at once.
    val grants = remember { DeviceGrants.of(context.applicationContext) }
    val devices by store.devices.collectAsState(initial = emptyList())
    val activeDevice by store.activeDevice.collectAsState(initial = null)

    var selectedFp by remember { mutableStateOf<String?>(null) }
    // Default the picker to the active companion once devices arrive.
    LaunchedEffect(devices, activeDevice) {
        if (selectedFp == null) {
            selectedFp = activeDevice?.fp ?: devices.firstOrNull()?.fp
        } else if (devices.none { it.fp == selectedFp }) {
            selectedFp = activeDevice?.fp ?: devices.firstOrNull()?.fp
        }
    }

    var query by remember { mutableStateOf("") }
    // One immutable snapshot per walk pass: rows pre-sorted and folder->file
    // membership precomputed off the main thread, so opening the screen and
    // every chunked update is just a pointer swap (no frame drops).
    var snapshot by remember { mutableStateOf(PolicySnapshot(emptyList(), emptyMap())) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(0) }
    // Live checkbox state for the picked device; toggle flips one row.
    val shared: SnapshotStateSet<String> = remember { mutableStateSetOf() }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    val scope = rememberCoroutineScope()
    fun notifyChanged() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(400)
            withContext(Dispatchers.IO) { CompanionService.refreshPolicy(context) }
        }
    }

    val fp = selectedFp
    // Load this device's grants when the picker moves.
    LaunchedEffect(fp) {
        if (fp == null) {
            shared.clear()
        } else {
            val ids = withContext(Dispatchers.IO) { store.grantIds(fp) }
            shared.clear()
            shared.addAll(ids)
        }
    }

    fun setGranted(uri: String, granted: Boolean) {
        val device = fp ?: return
        if (granted) shared.add(uri) else shared.remove(uri)
        grants.setGranted(device, uri, granted)
        notifyChanged()
    }

    val root = editor.browseRoot
    LaunchedEffect(root, fp) {
        if (root == null) return@LaunchedEffect
        scanning = true
        withContext(Dispatchers.IO) {
            // Migrate legacy pins/policy on first sight even if the service
            // hasn't started yet (the screen works standalone).
            val prefs = context.getSharedPreferences(com.xnotes.share.companion.CompanionService.PREFS, 0)
            runCatching {
                ShareStore.of(context.applicationContext).migrateLegacy(
                    prefs.getString(com.xnotes.share.companion.CompanionService.KEY_IP, "") ?: "",
                    prefs.getString(com.xnotes.share.companion.CompanionService.KEY_PORT, "42317")?.toIntOrNull() ?: 0,
                )
            }
            runCatching {
                val backend = ShareBackendImpl(context.applicationContext)
                val orderBy = store.grantIds(fp ?: "").toSet()
                val order = sharedFirst(orderBy)
                // Phase 1: everything already shared — stat each URI directly.
                val first = orderBy.mapNotNull { uri ->
                    backend.statEntry(uri)?.let { ShareServerEntry(it.uri, it.name, it.size, it.mtime, it.kind, it.isDir) }
                }.sortedWith(order)
                val seen = HashSet<String>(first.size * 2)
                first.forEach { seen.add(it.uri) }
                run {
                    val (sorted, kids) = buildSnapshot(first, emptyMap(), order)
                    snapshot = PolicySnapshot(sorted, kids)
                    scanned = first.size
                }
                // Phase 2: the rest, in chunks; parent links ride along so the
                // folder->file map needs no O(folders x files) pass later.
                val rest = ArrayList<ShareServerEntry>()
                val parentOf = HashMap<String, String>()
                val queue = ArrayDeque<String?>(listOf(null))
                while (queue.isNotEmpty()) {
                    val dir = queue.removeFirst()
                    for (e in backend.listEntries(dir, onlyShared = false)) {
                        if (!seen.add(e.uri)) continue
                        if (dir != null) parentOf[e.uri] = dir
                        if (e.isDir) {
                            queue.add(e.uri)
                            rest.add(ShareServerEntry(e.uri, e.name, e.size, e.mtime, "folder", true))
                        } else {
                            rest.add(ShareServerEntry(e.uri, e.name, e.size, e.mtime, e.kind, false))
                        }
                    }
                    if (rest.size >= 250) {
                        val (sorted, kids) = buildSnapshot(first + rest, parentOf, order)
                    snapshot = PolicySnapshot(sorted, kids)
                    scanned = first.size + rest.size
                    }
                }
                val (sorted, kids) = buildSnapshot(first + rest, parentOf, order)
                snapshot = PolicySnapshot(sorted, kids)
                scanned = first.size + rest.size
            }
        }
        scanning = false
    }
    DisposableEffect(Unit) {
        onDispose {
            refreshJob?.let { if (it.isActive) it.cancel() }
        }
    }

    // Descendant files per folder come precomputed with the snapshot; the
    // tri-state below is a set lookup, never a tree walk.
    val q = query.trim().lowercase()
    val shown = remember(snapshot, q) {
        val ordered = snapshot.entries
        if (q.isEmpty()) ordered else ordered.filter { it.lower.contains(q) }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Shared files",
            color = palette.text.toComposeColor(),
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(com.xnotes.R.string.policy_help, shared.size),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (root == null) {
            Text(
                stringResource(com.xnotes.R.string.policy_no_root),
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
            return@Column
        }
        if (devices.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (d in devices) {
                    FilterChip(
                        selected = d.fp == fp,
                        onClick = { selectedFp = d.fp },
                        label = { Text((if (d.active) "● " else "") + d.displayName, fontSize = 12.sp, maxLines = 1) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        } else {
            Text(
                "Pair a companion in Connect first — shares are per device.",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(com.xnotes.R.string.policy_search), fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        // One tap to share the whole tree with the picked companion.
        val deviceFp = fp
        if (deviceFp != null && !scanning && snapshot.entries.isNotEmpty()) {
            val allUris = remember(snapshot) { snapshot.entries.map { it.uri } }
            val allShared = allUris.isNotEmpty() && allUris.all { it in shared }
            var sharingAll by remember(deviceFp) { mutableStateOf(false) }
            if (!allShared) {
                TextButton(
                    onClick = {
                        sharingAll = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                for (uri in allUris) {
                                    grants.setGranted(deviceFp, uri, true)
                                }
                                CompanionService.refreshPolicy(context)
                            }
                            val ids = withContext(Dispatchers.IO) { store.grantIds(deviceFp) }
                            shared.clear()
                            shared.addAll(ids)
                            sharingAll = false
                        }
                    },
                    enabled = !sharingAll,
                ) {
                    Text(if (sharingAll) "Sharing…" else "Share all files with this companion")
                }
                Spacer(Modifier.height(2.dp))
            }
        }
        if (shown.isEmpty() && !scanning) {
            Text(
                stringResource(if (snapshot.entries.isEmpty()) com.xnotes.R.string.policy_empty else com.xnotes.R.string.policy_no_match),
                color = palette.textDim.toComposeColor(),
                fontSize = 13.sp,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(shown, key = { it.uri }) { e ->
                    val isShared = e.uri in shared
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = fp != null) {
                            if (e.isDir) {
                                if (isShared || folderState(e, shared, snapshot.descendants) == ToggleableState.Indeterminate) {
                                    setGranted(e.uri, false)
                                } else {
                                    setGranted(e.uri, true)
                                }
                            } else {
                                setGranted(e.uri, !isShared)
                            }
                        }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (e.isDir) {
                            TriStateCheckbox(
                                state = if (isShared) ToggleableState.On
                                else folderState(e, shared, snapshot.descendants),
                                onClick = {
                                    if (isShared || folderState(e, shared, snapshot.descendants) == ToggleableState.Indeterminate) {
                                        setGranted(e.uri, false)
                                    } else {
                                        setGranted(e.uri, true)
                                    }
                                },
                                enabled = fp != null,
                            )
                        } else {
                            Checkbox(
                                checked = isShared,
                                onCheckedChange = {
                                    setGranted(e.uri, it)
                                },
                                enabled = fp != null,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (e.isDir) "\u25B8 " else "") + e.name,
                                color = palette.text.toComposeColor(),
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                            Text(
                                "${e.kind} · ${formatBytes(e.size)} · ${formatDate(e.mtime)}",
                                color = palette.textDim.toComposeColor(),
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (scanning) {
                    item {
                        Text(
                            stringResource(com.xnotes.R.string.share_starting) + " ($scanned)",
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

/** One published walk pass: rows pre-sorted, folder->file membership precomputed. */
private data class PolicySnapshot(
    val entries: List<ShareServerEntry>,
    val descendants: Map<String, Set<String>> = emptyMap(),
)

/** Sorted rows + folder->file membership for one walk pass (runs off the main thread). */
private fun buildSnapshot(
    all: List<ShareServerEntry>,
    parents: Map<String, String>,
    order: Comparator<ShareServerEntry>,
): Pair<List<ShareServerEntry>, Map<String, Set<String>>> {
    val sorted = all.sortedWith(order)
    val kids = HashMap<String, MutableSet<String>>()
    val folders = all.filter { it.isDir }.map { it.uri }.toSet()
    for (f in all) {
        if (f.isDir) continue
        var p = parents[f.uri]
        var guard = 0
        while (p != null && guard++ < 64) {
            if (p in folders) kids.getOrPut(p) { HashSet() }.add(f.uri)
            p = parents[p]
        }
    }
    return sorted to kids
}

/** Tri-state for a folder row: checked when granted, partial when a descendant is. */
private fun folderState(
    folder: ShareServerEntry,
    shared: Set<String>,
    descendants: Map<String, Set<String>>,
): ToggleableState {
    if (folder.uri in shared) return ToggleableState.On
    val kids = descendants[folder.uri] ?: return ToggleableState.Off
    return if (kids.any { it in shared }) ToggleableState.Indeterminate else ToggleableState.Off
}

/** Shared entries first (fixed order after load), then folders, then by name. */
internal fun sharedFirst(shared: Set<String>): Comparator<ShareServerEntry> =
    compareByDescending<ShareServerEntry> { it.uri in shared }
        .thenByDescending { it.isDir }
        .thenBy { it.lower }

/** A shareable file or folder row: identity, display facts, no preview pixels (by design). */
internal data class ShareServerEntry(
    val uri: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val kind: String,
    val isDir: Boolean,
) {
    /** Lowercased once, so searching never re-lowercases the whole list per keystroke. */
    val lower: String = name.lowercase()
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
