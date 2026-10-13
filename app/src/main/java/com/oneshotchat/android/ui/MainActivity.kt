package com.oneshotchat.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oneshotchat.android.ui.screens.RoomBrowserScreen
import com.oneshotchat.android.ui.screens.RoomScreen
import com.oneshotchat.android.ui.screens.SettingsScreen
import com.oneshotchat.android.ui.screens.SetupScreen
import com.oneshotchat.android.ui.theme.OneShotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OneShotTheme {
                AppRoot()
            }
        }
    }
}

private enum class TopScreen { Browser, Settings }

@Composable
private fun AppRoot(vm: ChatViewModel = viewModel()) {
    val phase by vm.phase.collectAsStateLifecycle()
    val activeRoom by vm.activeRoom.collectAsStateLifecycle()
    val toast by vm.toast.collectAsStateLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var topScreen by remember { mutableStateOf(TopScreen.Browser) }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.toastShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (val p = phase) {
                is AppPhase.Loading -> Centered { CircularProgressIndicator() }
                is AppPhase.NeedsIdentity -> SetupScreen(
                    onCreate = vm::createIdentity,
                    onImport = vm::importIdentity,
                )
                is AppPhase.Error -> Centered {
                    Text(p.message, textAlign = TextAlign.Center)
                    Button(onClick = { vm.logout(forget = false) }, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Back to setup")
                    }
                }
                is AppPhase.Ready -> {
                    val room = activeRoom
                    when {
                        room != null -> RoomScreen(vm = vm, room = room, onBack = vm::closeRoom)
                        topScreen == TopScreen.Settings -> SettingsScreen(
                            vm = vm,
                            fingerprint = p.fingerprint,
                            name = p.name,
                            onBack = { topScreen = TopScreen.Browser },
                        )
                        else -> RoomBrowserScreen(
                            vm = vm,
                            onOpenSettings = { topScreen = TopScreen.Settings },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) { content() }
    }
}
