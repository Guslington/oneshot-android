package com.oneshotchat.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oneshotchat.android.crypto.Mentions
import com.oneshotchat.android.data.Storage
import com.oneshotchat.android.net.Mention
import com.oneshotchat.android.ui.ChatViewModel
import com.oneshotchat.android.ui.collectAsStateLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Messages that mentioned you in any room, newest first. Tap one to open its room. */
@Composable
fun MentionsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val items by vm.mentions.collectAsStateLifecycle()
    val loading by vm.mentionsLoading.collectAsStateLifecycle()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(Unit) { vm.openMentions() }
    var joining by remember { mutableStateOf<Pair<Mention, Storage.Contact>?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = dim)
            }
            Text("mentions", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (loading && items.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (items.isEmpty() && !loading) {
            Text(
                "nobody has mentioned you.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = dim,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.cursor ?: it.message.id.toString() }) { mention ->
                val contact = vm.sideRoomContact(mention)
                MentionRow(mention, me = vm.myFingerprint, sideFrom = contact?.petname) {
                    if (contact != null) joining = mention to contact
                    else mention.room?.name?.let { vm.openRoomByName(it); onBack() }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    joining?.let { (mention, contact) ->
        JoinSideRoomDialog(
            label = contact.petname,
            onDismiss = { joining = null },
            onJoin = {
                joining = null
                mention.room?.name?.let { name ->
                    vm.joinSideRoom(name, contact.fingerprint)
                    onBack()
                }
            },
        )
    }
}

private val dayTime = DateTimeFormatter.ofPattern("d MMM HH:mm")

@Composable
private fun MentionRow(mention: Mention, me: String?, sideFrom: String?, onClick: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val msg = mention.message
    val text = Mentions.render(msg.text) { fp -> if (fp == me) "@you" else "@" + fp.take(6) }
    val whenText = msg.createdAt?.let {
        runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(dayTime) }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row {
            Text(
                if (sideFrom != null) "side room from $sideFrom" else "#${mention.room?.name ?: "?"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (whenText != null) Text(whenText, style = MaterialTheme.typography.labelSmall, color = dim)
        }
        Text(msg.author.name ?: "anon", style = MaterialTheme.typography.labelMedium, color = dim)
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 4)
    }
}
