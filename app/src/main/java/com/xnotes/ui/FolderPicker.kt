package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks a folder in the tree under [root] to move things into, starting at [start] (a path of (document id,
 * name) below the top folder). [blocked] folders, the ones being moved, can't be opened or picked.
 *
 * Encrypted folders are vault-aware: a locked folder shows a lock, tapping it asks for the
 * password instead of entering, and a locked destination cannot be confirmed until unlocked.
 */
@Composable
internal fun FolderPickerDialog(
    editor: Editor,
    root: String,
    rootName: String,
    title: String,
    confirmLabel: String,
    start: List<Pair<String, String>>,
    blocked: (docId: String) -> Boolean,
    onPick: (docId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val rootId = remember(root) { editor.browseRootDocId(root) }
    val path = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(start) } }
    val here = path.lastOrNull()?.first ?: rootId
    val folders by produceState(editor.cachedChildren(root, here)?.filter { it.isDir }, root, here) {
        value = withContext(Dispatchers.IO) { editor.browseChildren(root, here).filter { it.isDir } }
    }
    // Re-evaluate lock badges whenever any folder locks/unlocks elsewhere.
    val vaultEpoch = com.xnotes.crypto.VaultSession.epoch
    val list = folders
    var lockedById by remember(root) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(root, here, list, vaultEpoch) {
        val items = list ?: return@LaunchedEffect
        lockedById = withContext(Dispatchers.IO) {
            items.associate { f ->
                val id = runCatching { editor.browseDocId(f.documentUri) }.getOrDefault("")
                id to runCatching { editor.isLockedFolder(root, id) }.getOrDefault(false)
            }
        }
    }
    var hereLocked by remember(root) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(root, here, vaultEpoch) {
        hereLocked = withContext(Dispatchers.IO) {
            runCatching { editor.isLockedFolder(root, here) }.getOrDefault(false)
        }
    }
    var unlockTarget by remember(root) { mutableStateOf<Pair<String, String>?>(null) }
    var unlockBusy by remember(root) { mutableStateOf(false) }
    var unlockError by remember(root) { mutableStateOf<String?>(null) }
    val unlockFailedText = stringResource(R.string.err_unlock_folder)
    unlockTarget?.let { (id, name) ->
        UnlockVaultDialog(
            error = unlockError,
            busy = unlockBusy,
            onConfirm = { password ->
                unlockBusy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { editor.unlockVault(root, id, password) }
                    unlockBusy = false
                    if (ok) {
                        unlockError = null
                        unlockTarget = null
                        // Refresh badges and the destination gate, then step into the folder just unlocked.
                        hereLocked = withContext(Dispatchers.IO) {
                            runCatching { editor.isLockedFolder(root, here) }.getOrDefault(false)
                        }
                        if (id != here) path.add(id to name)
                    } else {
                        unlockError = unlockFailedText
                    }
                }
            },
            onDismiss = { if (!unlockBusy) { unlockError = null; unlockTarget = null } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().height(380.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Crumb("${rootName}/", current = path.isEmpty()) { path.clear() }
                    path.forEachIndexed { i, (_, name) ->
                        Crumb("$name/", current = i == path.lastIndex) { while (path.size > i + 1) path.removeAt(path.lastIndex) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (hereLocked == true) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(XnotesIcons.lock, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.vault_destination_locked),
                            color = palette.textDim.toComposeColor(),
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            val name = path.lastOrNull()?.second ?: rootName
                            unlockTarget = here to name
                        }) { Text(stringResource(R.string.unlock_folder)) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                when {
                    list == null -> EmptyNote(stringResource(R.string.loading))
                    list.isEmpty() -> EmptyNote(stringResource(R.string.no_folders_here))
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(list, key = { it.documentUri }) { f ->
                            val id = editor.browseDocId(f.documentUri)
                            val off = blocked(id)
                            val locked = lockedById[id] == true
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(enabled = !off) {
                                        if (locked) unlockTarget = id to f.name
                                        else path.add(id to f.name)
                                    }
                                    .alpha(if (off) 0.4f else 1f)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val code = f.color?.let { codeTint(it, palette) }
                                Icon(
                                    if (locked) XnotesIcons.lock else XnotesIcons.folder,
                                    null,
                                    tint = code ?: palette.textDim.toComposeColor(),
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(f.name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(
                                    if (locked) XnotesIcons.lock else XnotesIcons.next,
                                    null,
                                    tint = palette.textDim.toComposeColor(),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(here) }, enabled = !blocked(here) && hereLocked != true) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
