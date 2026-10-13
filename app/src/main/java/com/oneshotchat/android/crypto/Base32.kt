package com.oneshotchat.android.crypto

/**
 * RFC 4648 base32, lowercase, no padding — the OneShotChat wire encoding for all
 * binary values (keys, signatures, challenges, seeds).
 *
 * The protocol says: "Most standard libraries encode base32 in uppercase with `=`
 * padding. Lowercase it and strip the `=`. When decoding, uppercase it and re-pad."
 * We implement it directly so there is no dependency on a particular library's quirks.
 */
object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val DECODE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c ->
            table[c.code] = i
            table[c.uppercaseChar().code] = i
        }
    }

    /** Encode bytes to lowercase base32 with no padding. */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer ushr bits) and 0x1f])
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        }
        return out.toString()
    }

    /** Decode lowercase/uppercase base32 (padding optional) to bytes. */
    fun decode(text: String): ByteArray {
        val clean = text.trim().trimEnd('=')
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) DECODE[c.code] else -1
            require(v >= 0) { "Invalid base32 character: '$c'" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}
