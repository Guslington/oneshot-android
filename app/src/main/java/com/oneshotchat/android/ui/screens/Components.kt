package com.oneshotchat.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oneshotchat.android.net.CreateSettings
import com.oneshotchat.android.ui.CredentialPrompt

/** Retention presets in seconds (the protocol allows 3600..31536000, or forever). */
val RETENTION_PRESETS: List<Pair<String, Int?>> = listOf(
    "1 hour" to 3600,
    "1 day" to 86_400,
    "7 days" to 604_800,
    "30 days" to 2_592_000,
    "1 year" to 31_536_000,
)

/** A quiet inline picker: options in a row, the selected one in the accent colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            options.forEach { (text, value) ->
                val on = value == selected
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onSelect(value) }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Join or create a room by name. The options only apply if the name is free and this
 * call creates the room; they're ignored when joining an existing room.
 */
@Composable
fun JoinRoomDialog(onDismiss: () -> Unit, onJoin: (name: String, create: CreateSettings?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var showOptions by remember { mutableStateOf(false) }
    var visibility by remember { mutableStateOf("listed") }
    var access by remember { mutableStateOf("open") }
    var speaking by remember { mutableStateOf("open") }
    var retention by remember { mutableStateOf<Int?>(604_800) }
    var topic by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    val clean = name.trim().removePrefix("#").lowercase()
    val validName = clean.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))
    val needsKey = showOptions && access == "key"
    val canJoin = validName && (!needsKey || key.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a room") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "If the name is free, you'll create the room.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(33) },
                    singleLine = true,
                    label = { Text("#room-name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showOptions = !showOptions }) {
                    Text(if (showOptions) "hide new-room options" else "new-room options")
                }
                if (showOptions) {
                    OutlinedTextField(
                        value = topic,
                        onValueChange = { topic = it.take(200) },
                        label = { Text("topic") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ChoiceRow(
                        "visibility",
                        listOf("listed" to "listed", "unlisted" to "unlisted"),
                        visibility,
                    ) { visibility = it }
                    ChoiceRow(
                        "who can join",
                        listOf("anyone" to "open", "key" to "key", "invite" to "invite"),
                        access,
                    ) { access = it }
                    if (access == "key") {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it.take(72) },
                            label = { Text("room key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    ChoiceRow(
                        "who can speak",
                        listOf("anyone" to "open", "voiced only" to "moderated"),
                        speaking,
                    ) { speaking = it }
                    ChoiceRow("keep messages for", RETENTION_PRESETS, retention) { retention = it }
                    Text(
                        "listed rooms are public, and unlisted isn't secret: anyone who guesses the name can find it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canJoin,
                onClick = {
                    val create = if (showOptions) {
                        CreateSettings(
                            visibility = visibility,
                            access = access,
                            speaking = speaking,
                            topic = topic.ifBlank { null },
                            retentionSeconds = retention,
                            key = if (access == "key") key else null,
                        )
                    } else null
                    onJoin(clean, create)
                },
            ) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for a room key or invite code after the server says one is required. */
@Composable
fun CredentialDialog(prompt: CredentialPrompt, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(prompt) { mutableStateOf("") }
    val isKey = prompt.kind == "key"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("#${prompt.name}") },
        text = {
            Column {
                Text(
                    when {
                        isKey && prompt.retry -> "That key didn't work. Try again."
                        isKey -> "This room needs a key."
                        prompt.retry -> "That invite code didn't work. Try again."
                        else -> "This room is invite-only. Enter an invite code."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    singleLine = true,
                    label = { Text(if (isKey) "room key" else "invite code") },
                    visualTransformation = if (isKey) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = value.isNotEmpty(), onClick = { onSubmit(value) }) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Start a side room with one person. The room is only created when you send the first
 * message. The mention is how they find out, so it's on by default and visibly optional.
 */
@Composable
fun SideRoomDialog(
    label: String,
    onDismiss: () -> Unit,
    onOpen: (body: String, mention: Boolean) -> Unit,
) {
    var body by remember { mutableStateOf("") }
    var mention by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Side room with $label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "a room for just the two of you. the operator can still read it. it closes after " +
                        "7 days with nobody in it, and either of you can close it any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(4000) },
                    label = { Text("first message") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("mention $label", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (mention) "this is how they'll be told."
                            else "they won't be told, so they may never see it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mention) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Switch(checked = mention, onCheckedChange = { mention = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = body.isNotBlank(), onClick = { onOpen(body, mention) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Confirm joining a side room someone opened with us. We never join automatically. */
@Composable
fun JoinSideRoomDialog(label: String, onDismiss: () -> Unit, onJoin: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label opened a side room") },
        text = {
            Text(
                "a room for just the two of you. the operator can still read it. it closes after " +
                    "7 days with nobody in it, and either of you can close it any time.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onJoin) { Text("Join") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
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
