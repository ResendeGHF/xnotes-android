package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.model.MathStudioInstanceItem
import com.xnotes.core.model.Rgba

@Composable
fun MathStudioInstanceDialog(editor: Editor) {
    val target = editor.mathInstanceEditTarget ?: return
    var name by remember(target) { mutableStateOf(target.name) }
    var color by remember(target) { mutableStateOf(target.color) }
    AlertDialog(
        onDismissRequest = { editor.dismissMathInstanceEdit() },
        title = { Text(stringResource(R.string.math_instance_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.math_instance_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (swatch in MathStudioInstanceItem.PALETTE) {
                        InstanceSwatch(
                            color = swatch,
                            active = swatch == color,
                            onClick = { color = swatch },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { editor.saveMathInstanceEdit(name, color) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { editor.dismissMathInstanceEdit() }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun InstanceSwatch(color: Rgba, active: Boolean, onClick: () -> Unit) {
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
