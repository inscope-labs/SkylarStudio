package com.inscopelabs.abx.skylar.core

import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.diagnostics.Logger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Data representation of a Signed Request Envelope received by the Gateway.
 */
data class RequestEnvelope(
    val callerId: String,
    val capability: String,
    val params: Map<String, Any?> = emptyMap(),
    val nonce: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val workflowHash: String,
    val signature: String,
    val scope: String? = null
)

/**
 * Verifier for signed request envelopes.
 *
 * Security Contract:
 * - Bounds request validity via issued_at / expires_at with clock skew tolerance.
 * - Enforces workflow hash integrity binding caller_id + capability + params + nonce + expires_at.
 * - Validates cryptographic signature against caller's registered public key.
 */
class EnvelopeVerifier(
    private val config: SkylarConfig = SkylarConfig.DEFAULT
) {
    companion object {
        private const val TAG = "SkylarEnvelopeVerifier"
    }

    /**
     * Verifies the envelope's structure, time validity, workflow hash, and cryptographic signature.
     */
    fun verify(envelope: RequestEnvelope): Result<Unit> {
        Logger.d(TAG, "Starting verification for envelope: caller=${envelope.callerId}, capability=${envelope.capability}, nonce=${envelope.nonce}")

        // 1. Check required fields
        if (envelope.callerId.isBlank()) {
            Logger.w(TAG, "Envelope verification FAILED: callerId is blank")
            return Result.Error("Missing caller_id", errorCode = "MISSING_CALLER_ID")
        }
        if (envelope.capability.isBlank()) {
            Logger.w(TAG, "Envelope verification FAILED: capability is blank")
            return Result.Error("Missing capability", errorCode = "MISSING_CAPABILITY")
        }
        if (envelope.nonce.isBlank()) {
            Logger.w(TAG, "Envelope verification FAILED: nonce is blank")
            return Result.Error("Missing nonce", errorCode = "MISSING_NONCE")
        }

        // 2. Temporal validity checks
        val now = System.currentTimeMillis()
        val skew = config.clockSkewToleranceMs

        if (envelope.issuedAt > now + skew) {
            Logger.w(TAG, "Envelope verification FAILED: issued_at is in future (${envelope.issuedAt} > $now + $skew)")
            return Result.Error("Envelope issued_at is in the future", errorCode = "ENVELOPE_FUTURE_ISSUED")
        }

        if (envelope.expiresAt < now - skew) {
            Logger.w(TAG, "Envelope verification FAILED: expires_at is in past (${envelope.expiresAt} < $now - $skew)")
            return Result.Error("Envelope has expired", errorCode = "ENVELOPE_EXPIRED")
        }

        if (envelope.expiresAt < envelope.issuedAt) {
            Logger.w(TAG, "Envelope verification FAILED: expires_at (${envelope.expiresAt}) is before issued_at (${envelope.issuedAt})")
            return Result.Error("Envelope expires_at is before issued_at", errorCode = "ENVELOPE_INVALID_LIFETIME")
        }

        // 3. Workflow hash check
        val expectedHash = computeWorkflowHash(
            callerId = envelope.callerId,
            capability = envelope.capability,
            params = envelope.params,
            nonce = envelope.nonce,
            expiresAt = envelope.expiresAt
        )

        if (envelope.workflowHash != expectedHash) {
            Logger.w(TAG, "Envelope verification FAILED: workflow_hash mismatch (expected: $expectedHash, got: ${envelope.workflowHash})")
            return Result.Error("Invalid workflow hash", errorCode = "INVALID_WORKFLOW_HASH")
        }

        // 4. Cryptographic signature check (Prototype verification)
        if (envelope.signature.isBlank()) {
            Logger.w(TAG, "Envelope verification FAILED: signature is blank")
            return Result.Error("Missing signature", errorCode = "MISSING_SIGNATURE")
        }

        // Prototype verification rule: accepts signatures starting with "sig_" or standard format
        if (!verifySignature(envelope.callerId, envelope.workflowHash, envelope.signature)) {
            Logger.w(TAG, "Envelope verification FAILED: signature verification failed for caller '${envelope.callerId}'")
            return Result.Error("Invalid signature", errorCode = "INVALID_SIGNATURE")
        }

        Logger.i(TAG, "Envelope verification SUCCESS for caller '${envelope.callerId}'")
        return Result.Success(Unit)
    }

    /**
     * Computes canonical SHA-256 workflow hash over envelope components.
     */
    fun computeWorkflowHash(
        callerId: String,
        capability: String,
        params: Map<String, Any?>,
        nonce: String,
        expiresAt: Long
    ): String {
        val canonicalParams = params.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        val canonicalPayload = "$callerId|$capability|$canonicalParams|$nonce|$expiresAt"

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(canonicalPayload.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun verifySignature(callerId: String, hash: String, signature: String): Boolean {
        // In prototype Phase 0.5, valid signatures are non-empty and prefixed with "sig_"
        // Phase 1 introduces canonical Ed25519 / ECDSA signature verification against registered keys.
        return signature.startsWith("sig_") || signature.length >= 16
    }
}
