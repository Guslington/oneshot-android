package com.oneshotchat.android.notify

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.oneshotchat.android.AppState
import com.oneshotchat.android.crypto.SideRoom
import com.oneshotchat.android.data.ChatRepository
import com.oneshotchat.android.data.Storage
import com.oneshotchat.android.net.EventSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a session and the receive-only WebSocket open while the app is in the
 * background, and turns `mention` frames into notifications. This needs no server
 * support beyond the existing protocol. It uses its own session (an identity may hold
 * many), joins no rooms, and skips notifications while the app is on screen.
 */
class MentionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var repository: ChatRepository? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifications.FOREGROUND_ID,
            Notifications.foreground(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (repository == null) start()
        return START_STICKY
    }

    private fun start() {
        val storage = Storage(applicationContext)
        val identity = storage.loadIdentity()
        if (identity == null) {
            stopSelf()
            return
        }
        val repo = ChatRepository(identity, storage, scope)
        repository = repo

        scope.launch {
            // Log in, retrying with a growing delay while offline.
            var wait = 15_000L
            while (isActive) {
                try {
                    repo.login()
                    break
                } catch (_: Exception) {
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(300_000L)
                }
            }
            repo.startSocket()

            launch {
                repo.socket.frames.collect { frame ->
                    when (frame) {
                        is EventSocket.Frame.MentionFrame -> {
                            val own = frame.mention.message.author.identity == identity.fingerprint
                            if (!own && !AppState.foreground) {
                                // A side room from a saved contact is announced as one.
                                val author = frame.mention.message.author.identity
                                val contact = storage.contacts().firstOrNull { it.fingerprint == author }
                                val sideFrom = contact?.takeIf {
                                    frame.mention.room?.name ==
                                        SideRoom.name(repo.serverOrigin, identity.fingerprint, author)
                                }?.petname
                                Notifications.mention(applicationContext, frame.mention, identity.fingerprint, sideFrom)
                            }
                        }
                        is EventSocket.Frame.SessionNotice -> {
                            if (frame.code == "session_expired") {
                                runCatching { repo.login() }
                            }
                        }
                        else -> Unit
                    }
                }
            }

            // Sessions last 24h; renew well before that. The socket picks up the new
            // token the next time it reconnects (the server closes it every 2h at most).
            while (isActive) {
                delay(6 * 60 * 60 * 1000L)
                runCatching { repo.login() }
            }
        }
    }

    override fun onDestroy() {
        repository?.socket?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
