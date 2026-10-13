package com.oneshotchat.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Join or create a room by name. */
@Composable
fun JoinRoomDialog(onDismiss: () -> Unit, onJoin: (name: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val clean = name.trim().removePrefix("#").lowercase()
    val valid = clean.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a room") },
        text = {
            Column {
                Text("Enter a room name. If it doesn't exist yet, you'll create it.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(33) },
                    singleLine = true,
                    label = { Text("#room-name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onJoin(clean) }) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Simple single-field text prompt. */
@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmText: String = "Save",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
