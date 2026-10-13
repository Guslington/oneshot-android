package com.oneshotchat.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oneshotchat.android.crypto.Mentions
import com.oneshotchat.android.net.Message
import com.oneshotchat.android.ui.ChatViewModel
import com.oneshotchat.android.ui.RoomView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(vm: ChatViewModel, room: RoomView, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<Message?>(null) }
    var reacting by remember { mutableStateOf<Message?>(null) }
    var contactFor by remember { mutableStateOf<String?>(null) }
    var sign by remember { mutableStateOf(false) }

    // Auto-scroll to the newest message when the count grows.
    LaunchedEffect(room.messages.size) {
        if (room.messages.isNotEmpty()) listState.animateScrollToItem(room.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("#${room.room.name ?: room.room.id}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val subtitle = room.room.topic?.takeIf { it.isNotBlank() }
                            ?: "${room.occupants.size} here"
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave")
                    }
                },
            )
        },
        bottomBar = {
            ComposeBar(
                vm = vm,
                sign = sign,
                onToggleSign = { sign = !sign },
                onSend = { text -> vm.send(text, sign) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            room.notices.forEach { notice ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text("📢 $notice", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(room.messages, key = { it.id }) { msg ->
                    MessageItem(
                        vm = vm,
                        room = room,
                        msg = msg,
                        onEdit = { editing = msg },
                        onReact = { reacting = msg },
                        onSaveContact = { contactFor = msg.author.identity },
                    )
                }
            }
        }
    }

    editing?.let { msg ->
        TextPromptDialog(
            title = "Edit message",
            label = "New text",
            initial = msg.text,
            confirmText = "Save edit",
            onDismiss = { editing = null },
            onConfirm = { newText ->
                editing = null
                vm.edit(msg, newText, sign)
            },
        )
    }

    reacting?.let { msg ->
        ReactionPicker(
            onDismiss = { reacting = null },
            onPick = { emoji ->
                reacting = null
                vm.react(msg, emoji)
            },
        )
    }

    contactFor?.let { fp ->
        TextPromptDialog(
            title = "Save contact",
            label = "Petname for this fingerprint",
            onDismiss = { contactFor = null },
            onConfirm = { petname ->
                contactFor = null
                if (petname.isNotBlank()) vm.saveContact(fp, petname.trim())
            },
        )
    }
}

@Composable
private fun MessageItem(
    vm: ChatViewModel,
    room: RoomView,
    msg: Message,
    onEdit: () -> Unit,
    onReact: () -> Unit,
    onSaveContact: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val mine = msg.author.identity == vm.myFingerprint
    val isAdmin = msg.author.identity in room.admins
    val label = room.labels[msg.author.identity] ?: msg.author.name ?: "anon"
    val verified = msg.signature != null && vm.verified(msg)
    val rendered = Mentions.render(msg.text) { fp ->
        "@" + (room.labels[fp] ?: fp.take(8))
    }

    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { menu = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (isAdmin) {
                    Spacer(Modifier.width(4.dp))
                    Text("ADMIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Signed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(14.dp),
                    )
                }
                if (msg.editedAt != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("(edited)", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(rendered, style = MaterialTheme.typography.bodyLarge)
            if (msg.reactions.isNotEmpty()) {
                ReactionRow(msg) { emoji -> vm.react(msg, emoji) }
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Copy text") },
                onClick = { menu = false; clipboard.setText(AnnotatedString(rendered)) },
            )
            DropdownMenuItem(text = { Text("React") }, onClick = { menu = false; onReact() })
            if (mine) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
            if (!mine) DropdownMenuItem(text = { Text("Save contact") }, onClick = { menu = false; onSaveContact() })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionRow(msg: Message, onToggle: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        msg.reactions.forEach { r ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.clickable { onToggle(r.reaction) },
            ) {
                Text(
                    "${r.reaction} ${r.count}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ReactionPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val common = listOf("👍", "❤️", "😂", "🎉", "🤔", "👀", "🔥", "+1")
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("React") },
        text = {
            FlowRowCompat(common, onPick)
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(items: List<String>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { e ->
            Text(
                e,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clickable { onPick(e) }.padding(6.dp),
            )
        }
    }
}

@Composable
private fun ComposeBar(
    vm: ChatViewModel,
    sign: Boolean,
    onToggleSign: () -> Unit,
    onSend: (String) -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue()) }

    // Detect an in-progress "@mention" immediately before the cursor.
    val mentionQuery = remember(field) { currentMentionQuery(field) }
    val candidates = if (mentionQuery != null) vm.mentionCandidates(mentionQuery.query) else emptyList()

    Column(Modifier.fillMaxWidth()) {
        if (candidates.isNotEmpty() && mentionQuery != null) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    candidates.forEach { c ->
                        Text(
                            "@${c.label}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { field = applyMention(field, mentionQuery, vm.mentionToken(c.fingerprint)) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleSign) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = if (sign) "Signing on" else "Signing off",
                    tint = if (sign) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextField(
                value = field,
                onValueChange = { field = it },
                placeholder = { Text(if (sign) "Signed message…" else "Message…") },
                modifier = Modifier.width(0.dp).weight(1f),
                maxLines = 5,
            )
            IconButton(
                onClick = {
                    val text = field.text.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        field = TextFieldValue()
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The '@query' currently being typed just before the cursor, if any. */
private data class MentionSpan(val atIndex: Int, val query: String)

private fun currentMentionQuery(field: TextFieldValue): MentionSpan? {
    val cursor = field.selection.end
    if (cursor == 0 || cursor > field.text.length) return null
    val before = field.text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0) return null
    val token = before.substring(at + 1)
    if (token.any { it.isWhitespace() }) return null
    return MentionSpan(at, token)
}

/** Replace the typed '@query' with a real mention token and a trailing space. */
private fun applyMention(field: TextFieldValue, span: MentionSpan, token: String): TextFieldValue {
    val cursor = field.selection.end
    val prefix = field.text.substring(0, span.atIndex)
    val suffix = field.text.substring(cursor)
    val inserted = "$token "
    val newText = prefix + inserted + suffix
    val newCursor = (prefix + inserted).length
    return TextFieldValue(text = newText, selection = TextRange(newCursor))
}
