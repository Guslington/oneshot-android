package com.oneshotchat.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.oneshotchat.android.crypto.Mentions
import com.oneshotchat.android.data.Storage
import com.oneshotchat.android.ui.ChatViewModel

/**
 * People you've saved by full fingerprint, with a local petname. A saved contact is also
 * how a side room opened by someone is recognised (strangers can't use side rooms to
 * reach you), and the place to start one with someone who isn't in your current room.
 */
@Composable
fun ContactsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var contacts by remember { mutableStateOf(vm.contacts()) }
    var withSide by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var sideTarget by remember { mutableStateOf<Storage.Contact?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    // Side room names are derived, so "do we have one?" is a lookup per contact.
    LaunchedEffect(contacts) { vm.contactsWithSideRoom { withSide = it } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = dim)
            }
            Text("contacts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add contact", tint = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (contacts.isEmpty()) {
            Text(
                "nobody saved yet.\ntap a message, then \"save contact\", or add a fingerprint with +.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(contacts, key = { it.fingerprint }) { c ->
                val hasSide = c.fingerprint in withSide
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = if (selected == c.fingerprint) null else c.fingerprint }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.petname, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (hasSide) Text("side room", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        c.fingerprint.take(16) + "…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = dim,
                    )
                    if (selected == c.fingerprint) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (hasSide) {
                                Action("open side room") { vm.openSideRoom(c.fingerprint) }
                            } else {
                                Action("start side room") { sideTarget = c }
                            }
                            Action("remove") {
                                vm.removeContact(c.fingerprint)
                                contacts = vm.contacts()
                                selected = null
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    sideTarget?.let { c ->
        SideRoomDialog(
            label = c.petname,
            onDismiss = { sideTarget = null },
            onOpen = { body, mention ->
                sideTarget = null
                vm.startSideRoom(c.fingerprint, body, mention)
            },
        )
    }

    if (showAdd) {
        AddContactDialog(
            onDismiss = { showAdd = false },
            onAdd = { fp, petname ->
                showAdd = false
                vm.saveContact(fp, petname)
                contacts = vm.contacts()
            },
        )
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onAdd: (fingerprint: String, petname: String) -> Unit) {
    var fingerprint by remember { mutableStateOf("") }
    var petname by remember { mutableStateOf("") }
    val fp = fingerprint.trim().lowercase()
    val valid = Mentions.isFingerprint(fp) && petname.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text("full fingerprint (52 characters)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = petname,
                    onValueChange = { petname = it.take(64) },
                    label = { Text("petname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(fp, petname.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
