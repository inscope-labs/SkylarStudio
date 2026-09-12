package com.inscopelabs.abx.skylar.mesh

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for Skylar's embedded userspace Tailscale mesh node (Lane A).
 *
 * Phase 2 (libtailscale critical-path spike) scope only:
 *  - start / stop / status / auth-key injection (phased-development-plan
 *    Phase 2 §2, in scope)
 *  - no `VpnService`, no system-VPN privilege (architecture doc §1's
 *    Lane A implementation note; Phase 2 validation criterion V2.2)
 *  - transport credential only — never substitutable for a
 *    request-signing credential (architecture doc §5)
 *
 * Concrete implementations MUST NOT declare a `VpnService` in the
 * manifest and MUST NOT request the system VPN slot at runtime.
 * Validation criterion V2.2 depends on this being verifiable by manifest
 * inspection alone.
 *
 * Full Skylar request handling (verify/authorize/route/audit) is
 * explicitly out of scope for this interface and for Phase 2 as a whole
 * (phased-development-plan Phase 2 §2, "Out of Scope").
 */
interface MeshNode {

    /** Current lifecycle state, observable for diagnostics/UI. */
    val state: StateFlow<MeshNodeState>

    /**
     * Start the userspace node and join the tailnet using [authKey].
     *
     * [authKey] MUST be a short-lived, single-use Tailscale auth key
     * (Phase 2 §4 work item 4) — never a long-lived key, and never
     * persisted by an implementation of this interface. Storage and
     * rotation of the underlying transport credential is a separate,
     * explicit Phase 2 deliverable (§4 work item 6), not implied by
     * this method.
     */
    suspend fun start(authKey: String)

    /**
     * Stop the node and release its tailnet session.
     *
     * Must be safe to call when already in [MeshNodeState.Stopped] — a
     * no-op in that case, not an error. Phase 2's stress-test work item
     * (§4 work item 7) exercises start/stop/process-death/restart, so
     * idempotency here is load-bearing, not incidental.
     */
    suspend fun stop()

    /** True only when [state] is currently [MeshNodeState.Running]. */
    fun isRunning(): Boolean
}
