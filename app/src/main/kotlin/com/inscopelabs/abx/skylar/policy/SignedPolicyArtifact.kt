package com.inscopelabs.abx.skylar.policy

import com.inscopelabs.abx.skylar.crypto.Base64Codec
import com.inscopelabs.abx.skylar.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.crypto.KeyRegistry
import com.inscopelabs.abx.skylar.crypto.SignatureProvider
import com.inscopelabs.abx.skylar.diagnostics.Logger
import java.nio.charset.StandardCharsets

/**
 * Generic signed-container wrapper for policy artefacts (architecture
 * doc §2/§3: the authorization matrix and routing table must both be
 * signed and *verified*, not just carried alongside a `signature` field
 * that nothing actually checks).
 *
 * The prior prototype set `signature = "sig_valid_proto_matrix_..."`
 * directly on the data class at load time — a value that was never
 * independently checked against anything, since the paired
 * `EnvelopeVerifier` stub accepted any 16+ character string. This
 * wrapper makes "unsigned or incorrectly signed policy is rejected"
 * (Phase 1 deliverable 1) mechanically true: [isValid] performs a real
 * signature check and there is no path that returns `true` without one.
 */
data class SignedPolicyArtifact(
    val version: String,
    val canonicalPayload: String,
    val signerId: String,
    val signature: String
) {
    /**
     * Verifies [signature] over [canonicalPayload] against [signerId]'s
     * registered public key. Returns `false` (fail closed) for any
     * unregistered signer, malformed signature, or cryptographic
     * mismatch — never throws out to the caller.
     */
    fun isValid(
        keyRegistry: KeyRegistry,
        signatureProvider: SignatureProvider = EcdsaP256SignatureProvider()
    ): Boolean {
        val publicKey = keyRegistry.publicKeyFor(signerId)
        if (publicKey == null) {
            Logger.w(TAG, "Policy artefact signer '$signerId' is not a registered identity")
            return false
        }

        val signatureBytes = try {
            Base64Codec.decode(signature)
        } catch (e: Exception) {
            Logger.w(TAG, "Policy artefact signature is not valid Base64: ${e.message}")
            return false
        }

        return signatureProvider.verify(
            publicKey,
            canonicalPayload.toByteArray(StandardCharsets.UTF_8),
            signatureBytes
        )
    }

    companion object {
        private const val TAG = "SkylarSignedPolicyArtifact"
    }
}
