package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.StickyNoteItem
import com.xnotes.ui.theme.LocalPalette

/**
 * The sticky note's text and paper color: plain text plus a swatch row of
 * post-it papers. Collapsing, moving and deleting stay on the canvas itself
 * (the × corner, drag, selection delete); this dialog only edits content.
 */
@Composable
fun StickyNoteDialog(editor: Editor) {
    val target = editor.stickyEditTarget ?: return
    var text by remember(target) { mutableStateOf(target.text) }
    var color by remember(target) { mutableStateOf(target.color) }
    val palette = LocalPalette.current
    AlertDialog(
        onDismissRequest = { editor.dismissStickyEdit() },
        title = { Text(stringResource(R.string.sticky_note_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.sticky_note_text_hint)) },
                    minLines = 3,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (swatch in StickyNoteItem.PALETTE) {
                        StickySwatch(
                            color = swatch,
                            active = swatch == color,
                            onClick = { color = swatch },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { editor.saveStickyEdit(text, color) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { editor.dismissStickyEdit() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun StickySwatch(color: Rgba, active: Boolean, onClick: () -> Unit) {
    val fill = Color(color.r, color.g, color.b, 255)
    Box(
        Modifier
            .size(36.dp)
            .then(if (active) Modifier.border(2.dp, fill, CircleShape) else Modifier)
            .padding(5.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onClick),
    )
}

/**
 * The shared swatch picker opened from a sticky note's color dot: the same
 * swatches/spectrum/HEX picker the style bars and popups use, anchored at the
 * dot. One pick repaints the paper and closes.
 */
@Composable
fun StickyColorPopup(editor: Editor) {
    val target = editor.stickyColorTarget ?: return
    val density = LocalDensity.current
    val xDp = with(density) { target.anchorViewport.x.toFloat().toDp() }
    val yDp = with(density) { target.anchorViewport.y.toFloat().toDp() }
    Box(modifier = Modifier.offset(xDp, yDp).size(1.dp)) {
        ColorPickerPopup(
            initial = target.item.color,
            recents = editor.recentColors,
            onDismiss = { editor.dismissStickyColor() },
            onPick = { editor.saveStickyColor(target.item, it) },
        )
    }
}
