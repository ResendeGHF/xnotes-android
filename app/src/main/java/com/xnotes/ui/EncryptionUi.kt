package com.xnotes.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.crypto.PgpKeyStore
import com.xnotes.crypto.VaultCrypto
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings block: create, import and remove OpenPGP keys used to move notes. */
@Composable
fun EncryptionSettings(editor: Editor) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var keys by remember { mutableStateOf(editor.pgp.list()) }
    fun reload() { keys = editor.pgp.list() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var identity by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var importingSecret by remember { mutableStateOf<ByteArray?>(null) }
    var secretPass by remember { mutableStateOf("") }
    var pendingPublicExport by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSecretExport by rememberSaveable { mutableStateOf<String?>(null) }

    val importPublic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readUri(context, uri) } ?: return@launch
            val result = withContext(Dispatchers.IO) { runCatching { editor.pgp.importPublic(bytes) } }
            bytes.fill(0)
            error = result.exceptionOrNull()?.message
            reload()
        }
    }
    val importSecret = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importingSecret = withContext(Dispatchers.IO) { readUri(context, uri) }
            secretPass = ""
        }
    }
    val exportPublic = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pgp-keys")) { uri ->
        val pending = pendingPublicExport
        pendingPublicExport = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) }
            }
        }
    }
    val exportSecret = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pgp-keys")) { uri ->
        val pending = pendingSecretExport
        pendingSecretExport = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) }
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        context.getString(R.string.pref_encryption_help),
        color = palette.textDim.toComposeColor(),
        fontSize = 12.sp,
    )
    if (keys.isEmpty()) {
        Text(context.getString(R.string.pgp_no_keys), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
    }
    keys.forEach { key ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(key.userId, color = palette.text.toComposeColor(), fontSize = 14.sp)
                Text(
                    (if (key.secret) context.getString(R.string.pgp_secret) else context.getString(R.string.pgp_public)) +
                        "  " + key.fingerprint.take(16),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 11.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = {
                    pendingPublicExport = editor.pgp.exportPublic(key.fingerprint)
                    exportPublic.launch("${key.userId.substringBefore(' ').ifBlank { "key" }}.asc")
                }) { Text(context.getString(R.string.pgp_export_public)) }
                if (key.secret) {
                    TextButton(onClick = {
                        // The stored secret key is still wrapped by its passphrase; exporting the
                        // wrapped form is what lets the user back it up without revealing it here.
                        val armored = editor.pgp.exportSecret(key.fingerprint) ?: return@TextButton
                        pendingSecretExport = armored
                        exportSecret.launch("${key.userId.substringBefore(' ').ifBlank { "key" }}-private.asc")
                    }) { Text(context.getString(R.string.pgp_export_secret)) }
                }
                TextButton(onClick = { editor.pgp.delete(key.fingerprint); reload() }) {
                    Text(context.getString(R.string.delete))
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { generating = true; error = null }) { Text(context.getString(R.string.pgp_generate)) }
        TextButton(onClick = { importPublic.launch(arrayOf("*/*")) }) { Text(context.getString(R.string.pgp_import_public)) }
        TextButton(onClick = { importSecret.launch(arrayOf("*/*")) }) { Text(context.getString(R.string.pgp_import_secret)) }
    }
    error?.let { Text(it, color = palette.danger.toComposeColor(), fontSize = 12.sp) }

    if (generating) {
        AlertDialog(
            onDismissRequest = { if (!busy) generating = false },
            title = { Text(context.getString(R.string.pgp_generate)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(context.getString(R.string.pgp_generate_help), color = palette.textDim.toComposeColor(), fontSize = 12.sp)
                    OutlinedTextField(identity, { identity = it }, singleLine = true, label = { Text(context.getString(R.string.pgp_identity)) })
                    OutlinedTextField(
                        passphrase, { passphrase = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text(context.getString(R.string.password)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && identity.isNotBlank() && passphrase.length >= 8,
                    onClick = {
                        busy = true
                        val id = identity.trim()
                        val pass = passphrase.toCharArray()
                        passphrase = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { editor.pgp.generate(id, pass) } }
                            pass.fill('\u0000')
                            busy = false
                            error = result.exceptionOrNull()?.message
                            if (result.isSuccess) { generating = false; identity = ""; reload() }
                        }
                    },
                ) { Text(context.getString(R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { if (!busy) generating = false }) { Text(context.getString(R.string.cancel)) } },
        )
    }

    importingSecret?.let { bytes ->
        AlertDialog(
            onDismissRequest = { bytes.fill(0); importingSecret = null },
            title = { Text(context.getString(R.string.pgp_import_secret)) },
            text = {
                OutlinedTextField(
                    secretPass, { secretPass = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(context.getString(R.string.password)) },
                )
                Text(
                    context.getString(R.string.pgp_key_passphrase_help),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val pass = secretPass.toCharArray()
                        secretPass = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { editor.pgp.importSecret(bytes, pass) } }
                            pass.fill('\u0000')
                            bytes.fill(0)
                            busy = false
                            importingSecret = null
                            error = result.exceptionOrNull()?.message
                            reload()
                        }
                    },
                ) { Text(context.getString(R.string.import_title)) }
            },
            dismissButton = {
                TextButton(onClick = { bytes.fill(0); importingSecret = null }) { Text(context.getString(R.string.cancel)) }
            },
        )
    }
    }
}

