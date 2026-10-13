package com.oneshotchat.android.crypto

/**
 * Mention tokens are `<@fingerprint>` with the full 52-char base32 fingerprint.
 * Text that looks like `<@…>` but isn't a valid fingerprint is just text.
 */
object Mentions {
    private val FINGERPRINT = Regex("[a-z2-7]{52}")
    private val TOKEN = Regex("<@([a-z2-7]{52})>")

    fun isFingerprint(s: String): Boolean = FINGERPRINT.matches(s)

    /** A mention token to put in outgoing message text when the user picks someone. */
    fun token(fingerprint: String): String = "<@$fingerprint>"

    /** All valid fingerprints referenced by `<@…>` tokens in [text]. */
    fun extract(text: String): List<String> =
        TOKEN.findAll(text).map { it.groupValues[1] }.toList()

    /**
     * Replace each `<@fingerprint>` token with the result of [render]. Used to show
     * mentions as names/tripcodes. Unknown or malformed tokens are left verbatim.
     */
    fun render(text: String, render: (fingerprint: String) -> String): String =
        TOKEN.replace(text) { m -> render(m.groupValues[1]) }
}
