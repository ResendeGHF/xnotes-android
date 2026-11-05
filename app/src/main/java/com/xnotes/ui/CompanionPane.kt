package com.xnotes.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.share.ShareProtocol
import com.xnotes.share.companion.CompanionService
import com.xnotes.share.db.DeviceEntity
import com.xnotes.share.db.ShareStore
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.text.DateFormat
import java.util.Date

/**
 * Companion pane: the desktop Linux companion this phone knows, which one is
 * active, and pairing new ones. At most one companion is connected; the rest
 * stay remembered but disconnected until picked.
 */
@Composable
fun CompanionPane() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val status by CompanionService.companionStatus.collectAsState()
    val companionOn by CompanionService.companionOn.collectAsState()
    val codeNeeded by CompanionService.codeNeeded.collectAsState()
    val store = remember { ShareStore.of(context.applicationContext) }
    LaunchedEffect(Unit) { CompanionService.syncFlagFlows(context) }
    val allDevices by store.devices.collectAsState(initial = emptyList())
    val devices = remember(allDevices) { allDevices.filter { it.kind == com.xnotes.share.db.DeviceKind.LINUX } }
    val activeDevice by store.activeDevice.collectAsState(initial = null)

    var host by remember {
        mutableStateOf(
            context.getSharedPreferences(CompanionService.PREFS, 0).getString(CompanionService.KEY_IP, "") ?: "",
        )
    }
    var portText by remember {
        mutableStateOf(
            context.getSharedPreferences(CompanionService.PREFS, 0).getString(CompanionService.KEY_PORT, ShareProtocol.DEFAULT_PORT.toString())
                ?: ShareProtocol.DEFAULT_PORT.toString(),
        )
    }
    var code by remember { mutableStateOf("") }
    var ownName by remember {
        mutableStateOf(
            context.getSharedPreferences(CompanionService.PREFS, 0).getString(CompanionService.KEY_NAME, "") ?: "",
        )
    }
    var renameTarget by remember { mutableStateOf<DeviceEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var forgetTarget by remember { mutableStateOf<DeviceEntity?>(null) }
    var editTarget by remember { mutableStateOf<DeviceEntity?>(null) }
    var editHost by remember { mutableStateOf("") }
    var editPort by remember { mutableStateOf("") }

    fun saveTarget(): Pair<String, Int>? {
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val target = host.trim().ifEmpty { "127.0.0.1" }
        context.getSharedPreferences(CompanionService.PREFS, 0).edit()
            .putString(CompanionService.KEY_IP, target)
            .putString(CompanionService.KEY_PORT, port.toString())
            .apply()
        return target to port
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Companion", color = palette.text.toComposeColor(), fontSize = 20.sp)
        Text(
            "Dial a Linux companion over TLS, then type the 6-digit code it shows. " +
                "For USB, run `adb reverse tcp:42317 tcp:42317` and use 127.0.0.1.",
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Companion sharing", color = palette.text.toComposeColor(), modifier = Modifier.weight(1f))
            Switch(
                checked = companionOn,
                onCheckedChange = {
                    if (it) {
                        saveTarget()
                        CompanionService.setCompanionEnabled(context, true)
                    } else {
                        CompanionService.setCompanionEnabled(context, false)
                    }
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = ownName,
            onValueChange = {
                ownName = it
                CompanionService.setOwnName(context, it.trim())
            },
            label = { Text("This phone's nickname", fontSize = 13.sp) },
            placeholder = { Text(android.os.Build.MODEL ?: "Android", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = companionOn,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Paired companions (${devices.size}) — one active at a time",
            color = palette.text.toComposeColor(),
            fontSize = 13.sp,
        )
        if (devices.isEmpty()) {
            Text(
                "None yet. Add one below, then type the code it shows.",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(devices, key = { it.fp }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = companionOn) {
                            if (!d.active) CompanionService.switchDevice(context, d.fp)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = d.active,
                            onClick = { if (!d.active) CompanionService.switchDevice(context, d.fp) },
                            enabled = companionOn,
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.displayName, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1)
                            Text(
                                "${d.host.ifEmpty { "?" }}:${if (d.port > 0) d.port else "?"} · ${relativeDate(d.lastSeen)}",
                                color = palette.textDim.toComposeColor(),
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                        TextButton(
                            onClick = {
                                renameTarget = d
                                renameText = d.nickname ?: ""
                            },
                            enabled = companionOn,
                        ) { Text("Rename", fontSize = 12.sp) }
                        TextButton(
                            onClick = {
                                editTarget = d
                                editHost = d.host
                                editPort = if (d.port > 0) d.port.toString() else ""
                            },
                            enabled = companionOn,
                        ) { Text("Edit", fontSize = 12.sp) }
                        TextButton(onClick = { forgetTarget = d }, enabled = companionOn) {
                            Text("Forget", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Companion IP", fontSize = 13.sp) },
                placeholder = { Text("127.0.0.1", fontSize = 13.sp) },
                singleLine = true,
                enabled = companionOn,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("Port", fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = companionOn,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val (target, port) = saveTarget() ?: return@Button
                    CompanionService.reconnect(context, target, port)
                                },
                enabled = companionOn,
            ) { Text(if (devices.isEmpty()) "Pair" else "Add") }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { activeDevice?.let { CompanionService.switchDevice(context, it.fp) } }, enabled = companionOn && activeDevice != null) {
                Text("Connect")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { CompanionService.disconnect() }, enabled = companionOn) { Text("Disconnect") }
            Spacer(Modifier.width(12.dp))
            Text(status.text, color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        }
        if (codeNeeded && companionOn) {
            Spacer(Modifier.height(12.dp))
            Text("Enter the code shown on the desktop.", color = palette.text.toComposeColor(), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("6-digit code", fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { CompanionService.submitCode(context, code); code = "" }, enabled = code.length == 6) {
                    Text("Send")
                }
            }
        }
        if (status.paired != null && companionOn) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Paired device: ${status.paired}",
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
            )
        }
    }

    renameTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename companion", fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Nickname (blank clears it)", fontSize = 13.sp) },
                    placeholder = { Text(d.name, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    CompanionService.renameDevice(context, d.fp, renameText.trim())
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    forgetTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${d.displayName}?", fontSize = 16.sp) },
            text = {
                Text(
                    "Its TLS pin and every share grant are deleted. You can pair it again any time.",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    CompanionService.forgetDevice(context, d.fp)
                    forgetTarget = null
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Keep") } },
        )
    }
    editTarget?.let { d ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit ${d.displayName}", fontSize = 16.sp) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editHost,
                        onValueChange = { editHost = it.trim() },
                        label = { Text("Companion IP", fontSize = 13.sp) },
                        placeholder = { Text("127.0.0.1", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editPort,
                        onValueChange = { editPort = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Port", fontSize = 13.sp) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val port = editPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 42317
                    CompanionService.editDevice(context, d.fp, editHost.ifEmpty { "127.0.0.1" }, port)
                    editTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("Cancel") } },
        )
    }
}

private fun relativeDate(ms: Long): String {
    if (ms <= 0) return "never connected"
    val d = Date(ms)
    return try {
        DateFormat.getDateInstance(DateFormat.SHORT).format(d)
    } catch (_: Exception) {
        d.toString()
    }
}
