package com.inscopelabs.abx.skylar.policy

import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * In-memory representation of the Routing Table: capability name ->
 * concrete execution target (e.g. "starlight", "sfm", "xtools").
 *
 * Security Contract:
 * - Default-deny: unknown capabilities resolve to `null` and are
 *   rejected before dispatch, never routed anywhere by fallback.
 * - Routing destination is never resolved from caller-supplied input,
 *   only from this table.
 * - As with [AuthorizationMatrix], the `signature` field that used to
 *   live directly on this class is gone — see [SignedPolicyArtifact]
 *   and [PolicyLoader] for where that check now actually happens.
 */
class RoutingTable(
    val version: String,
    private val routes: Map<String, String> = emptyMap()
) {
    companion object {
        private const val TAG = "SkylarRoutingTable"
        val EMPTY = RoutingTable("0.0.0", emptyMap())
    }

    fun resolveTarget(capability: String): String? {
        val target = routes[capability]
        if (target == null) {
            Logger.w(TAG, "Routing resolution FAILED: capability '$capability' unknown (default-deny)")
        } else {
            Logger.i(TAG, "Routing resolution SUCCESS: capability '$capability' -> '$target'")
        }
        return target
    }

    fun hasRoute(capability: String): Boolean = routes.containsKey(capability)
    fun supportedCapabilities(): Set<String> = routes.keys
    fun routeCount(): Int = routes.size
}
