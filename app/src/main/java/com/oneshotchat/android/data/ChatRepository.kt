package com.oneshotchat.android.data

import com.oneshotchat.android.crypto.Identity
import com.oneshotchat.android.crypto.SignedStrings
import com.oneshotchat.android.net.ClientMeta
import com.oneshotchat.android.net.Discovery
import com.oneshotchat.android.net.EditMessageRequest
import com.oneshotchat.android.net.EventSocket
import com.oneshotchat.android.net.IdentityInfo
import com.oneshotchat.android.net.Message
import com.oneshotchat.android.net.OneShotApi
import com.oneshotchat.android.net.SendMessageRequest
import com.oneshotchat.android.net.SessionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

/**
 * Owns the session: discovery, login (challenge → sign → session), presence heartbeat,
 * message signing, and the shared receive-only [EventSocket]. Room-level state is kept
 * by the ViewModel, which calls through to [api].
 */
class ChatRepository(
    val identity: Identity,
    val storage: Storage,
    private val scope: CoroutineScope,
) {
    val api = OneShotApi(baseUrl = storage.serverBaseUrl)
    val socket = EventSocket(api, scope)

    /** The origin used in every signed string — derived from OUR configured base URL. */
    val serverOrigin: String = originOf(api.baseUrl)

    var discovery: Discovery? = null
        private set
    var self: IdentityInfo? = null
        private set

    private var heartbeatJob: Job? = null

    private val clientMeta = ClientMeta(
        name = "oneshot-android",
        version = "0.1.0",
        url = storage.clientUrl,
    )

    /** Full login: discover, get challenge, sign it, open a session, start heartbeating. */
    suspend fun login(): IdentityInfo {
        discovery = runCatching { api.discover() }.getOrNull()

        val challenge = api.challenge(identity.fingerprint).challenge
        val loginString = SignedStrings.login(serverOrigin, identity.fingerprint, challenge)
        val signature = identity.sign(loginString)

        val resp = api.session(
            SessionRequest(
                identity = identity.fingerprint,
                challenge = challenge,
                signature = signature,
                name = storage.displayName,
                incognito = storage.incognito,
                client = clientMeta,
            ),
        )
        api.token = resp.token
        self = resp.identity
        startHeartbeat()
        return resp.identity
    }

    suspend fun ensureSession() {
        if (api.token == null) login()
    }

    /** Keep presence alive while idle (every 2 minutes), independent of the socket. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (scope.isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                runCatching { api.heartbeat() }
            }
        }
    }

    fun startSocket() = socket.start()
    fun stopSocket() = socket.stop()

    suspend fun logout() {
        heartbeatJob?.cancel()
        socket.stop()
        runCatching { api.logout() }
        api.token = null
    }

    suspend fun setDisplayName(name: String) {
        api.setName(name)
        storage.displayName = name
    }

    // ---- Sending (with optional signing) --------------------------------------

    /** Send a message. If [sign] is true, attach an Ed25519 signature (version 1). */
    suspend fun sendMessage(roomId: String, text: String, sign: Boolean): Message {
        val nonce = newNonce()
        val signature = if (sign) {
            identity.sign(SignedStrings.message(serverOrigin, roomId, identity.fingerprint, nonce, 1, text))
        } else null
        return api.send(roomId, SendMessageRequest(text = text, nonce = nonce, signature = signature))
    }

    /** Edit a message. Signs the new version iff [sign] and we know the original nonce. */
    suspend fun editMessage(roomId: String, original: Message, newText: String, sign: Boolean): Message {
        val newVersion = original.version + 1
        val signature = if (sign && original.nonce != null) {
            identity.sign(
                SignedStrings.message(
                    serverOrigin, roomId, identity.fingerprint, original.nonce, newVersion, newText,
                ),
            )
        } else null
        return api.edit(roomId, original.id, EditMessageRequest(newText, newVersion, signature))
    }

    /** Verify a received signed message ourselves rather than trusting the server. */
    fun verify(message: Message, roomId: String): Boolean {
        val sig = message.signature ?: return false
        val nonce = message.nonce ?: return false
        return SignedStrings.verifyMessage(
            server = serverOrigin,
            room = roomId,
            author = message.author.identity,
            nonce = nonce,
            version = message.version,
            text = message.text,
            signatureBase32 = sig,
        )
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 120_000L

        fun newNonce(): String = UUID.randomUUID().toString().replace("-", "")

        /**
         * Compute a signing origin "scheme://host[:port]" with default ports and any
         * path stripped, lowercased — per protocol section 4.
         */
        fun originOf(baseUrl: String): String {
            val uri = URI(baseUrl.trim())
            val scheme = (uri.scheme ?: "https").lowercase()
            val host = (uri.host ?: "").lowercase()
            val port = uri.port
            val isDefault = (scheme == "https" && (port == 443 || port == -1)) ||
                (scheme == "http" && (port == 80 || port == -1))
            return if (isDefault) "$scheme://$host" else "$scheme://$host:$port"
        }
    }
}
