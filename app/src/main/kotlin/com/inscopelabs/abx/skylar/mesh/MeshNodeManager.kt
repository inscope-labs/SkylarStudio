package com.inscopelabs.abx.skylar.mesh

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Manages the lifecycle of the embedded userspace mesh node (Lane A transport).
 *
 * Security Contract:
 * - The mesh node runs in userspace within the process; it does not act as a system VPN.
 * - Confers network reachability only.
 * - Transport keys and request-signing keys are strictly isolated.
 */
class MeshNodeManager(
    private val context: Context,
    private val config: SkylarConfig = SkylarConfig.DEFAULT
) {
    companion object {
        private const val TAG = "SkylarMeshNode"
    }

    enum class NodeState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        STOPPING,
        STOPPED,
        ERROR
    }

    private val lock = Any()
    private var currentState: NodeState = NodeState.DISCONNECTED
    private var activeCredential: TransportCredential? = null
    private var nodeIpAddress: String? = null

    val state: NodeState
        get() = synchronized(lock) { currentState }

    val ipAddress: String?
        get() = synchronized(lock) { nodeIpAddress }

    val credential: TransportCredential?
        get() = synchronized(lock) { activeCredential }

    /**
     * Configures the transport credential for mesh enrollment.
     */
    fun setCredential(credential: TransportCredential): Result<Unit> {
        Logger.i(TAG, "Configuring mesh transport credential: id=${credential.credentialId}, node=${credential.meshNodeId}")
        if (!credential.isValid()) {
            Logger.w(TAG, "Rejecting invalid or expired transport credential: id=${credential.credentialId}")
            return Result.Error("Transport credential is expired or revoked", errorCode = "CREDENTIAL_INVALID")
        }

        synchronized(lock) {
            activeCredential = credential
        }
        return Result.Success(Unit)
    }

    /**
     * Starts the embedded mesh node with the registered credential.
     */
    fun start(): Result<String> {
        synchronized(lock) {
            Logger.i(TAG, "Starting mesh node lifecycle. Current state: $currentState")

            val cred = activeCredential
            if (cred == null || !cred.isValid()) {
                Logger.e(TAG, "Cannot start mesh node without valid transport credential")
                currentState = NodeState.ERROR
                return Result.Error("Missing or invalid transport credential", errorCode = "NO_VALID_CREDENTIAL")
            }

            if (currentState == NodeState.CONNECTED) {
                Logger.d(TAG, "Mesh node is already connected at IP: $nodeIpAddress")
                return Result.Success(nodeIpAddress ?: "100.64.0.1")
            }

            currentState = NodeState.CONNECTING
            Logger.i(TAG, "Mesh node connecting to private mesh on port ${config.meshPort}...")

            // Prototype / Phase 0.5 scaffold: simulates node binding and assigned IP
            // In Phase 2, this interfaces directly with libtailscale native bindings.
            nodeIpAddress = "100.64.0.${(10..250).random()}"
            currentState = NodeState.CONNECTED

            Logger.i(TAG, "Mesh node successfully connected. Assigned mesh IP: $nodeIpAddress (node: ${config.meshNodeName})")
            return Result.Success(nodeIpAddress!!)
        }
    }

    /**
     * Stops the embedded mesh node gracefully.
     */
    fun stop(): Result<Unit> {
        synchronized(lock) {
            Logger.i(TAG, "Stopping mesh node. Current state: $currentState")
            if (currentState == NodeState.DISCONNECTED || currentState == NodeState.STOPPED) {
                Logger.d(TAG, "Mesh node already inactive")
                return Result.Success(Unit)
            }

            currentState = NodeState.STOPPING
            nodeIpAddress = null
            currentState = NodeState.STOPPED
            Logger.i(TAG, "Mesh node gracefully stopped")
            return Result.Success(Unit)
        }
    }

    /**
     * Checks whether the mesh node is currently healthy and reachable.
     */
    fun isReachable(): Boolean {
        synchronized(lock) {
            return currentState == NodeState.CONNECTED && activeCredential?.isValid() == true
        }
    }
}
