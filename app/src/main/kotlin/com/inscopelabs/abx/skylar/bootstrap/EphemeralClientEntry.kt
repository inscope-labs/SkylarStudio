package com.inscopelabs.abx.skylar.bootstrap

/**
 * Pre-registered client identity for ephemeral callers (architecture §5).
 *
 * Provisioned out-of-band to known ephemeral caller classes (LLM agents, CI runners,
 * plugin developers) prior to their first request.
 */
data class EphemeralClientEntry(
    val clientId: String,
    val clientSecret: String,
    val callerId: String,
    val allowedScopes: Set<String>,
    val maxTtlMs: Long = 15 * 60 * 1000L, // default 15 minutes
    val description: String = ""
) {
    init {
        require(clientId.isNotBlank()) { "clientId cannot be blank" }
        require(clientSecret.isNotBlank()) { "clientSecret cannot be blank" }
        require(callerId.isNotBlank()) { "callerId cannot be blank" }
        require(allowedScopes.isNotEmpty()) { "allowedScopes cannot be empty" }
    }
}
