package com.xnotes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.core.tools.ToolbarTransferDraft
import com.xnotes.core.tools.ToolbarWorkspace
import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarWorkspacePreset
import com.xnotes.ui.icons.XnotesIcons

/** Review the destination as it will look, including existing bars and omitted tools. */
@Composable
internal fun ToolbarCopyDialog(
    targetLabel: String,
    candidate: ToolbarWorkspace,
    draft: ToolbarTransferDraft,
    look: ToolbarLook,
    colors: List<Rgba>,
    activeColorIndex: Int,
    sharedCanvasWillDisable: Boolean,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy bar to $targetLabel") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Preview in the destination · ${draft.retainedCount} elements compatible")
                ToolbarWorkspacePreview(
                    workspace = candidate,
                    look = look,
                    selectedId = draft.bar.id,
                    selectedEntry = null,
                    colors = colors,
                    activeColorIndex = activeColorIndex,
                    pageNumber = 1,
                    pageCount = 1,
                    zoomLabel = "100%",
                    titleLabel = targetLabel,
                    onSelectBar = {},
                    onSelectEntry = { _, _, _ -> },
                    onMoveBar = { _, _ -> },
                )
                if (draft.omitted.isNotEmpty()) {
                    Text("Will not be copied (${draft.omitted.size}): " +
                        draft.omitted.joinToString { it.id.replace('_', ' ') })
                } else Text("All elements are compatible.")
                if (sharedCanvasWillDisable) {
                    Text("pcanvas uses the shared profile. Confirming will enable its individual profile.")
                }
                Text("The bar's dimensions, sections, spacing and alignment will be preserved.")
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy, enabled = draft.retainedCount > 0) { Text("Copy bar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A compact library with saved profiles and built-in layouts in separate sections. */
@Composable
internal fun ToolbarPresetsDialog(
    presets: List<ToolbarWorkspacePreset>,
    templates: List<ToolbarWorkspacePreset>,
    statusMessage: String?,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onUseTemplate: (ToolbarWorkspacePreset) -> Unit,
    onSave: (String) -> Boolean,
    onRestore: (ToolbarWorkspacePreset) -> Unit,
    onDelete: (String) -> Unit,
    onExportOne: (ToolbarWorkspacePreset) -> Unit,
    onExportCurrent: () -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var libraryMenuOpen by remember { mutableStateOf(false) }
    var presetMenuOpen by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workspaces") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedTab == 0, onClick = { onSelectTab(0) }, label = { Text("My saved") })
                    FilterChip(selected = selectedTab == 1, onClick = { onSelectTab(1) }, label = { Text("Templates") })
                }
                statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (selectedTab == 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { saving = !saving; error = null }) { Text("Save current") }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { libraryMenuOpen = true }) {
                                Icon(XnotesIcons.more, contentDescription = "Import and export workspaces")
                            }
                            DropdownMenu(
                                expanded = libraryMenuOpen,
                                onDismissRequest = { libraryMenuOpen = false },
                            ) {
                                DropdownMenuItem(text = { Text("Import") }, onClick = {
                                    libraryMenuOpen = false
                                    onImport()
                                })
                                DropdownMenuItem(text = { Text("Export current workspace") }, onClick = {
                                    libraryMenuOpen = false
                                    onExportCurrent()
                                })
                                if (presets.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text("Export all saved") }, onClick = {
                                        libraryMenuOpen = false
                                        onExportAll()
                                    })
                                }
                            }
                        }
                    }
                    if (saving) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(48); error = null },
                            label = { Text("Workspace name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                if (onSave(name)) {
                                    saving = false
                                    name = ""
                                    error = null
                                } else error = "Use a different name (limit of 20)."
                            }) { Text("Save") }
                            TextButton(onClick = { saving = false }) { Text("Cancel") }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (presets.isEmpty()) Text("No saved workspaces yet.")
                    presets.forEach { preset ->
                        Column(
                            Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .padding(4.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(
                                    Modifier.weight(1f)
                                        .clickable { onRestore(preset) }
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${preset.workspaces.note.bars.size} pnotes bars · ${preset.workspaces.canvas.bars.size} pcanvas",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Box {
                                    IconButton(onClick = { presetMenuOpen = preset.id }) {
                                        Icon(XnotesIcons.more, contentDescription = "Options for ${preset.name}")
                                    }
                                    DropdownMenu(
                                        expanded = presetMenuOpen == preset.id,
                                        onDismissRequest = { presetMenuOpen = null },
                                    ) {
                                        DropdownMenuItem(text = { Text("Export") }, onClick = {
                                            presetMenuOpen = null
                                            onExportOne(preset)
                                        })
                                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                                            presetMenuOpen = null
                                            pendingDelete = preset.id
                                        })
                                    }
                                }
                            }
                            if (pendingDelete == preset.id) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { onDelete(preset.id); pendingDelete = null }) { Text("Confirm delete") }
                                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                                }
                            }
                        }
                    }
                } else {
                    templates.forEach { template ->
                        Column(
                            Modifier.fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                                .clickable { onUseTemplate(template) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(template.name, style = MaterialTheme.typography.titleSmall)
                            Text(workspaceTemplateDescription(template.id),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun workspaceTemplateDescription(id: String): String = when (id) {
    "builtin-original" -> "Default pnotes bar, to restart a customization."
    "builtin-focus" -> "A single compact bottom bar for distraction-free writing."
    "builtin-study" -> "Top navigation with writing on a separate bottom bar."
    "builtin-pdf" -> "Document navigation on top, annotation tools on the right."
    "builtin-drawing" -> "Strokes arranged in two rows, bottom palette, actions on top."
    "builtin-markup" -> "Bottom markup case with separate document controls."
    "builtin-complete" -> "All main actions on top, aligned to start, center and end."
    "builtin-two-rows" -> "Tools in two bottom rows with navigation on top."
    else -> "Another tool arrangement."
}

/** Preview both profiles before replacing the live workspace with a saved or built-in layout. */
@Composable
internal fun ToolbarPresetPreviewDialog(
    preset: ToolbarWorkspacePreset,
    canvasInitially: Boolean,
    onApply: (ToolbarContext?) -> Unit,
    onDismiss: () -> Unit,
) {
    var canvasPreview by remember(preset.id) { mutableStateOf(canvasInitially) }
    var applyBoth by remember(preset.id) { mutableStateOf(false) }
    val context = if (canvasPreview) ToolbarContext.CANVAS else ToolbarContext.NOTE
    val workspace = preset.workspaces.forContext(context)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(preset.name) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !canvasPreview, onClick = { canvasPreview = false },
                        label = { Text("pnotes") })
                    FilterChip(selected = canvasPreview, onClick = { canvasPreview = true },
                        label = { Text("pcanvas") })
                }
                ToolbarWorkspacePreview(
                    workspace = workspace,
                    look = preset.look,
                    selectedId = workspace.bars.first().id,
                    selectedEntry = null,
                    colors = preset.colors.take(preset.colorCount),
                    activeColorIndex = 0,
                    pageNumber = 1,
                    pageCount = 1,
                    zoomLabel = "100%",
                    titleLabel = if (canvasPreview) "Canvas" else "Note",
                    onSelectBar = {},
                    onSelectEntry = { _, _, _ -> },
                    onMoveBar = { _, _ -> },
                )
                FilterChip(
                    selected = applyBoth,
                    onClick = { applyBoth = !applyBoth },
                    label = { Text("Also apply to the other editor") },
                )
                Text(if (applyBoth)
                    "Replaces the pnotes and pcanvas bars, plus the global appearance and palette."
                    else "Replaces only the ${if (canvasPreview) "pcanvas" else "pnotes"} bars. The other editor, colors and global appearance will be preserved.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(if (applyBoth) null else context) }) {
                Text(if (applyBoth) "Apply to both" else "Apply to ${if (canvasPreview) "pcanvas" else "pnotes"}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back to list") } },
    )
}
