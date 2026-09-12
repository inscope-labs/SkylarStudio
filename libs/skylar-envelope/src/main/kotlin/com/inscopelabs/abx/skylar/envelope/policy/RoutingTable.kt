package com.inscopelabs.abx.skylar.envelope.policy

import com.inscopelabs.abx.skylar.envelope.EnvelopeLog

/**
 * In-memory representation of the Routing Table: capability name -> concrete execution target
 * (architecture doc §3).
 *
 * Enforces default-deny: unknown capabilities resolve to null.
 */
class RoutingTable(
    val version: String,
    private val routes: Map<String, String> = emptyMap()
) {
    companion object {
        private const val TAG = "RoutingTable"
        val EMPTY = RoutingTable("0.0.0", emptyMap())
    }

    fun resolveTarget(capability: String): String? {
        val target = routes[capability]
        if (target == null) {
            EnvelopeLog.w(TAG, "Routing resolution FAILED: capability '$capability' unknown (default-deny)")
        } else {
            EnvelopeLog.i(TAG, "Routing resolution SUCCESS: capability '$capability' -> '$target'")
        }
        return target
    }

    fun hasRoute(capability: String): Boolean = routes.containsKey(capability)
    fun supportedCapabilities(): Set<String> = routes.keys
    fun routeCount(): Int = routes.size
    fun asMap(): Map<String, String> = routes
}
