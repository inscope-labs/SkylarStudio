package com.inscopelabs.abx.skylar.policy

import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * In-memory representation of the signed Routing Table.
 * Maps capability names to concrete execution targets (e.g. "starlight", "sfm", "xtools").
 *
 * Security Contract:
 * - Default-deny: unknown capabilities return null and are rejected before dispatch.
 * - Routing destination is never resolved from caller input.
 */
class RoutingTable(
    val version: String,
    private val routes: Map<String, String> = emptyMap(),
    val signature: String? = null
) {
    companion object {
        private const val TAG = "SkylarRoutingTable"
        val EMPTY = RoutingTable("0.0.0", emptyMap(), null)
    }

    /**
     * Resolves the target execution handler for a capability name.
     * Returns null if unmapped (default-deny).
     */
    fun resolveTarget(capability: String): String? {
        Logger.d(TAG, "Resolving target for capability: '$capability'")
        val target = routes[capability]
        if (target == null) {
            Logger.w(TAG, "Routing resolution FAILED: Capability '$capability' is unknown in routing table")
        } else {
            Logger.i(TAG, "Routing resolution SUCCESS: Capability '$capability' maps to target '$target'")
        }
        return target
    }

    /**
     * Checks if a capability is known in this routing table.
     */
    fun hasRoute(capability: String): Boolean = routes.containsKey(capability)

    /**
     * Returns all supported capabilities.
     */
    fun supportedCapabilities(): Set<String> = routes.keys

    /**
     * Returns total number of registered routes.
     */
    fun routeCount(): Int = routes.size
}
