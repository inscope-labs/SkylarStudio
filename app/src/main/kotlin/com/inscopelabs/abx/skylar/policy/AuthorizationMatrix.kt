package com.inscopelabs.abx.skylar.policy

import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * In-memory representation of the signed Authorization Matrix.
 * Maps caller identities to allowed capabilities and their respective scopes.
 *
 * Security Contract:
 * - Default-deny: any caller or capability not explicitly declared is rejected.
 * - Valid signature proves identity, NOT permission.
 */
class AuthorizationMatrix(
    val version: String,
    private val matrix: Map<String, Map<String, Set<String>>> = emptyMap(),
    val signature: String? = null
) {
    companion object {
        private const val TAG = "SkylarAuthMatrix"
        val EMPTY = AuthorizationMatrix("0.0.0", emptyMap(), null)
    }

    /**
     * Checks if a caller has authorization to invoke the given capability with optional required scope.
     */
    fun isAuthorized(callerId: String, capability: String, requiredScope: String? = null): Boolean {
        Logger.d(TAG, "Evaluating authorization: caller=$callerId, capability=$capability, requiredScope=$requiredScope")

        val callerCapabilities = matrix[callerId]
        if (callerCapabilities == null) {
            Logger.w(TAG, "Authorization DENIED: Caller '$callerId' not found in authorization matrix (default-deny)")
            return false
        }

        val allowedScopes = callerCapabilities[capability]
        if (allowedScopes == null) {
            Logger.w(TAG, "Authorization DENIED: Caller '$callerId' lacks capability '$capability'")
            return false
        }

        if (requiredScope != null && !allowedScopes.contains(requiredScope) && !allowedScopes.contains("*")) {
            Logger.w(TAG, "Authorization DENIED: Caller '$callerId' has capability '$capability' but lacks scope '$requiredScope'")
            return false
        }

        Logger.i(TAG, "Authorization GRANTED: Caller '$callerId' permitted for '$capability'")
        return true
    }

    /**
     * Returns the set of all capabilities permitted for a given caller.
     */
    fun getPermittedCapabilities(callerId: String): Set<String> {
        return matrix[callerId]?.keys ?: emptySet()
    }

    /**
     * Returns the total number of registered callers.
     */
    fun callerCount(): Int = matrix.size
}
