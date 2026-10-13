package com.oneshotchat.android.net

/** An error returned by the server, branchable on [code] (never on [message]). */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    /** For 429 responses, the Retry-After value in seconds, if present. */
    val retryAfterSeconds: Long? = null,
) : Exception(message) {

    val isAuthExpired: Boolean get() = code == "session_expired" || code == "unauthorized"
    val isRateLimited: Boolean get() = status == 429 || code == "rate_limited"
}
