package com.oneshotchat.android.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oneshotchat.android.crypto.Identity
import com.oneshotchat.android.crypto.Mentions
import com.oneshotchat.android.crypto.SideRoom
import com.oneshotchat.android.crypto.Tripcode
import com.oneshotchat.android.data.ChatRepository
import com.oneshotchat.android.data.Storage
import com.oneshotchat.android.net.ApiException
import com.oneshotchat.android.net.CreateSettings
import com.oneshotchat.android.net.EventSocket
import com.oneshotchat.android.net.JoinRequest
import com.oneshotchat.android.net.Mention
import com.oneshotchat.android.net.Message
import com.oneshotchat.android.net.Occupant
import com.oneshotchat.android.net.RoleEntry
import com.oneshotchat.android.net.Room
import com.oneshotchat.android.notify.MentionService
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
    /** True for room operators and server admins. */
    val canOperate: Boolean,
    val me: String?,
)

/** Asks the user for a room key or invite code, then retries the join. */
data class CredentialPrompt(val name: String, val kind: String, val retry: Boolean)

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

    private val _prompt = MutableStateFlow<CredentialPrompt?>(null)
    val prompt: StateFlow<CredentialPrompt?> = _prompt.asStateFlow()

    private val _mentions = MutableStateFlow<List<Mention>>(emptyList())
    val mentions: StateFlow<List<Mention>> = _mentions.asStateFlow()

    private val _mentionsLoading = MutableStateFlow(false)
    val mentionsLoading: StateFlow<Boolean> = _mentionsLoading.asStateFlow()

    private val _unreadMentions = MutableStateFlow(0)
    val unreadMentions: StateFlow<Int> = _unreadMentions.asStateFlow()

    private val _backgroundNotifications = MutableStateFlow(storage.backgroundNotifications)
    val backgroundNotifications: StateFlow<Boolean> = _backgroundNotifications.asStateFlow()

    /** Set when a side-room notification is tapped: show the mentions feed, don't join. */
    private val _showMentionsRequest = MutableStateFlow(false)
    val showMentionsRequest: StateFlow<Boolean> = _showMentionsRequest.asStateFlow()
    fun requestMentions() { _showMentionsRequest.value = true }
    fun mentionsRequestHandled() { _showMentionsRequest.value = false }

    /** A room to open as soon as login finishes (e.g. from a notification tap). */
    private var pendingRoom: String? = null

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
            pollUnread()
            updateService()
            pendingRoom?.let { pendingRoom = null; joinByName(it) }
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
                        pollUnread()
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

    fun createIdentity(name: String?, incognito: Boolean) {
        viewModelScope.launch {
            val identity = Identity.generate(name?.ifBlank { null })
            storage.saveIdentity(identity)
            storage.incognito = incognito
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
            updateService()
            _unreadMentions.value = 0
            _mentions.value = emptyList()
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

    /**
     * Join (or create) a room. [key] / [invite] answer a [CredentialPrompt]; [create]
     * only applies if the name is free and this call creates the room.
     */
    fun joinByName(
        name: String,
        key: String? = null,
        invite: String? = null,
        create: CreateSettings? = null,
    ) {
        val repository = repo ?: return
        viewModelScope.launch {
            try {
                val expect = storage.rememberedRoom(name)
                val resp = try {
                    repository.api.join(JoinRequest(name, expect, key, invite, create))
                } catch (e: ApiException) {
                    if (e.code == "room_changed") {
                        toast("#$name now points to a different room than before.")
                        repository.api.join(JoinRequest(name = name, key = key, invite = invite, create = create))
                    } else throw e
                }
                _prompt.value = null
                if (!properSide(resp.room)) {
                    runCatching { repository.api.leave(resp.room.id) }
                    toast("This isn't a proper side room, so I left.")
                    return@launch
                }
                storage.rememberRoom(name, resp.room.id)
                openRoom(resp.room, resp.role)
            } catch (e: ApiException) {
                when (e.code) {
                    "key_required" -> _prompt.value = CredentialPrompt(name, "key", retry = key != null)
                    "invite_required" -> _prompt.value = CredentialPrompt(name, "invite", retry = invite != null)
                    "banned" -> toast("You're banned from #$name.")
                    else -> toast("Join failed: ${e.code} — ${e.message}")
                }
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
            when (e.code) {
                "not_in_room" -> rejoinActive()
                "room_not_found" -> {
                    // Closed (side rooms can be closed by either person) or expired.
                    toast("That room is gone.")
                    closeRoom()
                }
            }
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
                    if (id == myFingerprint) currentRole = role
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
            canOperate = currentRole == "operator" || (myFingerprint != null && myFingerprint in admins),
            me = myFingerprint,
        )
    }

    // ---- Credentials, deep links, background service ---------------------------

    fun dismissPrompt() { _prompt.value = null }

    /** Open a room by name once logged in (used when a notification is tapped). */
    fun openRoomByName(name: String) {
        if (_phase.value is AppPhase.Ready) joinByName(name) else pendingRoom = name
    }

    fun setBackgroundNotifications(enabled: Boolean) {
        storage.backgroundNotifications = enabled
        _backgroundNotifications.value = enabled
        updateService()
    }

    private fun updateService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, MentionService::class.java)
        runCatching {
            if (storage.backgroundNotifications && repo != null) ContextCompat.startForegroundService(ctx, intent)
            else ctx.stopService(intent)
        }
    }

    // ---- Side rooms ------------------------------------------------------------

    /**
     * A room that claims to be a side room must have exactly two members, include us, and
     * carry the name derived from those two fingerprints. Ordinary rooms pass.
     */
    private fun properSide(room: Room): Boolean {
        val side = room.side ?: return true
        val me = myFingerprint ?: return false
        val m = side.members
        if (m.size != 2 || me !in m) return false
        return room.name == SideRoom.name(serverOrigin, m[0], m[1])
    }

    /** If [mention] is a side room opened by a saved contact, return that contact. */
    fun sideRoomContact(mention: Mention): Storage.Contact? {
        val me = myFingerprint ?: return null
        val roomName = mention.room?.name ?: return null
        val author = mention.message.author.identity
        if (author == me) return null
        val contact = storage.contacts().firstOrNull { it.fingerprint == author } ?: return null
        return contact.takeIf { roomName == SideRoom.name(serverOrigin, me, author) }
    }

    /**
     * Open a side room with [other] by sending a first message. Only creates the room once
     * there is something to say, so no empty rooms nobody was told about.
     */
    fun startSideRoom(other: String, body: String, mention: Boolean) {
        val repository = repo ?: return
        val me = myFingerprint ?: return
        if (body.isBlank()) return
        viewModelScope.launch {
            try {
                val name = SideRoom.name(serverOrigin, me, other)
                val resp = repository.api.join(
                    JoinRequest(
                        name = name,
                        create = CreateSettings(sideWith = other, retentionSeconds = 604_800),
                    ),
                )
                val members = resp.room.side?.members?.toSet()
                if (members != setOf(me, other) || !properSide(resp.room)) {
                    runCatching { repository.api.leave(resp.room.id) }
                    toast("This isn't a proper side room, so I left.")
                    return@launch
                }
                storage.rememberRoom(name, resp.room.id)
                openRoom(resp.room, resp.role)
                val text = (if (mention) Mentions.token(other) + " " else "") + body.trim()
                val msg = repository.sendMessage(resp.room.id, text, sign = false)
                roomLock.withLock {
                    msgs[msg.id] = msg
                    processedSeq = maxOf(processedSeq, msg.id)
                    publish()
                }
            } catch (e: ApiException) {
                toast("Side room failed: ${e.code} — ${e.message}")
            } catch (e: Exception) {
                toast("Side room failed: ${e.message}")
            }
        }
    }

    /** Join a side room someone opened with us, after checking it really is one. */
    fun joinSideRoom(name: String, other: String) {
        val repository = repo ?: return
        val me = myFingerprint ?: return
        viewModelScope.launch {
            try {
                val room = repository.api.roomByName(name)
                val ok = room.side?.members?.toSet() == setOf(me, other) && properSide(room)
                if (!ok) {
                    toast("This isn't a proper side room, so I didn't join.")
                    return@launch
                }
                joinByName(name)
            } catch (e: ApiException) {
                if (e.code == "room_not_found") toast("That side room is gone.")
                else toast("Couldn't open it: ${e.code} — ${e.message}")
            } catch (e: Exception) {
                toast("Couldn't open it: ${e.message}")
            }
        }
    }

    /** Open an existing side room with [other] (its name is derived, so we can just look it up). */
    fun openSideRoom(other: String) {
        val me = myFingerprint ?: return
        joinSideRoom(SideRoom.name(serverOrigin, me, other), other)
    }

    /** Close the open side room for both people. */
    fun closeSideRoom() {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        viewModelScope.launch {
            try {
                repository.api.closeRoom(roomId)
                closeRoomInternal()
                toast("Side room closed.")
            } catch (e: ApiException) {
                toast("Couldn't close it: ${e.code} — ${e.message}")
            } catch (e: Exception) {
                toast("Couldn't close it: ${e.message}")
            }
        }
    }

    /** Which saved contacts already have a side room with us (looked up by derived name). */
    fun contactsWithSideRoom(onResult: (Set<String>) -> Unit) {
        val repository = repo ?: return
        val me = myFingerprint ?: return
        viewModelScope.launch {
            val found = mutableSetOf<String>()
            for (c in storage.contacts().take(30)) {
                try {
                    val room = repository.api.roomByName(SideRoom.name(serverOrigin, me, c.fingerprint))
                    if (room.side?.members?.toSet() == setOf(me, c.fingerprint)) found += c.fingerprint
                } catch (_: Exception) { /* none, or not reachable */ }
            }
            onResult(found)
        }
    }

    // ---- Mentions feed ---------------------------------------------------------

    private suspend fun fetchMentions(after: String?, maxPages: Int): List<Mention> {
        val repository = repo ?: return emptyList()
        val all = mutableListOf<Mention>()
        var cursor = after
        var pages = 0
        while (pages < maxPages) {
            val page = repository.api.mentions(after = cursor, limit = 100)
            if (page.mentions.isEmpty()) break
            all += page.mentions
            val next = page.nextCursor?.takeIf { it.isNotBlank() } ?: page.mentions.last().cursor
            if (next == null || next == cursor) break
            cursor = next
            pages++
        }
        return all
    }

    private fun seenCursor(): String? = storage.mentionsSeenCursor?.takeIf { it != START }

    /** Refresh the unread-mentions badge. The first run silently marks history as seen. */
    fun pollUnread() {
        if (repo == null) return
        viewModelScope.launch {
            try {
                if (storage.mentionsSeenCursor == null) {
                    val all = fetchMentions(null, 20)
                    storage.mentionsSeenCursor = all.lastOrNull()?.cursor ?: START
                    _unreadMentions.value = 0
                } else {
                    _unreadMentions.value = fetchMentions(seenCursor(), 3).size
                }
            } catch (_: Exception) { /* transient */ }
        }
    }

    /** Load the feed (newest first) and mark everything as seen. */
    fun openMentions() {
        viewModelScope.launch {
            _mentionsLoading.value = true
            try {
                val all = fetchMentions(null, 20)
                _mentions.value = all.asReversed().take(100)
                storage.mentionsSeenCursor = all.lastOrNull()?.cursor ?: START
                _unreadMentions.value = 0
            } catch (e: Exception) {
                toast("Couldn't load mentions: ${(e as? ApiException)?.message ?: e.message}")
            } finally {
                _mentionsLoading.value = false
            }
        }
    }

    // ---- Operator tools --------------------------------------------------------

    fun updateRoomSettings(settings: JsonObject) {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        viewModelScope.launch {
            try {
                val updated = repository.api.updateRoom(roomId, settings)
                roomLock.withLock {
                    currentRoom = updated.copy(occupants = updated.occupants ?: currentRoom?.occupants)
                    publish()
                }
            } catch (e: ApiException) {
                toast(operatorError(e))
            } catch (e: Exception) {
                toast("Update failed: ${e.message}")
            }
        }
    }

    fun loadRoles(onResult: (List<RoleEntry>) -> Unit) {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        viewModelScope.launch {
            runCatching { repository.api.roles(roomId) }.onSuccess(onResult)
        }
    }

    /** Set [role] for [identity], or clear it when [role] is null. */
    fun setRole(identity: String, role: String?, done: () -> Unit = {}) {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        viewModelScope.launch {
            try {
                if (role == null) repository.api.clearRole(roomId, identity)
                else repository.api.setRole(roomId, identity, role)
                done()
            } catch (e: ApiException) {
                toast(operatorError(e))
            } catch (e: Exception) {
                toast("Failed: ${e.message}")
            }
        }
    }

    fun kick(identity: String, reason: String?) {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        viewModelScope.launch {
            try {
                repository.api.kick(roomId, identity, reason)
            } catch (e: ApiException) {
                toast(operatorError(e))
            } catch (e: Exception) {
                toast("Failed: ${e.message}")
            }
        }
    }

    fun createInvite(uses: Int, expiresIn: Int, onResult: (String) -> Unit) {
        val repository = repo ?: return
        val roomId = activeRoomId ?: return
        viewModelScope.launch {
            try {
                onResult(repository.api.createInvite(roomId, uses, expiresIn).code)
            } catch (e: ApiException) {
                toast(operatorError(e))
            } catch (e: Exception) {
                toast("Failed: ${e.message}")
            }
        }
    }

    private fun operatorError(e: ApiException): String = when (e.code) {
        "not_operator" -> "You need to be an operator for that."
        "forbidden" -> "You can't do that to this person."
        else -> "${e.code}: ${e.message}"
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
    fun removeContact(fingerprint: String) = storage.removeContact(fingerprint)
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

    private companion object {
        /** Stored when the mentions feed was empty, so we can tell "seen nothing" from "first run". */
        const val START = "start"
    }
}
