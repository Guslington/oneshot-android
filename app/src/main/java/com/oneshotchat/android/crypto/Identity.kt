package com.oneshotchat.android.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A local identity: an Ed25519 keypair plus an optional display-name hint.
 * The [fingerprint] is the base32 of the public key (52 chars) and is the permanent ID.
 */
class Identity private constructor(
    val keyPair: Ed25519.KeyPair,
    val nameHint: String?,
) {
    val fingerprint: String = Base32.encode(keyPair.publicKey)
    val seedBase32: String get() = Base32.encode(keyPair.seed)

    fun sign(message: String): String =
        Base32.encode(keyPair.sign(message.toByteArray(Charsets.UTF_8)))

    /** Serialise to the portable `.identity` file format. */
    fun toFile(): String = Json { prettyPrint = true }.encodeToString(
        IdentityFile.serializer(),
        IdentityFile(
            seed = seedBase32,
            identity = fingerprint,
            name = nameHint,
        ),
    )

    fun withName(name: String?): Identity = Identity(keyPair, name)

    companion object {
        fun generate(name: String? = null): Identity = Identity(Ed25519.generate(), name)

        fun fromSeedBase32(seed: String, name: String? = null): Identity =
            Identity(Ed25519.fromSeed(Base32.decode(seed)), name)

        /**
         * Parse a `.identity` file, deriving the public key from the seed and checking
         * it matches the declared fingerprint (as the protocol requires on import).
         */
        fun fromFile(text: String): Identity {
            val parsed = Json { ignoreUnknownKeys = true }
                .decodeFromString(IdentityFile.serializer(), text)
            require(parsed.format == "oneshotchat-identity") {
                "Not a OneShotChat identity file"
            }
            val seed = Base32.decode(parsed.seed)
            val derived = Base32.encode(Ed25519.publicKeyFromSeed(seed))
            require(derived == parsed.identity) {
                "Identity file is corrupt: seed does not match fingerprint"
            }
            return Identity(Ed25519.fromSeed(seed), parsed.name)
        }
    }
}

@Serializable
data class IdentityFile(
    val format: String = "oneshotchat-identity",
    val version: Int = 1,
    val seed: String,
    val identity: String,
    @SerialName("name") val name: String? = null,
)
