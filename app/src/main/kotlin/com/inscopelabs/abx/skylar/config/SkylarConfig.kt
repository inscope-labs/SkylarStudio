package com.inscopelabs.abx.skylar.config

/**
 * Configuration baseline for the Skylar Context Gateway.
 * Controls replay protection windows, clock skew tolerances, mesh node configurations,
 * and dispatch timeouts.
 */
data class SkylarConfig(
    val clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_TOLERANCE_MS,
    val nonceCacheTtlMs: Long = DEFAULT_NONCE_CACHE_TTL_MS,
    val maxEnvelopeLifetimeMs: Long = DEFAULT_MAX_ENVELOPE_LIFETIME_MS,
    val meshPort: Int = DEFAULT_MESH_PORT,
    val meshNodeName: String = DEFAULT_NODE_NAME,
    val policyAssetDirectory: String = DEFAULT_POLICY_DIR,
    val auditLogFileName: String = DEFAULT_AUDIT_LOG_FILE,
    val targetTimeoutMs: Long = DEFAULT_TARGET_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_CLOCK_SKEW_TOLERANCE_MS = 5 * 60 * 1000L // 5 minutes
        const val DEFAULT_NONCE_CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
        const val DEFAULT_MAX_ENVELOPE_LIFETIME_MS = 15 * 60 * 1000L // 15 minutes
        const val DEFAULT_MESH_PORT = 41641
        const val DEFAULT_NODE_NAME = "skylar-core-node"
        const val DEFAULT_POLICY_DIR = "policy"
        const val DEFAULT_AUDIT_LOG_FILE = "skylar_audit.log"
        const val DEFAULT_TARGET_TIMEOUT_MS = 10_000L // 10 seconds

        val DEFAULT = SkylarConfig()
    }
}
