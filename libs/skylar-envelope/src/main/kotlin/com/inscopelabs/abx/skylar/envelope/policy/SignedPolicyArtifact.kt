package com.inscopelabs.abx.skylar.envelope.policy

import com.inscopelabs.abx.skylar.envelope.EnvelopeLog
import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.KeyRegistry
import com.inscopelabs.abx.skylar.envelope.crypto.SignatureProvider
import java.nio.charset.StandardCharsets

/**
 * Generic signed container for policy artefacts (architecture doc §2/§3).
 *
 * Wraps policy payloads (authorization matrix, routing table) and verifies
 * authenticity against the registered policy signer's public key.
 */
data class SignedPolicyArtifact(
    val version: String,
    val canonicalPayload: String,
    val signerId: String,
    val signature: String
) {
    fun isValid(
        keyRegistry: KeyRegistry,
        signatureProvider: SignatureProvider = EcdsaP256SignatureProvider()
    ): Boolean {
        if (signature.isBlank()) {
            EnvelopeLog.w(TAG, "Policy artefact signature is blank — rejecting unsigned policy")
            return false
        }

        val publicKey = keyRegistry.publicKeyFor(signerId)
        if (publicKey == null) {
            EnvelopeLog.w(TAG, "Policy artefact signer '$signerId' is not a registered identity")
            return false
        }

        val signatureBytes = try {
            Base64Codec.decode(signature)
        } catch (e: Exception) {
            EnvelopeLog.w(TAG, "Policy artefact signature is not valid Base64: ${e.message}")
            return false
        }

        return signatureProvider.verify(
            publicKey,
            canonicalPayload.toByteArray(StandardCharsets.UTF_8),
            signatureBytes
        )
    }

    companion object {
        private const val TAG = "SignedPolicyArtifact"
    }
}
