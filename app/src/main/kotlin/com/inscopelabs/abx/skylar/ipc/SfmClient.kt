package com.inscopelabs.abx.skylar.ipc

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * IPC client stub for SFM (System File Manager / storage vault) target.
 *
 * Security Contract:
 * - Scoped fail-closed isolation.
 * - Platform access-controlled local IPC invocation.
 */
class SfmClient(
    private val context: Context
) {
    companion object {
        private const val TAG = "SkylarSfmClient"
        const val TARGET_ID = "sfm"
    }

    private var isSimulatedOffline: Boolean = false

    /**
     * Dispatches an authorized capability request to SFM via IPC.
     */
    fun execute(capability: String, params: Map<String, Any?>): Result<Map<String, Any?>> {
        Logger.i(TAG, "Dispatching request to SFM: capability='$capability', paramsCount=${params.size}")

        if (isSimulatedOffline) {
            Logger.e(TAG, "SFM service is currently unavailable (fail-closed)")
            return Result.Error("SFM IPC target unreachable", errorCode = "TARGET_UNAVAILABLE")
        }

        return try {
            val response = mapOf(
                "target" to TARGET_ID,
                "status" to "COMPLETED",
                "capability" to capability,
                "timestamp" to System.currentTimeMillis()
            )
            Logger.i(TAG, "SFM execution completed successfully for '$capability'")
            Result.Success(response)
        } catch (e: Exception) {
            Logger.e(TAG, "SFM IPC dispatch encountered an error", e)
            Result.Error("SFM execution failed: ${e.message}", cause = e, errorCode = "EXECUTION_ERROR")
        }
    }

    fun setSimulatedOffline(offline: Boolean) {
        isSimulatedOffline = offline
        Logger.w(TAG, "SFM simulated offline state set to: $offline")
    }

    fun isAvailable(): Boolean = !isSimulatedOffline
}
