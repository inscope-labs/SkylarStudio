package com.inscopelabs.abx.skylar.envelope

/**
 * Canonical Signed Request Envelope (architecture doc §2).
 *
 * Every field here is either verified independently by [EnvelopeVerifier]
 * or bound into [workflowHash], so mutating any of them after signing
 * invalidates the signature.
 */
data class RequestEnvelope(
    val envelopeVersion: Int = CURRENT_ENVELOPE_VERSION,
    val callerId: String,
    val capability: String,
    val params: Map<String, Any?> = emptyMap(),
    val nonce: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val workflowHash: String,
    val signature: String,
    val scope: String? = null
) {
    companion object {
        /**
         * Bumped whenever canonicalization or signing changes in a way
         * that isn't backward compatible (architecture doc §2: "Version
         * field inside the envelope so future algorithm changes remain
         * backward-compatible.").
         */
        const val CURRENT_ENVELOPE_VERSION = 1
    }
}
