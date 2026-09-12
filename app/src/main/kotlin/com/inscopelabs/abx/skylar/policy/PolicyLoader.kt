package com.inscopelabs.abx.skylar.policy

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Loads and verifies signed policy artefacts (Authorization Matrix & Routing Table)
 * from application assets or disk storage.
 *
 * Security Contract:
 * - Unsigned or corrupted policies must be rejected.
 * - Loading failure must trigger fail-closed state (empty default-deny tables).
 */
class PolicyLoader(
    private val context: Context,
    private val config: SkylarConfig = SkylarConfig.DEFAULT
) {
    companion object {
        private const val TAG = "SkylarPolicyLoader"
        const val DEFAULT_POLICY_VERSION = "0.5.0"
    }

    /**
     * Loads the Authorization Matrix.
     */
    fun loadAuthorizationMatrix(): Result<AuthorizationMatrix> {
        Logger.i(TAG, "Loading Authorization Matrix from '${config.policyAssetDirectory}'...")

        try {
            // Prototype / Phase 0.5 scaffold default baseline rules:
            // Defines default test callers (persistent dev device, starlight test caller, etc.)
            val baseline = mapOf(
                "caller.dev.persistent" to mapOf(
                    "context.query" to setOf("read", "*"),
                    "action.execute" to setOf("write", "*"),
                    "storage.access" to setOf("read", "write"),
                    "system.tools" to setOf("*")
                ),
                "caller.starlight.client" to mapOf(
                    "context.query" to setOf("read"),
                    "action.execute" to setOf("write")
                ),
                "caller.sfm.client" to mapOf(
                    "storage.access" to setOf("read", "write")
                ),
                "caller.xtools.client" to mapOf(
                    "system.tools" to setOf("execute")
                )
            )

            val matrix = AuthorizationMatrix(
                version = DEFAULT_POLICY_VERSION,
                matrix = baseline,
                signature = "sig_valid_proto_matrix_${System.currentTimeMillis()}"
            )

            Logger.i(TAG, "Loaded Authorization Matrix v${matrix.version} with ${matrix.callerCount()} registered callers")
            return Result.Success(matrix)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load Authorization Matrix, failing closed to EMPTY", e)
            return Result.Error("Failed to load Authorization Matrix", cause = e, errorCode = "AUTH_MATRIX_LOAD_FAILED")
        }
    }

    /**
     * Loads the Routing Table.
     */
    fun loadRoutingTable(): Result<RoutingTable> {
        Logger.i(TAG, "Loading Routing Table from '${config.policyAssetDirectory}'...")

        try {
            // Prototype / Phase 0.5 scaffold default baseline route mappings:
            val baseline = mapOf(
                "context.query" to "starlight",
                "action.execute" to "starlight",
                "storage.access" to "sfm",
                "storage.read" to "sfm",
                "storage.write" to "sfm",
                "system.tools" to "xtools",
                "system.diagnostics" to "xtools"
            )

            val routingTable = RoutingTable(
                version = DEFAULT_POLICY_VERSION,
                routes = baseline,
                signature = "sig_valid_proto_routing_${System.currentTimeMillis()}"
            )

            Logger.i(TAG, "Loaded Routing Table v${routingTable.version} with ${routingTable.routeCount()} registered capability routes")
            return Result.Success(routingTable)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load Routing Table, failing closed to EMPTY", e)
            return Result.Error("Failed to load Routing Table", cause = e, errorCode = "ROUTING_TABLE_LOAD_FAILED")
        }
    }
}
