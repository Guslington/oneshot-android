package com.oneshotchat.android.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces every test vector from the protocol's Appendix A. Ed25519 signatures are
 * deterministic, so these must match exactly — if they don't, the client can't log in,
 * sign, or retire.
 */
class CryptoTest {

    private val server = "https://api.oneshotchat.com"
    private val seedB32 = "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dypq"
    private val fingerprint = "aoqqpp7tzyil4hlq3umoos6atft6jvrqtosq2xy53sdgiesvgg4a"
    private val key = Identity.fromSeedBase32(seedB32)

    @Test
    fun base32_roundtrip_and_known_bytes() {
        val seed = ByteArray(32) { it.toByte() } // 0x00..0x1f
        assertEquals(seedB32, Base32.encode(seed))
        assertTrue(seed.contentEquals(Base32.decode(seedB32)))

        val challenge = ByteArray(32) { (0xa0 + it).toByte() } // 0xa0..0xbf
        assertEquals("ucq2fi5euwtkpkfjvkv2zlnov6yldmvtws23nn5yxg5lxpf5x27q", Base32.encode(challenge))
    }

    @Test
    fun fingerprint_derives_from_seed() {
        assertEquals(fingerprint, key.fingerprint)
    }

    @Test
    fun login_signature_matches_vector() {
        val challenge = "ucq2fi5euwtkpkfjvkv2zlnov6yldmvtws23nn5yxg5lxpf5x27q"
        val str = SignedStrings.login(server, fingerprint, challenge)
        assertEquals(
            "z5zei344qrqbaniwwrydxop4am5obm7pha6bu5yk4572zcp3djplidm7kx53xablztzb4pbqkw2b7dl6e3cwegrjtudlwo5ggr3wacy",
            key.sign(str),
        )
    }

    @Test
    fun message_signature_matches_vector() {
        val room = "ibaueq2eivdeoscjjjfuytkoj4"
        val text = "hello <@$fingerprint> \uD83D\uDC4B\nsecond line"
        val str = SignedStrings.message(server, room, fingerprint, "01hzx7k2m9", 1, text)
        assertEquals(
            "eamc7n7p5zuvqvdy6vuazz4d4lpekvahyksxepseyhs6mgabla47weo543dnioirlotht72pjruybm4aprnjxbigyajzib4ket5iqcy",
            key.sign(str),
        )
        // And we can verify it ourselves.
        assertTrue(
            SignedStrings.verifyMessage(
                server, room, fingerprint, "01hzx7k2m9", 1, text,
                "eamc7n7p5zuvqvdy6vuazz4d4lpekvahyksxepseyhs6mgabla47weo543dnioirlotht72pjruybm4aprnjxbigyajzib4ket5iqcy",
            ),
        )
    }

    @Test
    fun edit_signature_matches_vector() {
        val room = "ibaueq2eivdeoscjjjfuytkoj4"
        val str = SignedStrings.message(server, room, fingerprint, "01hzx7k2m9", 2, "hello again")
        assertEquals(
            "zuf66ul3m6lxwwqq6volbgle3wpflxzb7qkywqebtabb3y2d5mb7hbsrooq5lpp26qbcnxwfb3yfhufb4ocz332pkjmki7vs6sxrwcy",
            key.sign(str),
        )
    }

    @Test
    fun retire_signature_matches_vector() {
        val str = SignedStrings.retire(server, fingerprint)
        assertEquals(
            "bxg36lyugvtkbbugavqgus5quohpfy7pwlimf7uf7oz4rwvhv4iz46stlrargyvr6bliw3g2unio76bbl4cjnq6rmxnx423ty5lxadi",
            key.sign(str),
        )
    }

    @Test
    fun tampered_signature_fails_verification() {
        val room = "ibaueq2eivdeoscjjjfuytkoj4"
        assertFalse(
            SignedStrings.verifyMessage(
                server, room, fingerprint, "01hzx7k2m9", 1, "a different text",
                "eamc7n7p5zuvqvdy6vuazz4d4lpekvahyksxepseyhs6mgabla47weo543dnioirlotht72pjruybm4aprnjxbigyajzib4ket5iqcy",
            ),
        )
    }

    @Test
    fun identity_file_roundtrips_and_validates() {
        val file = key.toFile()
        val reloaded = Identity.fromFile(file)
        assertEquals(key.fingerprint, reloaded.fingerprint)
    }

    @Test
    fun tripcodes_disambiguate_shared_names() {
        val a = Tripcode.Person("sam", "a7f0000000000000000000000000000000000000000000000000")
        val b = Tripcode.Person("sam", "a7c0000000000000000000000000000000000000000000000000")
        val c = Tripcode.Person("sam", "b000000000000000000000000000000000000000000000000000")
        val labels = Tripcode.labels(listOf(a, b, c))
        assertEquals("sam#a7f", labels[a.fingerprint])
        assertEquals("sam#a7c", labels[b.fingerprint])
        assertEquals("sam#b", labels[c.fingerprint])

        // Unique name needs no tripcode.
        val solo = Tripcode.Person("alex", "c000000000000000000000000000000000000000000000000000")
        assertEquals("alex", Tripcode.label(solo, listOf(solo, a)))
    }

    @Test
    fun origin_strips_default_port_and_path() {
        assertEquals(
            "https://api.oneshotchat.com",
            com.oneshotchat.android.data.ChatRepository.originOf("https://api.oneshotchat.com/"),
        )
    }
}
