package com.inscopelabs.abx.skylar.envelope.policy

import com.inscopelabs.abx.skylar.envelope.EnvelopeLog

/**
 * In-memory representation of the Authorization Matrix: caller_id -> { capability: scope }
 * (architecture doc §3).
 *
 * Enforces default-deny: any caller or capability not explicitly declared is rejected.
 */
class AuthorizationMatrix(
    val version: String,
    private val matrix: Map<String, Map<String, Set<String>>> = emptyMap()
) {
    companion object {
        private const val TAG = "AuthorizationMatrix"
        val EMPTY = AuthorizationMatrix("0.0.0", emptyMap())
    }

    fun isAuthorized(callerId: String, capability: String, requiredScope: String? = null): Boolean {
        val callerCapabilities = matrix[callerId]
        if (callerCapabilities == null) {
            EnvelopeLog.w(TAG, "Authorization DENIED: caller '$callerId' not in matrix (default-deny)")
            return false
        }

        val allowedScopes = callerCapabilities[capability]
        if (allowedScopes == null) {
            EnvelopeLog.w(TAG, "Authorization DENIED: caller '$callerId' lacks capability '$capability'")
            return false
        }

        if (requiredScope != null && requiredScope !in allowedScopes && "*" !in allowedScopes) {
            EnvelopeLog.w(TAG, "Authorization DENIED: caller '$callerId' lacks scope '$requiredScope' for '$capability'")
            return false
        }

        EnvelopeLog.i(TAG, "Authorization GRANTED: caller '$callerId' permitted for '$capability'")
        return true
    }

    fun getPermittedCapabilities(callerId: String): Set<String> = matrix[callerId]?.keys ?: emptySet()
    fun callerCount(): Int = matrix.size
    fun asMap(): Map<String, Map<String, Set<String>>> = matrix
}
