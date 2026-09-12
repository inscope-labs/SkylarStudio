package com.inscopelabs.abx.skylar.envelope.policy

import com.inscopelabs.abx.skylar.envelope.EnvelopeLog
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.KeyRegistry
import com.inscopelabs.abx.skylar.envelope.crypto.SignatureProvider

/**
 * Reads and verifies signed policy artefacts (Phase 1 deliverable 1).
 *
 * Mechanically rejects unsigned or incorrectly signed policy artefacts,
 * ensuring no unverified policy can be loaded into active enforcement.
 */
class PolicyArtifactReader(
    private val keyRegistry: KeyRegistry,
    private val signatureProvider: SignatureProvider = EcdsaP256SignatureProvider()
) {
    companion object {
        private const val TAG = "PolicyArtifactReader"
    }

    sealed class Result<out T> {
        data class Success<out T>(val value: T) : Result<T>()
        data class Error(val message: String, val errorCode: String, val cause: Throwable? = null) : Result<Nothing>()

        val isSuccess: Boolean get() = this is Success
        val isError: Boolean get() = this is Error
        fun getOrNull(): T? = (this as? Success)?.value
    }

    fun readAuthorizationMatrix(
        artifact: SignedPolicyArtifact,
        parser: (String) -> Map<String, Map<String, Set<String>>>
    ): Result<AuthorizationMatrix> {
        if (!artifact.isValid(keyRegistry, signatureProvider)) {
            EnvelopeLog.e(TAG, "Authorization Matrix artefact rejected: invalid or missing signature")
            return Result.Error("Authorization Matrix signature invalid", "AUTH_MATRIX_SIGNATURE_INVALID")
        }

        return try {
            val matrixData = parser(artifact.canonicalPayload)
            Result.Success(AuthorizationMatrix(artifact.version, matrixData))
        } catch (e: Exception) {
            EnvelopeLog.e(TAG, "Authorization Matrix payload parsing failed: ${e.message}", e)
            Result.Error("Authorization Matrix payload malformed", "AUTH_MATRIX_MALFORMED", e)
        }
    }

    fun readRoutingTable(
        artifact: SignedPolicyArtifact,
        parser: (String) -> Map<String, String>
    ): Result<RoutingTable> {
        if (!artifact.isValid(keyRegistry, signatureProvider)) {
            EnvelopeLog.e(TAG, "Routing Table artefact rejected: invalid or missing signature")
            return Result.Error("Routing Table signature invalid", "ROUTING_TABLE_SIGNATURE_INVALID")
        }

        return try {
            val routeData = parser(artifact.canonicalPayload)
            Result.Success(RoutingTable(artifact.version, routeData))
        } catch (e: Exception) {
            EnvelopeLog.e(TAG, "Routing Table payload parsing failed: ${e.message}", e)
            Result.Error("Routing Table payload malformed", "ROUTING_TABLE_MALFORMED", e)
        }
    }
}
