package com.oneshotchat.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oneshotchat.android.net.RoleEntry
import com.oneshotchat.android.net.Room
import com.oneshotchat.android.ui.ChatViewModel
import com.oneshotchat.android.ui.RoomView
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Who's here. Everyone can see the list; operators (and server admins) can also set
 * roles, kick, change room settings and create invite codes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleSheet(
    vm: ChatViewModel,
    room: RoomView,
    onDismiss: () -> Unit,
    onSideRoom: (fingerprint: String, label: String) -> Unit,
) {
    val isSide = room.room.side != null
    var confirmClose by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf<String?>(null) }
    var roles by remember { mutableStateOf<List<RoleEntry>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var kickTarget by remember { mutableStateOf<String?>(null) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    fun reload() {
        if (room.canOperate) vm.loadRoles { roles = it }
    }
    LaunchedEffect(room.canOperate) { reload() }

    val presentIds = room.occupants.map { it.identity }.toSet()
    val absent = roles.filter { it.identity !in presentIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("here · ${room.occupants.size}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            room.occupants.forEach { o ->
                val label = room.labels[o.identity] ?: o.name ?: "anon"
                val isMe = o.identity == room.me
                val isAdmin = o.admin || o.identity in room.admins
                val manageable = room.canOperate && !isMe && !isAdmin
                val tags = listOfNotNull(
                    "you".takeIf { isMe },
                    "admin".takeIf { isAdmin },
                    o.role,
                ).joinToString(" · ")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isMe) {
                            selected = if (selected == o.identity) null else o.identity
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (tags.isNotEmpty()) Text(tags, style = MaterialTheme.typography.labelMedium, color = dim)
                    }
                    if (selected == o.identity) {
                        Column {
                            SheetAction("side room") { onSideRoom(o.identity, label) }
                            if (manageable && !isSide) {
                                RoleActions(
                                    onRole = { role -> vm.setRole(o.identity, role) { reload() } },
                                    onKick = { kickTarget = o.identity },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (absent.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("roles (not here)", style = MaterialTheme.typography.labelMedium, color = dim)
                absent.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.identity.take(12) + "…",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(r.role, style = MaterialTheme.typography.labelMedium, color = dim)
                        TextButton(onClick = { vm.setRole(r.identity, null) { reload() } }) { Text("clear") }
                    }
                }
            }

            if (isSide) {
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = { confirmClose = true }) {
                    Text("close this side room", color = MaterialTheme.colorScheme.error)
                }
            }

            if (room.canOperate && !isSide) {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showSettings = true }) { Text("room settings") }
                    TextButton(onClick = { showInvite = true }) { Text("invite code") }
                }
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close side room?") },
            text = { Text("This deletes the room and everything in it for both of you.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    onDismiss()
                    vm.closeSideRoom()
                }) { Text("Close it", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } },
        )
    }
    if (showSettings) {
        RoomSettingsDialog(
            room = room.room,
            onDismiss = { showSettings = false },
            onSave = { settings ->
                showSettings = false
                if (settings.isNotEmpty()) vm.updateRoomSettings(settings)
            },
        )
    }
    if (showInvite) {
        InviteDialog(vm = vm, onDismiss = { showInvite = false })
    }
    kickTarget?.let { id ->
        TextPromptDialog(
            title = "Kick",
            label = "Reason (optional)",
            confirmText = "Kick",
            onDismiss = { kickTarget = null },
            onConfirm = { reason ->
                kickTarget = null
                vm.kick(id, reason)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleActions(onRole: (String?) -> Unit, onKick: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
        SheetAction("voice") { onRole("voice") }
        SheetAction("mute") { onRole("muted") }
        SheetAction("operator") { onRole("operator") }
        SheetAction("clear role") { onRole(null) }
        SheetAction("kick") { onKick() }
        SheetAction("ban", danger = true) { onRole("banned") }
    }
}

@Composable
private fun SheetAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

/** Edit room settings. Only fields that changed are sent. */
@Composable
fun RoomSettingsDialog(room: Room, onDismiss: () -> Unit, onSave: (kotlinx.serialization.json.JsonObject) -> Unit) {
    var topic by remember { mutableStateOf(room.topic.orEmpty()) }
    var visibility by remember { mutableStateOf(room.visibility ?: "listed") }
    var access by remember { mutableStateOf(room.access ?: "open") }
    var speaking by remember { mutableStateOf(room.speaking ?: "open") }
    var retention by remember { mutableStateOf(room.retentionSeconds) }
    var key by remember { mutableStateOf("") }

    val current = room.retentionSeconds
    val retentionOptions = buildList<Pair<String, Int?>> {
        addAll(RETENTION_PRESETS)
        if (current != null && RETENTION_PRESETS.none { it.second == current }) add("${current / 3600}h (current)" to current)
        add("forever" to null)
    }
    // Switching to key access needs a key; keeping key access without typing one is fine.
    val needsKey = access == "key" && room.access != "key"
    val canSave = !needsKey || key.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it.take(200) },
                    label = { Text("topic") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceRow("visibility", listOf("listed" to "listed", "unlisted" to "unlisted"), visibility) { visibility = it }
                ChoiceRow("who can join", listOf("anyone" to "open", "key" to "key", "invite" to "invite"), access) { access = it }
                if (access == "key") {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it.take(72) },
                        label = { Text(if (room.access == "key") "new key (leave blank to keep)" else "room key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                ChoiceRow("who can speak", listOf("anyone" to "open", "voiced only" to "moderated"), speaking) { speaking = it }
                ChoiceRow("keep messages for", retentionOptions, retention) { retention = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val retentionValue = retention
                    onSave(
                        buildJsonObject {
                            if (topic != room.topic.orEmpty()) put("topic", topic)
                            if (visibility != room.visibility) put("visibility", visibility)
                            if (access != room.access) put("access", access)
                            if (speaking != room.speaking) put("speaking", speaking)
                            if (access == "key" && key.isNotBlank()) put("key", key)
                            if (retentionValue != room.retentionSeconds) {
                                put("retention_seconds", if (retentionValue == null) JsonNull else JsonPrimitive(retentionValue))
                            }
                        },
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Create an invite code for an invite-only room. */
@Composable
fun InviteDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    var uses by remember { mutableStateOf(1) }
    var expiresIn by remember { mutableStateOf(86_400) }
    var code by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite code") },
        text = {
            Column {
                val created = code
                if (created == null) {
                    ChoiceRow("uses", listOf("1" to 1, "5" to 5, "10" to 10, "100" to 100), uses) { uses = it }
                    ChoiceRow(
                        "expires after",
                        listOf("1 hour" to 3600, "1 day" to 86_400, "7 days" to 604_800),
                        expiresIn,
                    ) { expiresIn = it }
                } else {
                    Text(created, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    Text(
                        "share it with whoever should join. tap copy to put it on the clipboard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (code == null) {
                TextButton(onClick = { vm.createInvite(uses, expiresIn) { code = it } }) { Text("Create") }
            } else {
                TextButton(onClick = { clipboard.setText(AnnotatedString(code.orEmpty())) }) { Text("Copy") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (code == null) "Cancel" else "Done") } },
    )
}
