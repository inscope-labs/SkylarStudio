package com.inscopelabs.abx.skylar.envelope.crypto

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Signs and verifies byte payloads for the Skylar security contract.
 *
 * Interface only covers byte-level sign/verify; callers are responsible
 * for canonicalizing the payload first and for encoding/decoding signatures.
 */
interface SignatureProvider {
    val algorithmId: String
    fun sign(privateKeyBytes: ByteArray, message: ByteArray): ByteArray
    fun verify(publicKeyBytes: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/**
 * ECDSA over the P-256 curve with SHA-256 ("SHA256withECDSA" in the JCA).
 *
 * Backwards-compatible across Android API levels down to minSdk 24 using java.security.
 * Fails closed on any invalid key or signature mismatch.
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
