package com.oneshotchat.android.crypto

/**
 * Builders for the three signed strings in the protocol (section 4). Lines are joined
 * with a single '\n' and there is NO trailing newline. The server origin must come
 * from the client's own configuration, never from the server.
 *
 * Verified against the spec's test vectors in SigningTest.
 */
object SignedStrings {

    fun login(server: String, identity: String, challenge: String): String = listOf(
        "oneshotchat/v1/login",
        "server:$server",
        "identity:$identity",
        "challenge:$challenge",
    ).joinToString("\n")

    /**
     * The message string (used for both send and edit). [text] is appended verbatim
     * after the last field line and may itself contain newlines.
     */
    fun message(
        server: String,
        room: String,
        author: String,
        nonce: String,
        version: Int,
        text: String,
    ): String {
        val header = listOf(
            "oneshotchat/v1/message",
            "server:$server",
            "room:$room",
            "author:$author",
            "nonce:$nonce",
            "version:$version",
        ).joinToString("\n")
        return "$header\n$text"
    }

    fun retire(server: String, identity: String): String = listOf(
        "oneshotchat/v1/retire",
        "server:$server",
        "identity:$identity",
    ).joinToString("\n")

    /**
     * Rebuild a message string and verify its signature against the author's
     * fingerprint, exactly as a careful client should rather than trusting the server.
     */
    fun verifyMessage(
        server: String,
        room: String,
        author: String,
        nonce: String,
        version: Int,
        text: String,
        signatureBase32: String,
    ): Boolean {
        return try {
            val publicKey = Base32.decode(author)
            val signature = Base32.decode(signatureBase32)
            val str = message(server, room, author, nonce, version, text)
            Ed25519.verify(publicKey, str.toByteArray(Charsets.UTF_8), signature)
        } catch (_: Exception) {
            false
        }
    }
}
