package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.model.NoteLinkItem
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The form half of creating a note link; the activity supplies the picked target file. */
data class NoteLinkForm(
    val displayName: String,
    val self: Boolean,
    val pages: List<Int>,
    val color: Rgba,
    val rounded: Boolean,
)

/**
 * The dialog's editable state, held by the caller (not the dialog) so opening the
 * in-app file picker — which swaps the dialog out — never resets the form.
 */
@Stable
class NoteLinkFormState {
    var displayName by mutableStateOf("")
    var self by mutableStateOf(true)
    var spec by mutableStateOf("")
    var color by mutableStateOf(NoteLinkItem.DEFAULT_COLOR)
    var rounded by mutableStateOf(false)

    fun reset() {
        displayName = ""
        self = true
        spec = ""
        color = NoteLinkItem.DEFAULT_COLOR
        rounded = false
    }

    /** The validated form, or null when no parseable pages were entered. */
    fun toForm(): NoteLinkForm? {
        val pages = NoteLinkItem.parsePages(spec)
        if (pages.isEmpty()) return null
        return NoteLinkForm(displayName.trim(), self, pages, color, rounded)
    }
}

/**
 * Dialog for creating a note link: a label, a target (this note, an app note or a
 * file), 1-based pages, the shared swatch picker for color and a button shape.
 * [targetName] is the picked file's display name ("" = none yet).
 */
@Composable
fun NoteLinkDialog(
    form: NoteLinkFormState,
    initialTargetName: String = "",
    targetName: String = "",
    onPickFile: () -> Unit,
    onPickInternal: () -> Unit = {},
    onConfirm: (NoteLinkForm) -> Unit,
    onDismiss: () -> Unit,
) {
    var colorMenuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val badPages = stringResource(R.string.note_link_bad_pages)
    val needsTarget = stringResource(R.string.note_link_needs_target)
    val shown = if (form.self) initialTargetName else targetName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.displayName,
                    onValueChange = { form.displayName = it },
                    label = { Text(stringResource(R.string.note_link_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = form.self, onCheckedChange = { form.self = it })
                    Text(
                        stringResource(R.string.note_link_this_note),
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clickable { form.self = !form.self },
                    )
                }
                if (!form.self) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onPickInternal, modifier = Modifier.weight(1f)) {
                            Text(
                                if (shown.isEmpty()) stringResource(R.string.note_link_browse_notes)
                                else shown,
                            )
                        }
                        TextButton(onClick = onPickFile) {
                            Text(stringResource(R.string.note_link_choose_file))
                        }
                    }
                }
                OutlinedTextField(
                    value = form.spec,
                    onValueChange = { form.spec = it },
                    label = { Text(stringResource(R.string.note_link_pages)) },
                    placeholder = { Text(stringResource(R.string.note_link_pages_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.note_link_color), style = MaterialTheme.typography.labelLarge)
                // Compact preview: the shared swatches open only once pressed.
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, LocalPalette.current.border.toComposeColor(), RoundedCornerShape(8.dp))
                            .clickable { colorMenuOpen = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(form.color.toComposeColor())
                                .border(1.dp, LocalPalette.current.border.toComposeColor(), CircleShape),
                        )
                        Text(
                            Rgba.toHex(form.color).uppercase(),
                            color = LocalPalette.current.text.toComposeColor(),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = LocalPalette.current.textDim.toComposeColor())
                    }
                    DropdownMenu(
                        expanded = colorMenuOpen,
                        onDismissRequest = { colorMenuOpen = false },
                        modifier = Modifier.width(292.dp),
                    ) {
                        Column(
                            Modifier
                                .heightIn(max = 248.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            FullSwatchGrid {
                                form.color = it
                                colorMenuOpen = false
                            }
                        }
                    }
                }
                Text(stringResource(R.string.note_link_shape), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !form.rounded,
                        onClick = { form.rounded = false },
                        label = { Text(stringResource(R.string.note_link_shape_rect)) },
                    )
                    FilterChip(
                        selected = form.rounded,
                        onClick = { form.rounded = true },
                        label = { Text(stringResource(R.string.note_link_shape_rounded)) },
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!form.self && targetName.isEmpty()) {
                    error = needsTarget
                    return@TextButton
                }
                val f = form.toForm()
                if (f == null) {
                    error = badPages
                    return@TextButton
                }
                onConfirm(f)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
