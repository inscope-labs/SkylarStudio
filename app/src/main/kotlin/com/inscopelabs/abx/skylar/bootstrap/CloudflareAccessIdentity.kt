package com.inscopelabs.abx.skylar.bootstrap

/**
 * Verified Cloudflare Access identity assertion passed on Lane B edge requests (architecture §5).
 *
 * Architecture doc §5:
 * "A Cloudflare Access token alone is never sufficient — it proves edge-policy admission,
 * not authorization to receive a signing credential."
 */
data class CloudflareAccessIdentity(
    val identityToken: String,
    val userEmail: String,
    val issuer: String,
    val audience: String,
    val issuedAt: Long,
    val expiresAt: Long
) {
    /**
     * Checks if this identity token assertion is within its validity window.
     */
    fun isValid(currentTime: Long = System.currentTimeMillis()): Boolean {
        return identityToken.isNotBlank() &&
            userEmail.isNotBlank() &&
            currentTime in issuedAt..expiresAt
    }
}
