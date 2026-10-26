package com.xnotes.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
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
    // The highlight belongs to the tab strip, not the individual buttons, so it can
    // travel between tabs (including tabs with different title lengths).
    val bounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val selected = activeUri?.let(bounds::get)
    val highlightX by animateFloatAsState(selected?.first ?: 0f, tween(220), label = "tabHighlightX")
    val highlightWidth by animateFloatAsState(selected?.second ?: 0f, tween(220), label = "tabHighlightWidth")
    val highlight = palette.selectionBackground.toComposeColor()
    Row(
        modifier.fillMaxWidth()
            .background(palette.bg.toComposeColor())
            .horizontalScroll(rememberScrollState())
            .height(42.dp)
            .drawBehind {
                if (selected != null && highlightWidth > 0f) {
                    val inset = 2.dp.toPx()
                    drawRoundRect(
                        highlight.copy(alpha = 0.32f),
                        topLeft = Offset(highlightX, inset),
                        size = Size(highlightWidth, size.height - inset * 2),
                        cornerRadius = CornerRadius((size.height - inset * 2) / 2),
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.tabs.forEach { tab ->
            Row(
                Modifier.padding(horizontal = 2.dp)
                    .onGloballyPositioned { coordinates ->
                        bounds[tab.uri] = coordinates.positionInParent().x to coordinates.size.width.toFloat()
                    }
                    .clip(RoundedCornerShape(8.dp)), 
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
