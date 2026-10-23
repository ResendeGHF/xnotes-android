package com.xnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// notes: this fork. xnotes: the original project it's built on.
private const val N_REPO_URL = "https://github.com/ResendeGHF/xnotes-android"
private const val UPSTREAM_REPO_URL = "https://github.com/shardulvs/xnotes-android"
private const val LICENSES_URL = "https://github.com/ResendeGHF/xnotes-android/blob/main/LICENSE"
private const val MIN_FILL_MS = 120L

/**
 * The About backstage pane: app identity + the one place that points users back at the
 * project. Since the app ships with no accounts or telemetry, these links are the only
 * feedback channel. The bug/feature links open GitHub's new-issue form with the body
 * pre-filled (app version + device + OS for bug reports). Back from here returns to Home.
 */
@Composable
fun AboutPane() {
    val palette = LocalPalette.current
    val ctx = LocalContext.current

    val version = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName} ($code)"
        }.getOrDefault("")
    }

    fun open(url: String) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 16.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The angular "n" mark, drawn from the launcher-icon path so it matches the app icon.
            NLogo(Modifier.size(72.dp), color = palette.accent.toComposeColor())
            Spacer(Modifier.height(12.dp))
            Text("notes", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 24.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "A customized fork of xnotes, a handwriting notes and sketching app for Android",
                color = palette.textDim.toComposeColor(), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            if (version.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // Tap to copy, so a version string is easy to paste into a report.
                Tooltip(stringResource(R.string.copy_version)) {
                    Row(
                        Modifier.clickable { copyVersion(ctx, version) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.version_n, version), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(XnotesIcons.copy, stringResource(R.string.copy_version), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(12.dp))
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            
            // Draw more attention to open source licenses
            Text("Legal", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AboutButton(Icons.Outlined.Info, "Open Source Licenses") { 
                    runCatching {
                        val intent = Intent().setClassName(ctx.packageName, "com.google.android.gms.oss.licenses.OssLicensesMenuActivity")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    }.onFailure {
                        open(LICENSES_URL)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            
            // Contributors
            Text("Contributors", color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(14.dp))
            Text(
                "ResendeGHF, shardulvs, and the open-source community",
                color = palette.textDim.toComposeColor(), fontSize = 13.sp, textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(36.dp))
            
            // Quiet, single-line links for repos
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("GitHub Repos: ", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                Text("notes", color = palette.accent.toComposeColor(), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { open(N_REPO_URL) }.padding(4.dp))
                Text(" | ", color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                Text("xnotes", color = palette.accent.toComposeColor(), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { open(UPSTREAM_REPO_URL) }.padding(4.dp))
            }
        }
    }
}

/** A link button: line glyph above a label. Fills with the selection container while pressed. */
@Composable
private fun RowScope.AboutButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    // Keep the pressed fill visible for a minimum time so even a millisecond tap registers.
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        val scope = this
        var pressedAt = 0L
        var clearJob: Job? = null
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    clearJob?.cancel()
                    pressedAt = System.currentTimeMillis()
                    pressed = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    val remaining = MIN_FILL_MS - (System.currentTimeMillis() - pressedAt)
                    clearJob?.cancel()
                    clearJob = scope.launch {
                        if (remaining > 0) delay(remaining)
                        pressed = false
                    }
                }
            }
        }
    }
    val accent = palette.accent.toComposeColor()
    val onAccent = palette.selectionForeground.toComposeColor()
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.medium)
            .background(if (pressed) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .border(1.dp, if (pressed) accent else palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (pressed) onAccent else accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = if (pressed) onAccent else palette.text.toComposeColor(),
            fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
        )
    }
}

private fun copyVersion(ctx: Context, version: String) {
    runCatching {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("notes version", "notes $version"))
        Toast.makeText(ctx, "Version copied", Toast.LENGTH_SHORT).show()
    }
}


