package com.inscopelabs.abx.skylar.policy

import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * In-memory representation of the Authorization Matrix: `caller_id ->
 * {capability: scope}` (architecture doc §3).
 *
 * Security Contract:
 * - Default-deny: any caller or capability not explicitly declared is rejected.
 * - This class no longer carries its own `signature` field. Authenticity
 *   of the data used to construct it is [SignedPolicyArtifact]'s concern,
 *   checked *before* this constructor is ever called with real data —
 *   see [PolicyLoader]. A signature living on this class next to the
 *   already-trusted data it describes added no protection; it just gave
 *   the prior prototype a plausible-looking field to fake.
 *
 * The public constructor stays available for tests and for programmatic
 * construction (e.g. a Phase 3 hot-reload of already-verified policy).
 * Loading from a *signed artefact on disk* must go through [PolicyLoader].
 */
class AuthorizationMatrix(
    val version: String,
    private val matrix: Map<String, Map<String, Set<String>>> = emptyMap()
) {
    companion object {
        private const val TAG = "SkylarAuthMatrix"
        val EMPTY = AuthorizationMatrix("0.0.0", emptyMap())
    }

    fun isAuthorized(callerId: String, capability: String, requiredScope: String? = null): Boolean {
        val callerCapabilities = matrix[callerId]
        if (callerCapabilities == null) {
            Logger.w(TAG, "Authorization DENIED: caller '$callerId' not in matrix (default-deny)")
            return false
        }

        val allowedScopes = callerCapabilities[capability]
        if (allowedScopes == null) {
            Logger.w(TAG, "Authorization DENIED: caller '$callerId' lacks capability '$capability'")
            return false
        }

        if (requiredScope != null && requiredScope !in allowedScopes && "*" !in allowedScopes) {
            Logger.w(TAG, "Authorization DENIED: caller '$callerId' lacks scope '$requiredScope' for '$capability'")
            return false
        }

        Logger.i(TAG, "Authorization GRANTED: caller '$callerId' permitted for '$capability'")
        return true
    }

    fun getPermittedCapabilities(callerId: String): Set<String> = matrix[callerId]?.keys ?: emptySet()
    fun callerCount(): Int = matrix.size
}
