package com.inscopelabs.abx.skylar.envelope

import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.KeyRegistry
import com.inscopelabs.abx.skylar.envelope.crypto.SignatureProvider
import com.inscopelabs.abx.skylar.envelope.nonce.NonceCache

/**
 * Verifier for signed request envelopes (architecture doc §2).
 *
 * Checks envelope version, required fields, timestamp bounds, workflow hash,
 * registered caller key material, and cryptographic signature. Fails closed.
 */
class EnvelopeVerifier(
    private val keyRegistry: KeyRegistry,
    private val signatureProvider: SignatureProvider = EcdsaP256SignatureProvider(),
    private val clockSkewToleranceMs: Long = 30_000L,
    private val nonceCache: NonceCache? = null,
    private val supportedVersions: Set<Int> = setOf(RequestEnvelope.CURRENT_ENVELOPE_VERSION)
) {
    companion object {
        private const val TAG = "EnvelopeVerifier"
    }

    fun verify(envelope: RequestEnvelope, currentTimeMs: Long = System.currentTimeMillis()): EnvelopeVerificationResult {
        EnvelopeLog.d(TAG, "Verifying envelope: caller=${envelope.callerId}, capability=${envelope.capability}, nonce=${envelope.nonce}")

        // 1. Envelope version check
        if (envelope.envelopeVersion !in supportedVersions) {
            return deny("Unsupported envelope version: ${envelope.envelopeVersion}", "UNSUPPORTED_ENVELOPE_VERSION")
        }

        // 2. Missing fields check
        if (envelope.callerId.isBlank()) return deny("Missing caller_id", "MISSING_CALLER_ID")
        if (envelope.capability.isBlank()) return deny("Missing capability", "MISSING_CAPABILITY")
        if (envelope.nonce.isBlank()) return deny("Missing nonce", "MISSING_NONCE")
        if (envelope.signature.isBlank()) return deny("Missing signature", "MISSING_SIGNATURE")

        // 3. Timestamps check
        if (envelope.issuedAt > currentTimeMs + clockSkewToleranceMs) {
            return deny("issued_at is in the future", "ENVELOPE_FUTURE_ISSUED")
        }
        if (envelope.expiresAt < envelope.issuedAt) {
            return deny("expires_at is before issued_at", "ENVELOPE_INVALID_LIFETIME")
        }
        if (envelope.expiresAt < currentTimeMs - clockSkewToleranceMs) {
            return deny("envelope has expired", "ENVELOPE_EXPIRED")
        }

        // 4. Nonce replay check (if cache provided)
        if (nonceCache != null) {
            if (nonceCache.isSeen(envelope.callerId, envelope.nonce)) {
                return deny("Replay detected: nonce '${envelope.nonce}' already seen", "REPLAY_DETECTED")
            }
        }

        // 5. Workflow hash match
        val expectedHash = EnvelopeCanonicalizer.computeWorkflowHash(
            envelopeVersion = envelope.envelopeVersion,
            callerId = envelope.callerId,
            capability = envelope.capability,
            params = envelope.params,
            nonce = envelope.nonce,
            issuedAt = envelope.issuedAt,
            expiresAt = envelope.expiresAt,
            scope = envelope.scope
        )
        if (envelope.workflowHash != expectedHash) {
            return deny("workflow_hash mismatch", "INVALID_WORKFLOW_HASH")
        }

        // 6. Registered public key lookup
        val publicKey = keyRegistry.publicKeyFor(envelope.callerId)
            ?: return deny("caller_id '${envelope.callerId}' is not a registered identity", "UNKNOWN_CALLER")

        // 7. Signature decoding
        val signatureBytes = try {
            Base64Codec.decode(envelope.signature)
        } catch (e: Exception) {
            return deny("signature is not valid Base64: ${e.message}", "MALFORMED_SIGNATURE")
        }

        // 8. Cryptographic verification
        val canonicalBytes = EnvelopeCanonicalizer.canonicalBytes(
            envelopeVersion = envelope.envelopeVersion,
            callerId = envelope.callerId,
            capability = envelope.capability,
            params = envelope.params,
            nonce = envelope.nonce,
            issuedAt = envelope.issuedAt,
            expiresAt = envelope.expiresAt,
            scope = envelope.scope
        )

        if (!signatureProvider.verify(publicKey, canonicalBytes, signatureBytes)) {
            return deny("cryptographic signature verification failed", "INVALID_SIGNATURE")
        }

        // Mark nonce seen if verification passed
        nonceCache?.markSeen(envelope.callerId, envelope.nonce, envelope.expiresAt)

        EnvelopeLog.i(TAG, "Envelope verification SUCCESS for caller '${envelope.callerId}'")
        return EnvelopeVerificationResult.Success
    }

    private fun deny(reason: String, code: String): EnvelopeVerificationResult.Failure {
        EnvelopeLog.w(TAG, "Envelope verification FAILED: $reason ($code)")
        return EnvelopeVerificationResult.Failure(reason, code)
    }
}
