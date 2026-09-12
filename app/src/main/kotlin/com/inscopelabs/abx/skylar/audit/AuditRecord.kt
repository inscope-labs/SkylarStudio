package com.inscopelabs.abx.skylar.audit

import java.util.UUID

/**
 * Immutable audit record capturing every security decision and dispatch
 * outcome within the Skylar Context Gateway pipeline (architecture doc
 * §10: "logs, at minimum: caller_id, capability, decision, nonce,
 * timestamp, envelope hash").
 *
 * One addition relative to the prior version: [policyVersion]. Without
 * recording which signed Authorization Matrix / Routing Table version
 * was active, an old log entry's ALLOW or DENY can't be distinguished
 * after the fact between "the policy changed since this decision" and
 * "the code changed" — both look identical without this field.
 */
data class AuditRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val callerId: String,
    val capability: String,
    val decision: Decision,
    val reason: String,
    val nonce: String,
    val envelopeHash: String,
    val target: String? = null,
    val executionTimeMs: Long = 0L,
    val policyVersion: String? = null,
    val details: Map<String, String> = emptyMap()
) {
    enum class Decision {
        ALLOW,
        DENY
    }

    fun toFormattedLogLine(): String {
        return "[$timestamp] ID=$id DECISION=$decision CALLER=$callerId CAPABILITY=$capability " +
            "TARGET=${target ?: "NONE"} POLICY=${policyVersion ?: "UNKNOWN"} REASON=\"$reason\" " +
            "NONCE=$nonce HASH=$envelopeHash DURATION=${executionTimeMs}ms"
    }
}
