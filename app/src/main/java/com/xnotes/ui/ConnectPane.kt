package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * SYNC.md v1 Connect pane. The toggle at the top turns companion sharing on
 * and off; everything below it is disabled while off. When on, dial the Linux
 * companion over TLS and type the 6-digit code it shows.
 */
@Composable
fun ConnectPane() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val status by CompanionService.status.collectAsState()
    val sharing by CompanionService.active.collectAsState()
    val codeNeeded by CompanionService.codeNeeded.collectAsState()

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
        Text("Connect", color = palette.text.toComposeColor(), fontSize = 20.sp)
        Text(
            "Dial the Linux companion over TLS, then type the 6-digit code it shows. " +
                "For USB, run `adb reverse tcp:42317 tcp:42317` and use 127.0.0.1.",
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Companion sharing", color = palette.text.toComposeColor(), modifier = Modifier.weight(1f))
            Switch(
                checked = sharing,
                onCheckedChange = {
                    if (it) {
                        saveTarget()
                        CompanionService.start(context)
                    } else {
                        CompanionService.stop(context)
                    }
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Companion IP", fontSize = 13.sp) },
            placeholder = { Text("127.0.0.1", fontSize = 13.sp) },
            singleLine = true,
            enabled = sharing,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Port", fontSize = 13.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = sharing,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val (target, port) = saveTarget() ?: return@Button
                    CompanionService.reconnect(context, target, port)
                },
                enabled = sharing,
            ) { Text("Connect") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { CompanionService.disconnect() }, enabled = sharing) { Text("Disconnect") }
            Spacer(Modifier.width(12.dp))
            Text(status.text, color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        }
        if (codeNeeded && sharing) {
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
        val name = status.paired
        if (name != null && sharing) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Paired device: $name",
                    color = palette.text.toComposeColor(),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { CompanionService.unpair(context, name) }) {
                    Text("Unpair")
                }
            }
        }
    }
}
