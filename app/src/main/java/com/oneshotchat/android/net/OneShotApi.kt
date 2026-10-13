package com.oneshotchat.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The full OneShotChat v1 HTTP contract. Writes go over HTTP (this class); live events
 * arrive over a WebSocket (see [EventSocket]). All calls are suspend functions.
 *
 * [baseUrl] comes from the client's own configuration and is also the signing origin.
 */
class OneShotApi(
    val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    /** Bearer token for the current session; set after login. */
    @Volatile var token: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ---- Discovery -------------------------------------------------------------

    suspend fun discover(): Discovery = get("/v1", auth = false)

    suspend fun clients(): List<ClientEntry> {
        // GET /v1/clients returns { "clients": [...] }; tolerate either shape.
        val element = getRaw("/v1/clients", auth = false)
        return runCatching {
            json.decodeFromString<Map<String, List<ClientEntry>>>(element)["clients"].orEmpty()
        }.getOrElse {
            runCatching { json.decodeFromString<List<ClientEntry>>(element) }.getOrDefault(emptyList())
        }
    }

    // ---- Auth ------------------------------------------------------------------

    suspend fun challenge(identity: String): ChallengeResponse =
        post("/v1/auth/challenge", ChallengeRequest(identity), auth = false)

    suspend fun session(req: SessionRequest): SessionResponse =
        post("/v1/auth/session", req, auth = false)

    suspend fun me(): IdentityInfo {
        // GET /v1/me returns identity plus session info; we only need the identity.
        val raw = getRaw("/v1/me", auth = true)
        return runCatching {
            json.decodeFromString<Map<String, IdentityInfo>>(raw)["identity"]!!
        }.getOrElse { json.decodeFromString(IdentityInfo.serializer(), raw) }
    }

    suspend fun setName(name: String) {
        patchUnit("/v1/me", PatchNameRequest(name))
    }

    suspend fun logout() {
        deleteUnit("/v1/auth/session")
    }

    suspend fun heartbeat(): HeartbeatResponse = post("/v1/session/heartbeat", Unit, auth = true)

    // ---- Rooms -----------------------------------------------------------------

    suspend fun browseRooms(cursor: String? = null): RoomList {
        val path = if (cursor != null) "/v1/rooms?cursor=${enc(cursor)}" else "/v1/rooms"
        return get(path, auth = token != null)
    }

    suspend fun roomByName(name: String): Room = get("/v1/rooms/by-name/${enc(name)}", auth = token != null)

    suspend fun room(roomId: String): Room = get("/v1/rooms/${enc(roomId)}", auth = token != null)

    suspend fun join(req: JoinRequest): JoinResponse = post("/v1/rooms/join", req, auth = true)

    suspend fun leave(roomId: String) {
        postUnit("/v1/rooms/${enc(roomId)}/leave", Unit)
    }

    // ---- Messages --------------------------------------------------------------

    suspend fun messages(roomId: String, before: Long? = null, after: Long? = null, limit: Int = 50): MessagePage {
        val q = buildString {
            append("?limit=$limit")
            if (before != null) append("&before=$before")
            if (after != null) append("&after=$after")
        }
        return get("/v1/rooms/${enc(roomId)}/messages$q", auth = true)
    }

    suspend fun send(roomId: String, req: SendMessageRequest): Message =
        post("/v1/rooms/${enc(roomId)}/messages", req, auth = true)

    suspend fun edit(roomId: String, id: Long, req: EditMessageRequest): Message =
        patch("/v1/rooms/${enc(roomId)}/messages/$id", req, auth = true)

    suspend fun addReaction(roomId: String, id: Long, reaction: String) {
        putUnit("/v1/rooms/${enc(roomId)}/messages/$id/reactions/${enc(reaction)}")
    }

    suspend fun removeReaction(roomId: String, id: Long, reaction: String) {
        deleteUnit("/v1/rooms/${enc(roomId)}/messages/$id/reactions/${enc(reaction)}")
    }

    // ---- Events ----------------------------------------------------------------

    suspend fun events(roomId: String, after: Long, limit: Int = 100): EventPage =
        get("/v1/rooms/${enc(roomId)}/events?after=$after&limit=$limit", auth = true)

    // ---- Mentions --------------------------------------------------------------

    suspend fun mentions(after: String? = null, limit: Int = 50): MentionPage {
        val q = buildString {
            append("?limit=$limit")
            if (after != null) append("&after=${enc(after)}")
        }
        return get("/v1/me/mentions$q", auth = true)
    }

    // ---- WebSocket ticket ------------------------------------------------------

    suspend fun wsTicket(): WsTicket = post("/v1/ws-ticket", Unit, auth = true)

    // ---- Identity (public, signature-authenticated) ----------------------------

    suspend fun retire(fingerprint: String, signature: String) {
        postUnitRaw("/v1/identities/${enc(fingerprint)}/retire", """{"signature":"$signature"}""", auth = false)
    }

    // ---- HTTP plumbing ---------------------------------------------------------

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    private inline fun <reified T> encode(value: T): String =
        if (value is Unit) "{}" else json.encodeToString(value)

    private suspend inline fun <reified T> get(path: String, auth: Boolean): T = decode(getRaw(path, auth))

    private suspend fun getRaw(path: String, auth: Boolean): String =
        execute(request(path, auth).get().build())

    private suspend inline fun <reified B, reified T> post(path: String, body: B, auth: Boolean): T =
        decode(execute(request(path, auth).post(encode(body).toRequestBody(jsonMedia)).build()))

    private suspend inline fun <reified B> postUnit(path: String, body: B) {
        execute(request(path, true).post(encode(body).toRequestBody(jsonMedia)).build())
    }

    private suspend fun postUnitRaw(path: String, rawBody: String, auth: Boolean) {
        execute(request(path, auth).post(rawBody.toRequestBody(jsonMedia)).build())
    }

    private suspend inline fun <reified B, reified T> patch(path: String, body: B, auth: Boolean): T =
        decode(execute(request(path, auth).patch(encode(body).toRequestBody(jsonMedia)).build()))

    private suspend inline fun <reified B> patchUnit(path: String, body: B) {
        execute(request(path, true).patch(encode(body).toRequestBody(jsonMedia)).build())
    }

    private suspend fun putUnit(path: String) {
        execute(request(path, true).put("".toRequestBody(jsonMedia)).build())
    }

    private suspend fun deleteUnit(path: String) {
        execute(request(path, true).delete().build())
    }

    private fun request(path: String, auth: Boolean): Request.Builder {
        val url = (baseUrl.trimEnd('/') + path).toHttpUrl()
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (auth) token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    /** Execute a call off the main thread, parsing errors and honouring Retry-After. */
    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = client.newCall(request).await()
        response.use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return@withContext bodyStr
            throw toApiException(resp, bodyStr)
        }
    }

    private fun toApiException(resp: Response, body: String): ApiException {
        val parsed = runCatching { json.decodeFromString<ApiError>(body).error }.getOrNull()
        val retryAfter = resp.header("Retry-After")?.toLongOrNull()
        return ApiException(
            status = resp.code,
            code = parsed?.code ?: httpCodeName(resp.code),
            message = parsed?.message?.ifBlank { resp.message } ?: resp.message,
            retryAfterSeconds = retryAfter,
        )
    }

    private fun httpCodeName(code: Int) = when (code) {
        401 -> "unauthorized"
        403 -> "forbidden"
        404 -> "not_found"
        429 -> "rate_limited"
        500 -> "internal"
        503 -> "unavailable"
        else -> "http_$code"
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.oneshotchat.com"
        const val DEV_BASE_URL = "https://api.dev.oneshotchat.com"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** Suspend wrapper over OkHttp's async call. */
private suspend fun Call.await(): Response = suspendCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
    cont.context[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) cancel() }
}
