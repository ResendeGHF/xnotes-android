package com.xnotes.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Host approval for a companion-requested file change (SYNC.md v1
 * §Companion-requested create/delete). The host engine shows this when a
 * `delete_request` / `create_request` arrives; Allow applies the change and
 * replies ok, Deny (or dismiss) replies ok:false.
 */
@Composable
fun CompanionApprovalDialog(
    /** Pinned name of the companion asking, e.g. `desktop`. */
    deviceName: String,
    /** Display name of the file or folder concerned. */
    fileName: String,
    /** Either `delete` or `create`. */
    action: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(if (action == "delete") "Delete ‘$fileName’?" else "Create ‘$fileName’?") },
        text = { Text("Allow $deviceName to $action ‘$fileName’?") },
        confirmButton = {
            TextButton(onClick = onAllow) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("Deny") }
        },
    )
}
