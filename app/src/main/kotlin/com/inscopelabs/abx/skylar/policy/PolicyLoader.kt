package com.inscopelabs.abx.skylar.policy

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.crypto.KeyRegistry
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Loads and verifies signed policy artefacts (Authorization Matrix &
 * Routing Table) — architecture doc §3/§4.
 *
 * The prior prototype shipped a hardcoded baseline (four test callers,
 * a fixed route table) wrapped in a fake `"sig_valid_proto_..."` string
 * that its paired verifier accepted unconditionally. That satisfied
 * nothing — "rejects unsigned or incorrectly signed policy" (Phase 1
 * deliverable 1) requires an actual signature check, performed here via
 * [SignedPolicyArtifact.isValid] against [keyRegistry].
 *
 * There is deliberately no baked-in baseline data in this version: on
 * any verification or parse failure, callers get [Result.Error] and are
 * expected to fail closed to [AuthorizationMatrix.EMPTY] /
 * [RoutingTable.EMPTY] — see [failClosedDefaults] and how
 * [com.inscopelabs.abx.skylar.core.SkylarCore.initialize] uses it.
 *
 * What this class does NOT yet do: read a signed artefact off disk or
 * out of `app/src/main/assets/policy/`. The actual policy-signing
 * pipeline (`policy/signing/sign-policy.sh` per the canonical repo
 * structure doc) hasn't been built yet. Until it exists, callers must
 * construct a [SignedPolicyArtifact] themselves (e.g. from a test
 * fixture) and pass it to [loadAuthorizationMatrixFromArtifact] /
 * [loadRoutingTableFromArtifact] directly — there is nothing for this
 * class to read from an asset path that doesn't contain real signed
 * artefacts yet.
 */
class PolicyLoader(
    private val context: Context,
    private val keyRegistry: KeyRegistry,
    private val config: SkylarConfig = SkylarConfig.DEFAULT
) {
    companion object {
        private const val TAG = "SkylarPolicyLoader"
    }

    fun loadAuthorizationMatrixFromArtifact(
        artifact: SignedPolicyArtifact,
        parse: (String) -> Map<String, Map<String, Set<String>>>
    ): Result<AuthorizationMatrix> {
        if (!artifact.isValid(keyRegistry)) {
            Logger.e(TAG, "Authorization Matrix artefact failed signature verification — failing closed")
            return Result.Error("Authorization Matrix signature invalid", errorCode = "AUTH_MATRIX_SIGNATURE_INVALID")
        }
        return try {
            Result.Success(AuthorizationMatrix(artifact.version, parse(artifact.canonicalPayload)))
        } catch (e: Exception) {
            Logger.e(TAG, "Authorization Matrix payload failed to parse after signature check passed", e)
            Result.Error("Authorization Matrix payload malformed", cause = e, errorCode = "AUTH_MATRIX_MALFORMED")
        }
    }

    fun loadRoutingTableFromArtifact(
        artifact: SignedPolicyArtifact,
        parse: (String) -> Map<String, String>
    ): Result<RoutingTable> {
        if (!artifact.isValid(keyRegistry)) {
            Logger.e(TAG, "Routing Table artefact failed signature verification — failing closed")
            return Result.Error("Routing Table signature invalid", errorCode = "ROUTING_TABLE_SIGNATURE_INVALID")
        }
        return try {
            Result.Success(RoutingTable(artifact.version, parse(artifact.canonicalPayload)))
        } catch (e: Exception) {
            Logger.e(TAG, "Routing Table payload failed to parse after signature check passed", e)
            Result.Error("Routing Table payload malformed", cause = e, errorCode = "ROUTING_TABLE_MALFORMED")
        }
    }

    /** Explicit fail-closed path for when no verified artefact is available yet — not a fixture. */
    fun failClosedDefaults(): Pair<AuthorizationMatrix, RoutingTable> {
        Logger.w(TAG, "No verified policy artefact available — failing closed to EMPTY authorization matrix and routing table")
        return AuthorizationMatrix.EMPTY to RoutingTable.EMPTY
    }
}
