package com.inscopelabs.abx.skylar.core

import android.content.Context
import com.inscopelabs.abx.skylar.audit.AuditLogger
import com.inscopelabs.abx.skylar.audit.AuditRecord
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.envelope.EnvelopeVerifier
import com.inscopelabs.abx.skylar.envelope.RequestEnvelope
import com.inscopelabs.abx.skylar.envelope.crypto.KeyRegistry
import com.inscopelabs.abx.skylar.envelope.nonce.NonceCache
import com.inscopelabs.abx.skylar.envelope.nonce.PersistentNonceCache
import com.inscopelabs.abx.skylar.envelope.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.envelope.policy.PolicyArtifactReader
import com.inscopelabs.abx.skylar.envelope.policy.RoutingTable
import com.inscopelabs.abx.skylar.envelope.policy.SignedPolicyArtifact
import com.inscopelabs.abx.skylar.ipc.TargetDispatcher
import com.inscopelabs.abx.skylar.mesh.MeshNode
import com.inscopelabs.abx.skylar.mesh.TsnetMeshNode

/**
 * The single Policy Enforcement Point for the Skylar Context Gateway:
 * verify -> replay-check -> authorize -> route -> dispatch -> audit.
 *
 * As of this revision, wired to the canonical `libs/skylar-envelope`
 * module rather than a duplicate app-local implementation. A prior pass
 * had built the envelope/crypto/policy logic directly in `app/core`,
 * `app/crypto`, and `app/policy`; a *separate* pass then built the
 * correct, canonical `libs/skylar-envelope` module (matching the
 * repo-structure doc's intended shared-library shape, consumable by
 * Starlight/SFM/xtools too) without migrating the app off the
 * duplicate. Both implementations were legitimate, fail-closed designs
 * — this consolidation keeps the one in the architecturally-correct
 * location and removes the other, rather than picking a "winner" on
 * code-quality grounds alone.
 *
 * Also removed in this revision: a `initialize(matrix, routingTable)`
 * overload that had been added directly to this production class,
 * labeled "for internal testing," which bypassed all signature
 * verification with nothing gating it to actual test code — and, on
 * inspection, a pre-existing `reloadPolicy(matrix, routingTable)`
 * method (from this class's own original version) with the identical
 * unguarded-bypass shape, unused anywhere, removed for the same reason
 * rather than held to a different standard just because it predates
 * the newer one. See
 * `docs/skylar-context-gateway-architecture-addenda.md` (2026-09-12,
 * "Removed ungated test-bypass...") for the full rationale. Tests now
 * construct real [SignedPolicyArtifact]s instead, the same way
 * production code would have to.
 */
