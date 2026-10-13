package com.oneshotchat.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
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
import com.oneshotchat.android.AppState
import com.oneshotchat.android.notify.Notifications
import com.oneshotchat.android.ui.screens.ContactsScreen
import com.oneshotchat.android.ui.screens.CredentialDialog
import com.oneshotchat.android.ui.screens.MentionsScreen
import com.oneshotchat.android.ui.screens.RoomBrowserScreen
import com.oneshotchat.android.ui.screens.RoomScreen
import com.oneshotchat.android.ui.screens.SettingsScreen
import com.oneshotchat.android.ui.screens.SetupScreen
import com.oneshotchat.android.ui.theme.OneShotTheme

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            OneShotTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppState.foreground = true
    }

    override fun onStop() {
        AppState.foreground = false
        super.onStop()
    }

    /** A tapped mention notification carries the room to open. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifications.EXTRA_MENTIONS, false) == true) {
            intent.removeExtra(Notifications.EXTRA_MENTIONS)
            vm.requestMentions()
            return
        }
        val room = intent?.getStringExtra(Notifications.EXTRA_ROOM) ?: return
        intent.removeExtra(Notifications.EXTRA_ROOM)
        vm.openRoomByName(room)
    }
}

private enum class TopScreen { Browser, Settings, Mentions, Contacts }

@Composable
private fun AppRoot(vm: ChatViewModel = viewModel()) {
    val phase by vm.phase.collectAsStateLifecycle()
    val activeRoom by vm.activeRoom.collectAsStateLifecycle()
    val toast by vm.toast.collectAsStateLifecycle()
    val prompt by vm.prompt.collectAsStateLifecycle()
    val showMentions by vm.showMentionsRequest.collectAsStateLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var topScreen by remember { mutableStateOf(TopScreen.Browser) }

    // A tapped side-room notification lands on the mentions feed, never straight into a room.
    LaunchedEffect(showMentions) {
        if (showMentions) {
            topScreen = TopScreen.Mentions
            vm.mentionsRequestHandled()
        }
    }

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
                        topScreen == TopScreen.Mentions -> MentionsScreen(
                            vm = vm,
                            onBack = { topScreen = TopScreen.Browser },
                        )
                        topScreen == TopScreen.Contacts -> ContactsScreen(
                            vm = vm,
                            onBack = { topScreen = TopScreen.Browser },
                        )
                        topScreen == TopScreen.Settings -> SettingsScreen(
                            vm = vm,
                            fingerprint = p.fingerprint,
                            name = p.name,
                            onBack = { topScreen = TopScreen.Browser },
                        )
                        else -> RoomBrowserScreen(
                            vm = vm,
                            onOpenSettings = { topScreen = TopScreen.Settings },
                            onOpenMentions = { topScreen = TopScreen.Mentions },
                            onOpenContacts = { topScreen = TopScreen.Contacts },
                        )
                    }
                }
            }
        }
    }

    prompt?.let { p ->
        CredentialDialog(
            prompt = p,
            onSubmit = { value ->
                if (p.kind == "key") vm.joinByName(p.name, key = value)
                else vm.joinByName(p.name, invite = value)
            },
            onDismiss = vm::dismissPrompt,
        )
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
