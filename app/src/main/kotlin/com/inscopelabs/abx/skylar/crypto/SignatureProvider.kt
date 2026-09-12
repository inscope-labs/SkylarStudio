package com.inscopelabs.abx.skylar.crypto

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Signs and verifies byte payloads for the Skylar security contract.
 *
 * This interface only covers byte-level sign/verify; callers (see
 * [com.inscopelabs.abx.skylar.core.EnvelopeVerifier]) are responsible for
 * canonicalizing the payload first and for encoding signatures to/from a
 * transportable string via [Base64Codec].
 */
interface SignatureProvider {
    val algorithmId: String
    fun sign(privateKeyBytes: ByteArray, message: ByteArray): ByteArray
    fun verify(publicKeyBytes: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/**
 * ECDSA over the P-256 curve with SHA-256 ("SHA256withECDSA" in the JCA).
 *
 * Algorithm-selection note: architecture doc §2 leaves the concrete
 * signature algorithm as "Ed25519 or the algorithm selected by the
 * project." Ed25519 is only available via Android's default JCA
 * providers starting API 33 (Android 13); this project's minSdk is 24
 * (app/build.gradle.kts). ECDSA/P-256/SHA-256 works via the standard
 * `java.security` API back to Android's earliest API levels, with no
 * additional crypto library dependency. This selection should be
 * recorded in `skylar-context-gateway-architecture-addenda.md`.
 *
 * This replaces the prior prototype's `verifySignature()`, which
 * accepted any string 16+ characters long as a valid signature — a
 * fail-open stub sitting in the one place the entire security contract
 * depends on. Every failure path here returns `false`, never throws
 * past this boundary, and never returns `true` on anything but an
 * actual cryptographic match.
 */
class EcdsaP256SignatureProvider : SignatureProvider {

    override val algorithmId: String = "SHA256withECDSA/P-256"

    override fun sign(privateKeyBytes: ByteArray, message: ByteArray): ByteArray {
        val key = decodePrivateKey(privateKeyBytes)
        val signer = Signature.getInstance(JCA_ALGORITHM)
        signer.initSign(key)
        signer.update(message)
        return signer.sign()
    }

    override fun verify(publicKeyBytes: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return try {
            val key = decodePublicKey(publicKeyBytes)
            val verifier = Signature.getInstance(JCA_ALGORITHM)
            verifier.initVerify(key)
            verifier.update(message)
            verifier.verify(signature)
        } catch (e: Exception) {
            // Any parse/verify failure is a verification failure, not an
            // exception the caller must remember to catch. Fail closed.
            false
        }
    }

    private fun decodePrivateKey(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    private fun decodePublicKey(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    companion object {
        private const val JCA_ALGORITHM = "SHA256withECDSA"
    }
}

/**
 * Dependency-free Base64 (RFC 4648, standard alphabet, with padding).
 *
 * Deliberately not `java.util.Base64` (API 26+, above this project's
 * minSdk 24) and not `android.util.Base64` (an Android framework class
 * that returns default/empty values under this project's plain-JVM unit
 * test configuration — `app/build.gradle.kts` sets
 * `unitTests.isReturnDefaultValues = true` with no Robolectric shadow
 * registered for it, so real encode/decode calls would silently no-op
 * in tests). This implementation behaves identically on-device and in
 * JVM unit tests.
 */
object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0

            sb.append(ALPHABET[b0 ushr 2])
            sb.append(ALPHABET[(b0 shl 4 or (b1 ushr 4)) and 0x3F])
            sb.append(if (i + 1 < bytes.size) ALPHABET[(b1 shl 2 or (b2 ushr 6)) and 0x3F] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(encoded: String): ByteArray {
        val clean = encoded.trim().trimEnd('=')
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var buffer = 0
        var bitsCollected = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "Invalid Base64 character: '$c'" }
            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                out.add(((buffer shr bitsCollected) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
