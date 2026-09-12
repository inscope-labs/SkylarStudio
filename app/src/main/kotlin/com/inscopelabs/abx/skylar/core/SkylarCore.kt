package com.inscopelabs.abx.skylar.core

import android.content.Context
import com.inscopelabs.abx.skylar.audit.AuditLogger
import com.inscopelabs.abx.skylar.audit.AuditRecord
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.ipc.TargetDispatcher
import com.inscopelabs.abx.skylar.mesh.MeshNodeManager
import com.inscopelabs.abx.skylar.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.policy.PolicyLoader
import com.inscopelabs.abx.skylar.policy.RoutingTable

/**
 * The single Policy Enforcement Point for the Skylar Context Gateway.
 *
 * Security Contract:
 * - Executes the strictly ordered pipeline:
 *   1. Envelope Verification (timestamps, workflow hash, signature)
 *   2. Replay & Expiry Protection (atomic NonceCache lookup & record)
 *   3. Authorization Matrix Check (caller_id -> {capability: scope}, default-deny)
 *   4. Routing Table Resolution (capability -> target, default-deny)
 *   5. Scoped Fail-Closed Dispatch (TargetDispatcher)
 *   6. Append-Only Audit Logging (AuditLogger)
 * - Never executes business logic directly; always delegates to targets.
 */
class SkylarCore(
    private val context: Context,
    val config: SkylarConfig = SkylarConfig.DEFAULT,
    val verifier: EnvelopeVerifier = EnvelopeVerifier(config),
    val nonceCache: NonceCache = NonceCache(context, config),
    val auditLogger: AuditLogger = AuditLogger(context, config),
    val dispatcher: TargetDispatcher = TargetDispatcher(context),
    val meshManager: MeshNodeManager = MeshNodeManager(context, config)
) {
    companion object {
        private const val TAG = "SkylarCore"
    }

    private val lock = Any()
    private var authMatrix: AuthorizationMatrix = AuthorizationMatrix.EMPTY
    private var routingTable: RoutingTable = RoutingTable.EMPTY
    private var isInitialized = false

    /**
     * Initializes Skylar Core policies and subsystem state.
     */
    fun initialize(): Result<Unit> {
        synchronized(lock) {
            Logger.i(TAG, "Initializing Skylar Core policy engine...")
            val loader = PolicyLoader(context, config)

            val matrixResult = loader.loadAuthorizationMatrix()
            val routingResult = loader.loadRoutingTable()

            if (matrixResult.isError || routingResult.isError) {
                Logger.e(TAG, "Failed to load signed policy artefacts. Core failing closed.")
                isInitialized = false
                return Result.Error("Policy initialization failed", errorCode = "INIT_FAILED")
            }

            authMatrix = matrixResult.getOrNull() ?: AuthorizationMatrix.EMPTY
            routingTable = routingResult.getOrNull() ?: RoutingTable.EMPTY
            isInitialized = true

            Logger.i(TAG, "Skylar Core initialized successfully with ${authMatrix.callerCount()} callers and ${routingTable.routeCount()} routes")
            return Result.Success(Unit)
        }
    }

    /**
     * Processes an incoming signed request envelope through the enforcement pipeline.
     */
    fun processEnvelope(envelope: RequestEnvelope): Result<Map<String, Any?>> {
        val startTime = System.currentTimeMillis()
        Logger.i(TAG, "--> Pipeline START: caller='${envelope.callerId}', capability='${envelope.capability}', nonce='${envelope.nonce}'")

        // 1. Envelope Verification (timestamps, canonical workflow hash, signature)
        val verifyResult = verifier.verify(envelope)
        if (verifyResult is Result.Error) {
            recordAudit(
                envelope = envelope,
                decision = AuditRecord.Decision.DENY,
                reason = "Envelope verification failed: ${verifyResult.message}",
                startTime = startTime
            )
            return verifyResult
        }

        // 2. Replay Protection (cheapest persistent check)
        val isNewNonce = nonceCache.checkAndRecord(envelope.callerId, envelope.nonce, envelope.expiresAt)
        if (!isNewNonce) {
            val reason = "Replay detected for caller '${envelope.callerId}' with nonce '${envelope.nonce}'"
            Logger.w(TAG, reason)
            recordAudit(
                envelope = envelope,
                decision = AuditRecord.Decision.DENY,
                reason = reason,
                startTime = startTime
            )
            return Result.Error(reason, errorCode = "REPLAY_DETECTED")
        }

        // 3. Authorization Matrix Check (default-deny)
        val isAuthorized = synchronized(lock) {
            authMatrix.isAuthorized(envelope.callerId, envelope.capability, envelope.scope)
        }
        if (!isAuthorized) {
            val reason = "Caller '${envelope.callerId}' not authorized for capability '${envelope.capability}'"
            Logger.w(TAG, reason)
            recordAudit(
                envelope = envelope,
                decision = AuditRecord.Decision.DENY,
                reason = reason,
                startTime = startTime
            )
            return Result.Error(reason, errorCode = "UNAUTHORIZED")
        }

        // 4. Routing Table Resolution (default-deny)
        val target = synchronized(lock) {
            routingTable.resolveTarget(envelope.capability)
        }
        if (target == null) {
            val reason = "No routing target configured for capability '${envelope.capability}'"
            Logger.w(TAG, reason)
            recordAudit(
                envelope = envelope,
                decision = AuditRecord.Decision.DENY,
                reason = reason,
                startTime = startTime
            )
            return Result.Error(reason, errorCode = "UNMAPPED_CAPABILITY")
        }

        // 5. Target Dispatch
        Logger.i(TAG, "Dispatching to target '$target' for capability '${envelope.capability}'")
        val dispatchResult = dispatcher.dispatch(target, envelope.capability, envelope.params)

        val duration = System.currentTimeMillis() - startTime
        if (dispatchResult is Result.Success) {
            recordAudit(
                envelope = envelope,
                decision = AuditRecord.Decision.ALLOW,
                reason = "Successfully authorized and dispatched to $target",
                target = target,
                startTime = startTime
            )
            Logger.i(TAG, "<-- Pipeline SUCCESS: capability='${envelope.capability}' routed to '$target' in ${duration}ms")
        } else {
            val error = dispatchResult as Result.Error
            recordAudit(
                envelope = envelope,
                decision = AuditRecord.Decision.ALLOW,
                reason = "Authorized to $target but target execution failed: ${error.message}",
                target = target,
                startTime = startTime
            )
            Logger.e(TAG, "<-- Pipeline ERROR: target '$target' execution failed in ${duration}ms: ${error.message}")
        }

        return dispatchResult
    }

    private fun recordAudit(
        envelope: RequestEnvelope,
        decision: AuditRecord.Decision,
        reason: String,
        target: String? = null,
        startTime: Long
    ) {
        val duration = System.currentTimeMillis() - startTime
        val record = AuditRecord(
            callerId = envelope.callerId,
            capability = envelope.capability,
            decision = decision,
            reason = reason,
            nonce = envelope.nonce,
            envelopeHash = envelope.workflowHash,
            target = target,
            executionTimeMs = duration
        )
        auditLogger.record(record)
    }

    /**
     * Hot-reloads policy artefacts.
     */
    fun reloadPolicy(newMatrix: AuthorizationMatrix, newRoutingTable: RoutingTable) {
        synchronized(lock) {
            authMatrix = newMatrix
            routingTable = newRoutingTable
            Logger.i(TAG, "Policy updated: Matrix v${newMatrix.version}, Routing v${newRoutingTable.version}")
        }
    }

    fun isReady(): Boolean = synchronized(lock) { isInitialized }
}
