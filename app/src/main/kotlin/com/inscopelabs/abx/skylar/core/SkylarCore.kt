package com.inscopelabs.abx.skylar.core

import android.content.Context
import com.inscopelabs.abx.skylar.audit.AuditLogger
import com.inscopelabs.abx.skylar.audit.AuditRecord
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.crypto.KeyRegistry
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.ipc.TargetDispatcher
import com.inscopelabs.abx.skylar.mesh.MeshNode
import com.inscopelabs.abx.skylar.mesh.TsnetMeshNode
import com.inscopelabs.abx.skylar.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.policy.PolicyLoader
import com.inscopelabs.abx.skylar.policy.RoutingTable
import com.inscopelabs.abx.skylar.policy.SignedPolicyArtifact

/**
 * The single Policy Enforcement Point for the Skylar Context Gateway:
 * verify -> replay-check -> authorize -> route -> dispatch -> audit.
 *
 * Two behavioral changes relative to the prior prototype matter here:
 *
 * 1. [EnvelopeVerifier] now requires a real [KeyRegistry] and performs
 *    real signature verification, not a string-length check.
 * 2. [initialize] fails closed to [AuthorizationMatrix.EMPTY] /
 *    [RoutingTable.EMPTY] whenever no *verified* signed policy artefact
 *    is supplied, instead of silently loading a hardcoded fixture and
 *    reporting success. Until Phase 1's actual policy-signing pipeline
 *    exists and produces real artefacts, starting in the fail-closed
 *    (all-deny) state is the CORRECT behavior, not a bug to work around.
 */
class SkylarCore(
    private val context: Context,
    val keyRegistry: KeyRegistry,
    val config: SkylarConfig = SkylarConfig.DEFAULT,
    val verifier: EnvelopeVerifier = EnvelopeVerifier(keyRegistry, config),
    val nonceCache: NonceCache = PersistentNonceCache(context, config),
    val auditLogger: AuditLogger = AuditLogger(context, config),
    val dispatcher: TargetDispatcher = TargetDispatcher(context),
    val meshNode: MeshNode = TsnetMeshNode(),
    private val policyLoader: PolicyLoader = PolicyLoader(context, keyRegistry, config)
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

            val matrixResult = policyLoader.loadAuthorizationMatrixFromArtifact(authorityArtifact, parseAuthMatrix)
            val routingResult = policyLoader.loadRoutingTableFromArtifact(routingArtifact, parseRoutingTable)

            if (matrixResult is Result.Error || routingResult is Result.Error) {
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
        if (verifyResult is Result.Error) {
            return deny(envelope, "Envelope verification failed: ${verifyResult.message}", startTime, "ENVELOPE_INVALID")
        }

        val isNewNonce = nonceCache.checkAndRecord(envelope.callerId, envelope.nonce, envelope.expiresAt)
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

    fun reloadPolicy(newMatrix: AuthorizationMatrix, newRoutingTable: RoutingTable) {
        synchronized(lock) {
            authMatrix = newMatrix
            routingTable = newRoutingTable
            Logger.i(TAG, "Policy updated: Matrix v${newMatrix.version}, Routing v${newRoutingTable.version}")
        }
    }

    fun isReady(): Boolean = synchronized(lock) { isInitialized }
}