@Composable
internal fun EncryptedFolderDialog(
    error: String?,
    busy: Boolean,
    onConfirm: (name: String, password: CharArray, params: VaultCrypto.Params) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf(VaultCrypto.Params.DEFAULT.memoryKiB.toString()) }
    var iterations by remember { mutableStateOf(VaultCrypto.Params.DEFAULT.iterations.toString()) }
    var lanes by remember { mutableStateOf(VaultCrypto.Params.DEFAULT.parallelism.toString()) }
    val mismatch = again.isNotEmpty() && again != password
    fun applyPreset(m: Int, i: Int, l: Int) {
        memory = m.toString()
        iterations = i.toString()
        lanes = l.toString()
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(R.string.new_encrypted_folder)) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    androidx.compose.ui.res.stringResource(R.string.encrypted_folder_help),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                )
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(androidx.compose.ui.res.stringResource(R.string.folder_name)) })
                OutlinedTextField(
                    password, { password = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.password)) },
                )
                OutlinedTextField(
                    again, { again = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = mismatch,
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.password_again)) },
                )
                Text(androidx.compose.ui.res.stringResource(R.string.argon2_params), color = palette.accent.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    androidx.compose.ui.res.stringResource(R.string.argon2_cipher_note),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { applyPreset(32 * 1024, 2, 1) }) { Text(androidx.compose.ui.res.stringResource(R.string.argon2_preset_quick)) }
                    TextButton(enabled = !busy, onClick = { applyPreset(VaultCrypto.Params.DEFAULT.memoryKiB, VaultCrypto.Params.DEFAULT.iterations, VaultCrypto.Params.DEFAULT.parallelism) }) { Text(androidx.compose.ui.res.stringResource(R.string.argon2_preset_default)) }
                    TextButton(enabled = !busy, onClick = { applyPreset(128 * 1024, 4, 2) }) { Text(androidx.compose.ui.res.stringResource(R.string.argon2_preset_strong)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(memory, { memory = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(androidx.compose.ui.res.stringResource(R.string.argon2_memory)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(iterations, { iterations = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(androidx.compose.ui.res.stringResource(R.string.argon2_iterations)) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(lanes, { lanes = it.filter { c -> c.isDigit() }.take(1) }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(androidx.compose.ui.res.stringResource(R.string.argon2_lanes)) }, modifier = Modifier.weight(1f))
                }
                Text(
                    "8 192 – 262 144 KiB · 1 – 16 passes · 1 – 4 lanes",
                    color = palette.textDim.toComposeColor(),
                    fontSize = 11.sp,
                )
                error?.let { Text(it, color = palette.danger.toComposeColor(), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && password.length >= 8 && password == again,
                onClick = {
                    val pass = password.toCharArray()
                    password = ""
                    again = ""
                    onConfirm(
                        name.trim(),
                        pass,
                        VaultCrypto.Params.sanitize(
                            memory.toIntOrNull() ?: VaultCrypto.Params.DEFAULT.memoryKiB,
                            iterations.toIntOrNull() ?: VaultCrypto.Params.DEFAULT.iterations,
                            lanes.toIntOrNull() ?: VaultCrypto.Params.DEFAULT.parallelism,
                        ),
                    )
                },
            ) { Text(androidx.compose.ui.res.stringResource(R.string.create)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun UnlockVaultDialog(
    error: String?,
    busy: Boolean,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(R.string.unlock_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.unlock_folder_help), fontSize = 12.sp, color = LocalPalette.current.textDim.toComposeColor())
                OutlinedTextField(
                    password, { password = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.password)) },
                )
                error?.let { Text(it, color = LocalPalette.current.danger.toComposeColor(), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.isNotEmpty(),
                onClick = {
                    val pass = password.toCharArray()
                    password = ""
                    onConfirm(pass)
                },
            ) { Text(androidx.compose.ui.res.stringResource(R.string.unlock_folder)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun PgpExportDialog(
    editor: Editor,
    fileName: String,
    busy: Boolean,
    error: String?,
    onConfirm: (recipient: String, signer: String, passphrase: CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val keys = remember { editor.pgp.list() }
    val secrets = keys.filter { it.secret }
    val recipients = keys
    var recipient by remember { mutableStateOf(recipients.firstOrNull()?.fingerprint.orEmpty()) }
    var signer by remember { mutableStateOf(secrets.firstOrNull()?.fingerprint.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(R.string.export_pgp)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.export_pgp_help, fileName), fontSize = 12.sp, color = LocalPalette.current.textDim.toComposeColor())
                if (recipients.isEmpty() || secrets.isEmpty()) {
                    Text(androidx.compose.ui.res.stringResource(R.string.pgp_need_keys), color = LocalPalette.current.danger.toComposeColor(), fontSize = 13.sp)
                } else {
                    Text(androidx.compose.ui.res.stringResource(R.string.pgp_recipient), fontSize = 13.sp)
                    recipients.forEach { key ->
                        TextButton(onClick = { recipient = key.fingerprint }) {
                            Text((if (recipient == key.fingerprint) "● " else "") + key.userId)
                        }
                    }
                    Text(androidx.compose.ui.res.stringResource(R.string.pgp_signer), fontSize = 13.sp)
                    secrets.forEach { key ->
                        TextButton(onClick = { signer = key.fingerprint }) {
                            Text((if (signer == key.fingerprint) "● " else "") + key.userId)
                        }
                    }
                    OutlinedTextField(
                        pass, { pass = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.password)) },
                    )
                }
                error?.let { Text(it, color = LocalPalette.current.danger.toComposeColor(), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && recipient.isNotEmpty() && signer.isNotEmpty(),
                onClick = {
                    val chars = pass.toCharArray()
                    pass = ""
                    onConfirm(recipient, signer, chars)
                },
            ) { Text(androidx.compose.ui.res.stringResource(R.string.export_pgp)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun PgpImportDialog(
    editor: Editor,
    busy: Boolean,
    error: String?,
    onConfirm: (secretFingerprint: String, passphrase: CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val secrets = remember { editor.pgp.list().filter { it.secret } }
    var signer by remember { mutableStateOf(secrets.firstOrNull()?.fingerprint.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(R.string.import_pgp)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (secrets.isEmpty()) {
                    Text(androidx.compose.ui.res.stringResource(R.string.pgp_need_secret), color = LocalPalette.current.danger.toComposeColor(), fontSize = 13.sp)
                } else {
                    secrets.forEach { key ->
                        TextButton(onClick = { signer = key.fingerprint }) {
                            Text((if (signer == key.fingerprint) "● " else "") + key.userId)
                        }
                    }
                    OutlinedTextField(
                        pass, { pass = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text(androidx.compose.ui.res.stringResource(R.string.password)) },
                    )
                }
                error?.let { Text(it, color = LocalPalette.current.danger.toComposeColor(), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && signer.isNotEmpty(),
                onClick = {
                    val chars = pass.toCharArray()
                    pass = ""
                    onConfirm(signer, chars)
                },
            ) { Text(androidx.compose.ui.res.stringResource(R.string.import_title)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
    )
}

private fun readUri(context: android.content.Context, uri: Uri): ByteArray? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

/** Which imported key can receive or sign. Exposed for the export dialog's empty state. */
internal fun PgpKeyStore.Identity.label(): String = userId
