package com.inscopelabs.abx.skylar.ipc

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * IPC client stub for Starlight target (accessibility / execution agent).
 *
 * Security Contract:
 * - Local IPC surface protected by caller-UID / signature permissions.
 * - Enforces fail-closed isolation: Starlight failure does not impact SFM or xtools.
 */
class StarlightClient(
    private val context: Context
) {
    companion object {
        private const val TAG = "SkylarStarlightClient"
        const val TARGET_ID = "starlight"
    }

    private var isSimulatedOffline: Boolean = false

    /**
     * Dispatches an authorized capability request to Starlight via IPC.
     */
    fun execute(capability: String, params: Map<String, Any?>): Result<Map<String, Any?>> {
        Logger.i(TAG, "Dispatching request to Starlight: capability='$capability', paramsCount=${params.size}")

        if (isSimulatedOffline) {
            Logger.e(TAG, "Starlight service is currently unavailable (fail-closed)")
            return Result.Error("Starlight IPC target unreachable", errorCode = "TARGET_UNAVAILABLE")
        }

        return try {
            // Prototype / Phase 0.5 execution response
            val response = mapOf(
                "target" to TARGET_ID,
                "status" to "COMPLETED",
                "capability" to capability,
                "timestamp" to System.currentTimeMillis()
            )
            Logger.i(TAG, "Starlight execution completed successfully for '$capability'")
            Result.Success(response)
        } catch (e: Exception) {
            Logger.e(TAG, "Starlight IPC dispatch encountered an error", e)
            Result.Error("Starlight execution failed: ${e.message}", cause = e, errorCode = "EXECUTION_ERROR")
        }
    }

    fun setSimulatedOffline(offline: Boolean) {
        isSimulatedOffline = offline
        Logger.w(TAG, "Starlight simulated offline state set to: $offline")
    }

    fun isAvailable(): Boolean = !isSimulatedOffline
}
