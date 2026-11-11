package com.xnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xnotes.core.tools.ToolbarContext
import com.xnotes.settings.ToolbarWorkspaceExchangeJson
import com.xnotes.settings.ToolbarWorkspacePreset
import com.xnotes.settings.ToolbarWorkspaceTemplates

/** Navigation and pending file exchange belong to the configurator, never to the active workspace. */
internal class ToolbarWorkspaceLibraryState(initialPresets: List<ToolbarWorkspacePreset>) {
    var visible by mutableStateOf(false)
    var selectedTab by mutableStateOf(0)
    var presets by mutableStateOf(initialPresets)
    var preview by mutableStateOf<ToolbarWorkspacePreset?>(null)
    var exportContent by mutableStateOf("")
    var statusMessage by mutableStateOf<String?>(null)

    fun show(presets: List<ToolbarWorkspacePreset>) {
        this.presets = presets
        statusMessage = null
        visible = true
    }
}

/** Android file pickers and library navigation; the caller owns saving and applying profiles. */
@Composable
internal fun ToolbarWorkspaceLibrary(
    state: ToolbarWorkspaceLibraryState,
    canvasInitially: Boolean,
    currentPreset: () -> ToolbarWorkspacePreset,
    getPresets: () -> List<ToolbarWorkspacePreset>,
    onSave: (String) -> Boolean,
    onDelete: (String) -> Unit,
    onImport: (List<ToolbarWorkspacePreset>) -> Int,
    onApply: (ToolbarWorkspacePreset, ToolbarContext?) -> Unit,
) {
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(state.exportContent.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open the file.")
            }.onSuccess { state.statusMessage = "Workspace exported successfully." }
                .onFailure { state.statusMessage = "Export failed: ${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the file.")
                require(raw.length <= 1_000_000) { "File too large." }
                onImport(ToolbarWorkspaceExchangeJson.import(raw))
            }.onSuccess { count ->
                state.presets = getPresets()
                state.statusMessage = if (count > 0) "$count workspace(s) imported."
                    else "Saved workspace limit of 20 reached."
            }.onFailure { state.statusMessage = "Import failed: ${it.message}" }
        }
    }
    fun export(presets: List<ToolbarWorkspacePreset>, filename: String) {
        state.exportContent = ToolbarWorkspaceExchangeJson.export(presets)
        exportLauncher.launch(filename)
    }
    fun showPreview(preset: ToolbarWorkspacePreset) {
        state.visible = false
        state.preview = preset
    }
    if (state.visible) {
        ToolbarPresetsDialog(
            presets = state.presets,
            templates = ToolbarWorkspaceTemplates.presets,
            statusMessage = state.statusMessage,
            selectedTab = state.selectedTab,
            onSelectTab = { state.selectedTab = it },
            onExportOne = { preset ->
                val basename = preset.name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
                export(listOf(preset), "$basename.xworkspaces.json")
            },
            onExportCurrent = { export(listOf(currentPreset()), "toolbar-current.xworkspaces.json") },
            onExportAll = { export(state.presets, "saved-toolbars.xworkspaces.json") },
            onImport = { importLauncher.launch(arrayOf("application/json", "application/octet-stream")) },
            onUseTemplate = { showPreview(it) },
            onSave = { name ->
                onSave(name).also { saved ->
                    if (saved) state.presets = getPresets()
                }
            },
            onRestore = { showPreview(it) },
            onDelete = { id ->
                onDelete(id)
                state.presets = getPresets()
            },
            onDismiss = { state.visible = false },
        )
    }
    state.preview?.let { preset ->
        ToolbarPresetPreviewDialog(
            preset = preset,
            canvasInitially = canvasInitially,
            onApply = { targetContext ->
                onApply(preset, targetContext)
                state.preview = null
            },
            onDismiss = {
                state.preview = null
                state.visible = true
            },
        )
    }
}
