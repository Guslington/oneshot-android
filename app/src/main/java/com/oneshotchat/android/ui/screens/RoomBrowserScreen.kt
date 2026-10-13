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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oneshotchat.android.net.Room
import com.oneshotchat.android.ui.ChatViewModel
import com.oneshotchat.android.ui.collectAsStateLifecycle
import kotlinx.coroutines.delay

@Composable
fun RoomBrowserScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenMentions: () -> Unit,
    onOpenContacts: () -> Unit,
) {
    val rooms by vm.rooms.collectAsStateLifecycle()
    val unread by vm.unreadMentions.collectAsStateLifecycle()
    val loading by vm.roomsLoading.collectAsStateLifecycle()
    var showJoin by remember { mutableStateOf(false) }

    // The browser is cacheable for 10s server-side, so refreshing every 15s is polite.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshRooms()
            vm.pollUnread()
            delay(15_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("oshi", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenContacts) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Contacts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenMentions) {
                BadgedBox(badge = { if (unread > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                    Icon(
                        Icons.Default.AlternateEmail,
                        contentDescription = if (unread > 0) "Mentions, $unread new" else "Mentions",
                        tint = if (unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showJoin = true }) {
                Icon(Icons.Default.Add, contentDescription = "Join a room", tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (loading && rooms.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (rooms.isEmpty() && !loading) {
            Text(
                "nothing here.\ntap + to join a room. try #lobby.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(rooms, key = { it.id }) { room ->
                RoomRow(room) { vm.openRoomFromBrowser(room) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (showJoin) {
        JoinRoomDialog(
            onDismiss = { showJoin = false },
            onJoin = { name, create ->
                showJoin = false
                vm.joinByName(name, create = create)
            },
        )
    }
}

@Composable
private fun RoomRow(room: Room, onClick: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val count = room.occupantCount ?: room.occupants?.size
    val meta = listOfNotNull(
        room.access?.takeIf { it != "open" },
        room.speaking?.takeIf { it != "open" },
        count?.toString(),
    ).joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#${room.name ?: room.id}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelMedium, color = dim)
        }
        if (!room.topic.isNullOrBlank()) {
            Text(
                room.topic,
                style = MaterialTheme.typography.bodySmall,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
