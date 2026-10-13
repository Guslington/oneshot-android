package com.oneshotchat.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oneshotchat.android.net.Room
import com.oneshotchat.android.ui.ChatViewModel
import com.oneshotchat.android.ui.collectAsStateLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomBrowserScreen(vm: ChatViewModel, onOpenSettings: () -> Unit) {
    val rooms by vm.rooms.collectAsStateLifecycle()
    val loading by vm.roomsLoading.collectAsStateLifecycle()
    var showJoin by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rooms") },
                actions = {
                    IconButton(onClick = { vm.refreshRooms() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showJoin = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Join a room")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (rooms.isEmpty() && !loading) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.width(1.dp))
                    Text(
                        "No listed rooms. Tap + to join one by name — try #lobby or #conformance.",
                        modifier = Modifier.padding(32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rooms, key = { it.id }) { room ->
                    RoomRow(room) { vm.openRoomFromBrowser(room) }
                }
            }
        }
    }

    if (showJoin) {
        JoinRoomDialog(
            onDismiss = { showJoin = false },
            onJoin = { name ->
                showJoin = false
                vm.joinByName(name)
            },
        )
    }
}

@Composable
private fun RoomRow(room: Room, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${room.name ?: room.id}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                val count = room.occupantCount ?: room.occupants?.size
                if (count != null) {
                    Text("· $count here", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!room.topic.isNullOrBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(room.topic, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                room.access?.let { Chip(it) }
                room.speaking?.takeIf { it != "open" }?.let { Chip(it) }
                room.visibility?.takeIf { it != "listed" }?.let { Chip(it) }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .padding(0.dp),
    )
}
