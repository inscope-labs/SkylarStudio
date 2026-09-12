package com.inscopelabs.abx.skylar.mesh

/**
 * Transport-layer credential representing reachability on an ingress lane
 * (such as Tailscale mesh enrollment).
 *
 * Security Contract: A transport credential confers reachability only,
 * NEVER authorization. Holding a transport credential never implies a valid
 * request-signing credential.
 */
data class TransportCredential(
    val credentialId: String,
    val authKey: String,
    val meshNodeId: String,
    val issuedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,
    val isRevoked: Boolean = false
) {
    /**
     * Checks whether the transport credential is valid and unexpired at the given time.
     */
    fun isValid(currentTime: Long = System.currentTimeMillis()): Boolean {
        return !isRevoked && currentTime < expiresAt
    }
}
