package com.oneshotchat.android.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519 (RFC 8032) via BouncyCastle's lightweight API — no provider registration
 * needed, works identically on JVM and Android.
 *
 * An identity is a 32-byte seed (the RFC 8032 "private key"). The public key (also
 * 32 bytes) is the fingerprint, base32-encoded.
 */
object Ed25519 {
    const val SEED_LEN = 32
    const val PUBLIC_LEN = 32
    const val SIGNATURE_LEN = 64

    private val random = SecureRandom()

    /** A keypair backed by a 32-byte seed. */
    class KeyPair(val seed: ByteArray) {
        private val priv = Ed25519PrivateKeyParameters(seed, 0)
        val publicKey: ByteArray = priv.generatePublicKey().encoded

        /** Sign the UTF-8 bytes of [message], returning a 64-byte signature. */
        fun sign(message: ByteArray): ByteArray {
            val signer = Ed25519Signer()
            signer.init(true, priv)
            signer.update(message, 0, message.size)
            return signer.generateSignature()
        }
    }

    fun generate(): KeyPair {
        val seed = ByteArray(SEED_LEN)
        random.nextBytes(seed)
        return KeyPair(seed)
    }

    fun fromSeed(seed: ByteArray): KeyPair {
        require(seed.size == SEED_LEN) { "Seed must be $SEED_LEN bytes, was ${seed.size}" }
        return KeyPair(seed)
    }

    /** Derive the 32-byte public key from a seed. */
    fun publicKeyFromSeed(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    /** Verify a signature against a public key. Never throws; returns false on bad input. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            if (publicKey.size != PUBLIC_LEN || signature.size != SIGNATURE_LEN) return false
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }
}
