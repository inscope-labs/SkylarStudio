package com.inscopelabs.abx.skylar.audit

import java.util.UUID

/**
 * Immutable audit record capturing every security decision and dispatch outcome
 * within the Skylar Context Gateway pipeline.
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
    val details: Map<String, String> = emptyMap()
) {
    enum class Decision {
        ALLOW,
        DENY
    }

    fun toFormattedLogLine(): String {
        return "[$timestamp] ID=$id DECISION=$decision CALLER=$callerId CAPABILITY=$capability TARGET=${target ?: "NONE"} REASON=\"$reason\" NONCE=$nonce HASH=$envelopeHash DURATION=${executionTimeMs}ms"
    }
}
