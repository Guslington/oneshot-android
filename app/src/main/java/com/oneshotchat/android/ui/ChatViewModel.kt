package com.oneshotchat.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oneshotchat.android.crypto.Identity
import com.oneshotchat.android.crypto.Mentions
import com.oneshotchat.android.crypto.Tripcode
import com.oneshotchat.android.data.ChatRepository
import com.oneshotchat.android.data.Storage
import com.oneshotchat.android.net.ApiException
import com.oneshotchat.android.net.EventSocket
import com.oneshotchat.android.net.JoinRequest
import com.oneshotchat.android.net.Message
import com.oneshotchat.android.net.Occupant
import com.oneshotchat.android.net.Room
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Top-level app phase. */
sealed interface AppPhase {
    data object Loading : AppPhase
    data object NeedsIdentity : AppPhase
    data class Ready(val fingerprint: String, val name: String?) : AppPhase
    data class Error(val message: String) : AppPhase
}

/** The fully-resolved state of the room currently open. */
data class RoomView(
    val room: Room,
    val role: String?,
    val messages: List<Message>,
    val occupants: List<Occupant>,
    val labels: Map<String, String>,
    val admins: Set<String>,
    val notices: List<String>,
)

/** A candidate for the mention autocomplete. */
data class MentionCandidate(val fingerprint: String, val name: String, val label: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val storage = Storage(app)
    private val json = Json { ignoreUnknownKeys = true }

    private var repo: ChatRepository? = null

    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Loading)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _roomsLoading = MutableStateFlow(false)
    val roomsLoading: StateFlow<Boolean> = _roomsLoading.asStateFlow()

    private val _activeRoom = MutableStateFlow<RoomView?>(null)
    val activeRoom: StateFlow<RoomView?> = _activeRoom.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    val serverOrigin: String get() = repo?.serverOrigin ?: storage.serverBaseUrl
    val myFingerprint: String? get() = repo?.identity?.fingerprint

    // Active-room working state, guarded by [roomLock].
    private val roomLock = Mutex()
    private var activeRoomId: String? = null
    private var processedSeq: Long = 0
    private val msgs = LinkedHashMap<Long, Message>()
    private val occ = LinkedHashMap<String, Occupant>()
    private val notices = mutableListOf<String>()
    private var admins: Set<String> = emptySet()
    private var currentRoom: Room? = null
    private var currentRole: String? = null
    private var pollJob: Job? = null

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val identity = storage.loadIdentity()
            if (identity == null) {
                _phase.value = AppPhase.NeedsIdentity
                return@launch
            }
            startSession(identity)
        }
    }

    private suspend fun startSession(identity: Identity) {
        _phase.value = AppPhase.Loading
        val repository = ChatRepository(identity, storage, viewModelScope)
        repo = repository
        try {
            val info = repository.login()
            admins = repository.discovery?.admins?.toSet() ?: emptySet()
            repository.startSocket()
            collectSocket(repository)
            _phase.value = AppPhase.Ready(info.id, info.name ?: storage.displayName)
            refreshRooms()
        } catch (e: ApiException) {
            _phase.value = AppPhase.Error("Login failed: ${e.code} — ${e.message}")
        } catch (e: Exception) {
            _phase.value = AppPhase.Error("Login failed: ${e.message}")
        }
    }

    private fun collectSocket(repository: ChatRepository) {
        viewModelScope.launch {
            repository.socket.frames.collect { frame ->
                when (frame) {
                    is EventSocket.Frame.EventFrame -> {
                        if (frame.event.room == activeRoomId) applyEvent(frame.event)
                    }
                    is EventSocket.Frame.MentionFrame -> {
                        val who = frame.mention.message.author.name ?: "someone"
                        val room = frame.mention.room?.name?.let { "#$it" } ?: "a room"
                        toast("Mentioned by $who in $room")
                    }
                    is EventSocket.Frame.SessionNotice -> {
                        if (frame.code == "session_expired") reLogin()
                        else toast("Server: ${frame.text}")
                    }
                    EventSocket.Frame.Reconnected -> activeRoomId?.let { catchUp(it) }
                    EventSocket.Frame.Unavailable -> { /* polling covers it */ }
                }
            }
        }
    }

    // ---- Identity management ---------------------------------------------------

    fun createIdentity(name: String?) {
        viewModelScope.launch {
            val identity = Identity.generate(name?.ifBlank { null })
            storage.saveIdentity(identity)
            storage.displayName = name?.ifBlank { null }
            startSession(identity)
        }
    }

    fun importIdentity(fileContents: String) {
        viewModelScope.launch {
            try {
                val identity = Identity.fromFile(fileContents)
                storage.saveIdentity(identity)
                identity.nameHint?.let { storage.displayName = it }
                startSession(identity)
            } catch (e: Exception) {
                toast("Import failed: ${e.message}")
            }
        }
    }

    /** The current identity file contents, for export (or null before login). */
    fun exportIdentity(): String? = repo?.identity?.toFile()

    fun logout(forget: Boolean) {
        viewModelScope.launch {
            repo?.logout()
            if (forget) storage.clearIdentity()
            closeRoomInternal()
            repo = null
            _phase.value = AppPhase.NeedsIdentity
        }
    }

    private suspend fun reLogin() {
        val identity = repo?.identity ?: return
        runCatching { repo?.login() }
    }

    // ---- Room browser ----------------------------------------------------------

    fun refreshRooms() {
        val repository = repo ?: return
        viewModelScope.launch {
            _roomsLoading.value = true
            try {
                _rooms.value = repository.api.browseRooms().rooms
            } catch (e: Exception) {
                toast("Couldn't load rooms: ${(e as? ApiException)?.message ?: e.message}")
            } finally {
                _roomsLoading.value = false
            }
        }
    }

    // ---- Open / join a room ----------------------------------------------------

    fun joinByName(name: String) {
        val repository = repo ?: return
        viewModelScope.launch {
            try {
                val expect = storage.rememberedRoom(name)
                val resp = try {
                    repository.api.join(JoinRequest(name = name, expect = expect))
                } catch (e: ApiException) {
                    if (e.code == "room_changed") {
                        toast("#$name now points to a different room than before.")
                        repository.api.join(JoinRequest(name = name))
                    } else throw e
                }
                storage.rememberRoom(name, resp.room.id)
                openRoom(resp.room, resp.role)
            } catch (e: ApiException) {
                toast("Join failed: ${e.code} — ${e.message}")
            } catch (e: Exception) {
                toast("Join failed: ${e.message}")
            }
        }
    }

    fun openRoomFromBrowser(room: Room) {
        joinByName(room.name ?: return)
    }

    private suspend fun openRoom(room: Room, role: String?) {
        val repository = repo ?: return
        roomLock.withLock {
            activeRoomId = room.id
            currentRoom = room
            currentRole = role
            processedSeq = room.latestSeq ?: 0
            msgs.clear(); occ.clear(); notices.clear()
            room.occupants?.forEach { occ[it.identity] = it }
        }
        // Load recent history, then follow events after latest_seq.
        try {
            val page = repository.api.messages(room.id, limit = 50)
            roomLock.withLock {
                page.messages.forEach { msgs[it.id] = it }
                publish()
            }
        } catch (_: Exception) { /* show empty room; events will fill in */ }
        startPolling(room.id)
    }

    /**
     * Poll events for the active room every few seconds. This is a complete sync on its
     * own (and keeps presence alive); the WebSocket just lowers latency. Dedup by seq.
     */
    private fun startPolling(roomId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive && activeRoomId == roomId) {
                catchUp(roomId)
                delay(3000)
            }
        }
    }

    private suspend fun catchUp(roomId: String) {
        val repository = repo ?: return
        try {
            var more = true
            while (more && activeRoomId == roomId) {
                val after = roomLock.withLock { processedSeq }
                val page = repository.api.events(roomId, after = after, limit = 100)
                if (page.truncated) {
                    // We were away longer than retention: reload from scratch.
                    val msgPage = repository.api.messages(roomId, limit = 50)
                    roomLock.withLock {
                        msgs.clear()
                        msgPage.messages.forEach { msgs[it.id] = it }
                        processedSeq = page.latestSeq ?: processedSeq
                        publish()
                    }
                    return
                }
                page.events.forEach { applyEvent(it) }
                more = page.hasMore
            }
        } catch (e: ApiException) {
            if (e.code == "not_in_room") rejoinActive()
        } catch (_: Exception) { /* transient; next poll retries */ }
    }

    private fun rejoinActive() {
        val name = currentRoom?.name ?: return
        viewModelScope.launch { runCatching { repo?.api?.join(JoinRequest(name = name)) } }
    }

    fun closeRoom() {
        viewModelScope.launch { closeRoomInternal() }
    }

    private suspend fun closeRoomInternal() {
        val roomId = activeRoomId
        pollJob?.cancel()
        activeRoomId = null
        _activeRoom.value = null
        if (roomId != null) runCatching { repo?.api?.leave(roomId) }
        roomLock.withLock { msgs.clear(); occ.clear(); notices.clear() }
    }

    // ---- Event application -----------------------------------------------------

    private suspend fun applyEvent(event: com.oneshotchat.android.net.Event) {
        roomLock.withLock {
            if (event.seq <= processedSeq) return@withLock
            val data = event.data as? JsonObject
            when (event.type) {
                "message.created", "message.edited" -> decodeMessage(data)?.let { msgs[it.id] = it }
                "reaction.added" -> mutateReaction(data, add = true)
                "reaction.removed" -> mutateReaction(data, add = false)
                "member.joined" -> decodeOccupant(data)?.let { occ[it.identity] = it }
                "member.left" -> data?.str("identity")?.let { occ.remove(it) }
                "member.renamed" -> data?.str("identity")?.let { id ->
                    occ[id]?.let { occ[id] = it.copy(name = data.str("new_name")) }
                }
                "member.role_changed" -> data?.str("identity")?.let { id ->
                    val role = data.str("role")
                    occ[id] = (occ[id] ?: Occupant(id)).copy(role = role)
                }
                "room.updated" -> decodeRoom(data)?.let { currentRoom = it }
                "notice" -> data?.str("text")?.let { notices.add(it) }
                else -> { /* unknown event type: skip gracefully */ }
            }
            processedSeq = maxOf(processedSeq, event.seq)
            publish()
        }
    }

    private fun mutateReaction(data: JsonObject?, add: Boolean) {
        data ?: return
        val id = data["message_id"]?.jsonPrimitive?.long ?: return
        val reaction = data.str("reaction") ?: return
        val who = data.str("identity") ?: return
        val msg = msgs[id] ?: return
        val list = msg.reactions.toMutableList()
        val idx = list.indexOfFirst { it.reaction == reaction }
        if (add) {
            if (idx >= 0) {
                val r = list[idx]
                if (who !in r.identities) list[idx] = r.copy(count = r.count + 1, identities = r.identities + who)
            } else {
                list.add(com.oneshotchat.android.net.Reaction(reaction, 1, listOf(who)))
            }
        } else if (idx >= 0) {
            val r = list[idx]
            val ids = r.identities - who
            if (ids.isEmpty()) list.removeAt(idx)
            else list[idx] = r.copy(count = (r.count - 1).coerceAtLeast(0), identities = ids)
        }
        msgs[id] = msg.copy(reactions = list)
    }

    private fun publish() {
        val room = currentRoom ?: return
        val ordered = msgs.values.sortedBy { it.id }
        // "In view" = occupants + authors of messages on screen.
        val people = buildList {
            occ.values.forEach { add(Tripcode.Person(it.name ?: "anon", it.identity)) }
            ordered.forEach { add(Tripcode.Person(it.author.name ?: "anon", it.author.identity)) }
        }
        _activeRoom.value = RoomView(
            room = room,
            role = currentRole,
            messages = ordered,
            occupants = occ.values.toList(),
            labels = Tripcode.labels(people),
            admins = admins,
            notices = notices.toList(),
        )
    }

    // ---- Composing -------------------------------------------------------------

    fun send(text: String, sign: Boolean) {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                val msg = repository.sendMessage(roomId, text, sign)
                roomLock.withLock { msgs[msg.id] = msg; processedSeq = maxOf(processedSeq, msg.id); publish() }
            } catch (e: ApiException) {
                toast(sendError(e))
            } catch (e: Exception) {
                toast("Send failed: ${e.message}")
            }
        }
    }

    fun edit(message: Message, newText: String, sign: Boolean) {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        viewModelScope.launch {
            try {
                val updated = repository.editMessage(roomId, message, newText, sign)
                roomLock.withLock { msgs[updated.id] = updated; publish() }
            } catch (e: ApiException) {
                if (e.code == "version_conflict") toast("That message changed on another device; reopen it.")
                else toast("Edit failed: ${e.message}")
            }
        }
    }

    fun react(message: Message, reaction: String) {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        val mine = myFingerprint
        val already = message.reactions.any { it.reaction == reaction && mine in it.identities }
        viewModelScope.launch {
            try {
                if (already) repository.api.removeReaction(roomId, message.id, reaction)
                else repository.api.addReaction(roomId, message.id, reaction)
            } catch (e: ApiException) {
                toast("Reaction failed: ${e.message}")
            }
        }
    }

    /** Whether we can currently verify this message's signature ourselves. */
    fun verified(message: Message): Boolean {
        val roomId = activeRoomId ?: return false
        return repo?.verify(message, roomId) ?: false
    }

    // ---- Mentions --------------------------------------------------------------

    fun mentionCandidates(query: String): List<MentionCandidate> {
        val labels = _activeRoom.value?.labels ?: emptyMap()
        val q = query.lowercase()
        val fromRoom = occ.values.map { MentionCandidate(it.identity, it.name ?: "anon", labels[it.identity] ?: (it.name ?: "anon")) }
        val fromContacts = storage.contacts().map { MentionCandidate(it.fingerprint, it.petname, it.petname) }
        return (fromRoom + fromContacts)
            .distinctBy { it.fingerprint }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.fingerprint.startsWith(q) }
            .take(8)
    }

    fun mentionToken(fingerprint: String) = Mentions.token(fingerprint)

    // ---- Settings / contacts ---------------------------------------------------

    fun setDisplayName(name: String) {
        viewModelScope.launch {
            runCatching { repo?.setDisplayName(name) }
            (phase.value as? AppPhase.Ready)?.let { _phase.value = it.copy(name = name) }
        }
    }

    fun setServer(baseUrl: String) {
        storage.serverBaseUrl = baseUrl.trim().trimEnd('/')
        toast("Server set. Log out and back in to apply.")
    }

    fun saveContact(fingerprint: String, petname: String) = storage.saveContact(fingerprint, petname)
    fun contacts() = storage.contacts()

    // ---- Helpers ---------------------------------------------------------------

    fun toastShown() { _toast.value = null }
    private fun toast(msg: String) { _toast.value = msg }

    private fun sendError(e: ApiException): String = when (e.code) {
        "muted" -> "You're muted in this room."
        "moderated" -> "This room is moderated; you need voice to speak."
        "not_in_room" -> "You're not in this room anymore."
        "rate_limited" -> "Slow down — try again in ${e.retryAfterSeconds ?: 1}s."
        else -> "Send failed: ${e.message}"
    }

    private fun decodeMessage(data: JsonObject?): Message? =
        data?.get("message")?.let { runCatching { json.decodeFromString<Message>(it.toString()) }.getOrNull() }

    private fun decodeRoom(data: JsonObject?): Room? =
        data?.get("room")?.let { runCatching { json.decodeFromString<Room>(it.toString()) }.getOrNull() }

    private fun decodeOccupant(data: JsonObject?): Occupant? =
        data?.let { runCatching { json.decodeFromString<Occupant>(it.toString()) }.getOrNull() }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
