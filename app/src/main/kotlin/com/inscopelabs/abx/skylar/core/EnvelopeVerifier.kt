package com.inscopelabs.abx.skylar.core

import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.crypto.Base64Codec
import com.inscopelabs.abx.skylar.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.crypto.KeyRegistry
import com.inscopelabs.abx.skylar.crypto.SignatureProvider
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Verifier for signed request envelopes (architecture doc §2).
 *
 * This replaces the earlier prototype's `verifySignature()`:
 *
 *     return signature.startsWith("sig_") || signature.length >= 16
 *
 * which accepted any sufficiently long string as a valid signature — a
 * fail-open stub sitting in the one place the entire security contract
 * depends on ("Transport is never authorization. Every request is
 * independently verified by Skylar Core." — Guiding Principle #1).
 * Every check below fails closed: any exception, mismatch, or missing
 * key is a DENY. Nothing here defaults to ALLOW.
 *
 * Requires a [KeyRegistry] with an entry for the caller — an
 * unregistered caller_id is rejected before signature math is even
 * attempted, since there would be nothing to verify against.
 */
class EnvelopeVerifier(
    private val keyRegistry: KeyRegistry,
    private val config: SkylarConfig = SkylarConfig.DEFAULT,
    private val signatureProvider: SignatureProvider = EcdsaP256SignatureProvider()
) {
    companion object {
        private const val TAG = "SkylarEnvelopeVerifier"
    }

    fun verify(envelope: RequestEnvelope): Result<Unit> {
        Logger.d(TAG, "Verifying envelope: caller=${envelope.callerId}, capability=${envelope.capability}, nonce=${envelope.nonce}")

        if (envelope.callerId.isBlank()) return deny(envelope, "Missing caller_id", "MISSING_CALLER_ID")
        if (envelope.capability.isBlank()) return deny(envelope, "Missing capability", "MISSING_CAPABILITY")
        if (envelope.nonce.isBlank()) return deny(envelope, "Missing nonce", "MISSING_NONCE")
        if (envelope.signature.isBlank()) return deny(envelope, "Missing signature", "MISSING_SIGNATURE")

        val now = System.currentTimeMillis()
        val skew = config.clockSkewToleranceMs

        if (envelope.issuedAt > now + skew) {
            return deny(envelope, "issued_at is in the future", "ENVELOPE_FUTURE_ISSUED")
        }
        if (envelope.expiresAt < envelope.issuedAt) {
            return deny(envelope, "expires_at is before issued_at", "ENVELOPE_INVALID_LIFETIME")
        }
        if (envelope.expiresAt < now - skew) {
            return deny(envelope, "envelope has expired", "ENVELOPE_EXPIRED")
        }

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
            return deny(envelope, "workflow_hash mismatch", "INVALID_WORKFLOW_HASH")
        }

        val publicKey = keyRegistry.publicKeyFor(envelope.callerId)
            ?: return deny(envelope, "caller_id is not a registered identity", "UNKNOWN_CALLER")

        val signatureBytes = try {
            Base64Codec.decode(envelope.signature)
        } catch (e: Exception) {
            return deny(envelope, "signature is not valid Base64: ${e.message}", "MALFORMED_SIGNATURE")
        }

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
            return deny(envelope, "cryptographic signature verification failed", "INVALID_SIGNATURE")
        }

        Logger.i(TAG, "Envelope verification SUCCESS for caller '${envelope.callerId}'")
        return Result.Success(Unit)
    }

    private fun deny(envelope: RequestEnvelope, reason: String, code: String): Result.Error {
        Logger.w(TAG, "Envelope verification FAILED for caller '${envelope.callerId}': $reason")
        return Result.Error(reason, errorCode = code)
    }
}
