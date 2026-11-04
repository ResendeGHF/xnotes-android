package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.share.ShareCrypto
import com.xnotes.share.ShareProtocol
import com.xnotes.share.companion.CompanionClient
import com.xnotes.share.companion.CompanionPins
import com.xnotes.share.companion.PairingClient
import com.xnotes.share.companion.PairingUi
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SYNC.md v1 Connect pane: dial the Linux companion (TLS client), run the
 * TOFU + 6-digit-code pairing, and hold the connection. The host engine
 * (index/fetch/op handling) attaches to the [CompanionClient] later; this
 * pane owns the client lifecycle (activity-bound, no service — the phone
 * dials out, so there is nothing to keep alive in the background once the
 * user leaves).
 */
@Composable
fun ConnectPane() {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS, 0).getString(KEY_IP, "") ?: "",
        )
    }
    var portText by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS, 0).getString(KEY_PORT, ShareProtocol.DEFAULT_PORT.toString())
                ?: ShareProtocol.DEFAULT_PORT.toString(),
        )
    }
    var status by remember { mutableStateOf("Not connected.") }
    var codeNeeded by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var pairedName by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val holder = remember { ClientHolder() }

    DisposableEffect(Unit) {
        // Show an already-pinned companion on entry.
        val pins = CompanionPins.of(File(context.filesDir, "share"))
        pairedName = pins.all().firstOrNull()?.name
        onDispose {
            holder.pairing?.close()
            holder.client?.disconnect()
            holder.client = null
            holder.pairing = null
        }
    }

    fun setStatus(s: String) {
        status = s
    }

    fun start() {
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
            setStatus("Port must be 1–65535.")
            return
        }
        val target = host.trim().ifEmpty { "127.0.0.1" }
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_IP, target)
            .putString(KEY_PORT, port.toString())
            .apply()
        busy = true
        setStatus("Connecting to $target:$port…")
        scope.launch(Dispatchers.IO) {
            val identity: ShareCrypto.ServerIdentity
            val pins: CompanionPins
            try {
                identity = ShareCrypto.loadOrCreate(context.filesDir)
                pins = CompanionPins.of(File(context.filesDir, "share"))
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    busy = false
                    setStatus("Identity failed: ${e.message}")
                }
                return@launch
            }
            val pinned = pins.all().firstOrNull()?.fp
            val deviceName = android.os.Build.MODEL ?: "Android"
            val client = CompanionClient(
                host = target,
                port = port,
                identity = identity,
                pinnedFp = pinned,
                autoReconnect = true,
            )
            val ui = object : PairingUi {
                override fun showCodeEntry() {
                    scope.launch(Dispatchers.Main) { codeNeeded = true }
                }
                override fun showStatus(s: String) {
                    scope.launch(Dispatchers.Main) { setStatus(s) }
                }
                override fun pairingDone(name: String) {
                    scope.launch(Dispatchers.Main) {
                        pairedName = name
                        codeNeeded = false
                        code = ""
                        setStatus("Paired with $name.")
                    }
                }
                override fun pairingFailed(reason: String) {
                    scope.launch(Dispatchers.Main) { setStatus(reason) }
                }
            }
            val pairing = PairingClient(deviceName, identity.fingerprintHex, pins, ui, client::send)
            pairing.onPaired = { name ->
                scope.launch(Dispatchers.Main) {
                    pairedName = name
                    setStatus("Connected to $name.")
                }
            }
            client.onConnected = { peerFp ->
                scope.launch(Dispatchers.Main) {
                    connected = true
                    busy = false
                    val known = pins.nameFor(peerFp)
                    setStatus(if (known != null) "Connected to $known." else "Connected. Pairing…")
                }
                pairing.onConnected(peerFp)
            }
            client.onMessage = { msg -> pairing.onMessage(msg) }
            client.onDisconnected = { willRetry ->
                scope.launch(Dispatchers.Main) {
                    connected = false
                    busy = false
                    if (!willRetry) setStatus("Disconnected.")
                }
            }
            client.onError = { err ->
                scope.launch(Dispatchers.Main) { setStatus(err) }
            }
            holder.client = client
            holder.pairing = pairing
            client.connect()
            withContext(Dispatchers.Main) { busy = false }
        }
    }

    fun stop() {
        holder.pairing?.close()
        holder.pairing = null
        holder.client?.disconnect()
        holder.client = null
        connected = false
        codeNeeded = false
        setStatus("Disconnected.")
    }

    Column(Modifier.fillMaxSize()) {
        Text("Connect", color = palette.text.toComposeColor(), fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Dial the Linux companion over TLS, then type the 6-digit code it shows. " +
                "For USB, run `adb reverse tcp:42317 tcp:42317` and use 127.0.0.1.",
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Companion IP", fontSize = 13.sp) },
            placeholder = { Text("127.0.0.1", fontSize = 13.sp) },
            singleLine = true,
            enabled = !connected && !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Port", fontSize = 13.sp) },
            singleLine = true,
            enabled = !connected && !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (connected) {
                Button(onClick = { stop() }) { Text("Disconnect") }
            } else {
                Button(onClick = { start() }, enabled = !busy) { Text("Connect") }
            }
            Spacer(Modifier.width(12.dp))
            Text(status, color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        }
        if (codeNeeded) {
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
                Button(onClick = { holder.pairing?.submitCode(code) }, enabled = code.length == 6) {
                    Text("Send")
                }
            }
        }
        val name = pairedName
        if (name != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Paired device: $name",
                    color = palette.text.toComposeColor(),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        CompanionPins.of(File(context.filesDir, "share")).unpin(name)
                        withContext(Dispatchers.Main) { pairedName = null }
                    }
                    stop()
                    setStatus("Unpaired $name.")
                }) {
                    Text("Unpair")
                }
            }
        }
    }
}

/** Holds the live client across recompositions (activity-bound lifecycle). */
private class ClientHolder {
    var client: CompanionClient? = null
    var pairing: PairingClient? = null
}

private const val PREFS = "companion"
private const val KEY_IP = "companion_last_ip"
private const val KEY_PORT = "companion_last_port"
