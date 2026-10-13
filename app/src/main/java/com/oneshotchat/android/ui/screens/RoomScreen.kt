package com.oneshotchat.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oneshotchat.android.crypto.Mentions
import com.oneshotchat.android.net.Message
import com.oneshotchat.android.ui.ChatViewModel
import com.oneshotchat.android.ui.RoomView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RoomScreen(vm: ChatViewModel, room: RoomView, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    var editing by remember { mutableStateOf<Message?>(null) }
    var reacting by remember { mutableStateOf<Message?>(null) }
    var contactFor by remember { mutableStateOf<String?>(null) }
    var sign by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }
    var sideTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Auto-scroll to the newest message when the count grows.
    LaunchedEffect(room.messages.size) {
        if (room.messages.isNotEmpty()) listState.animateScrollToItem(room.messages.lastIndex)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RoomHeader(room, onBack, onPeople = { showPeople = true }) },
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
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        notice,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(room.messages, key = { _, m -> m.id }) { index, msg ->
                    val prev = room.messages.getOrNull(index - 1)
                    MessageItem(
                        vm = vm,
                        room = room,
                        msg = msg,
                        showHeader = prev == null || prev.author.identity != msg.author.identity,
                        onEdit = { editing = msg },
                        onReact = { reacting = msg },
                        onSaveContact = { contactFor = msg.author.identity },
                        onSideRoom = {
                            sideTarget = msg.author.identity to
                                (room.labels[msg.author.identity] ?: msg.author.name ?: "anon")
                        },
                    )
                }
            }
        }
    }

    if (showPeople) {
        PeopleSheet(
            vm = vm,
            room = room,
            onDismiss = { showPeople = false },
            onSideRoom = { fp, label ->
                showPeople = false
                sideTarget = fp to label
            },
        )
    }

    sideTarget?.let { (fp, label) ->
        SideRoomDialog(
            label = label,
            onDismiss = { sideTarget = null },
            onOpen = { body, mention ->
                sideTarget = null
                vm.startSideRoom(fp, body, mention)
            },
        )
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
private fun RoomHeader(room: RoomView, onBack: () -> Unit, onPeople: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val side = room.room.side
    val other = side?.members?.firstOrNull { it != room.me }
    val title = if (side != null) {
        "side · " + (other?.let { room.labels[it] } ?: other?.take(8) ?: "?")
    } else {
        "#${room.room.name ?: room.room.id}"
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave", tint = dim)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = if (side != null) {
                    "closes after 7 days empty. the operator can read it."
                } else {
                    room.room.topic?.takeIf { it.isNotBlank() } ?: "${room.occupants.size} here"
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.clickable(onClick = onPeople).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Group, contentDescription = "People", tint = dim)
                Spacer(Modifier.width(6.dp))
                Text("${room.occupants.size}", style = MaterialTheme.typography.labelMedium, color = dim)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageItem(
    vm: ChatViewModel,
    room: RoomView,
    msg: Message,
    showHeader: Boolean,
    onEdit: () -> Unit,
    onReact: () -> Unit,
    onSaveContact: () -> Unit,
    onSideRoom: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    val mine = msg.author.identity == vm.myFingerprint
    val isAdmin = msg.author.identity in room.admins
    val label = room.labels[msg.author.identity] ?: msg.author.name ?: "anon"
    val verified = msg.signature != null && vm.verified(msg)
    val rendered = Mentions.render(msg.text) { fp -> "@" + (room.labels[fp] ?: fp.take(8)) }

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { expanded = !expanded }, onLongClick = onReact)
            .padding(start = 20.dp, end = 20.dp, top = if (showHeader) 14.dp else 3.dp, bottom = 3.dp),
    ) {
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (mine) primary else dim,
                )
                if (isAdmin) {
                    Spacer(Modifier.width(6.dp))
                    Text("admin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(rendered, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
            if (verified) {
                Spacer(Modifier.width(6.dp))
                Text("✓", style = MaterialTheme.typography.labelSmall, color = primary)
            }
            if (msg.editedAt != null) {
                Spacer(Modifier.width(6.dp))
                Text("edited", style = MaterialTheme.typography.labelSmall, color = dim)
            }
        }
        if (msg.reactions.isNotEmpty()) {
            ReactionRow(msg, vm.myFingerprint) { emoji -> vm.react(msg, emoji) }
        }
        if (expanded) {
            FlowRow(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                formatTime(msg.createdAt)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = dim)
                }
                ActionText("copy") { expanded = false; clipboard.setText(AnnotatedString(rendered)) }
                ActionText("react") { expanded = false; onReact() }
                if (mine) {
                    ActionText("edit") { expanded = false; onEdit() }
                } else {
                    ActionText("save contact") { expanded = false; onSaveContact() }
                    ActionText("side room") { expanded = false; onSideRoom() }
                }
            }
        }
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(iso: String?): String? = iso?.let {
    runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(timeFormat) }.getOrNull()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionRow(msg: Message, me: String?, onToggle: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        msg.reactions.forEach { r ->
            val mineReaction = me != null && me in r.identities
            Text(
                "${r.reaction} ${r.count}",
                style = MaterialTheme.typography.labelMedium,
                color = if (mineReaction) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onToggle(r.reaction) }.padding(vertical = 4.dp),
            )
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
        text = { EmojiGrid(common, onPick) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiGrid(items: List<String>, onPick: (String) -> Unit) {
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
    val hasText = field.text.isNotBlank()

    Column(Modifier.fillMaxWidth()) {
        if (candidates.isNotEmpty() && mentionQuery != null) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column {
                    candidates.forEach { c ->
                        Text(
                            "@${c.label}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { field = applyMention(field, mentionQuery, vm.mentionToken(c.fingerprint)) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleSign) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = if (sign) "Signing on" else "Signing off",
                    tint = if (sign) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
            TextField(
                value = field,
                onValueChange = { field = it },
                placeholder = { Text(if (sign) "signed" else "say something") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            if (hasText) {
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
