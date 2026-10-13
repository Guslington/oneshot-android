package com.oneshotchat.android.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The receive-only WebSocket (protocol section 9). It:
 *  - fetches a single-use ticket, opens the socket to the ticket URL,
 *  - pings every [PING_INTERVAL_MS] (keeps the connection and presence alive),
 *  - reconnects with exponential backoff after any close (the server always closes
 *    within 2 hours), and
 *  - surfaces events and other frames as a flow for the repository to consume.
 *
 * Catch-up after a reconnect (`GET …/events?after=`) is the repository's job, driven
 * by the [Frame.Reconnected] signal emitted on every (re)connect.
 */
class EventSocket(
    private val api: OneShotApi,
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient = OneShotApi.defaultClient(),
) {
    sealed interface Frame {
        data class EventFrame(val event: Event) : Frame
        data class MentionFrame(val mention: Mention) : Frame
        data class SessionNotice(val code: String, val text: String) : Frame
        /** Emitted each time the socket (re)connects — trigger a catch-up poll. */
        data object Reconnected : Frame
        data object Unavailable : Frame
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    val frames: SharedFlow<Frame> = _frames

    private var loopJob: Job? = null
    private var pingJob: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        loopJob = scope.launch { connectLoop() }
    }

    fun stop() {
        running.set(false)
        pingJob?.cancel()
        socket?.close(1000, "client stop")
        socket = null
        loopJob?.cancel()
    }

    private suspend fun connectLoop() {
        var backoffMs = 1000L
        while (scope.isActive && running.get()) {
            val opened = try {
                openOnce()
            } catch (e: ApiException) {
                if (e.code == "unavailable") {
                    _frames.emit(Frame.Unavailable)
                    return // server has no WebSocket; caller should poll instead
                }
                false
            } catch (_: Exception) {
                false
            }
            // Wait until the socket closes, then back off and retry.
            if (opened) backoffMs = 1000L
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    /** Open one connection and suspend-return when it closes. */
    private suspend fun openOnce(): Boolean {
        val ticket = api.wsTicket()
        val request = Request.Builder().url(ticket.url).build()
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()

        val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                scope.launch { _frames.emit(Frame.Reconnected) }
                startPinging(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { dispatch(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                pingJob?.cancel()
                if (!closed.isCompleted) closed.complete(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                pingJob?.cancel()
                if (!closed.isCompleted) closed.complete(Unit)
            }
        })
        socket = ws
        closed.await()
        return true
    }

    private fun startPinging(ws: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (scope.isActive && running.get()) {
                // Ping no more than once per 10s; every 2 min is enough for presence.
                ws.send("""{"action":"ping"}""")
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun dispatch(text: String) {
        val obj = runCatching { json.decodeFromString<JsonObject>(text) }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        when {
            // Server notices and control frames carry a top-level "type".
            type == "pong" -> Unit
            type == "mention" -> obj["mention"]?.let {
                runCatching { json.decodeFromString<Mention>(it.toString()) }
                    .getOrNull()?.let { m -> _frames.emit(Frame.MentionFrame(m)) }
            }
            type == "session.notice" -> {
                val data = obj["data"] as? JsonObject
                val code = data?.get("code")?.jsonPrimitive?.contentOrNull ?: "notice"
                val body = data?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                _frames.emit(Frame.SessionNotice(code, body))
            }
            // Anything with a "seq" is a room event.
            obj["seq"] != null -> runCatching { json.decodeFromString<Event>(text) }
                .getOrNull()?.let { _frames.emit(Frame.EventFrame(it)) }
            else -> Unit // unknown frame type: skip gracefully
        }
    }

    companion object {
        /** 2 minutes: well under the 5-min presence lapse and over the 10s floor. */
        const val PING_INTERVAL_MS = 120_000L
    }
}
