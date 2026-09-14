package com.inscopelabs.abx.skylar.bootstrap

/**
 * Request-signing credential minted by the centralized Issuer (architecture §5).
 *
 * This credential anchors request authorization at Skylar Core. It is strictly
 * separated from transport credentials: a caller holds [privateKeyBytes] to sign
 * canonical request envelopes, while Skylar Core verifies against [publicKeyBytes]
 * registered in the key registry.
 */
data class IssuedSigningCredential(
    val credentialId: String,
    val callerId: String,
    val callerClass: CallerClass,
    val privateKeyBytes: ByteArray,
    val publicKeyBytes: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
    val scope: String,
    val isRevoked: Boolean = false,
    val token: String? = null
) {
    /**
     * Checks if this signing credential is valid and unexpired at [currentTime].
     */
    fun isValid(currentTime: Long = System.currentTimeMillis()): Boolean {
        return !isRevoked && currentTime in issuedAt..expiresAt
    }

    /**
     * Checks if this credential covers the given [requiredScope].
     * Supports comma-separated or space-separated scope strings.
     */
    fun hasScope(requiredScope: String): Boolean {
        if (scope == "*" || scope == "admin") return true
        val grantedScopes = scope.split(Regex("[,\\s]+")).map { it.trim() }.toSet()
        return requiredScope in grantedScopes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IssuedSigningCredential

        if (credentialId != other.credentialId) return false
        if (callerId != other.callerId) return false
        if (callerClass != other.callerClass) return false
        if (!privateKeyBytes.contentEquals(other.privateKeyBytes)) return false
        if (!publicKeyBytes.contentEquals(other.publicKeyBytes)) return false
        if (issuedAt != other.issuedAt) return false
        if (expiresAt != other.expiresAt) return false
        if (scope != other.scope) return false
        if (isRevoked != other.isRevoked) return false
        if (token != other.token) return false

        return true
    }

    override fun hashCode(): Int {
        var result = credentialId.hashCode()
        result = 31 * result + callerId.hashCode()
        result = 31 * result + callerClass.hashCode()
        result = 31 * result + privateKeyBytes.contentHashCode()
        result = 31 * result + publicKeyBytes.contentHashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + scope.hashCode()
        result = 31 * result + isRevoked.hashCode()
        result = 31 * result + (token?.hashCode() ?: 0)
        return result
    }
}
