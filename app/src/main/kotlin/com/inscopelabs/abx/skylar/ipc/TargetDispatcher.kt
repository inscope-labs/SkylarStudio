package com.inscopelabs.abx.skylar.ipc

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Dispatcher for routing authorized capability invocations to the designated target client.
 *
 * Security Contract:
 * - Scoped fail-closed isolation: failure of one target does not degrade or halt routing to other targets.
 */
class TargetDispatcher(
    private val context: Context,
    val starlightClient: StarlightClient = StarlightClient(context),
    val sfmClient: SfmClient = SfmClient(context),
    val xtoolsBridge: XtoolsBridge = XtoolsBridge(context)
) {
    companion object {
        private const val TAG = "SkylarTargetDispatcher"
    }

    /**
     * Dispatches an authorized capability invocation to the specified target.
     */
    fun dispatch(
        target: String,
        capability: String,
        params: Map<String, Any?>
    ): Result<Map<String, Any?>> {
        Logger.i(TAG, "Dispatching capability '$capability' to target '$target'")

        return when (target.lowercase()) {
            StarlightClient.TARGET_ID -> {
                starlightClient.execute(capability, params)
            }
            SfmClient.TARGET_ID -> {
                sfmClient.execute(capability, params)
            }
            XtoolsBridge.TARGET_ID -> {
                xtoolsBridge.execute(capability, params)
            }
            else -> {
                Logger.e(TAG, "Unknown target '$target' requested for capability '$capability'")
                Result.Error("Unknown execution target: $target", errorCode = "UNKNOWN_TARGET")
            }
        }
    }

    /**
     * Checks if a target is currently available.
     */
    fun isTargetAvailable(target: String): Boolean {
        return when (target.lowercase()) {
            StarlightClient.TARGET_ID -> starlightClient.isAvailable()
            SfmClient.TARGET_ID -> sfmClient.isAvailable()
            XtoolsBridge.TARGET_ID -> xtoolsBridge.isAvailable()
            else -> false
        }
    }
}
