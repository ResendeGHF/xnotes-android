package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Floating "Go Back" + "Forget" buttons over the canvas's right-bottom corner, shown while an
 * internal PDF link jump can be undone ([Editor.pdfLinkBackStack] non-empty). "Go Back" pops
 * one jump at a time back to the start; "Forget" drops the whole trail and the buttons vanish.
 */
@Composable
fun BoxScope.PdfLinkBackButtons(editor: Editor) {
    if (!editor.canGoBackPdfLink) return
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(LocalToolbarCover.current)
            .padding(end = 16.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { editor.goBackPdfLink() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                XnotesIcons.undo,
                contentDescription = stringResource(R.string.pdf_link_back),
                tint = palette.accent.toComposeColor(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.pdf_link_back),
                color = palette.text.toComposeColor(),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { editor.forgetPdfLinkHistory() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                XnotesIcons.close,
                contentDescription = stringResource(R.string.pdf_link_forget),
                tint = palette.textDim.toComposeColor(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.pdf_link_forget),
                color = palette.text.toComposeColor(),
                fontSize = 13.sp,
            )
        }
    }
}
