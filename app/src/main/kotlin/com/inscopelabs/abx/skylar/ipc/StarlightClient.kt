package com.inscopelabs.abx.skylar.ipc

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * IPC client for Starlight target (accessibility / execution agent).
 *
 * Security Contract:
 * - Local IPC surface protected by caller-UID / signature permissions.
 * - Enforces fail-closed isolation: Starlight failure does not impact SFM or xtools.
 *
 * Status:
 * Target IPC bindings are scheduled for Phase 4 (AIDL / Local IPC).
 * Fails closed with an explicit error until Phase 4 connects the AIDL service.
 */
class StarlightClient(
    private val context: Context
) {
    companion object {
        private const val TAG = "SkylarStarlightClient"
        const val TARGET_ID = "starlight"
    }

    /**
     * Dispatches an authorized capability request to Starlight via IPC.
     * Fails closed until Phase 4 AIDL integration is complete.
     */
    fun execute(capability: String, params: Map<String, Any?>): Result<Map<String, Any?>> {
        Logger.w(TAG, "Starlight IPC target not yet connected — Phase 4 scope (fail-closed)")
        return Result.Error(
            message = "Starlight IPC target not yet connected (Phase 4 scope)",
            errorCode = "TARGET_NOT_CONNECTED"
        )
    }

    fun isAvailable(): Boolean = false
}
