package com.oneshotchat.android.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire models for the OneShotChat v1 protocol. Unknown fields are ignored by the Json
 * config (the server adds fields without bumping the version), and unknown enum-ish
 * values are kept as plain strings so the client never crashes on something new.
 */

@Serializable
data class Discovery(
    val name: String? = null,
    val protocol: String? = null,
    @SerialName("websocket_url") val websocketUrl: String? = null,
    val motd: String? = null,
    val admins: List<String> = emptyList(),
    @SerialName("conformance_bot") val conformanceBot: String? = null,
    val limits: JsonObject? = null,
)

@Serializable
data class ApiError(val error: ApiErrorBody? = null)

@Serializable
data class ApiErrorBody(val code: String = "unknown", val message: String = "")

@Serializable
data class ChallengeRequest(val identity: String)

@Serializable
data class ChallengeResponse(
    val challenge: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class ClientMeta(
    val name: String,
    val version: String? = null,
    val url: String? = null,
)

@Serializable
data class SessionRequest(
    val identity: String,
    val challenge: String,
    val signature: String,
    val name: String? = null,
    val incognito: Boolean = false,
    val client: ClientMeta,
)

@Serializable
data class IdentityInfo(
    val id: String,
    val name: String? = null,
    val status: String = "active",
    val incognito: Boolean = false,
    val admin: Boolean = false,
)

@Serializable
data class SessionResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    val identity: IdentityInfo,
)

@Serializable
data class HeartbeatResponse(@SerialName("expires_at") val expiresAt: String? = null)

@Serializable
data class Occupant(
    val identity: String,
    val name: String? = null,
    val role: String? = null,
    val admin: Boolean = false,
)

@Serializable
data class Activity(
    @SerialName("messages_last_10m") val messagesLast10m: Int? = null,
    @SerialName("last_message_at") val lastMessageAt: String? = null,
)

@Serializable
data class Room(
    val id: String,
    val name: String? = null,
    val topic: String? = null,
    val visibility: String? = null,
    val access: String? = null,
    val speaking: String? = null,
    @SerialName("retention_seconds") val retentionSeconds: Int? = null,
    val permanent: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("latest_seq") val latestSeq: Long? = null,
    val activity: Activity? = null,
    @SerialName("occupant_count") val occupantCount: Int? = null,
    val occupants: List<Occupant>? = null,
)

@Serializable
data class RoomList(val rooms: List<Room> = emptyList(), val next: String? = null)

@Serializable
data class CreateSettings(
    val visibility: String? = null,
    val access: String? = null,
    val speaking: String? = null,
    val topic: String? = null,
    @SerialName("retention_seconds") val retentionSeconds: Int? = null,
    val key: String? = null,
)

@Serializable
data class JoinRequest(
    val name: String,
    val expect: String? = null,
    val key: String? = null,
    val invite: String? = null,
    val create: CreateSettings? = null,
)

@Serializable
data class JoinResponse(
    val room: Room,
    val role: String? = null,
    val created: Boolean = false,
)

@Serializable
data class Author(val identity: String, val name: String? = null)

@Serializable
data class Reaction(
    val reaction: String,
    val count: Int = 0,
    val identities: List<String> = emptyList(),
)

@Serializable
data class Message(
    val id: Long,
    val room: String? = null,
    val author: Author,
    val client: ClientMeta? = null,
    val text: String,
    val mentions: List<String> = emptyList(),
    val nonce: String? = null,
    val signature: String? = null,
    val version: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    val reactions: List<Reaction> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class SendMessageRequest(
    val text: String,
    val nonce: String? = null,
    val signature: String? = null,
)

@Serializable
data class EditMessageRequest(
    val text: String,
    val version: Int,
    val signature: String? = null,
)

@Serializable
data class MessagePage(
    val messages: List<Message> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class Event(
    val seq: Long,
    val room: String? = null,
    val type: String,
    val at: String? = null,
    val data: JsonElement? = null,
)

@Serializable
data class EventPage(
    val events: List<Event> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("latest_seq") val latestSeq: Long? = null,
    val truncated: Boolean = false,
)

@Serializable
data class WsTicket(
    val ticket: String? = null,
    val url: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class Mention(
    val cursor: String? = null,
    val room: Room? = null,
    val message: Message,
)

@Serializable
data class MentionPage(
    val mentions: List<Mention> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class PatchNameRequest(val name: String)

@Serializable
data class ClientEntry(
    val name: String? = null,
    val version: String? = null,
    val url: String? = null,
)
