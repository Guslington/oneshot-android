package com.oneshotchat.android.crypto

import java.security.MessageDigest

/**
 * Side rooms (protocol section 6): a room for exactly two people. The name is derived
 * from both fingerprints and the server origin, so each person computes the same name
 * without anyone sending it: `side-` + the first 16 bytes of the SHA-256 of
 *
 *     oneshotchat/v1/side-room
 *     server:<origin>
 *     members:<lower fingerprint>,<higher fingerprint>
 *
 * in lowercase unpadded base32 (31 characters in all). The origin comes from our own
 * configuration, never from the server.
 */
object SideRoom {
    fun name(server: String, a: String, b: String): String {
        val (lo, hi) = if (a <= b) a to b else b to a
        val text = "oneshotchat/v1/side-room\nserver:$server\nmembers:$lo,$hi"
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return "side-" + Base32.encode(digest.copyOf(16))
    }
}
