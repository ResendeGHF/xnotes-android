package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.workspace.NoteTabs
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Independent workspace bar. It depends on tab metadata and actions, not on the page toolbar,
 * canvas, or Editor. A future customizable toolbar host can mount or replace this component.
 */
@Composable
fun NoteTabBar(
    tabs: NoteTabs,
    activeUri: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth()
            .background(palette.bg.toComposeColor())
            .horizontalScroll(rememberScrollState())
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.tabs.forEach { tab ->
            Row(
                Modifier.padding(horizontal = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background((if (tab.uri == activeUri) palette.accent else palette.border).toComposeColor().copy(alpha = 0.18f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onSelect(tab.uri) }) {
                    Text(tab.title, maxLines = 1, fontSize = 12.sp)
                }
                TextButton(onClick = { onClose(tab.uri) }, modifier = Modifier.width(36.dp)) {
                    Text("×", fontSize = 16.sp)
                }
            }
        }
    }
}
