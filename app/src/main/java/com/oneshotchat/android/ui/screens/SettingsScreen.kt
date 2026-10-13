package com.oneshotchat.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.oneshotchat.android.ui.ChatViewModel
import com.oneshotchat.android.ui.collectAsStateLifecycle

@Composable
fun SettingsScreen(vm: ChatViewModel, fingerprint: String, name: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var displayName by remember { mutableStateOf(name ?: "") }
    var server by remember { mutableStateOf(vm.serverOrigin) }
    var copied by remember { mutableStateOf(false) }
    val backgroundNotifications by vm.backgroundNotifications.collectAsStateLifecycle()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setBackgroundNotifications(true)
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val contents = vm.exportIdentity() ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(contents.toByteArray()) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = dim)
            }
            Text("settings", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Label("fingerprint")
            Text(
                fingerprint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(fingerprint))
                    copied = true
                },
            )
            Text(
                if (copied) "copied" else "tap to copy",
                style = MaterialTheme.typography.labelSmall,
                color = dim,
                modifier = Modifier.padding(top = 4.dp),
            )

            Section()
            Label("name")
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it.take(64) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { vm.setDisplayName(displayName.trim()) },
                enabled = displayName.isNotBlank(),
            ) { Text("update") }

            Section()
            Label("server")
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { vm.setServer(server) }) { Text("set (log out to apply)") }

            Section()
            Label("notifications")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("mentions in the background", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "keeps a connection open and shows a small ongoing notification. " +
                            "some phones need battery optimisation turned off for oshi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                    )
                }
                Switch(
                    checked = backgroundNotifications,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            vm.setBackgroundNotifications(on)
                        }
                    },
                )
            }

            Section()
            Label("identity")
            Text(
                "this file is you. anyone holding it can speak as you. keep it like a password.",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
            TextButton(onClick = { exporter.launch("${fingerprint.take(12)}.identity") }) {
                Text("export identity file")
            }

            Section()
            TextButton(onClick = { vm.logout(forget = false) }) { Text("log out") }
            TextButton(onClick = { vm.logout(forget = true) }) {
                Text("forget this identity", color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "the operator can read every room. presence in listed rooms is public. " +
                    "signed messages can't be denied later.",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Section() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(16.dp))
}
