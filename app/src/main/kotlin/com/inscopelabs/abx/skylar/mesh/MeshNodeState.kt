package com.inscopelabs.abx.skylar.mesh

/**
 * Lifecycle state of Skylar's embedded userspace mesh node.
 *
 * This node holds a transport credential only (architecture doc §5) and
 * must never be conflated with request-signing authority. It provides
 * Lane A reachability (architecture doc §1) and nothing else — it does
 * not verify, authorize, or route any request itself.
 */
sealed class MeshNodeState {

    /** No node process/session exists. Initial state and post-[stop] state. */
    object Stopped : MeshNodeState()

    /** Start has been requested; the node is attempting to join the tailnet. */
    object Starting : MeshNodeState()

    /** Node holds a valid tailnet session and is reachable on Lane A. */
    data class Running(val tailscaleIp: String?) : MeshNodeState()

    /** Node failed to start, or lost a previously-held session. */
    data class Error(val reason: String) : MeshNodeState()
}
