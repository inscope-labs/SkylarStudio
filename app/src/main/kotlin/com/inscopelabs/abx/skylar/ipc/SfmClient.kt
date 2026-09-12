package com.inscopelabs.abx.skylar.ipc

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * IPC client for SFM (System File Manager / storage vault) target.
 *
 * Security Contract:
 * - Scoped fail-closed isolation.
 * - Platform access-controlled local IPC invocation.
 *
 * Status:
 * Target IPC bindings are scheduled for Phase 4 (AIDL / Local IPC).
 * Fails closed with an explicit error until Phase 4 connects the AIDL service.
 */
class SfmClient(
    private val context: Context
) {
    companion object {
        private const val TAG = "SkylarSfmClient"
        const val TARGET_ID = "sfm"
    }

    /**
     * Dispatches an authorized capability request to SFM via IPC.
     * Fails closed until Phase 4 AIDL integration is complete.
     */
    fun execute(capability: String, params: Map<String, Any?>): Result<Map<String, Any?>> {
        Logger.w(TAG, "SFM IPC target not yet connected — Phase 4 scope (fail-closed)")
        return Result.Error(
            message = "SFM IPC target not yet connected (Phase 4 scope)",
            errorCode = "TARGET_NOT_CONNECTED"
        )
    }

    fun isAvailable(): Boolean = false
}