class SkylarCore(
    private val context: Context,
    val keyRegistry: KeyRegistry,
    val config: SkylarConfig = SkylarConfig.DEFAULT,
    val verifier: EnvelopeVerifier = EnvelopeVerifier(keyRegistry, clockSkewToleranceMs = config.clockSkewToleranceMs),
    val nonceCache: NonceCache = PersistentNonceCache(context, config.nonceCacheTtlMs),
    val auditLogger: AuditLogger = AuditLogger(context, config),
    val dispatcher: TargetDispatcher = TargetDispatcher(context),
    val meshManager: MeshNode = TsnetMeshNode(),
    private val policyReader: PolicyArtifactReader = PolicyArtifactReader(keyRegistry)
) {
    companion object {
        private const val TAG = "SkylarCore"
    }

    private val lock = Any()
    private var authMatrix: AuthorizationMatrix = AuthorizationMatrix.EMPTY
    private var routingTable: RoutingTable = RoutingTable.EMPTY
    private var isInitialized = false

    /**
     * Initializes with an explicitly-supplied, already-signed policy
     * pair. If either artefact is missing or fails verification, this
     * fails closed to EMPTY for BOTH tables — a trustworthy matrix
     * paired with an untrustworthy routing table (or vice versa) is not
     * a safe half-initialized state to run in.
     *
     * There is deliberately no other way to set the active policy from
     * production code. Tests that need a specific matrix/routing table
     * must sign one — see `SkylarCoreTest`/`SkylarCorePhase3Test` for
     * the pattern (generate an EC keypair, register it under a
     * policy-signer identity, sign the canonical payload).
     */
    fun initialize(
        authorityArtifact: SignedPolicyArtifact? = null,
        routingArtifact: SignedPolicyArtifact? = null,
        parseAuthMatrix: (String) -> Map<String, Map<String, Set<String>>> = { emptyMap() },
        parseRoutingTable: (String) -> Map<String, String> = { emptyMap() }
    ): Result<Unit> {
        synchronized(lock) {
            if (authorityArtifact == null || routingArtifact == null) {
                Logger.w(TAG, "No signed policy artefacts supplied — initializing fail-closed (default-deny)")
                authMatrix = AuthorizationMatrix.EMPTY
                routingTable = RoutingTable.EMPTY
                isInitialized = true // initialized INTO a safe, fully-deny state — not an error state
                return Result.Success(Unit)
            }

            val matrixResult = policyReader.readAuthorizationMatrix(authorityArtifact, parseAuthMatrix)
            val routingResult = policyReader.readRoutingTable(routingArtifact, parseRoutingTable)

            if (matrixResult.isError || routingResult.isError) {
                Logger.e(TAG, "Signed policy artefact verification failed — failing closed to EMPTY")
                authMatrix = AuthorizationMatrix.EMPTY
                routingTable = RoutingTable.EMPTY
                isInitialized = true
                return Result.Error("Policy verification failed", errorCode = "POLICY_VERIFICATION_FAILED")
            }

            authMatrix = matrixResult.getOrNull() ?: AuthorizationMatrix.EMPTY
            routingTable = routingResult.getOrNull() ?: RoutingTable.EMPTY
            isInitialized = true
            Logger.i(TAG, "Skylar Core initialized with verified policy: ${authMatrix.callerCount()} callers, ${routingTable.routeCount()} routes")
            return Result.Success(Unit)
        }
    }

    fun processEnvelope(envelope: RequestEnvelope): Result<Map<String, Any?>> {
        val startTime = System.currentTimeMillis()
        Logger.i(TAG, "--> Pipeline START: caller='${envelope.callerId}', capability='${envelope.capability}', nonce='${envelope.nonce}'")

        val verifyResult = verifier.verify(envelope)
        if (verifyResult.isFailure) {
            val failure = verifyResult.failureOrNull()
            return deny(envelope, "Envelope verification failed: ${failure?.reason}", startTime, failure?.errorCode ?: "ENVELOPE_INVALID")
        }

        // Signature is verified BEFORE the nonce is checked-and-marked (not the
        // reverse): marking a nonce "seen" for an envelope whose signature
        // hasn't been confirmed yet would let an attacker with no valid
        // signature still consume a legitimate caller's future nonce value,
        // a denial-of-service vector against that caller. See addenda entry
        // "Nonce-check ordering relative to signature verification".
        val isNewNonce = nonceCache.checkAndMarkSeen(envelope.callerId, envelope.nonce, envelope.expiresAt)
        if (!isNewNonce) {
            return deny(envelope, "Replay detected for nonce '${envelope.nonce}'", startTime, "REPLAY_DETECTED")
        }

        val isAuthorized = synchronized(lock) {
            authMatrix.isAuthorized(envelope.callerId, envelope.capability, envelope.scope)
        }
        if (!isAuthorized) {
            return deny(envelope, "Caller '${envelope.callerId}' not authorized for '${envelope.capability}'", startTime, "UNAUTHORIZED")
        }

        val target = synchronized(lock) { routingTable.resolveTarget(envelope.capability) }
        if (target == null) {
            return deny(envelope, "No routing target for capability '${envelope.capability}'", startTime, "UNMAPPED_CAPABILITY")
        }

        Logger.i(TAG, "Dispatching to target '$target' for capability '${envelope.capability}'")
        val dispatchResult = dispatcher.dispatch(target, envelope.capability, envelope.params)
        val duration = System.currentTimeMillis() - startTime

        // decision=ALLOW here reflects that the request WAS authorized and
        // dispatched — a downstream execution failure at the target is a
        // separate concern captured in `reason`, not a change to this
        // gateway's own allow/deny verdict.
        val reason = if (dispatchResult is Result.Success) {
            "Authorized and dispatched to $target"
        } else {
            "Authorized to $target but execution failed: ${(dispatchResult as Result.Error).message}"
        }
        auditLogger.record(
            AuditRecord(
                callerId = envelope.callerId,
                capability = envelope.capability,
                decision = AuditRecord.Decision.ALLOW,
                reason = reason,
                nonce = envelope.nonce,
                envelopeHash = envelope.workflowHash,
                target = target,
                executionTimeMs = duration,
                policyVersion = synchronized(lock) { authMatrix.version }
            )
        )
        Logger.i(TAG, "<-- Pipeline END: capability='${envelope.capability}' target='$target' in ${duration}ms")
        return dispatchResult
    }

    private fun deny(envelope: RequestEnvelope, reason: String, startTime: Long, code: String): Result.Error {
        Logger.w(TAG, reason)
        auditLogger.record(
            AuditRecord(
                callerId = envelope.callerId,
                capability = envelope.capability,
                decision = AuditRecord.Decision.DENY,
                reason = reason,
                nonce = envelope.nonce,
                envelopeHash = envelope.workflowHash,
                executionTimeMs = System.currentTimeMillis() - startTime,
                policyVersion = synchronized(lock) { authMatrix.version }
            )
        )
        return Result.Error(reason, errorCode = code)
    }

    fun isReady(): Boolean = synchronized(lock) { isInitialized }
}
