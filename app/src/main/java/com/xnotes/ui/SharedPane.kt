package com.xnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.share.companion.CompanionService
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * Shared pane: Android-to-Android sharing. This phone both hosts its own
 * files (other phones dial its listener) and reads files hosted by friends
 * (guest sessions, all connected at once). Pairing is mutual-confirm: both
 * phones show the same 6-digit code and each user confirms the match.
 */
@Composable
fun SharedPane() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val sharing by CompanionService.sharingOn.collectAsState()
    val port by CompanionService.listenPort.collectAsState()
    val peers by CompanionService.peers.collectAsState()
    val guests by CompanionService.guests.collectAsState()
    val guestIndex by CompanionService.guestIndex.collectAsState()
    val sasIn by CompanionService.sasInbound.collectAsState()
    val sasOut by CompanionService.sasOutbound.collectAsState()
    LaunchedEffect(Unit) { CompanionService.syncFlagFlows(context) }

    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var pendingCopy by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var forgetFp by remember { mutableStateOf<String?>(null) }
    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val (fp, remoteId, _) = pendingCopy ?: return@rememberLauncherForActivityResult
        pendingCopy = null
        if (uri != null) {
            val bytes = CompanionService.readGuestBytes(fp, remoteId)
            if (bytes != null) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
            }
        }
    }

    // Inbound peers only: the desktop companion lives on the Companion screen.
    val phones = remember(peers) { peers.filter { it.kind == com.xnotes.share.db.DeviceKind.PHONE } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Shared", color = palette.text.toComposeColor(), fontSize = 20.sp)
        Text(
            "Pair with a friend's phone to read each other's shared notes. " +
                "Each side hosts its own files; everyone connected to you can edit what you shared with them.",
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Phone sharing", color = palette.text.toComposeColor(), modifier = Modifier.weight(1f))
            Switch(
                checked = sharing,
                onCheckedChange = {
                    if (it) CompanionService.setSharingEnabled(context, true) else CompanionService.setSharingEnabled(context, false)
                },
            )
        }
        Text(
            if (sharing && port > 0) "This phone listens on port $port — friends enter your IP."
            else "Turn sharing on, then friends enter this phone's IP to pair.",
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))

        sasIn?.let { req ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = palette.selectionBackground.toComposeColor()),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "Pair with ${req.name}?",
                        color = palette.text.toComposeColor(),
                        fontSize = 14.sp,
                    )
                    Text(
                        "Confirm this code matches the one on their phone: ${req.sas}",
                        color = palette.text.toComposeColor(),
                        fontSize = 16.sp,
                    )
                    Row {
                        TextButton(onClick = { CompanionService.confirmInboundSas(context, req.key) }) {
                            Text("Allow", fontSize = 13.sp)
                        }
                        TextButton(onClick = { CompanionService.denyInboundSas(context, req.key) }) {
                            Text("Deny", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        sasOut?.let { req ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = palette.selectionBackground.toComposeColor()),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Confirm on both phones: ${req.sas}", color = palette.text.toComposeColor(), fontSize = 16.sp)
                    Text(
                        "Dialing ${req.name} — press Matches only if their phone shows the same code.",
                        color = palette.textDim.toComposeColor(),
                        fontSize = 12.sp,
                    )
                    Row {
                        TextButton(onClick = { CompanionService.confirmOutboundSas(req.key) }) {
                            Text("Matches", fontSize = 13.sp)
                        }
                        TextButton(onClick = { CompanionService.cancelOutboundSas(req.key) }) {
                            Text("Cancel", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Text("Add a phone", color = palette.text.toComposeColor(), fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("IP address", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = sharing,
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Port?", fontSize = 13.sp) },
                        placeholder = { Text("scan", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                        enabled = sharing,
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val h = host.trim()
                            if (h.isNotEmpty()) CompanionService.dialPeer(context, h, portText.toIntOrNull() ?: 0)
                        },
                        enabled = sharing && host.trim().isNotEmpty(),
                    ) { Text("Pair", fontSize = 13.sp) }
                }
                Spacer(Modifier.height(6.dp))
            }
            item {
                Text(
                    "Shared with me (${guests.sumOf { guestIndex[it.fp]?.size ?: 0 }} files)",
                    color = palette.text.toComposeColor(),
                    fontSize = 13.sp,
                )
                if (guests.isEmpty()) {
                    Text(
                        "Nothing yet. Pair a phone above; the files it shares with you appear here, grouped by phone.",
                        color = palette.textDim.toComposeColor(),
                        fontSize = 12.sp,
                    )
                }
            }
            for (g in guests) {
                item(key = "g:${g.key}") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1)
                            Text(
                                if (g.connected) "${g.fileCount} shared files"
                                else "connecting…",
                                color = palette.textDim.toComposeColor(),
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = { CompanionService.disconnectGuest(g.key) }) {
                            Text("Disconnect", fontSize = 12.sp)
                        }
                    }
                }
                val files = guestIndex[g.fp].orEmpty().filter { it.kind == 0 }
                    .sortedBy { it.path.lowercase() }
                items(files, key = { "f:${g.key}:${it.id}" }) { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val name = f.path.substringAfterLast('/').ifEmpty { "note" }
                            Text(name, color = palette.text.toComposeColor(), fontSize = 13.sp, maxLines = 1)
                            Text(
                                f.path + if (f.size > 0) " · ${f.size / 1024} KiB" else "",
                                color = palette.textDim.toComposeColor(),
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                        TextButton(
                            onClick = { g.fp?.let { CompanionService.fetchShared(context, it, f.id) } },
                            enabled = g.connected,
                        ) { Text("Open", fontSize = 12.sp) }
                        TextButton(
                            onClick = {
                                g.fp?.let { fp ->
                                    val base = f.path.substringAfterLast('/').ifEmpty { "note" }
                                    val suggest = if (base.endsWith(".xnote")) base else "$base.xnote"
                                    pendingCopy = Triple(fp, f.id, suggest)
                                    try {
                                        createDoc.launch(suggest)
                                    } catch (_: Exception) {
                                        pendingCopy = null
                                    }
                                }
                            },
                            enabled = g.connected,
                        ) { Text("Copy", fontSize = 12.sp) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Reading my files (${phones.count { it.connected }} connected)",
                    color = palette.text.toComposeColor(),
                    fontSize = 13.sp,
                )
                if (phones.isEmpty()) {
                    Text(
                        "Nobody yet. Use Share policy to choose what each paired phone may see.",
                        color = palette.textDim.toComposeColor(),
                        fontSize = 12.sp,
                    )
                }
            }
            items(phones, key = { "p:${it.fp}" }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1)
                        Text(
                            if (p.connected) "connected" else "offline",
                            color = palette.textDim.toComposeColor(),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = { forgetFp = p.fp }) { Text("Forget", fontSize = 12.sp) }
                }
            }
        }
    }

    forgetFp?.let { fp ->
        AlertDialog(
            onDismissRequest = { forgetFp = null },
            confirmButton = {
                TextButton(onClick = {
                    CompanionService.forgetPeer(context, fp)
                    forgetFp = null
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetFp = null }) { Text("Cancel") } },
            title = { Text("Forget this phone?", fontSize = 15.sp) },
            text = { Text("Its pairing and grants are removed. It will have to pair again.", fontSize = 13.sp) },
        )
    }
}
