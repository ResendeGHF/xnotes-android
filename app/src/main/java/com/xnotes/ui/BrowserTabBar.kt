package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

@Composable
fun BrowserTabBar(
    editors: List<Editor>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    onTabClosed: (Int) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val homeLabel = stringResource(R.string.home)
    val untitledLabel = stringResource(R.string.untitled)
    val closeLabel = stringResource(R.string.close_tab)
    val newTabLabel = stringResource(R.string.new_tab)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.menuBg.toComposeColor())
            .padding(top = 4.dp), 
        verticalAlignment = Alignment.Bottom
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Bottom
        ) {
            editors.forEachIndexed { index, editor ->
                val isSelected = index == selectedIndex
                val bgColor = if (isSelected) palette.bg.toComposeColor() else Color.Transparent
                val textColor = if (isSelected) palette.text.toComposeColor() else palette.textDim.toComposeColor()
                
                val tabTitle = if (editor.noteOpen) {
                    editor.title.ifBlank { untitledLabel }
                } else {
                    homeLabel
                }

                Row(
                    modifier = Modifier
                        .padding(end = 2.dp)
                        .height(if (isSelected) 40.dp else 36.dp) 
                        .widthIn(min = 120.dp, max = 200.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)) 
                        .background(bgColor)
                        .clickable { onTabSelected(index) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tabTitle,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (editors.size > 1) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onTabClosed(index) }
                                .background(if (isSelected) palette.menuBg.toComposeColor().copy(alpha = 0.5f) else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(XnotesIcons.close, closeLabel, tint = textColor, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(bottom = 6.dp, end = 8.dp, start = 4.dp)
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onNewTab() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, newTabLabel, tint = palette.text.toComposeColor(), modifier = Modifier.size(20.dp))
        }
    }
}