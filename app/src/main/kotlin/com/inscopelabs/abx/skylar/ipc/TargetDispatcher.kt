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

    fun interface TargetHandler {
        fun execute(capability: String, params: Map<String, Any?>): Result<Map<String, Any?>>
    }

    private val customHandlers = mutableMapOf<String, TargetHandler>()
    private val lock = Any()

    /**
     * Registers a target handler or test stub for a target identifier.
     */
    fun registerTargetHandler(target: String, handler: TargetHandler) {
        synchronized(lock) {
            customHandlers[target.lowercase()] = handler
            Logger.d(TAG, "Registered custom target handler for '$target'")
        }
    }

    /**
     * Unregisters a target handler.
     */
    fun unregisterTargetHandler(target: String) {
        synchronized(lock) {
            customHandlers.remove(target.lowercase())
            Logger.d(TAG, "Unregistered target handler for '$target'")
        }
    }

    /**
     * Dispatches an authorized capability invocation to the specified target.
     * Enforces fail-closed target isolation with exception safety.
     */
    fun dispatch(
        target: String,
        capability: String,
        params: Map<String, Any?>
    ): Result<Map<String, Any?>> {
        val targetKey = target.lowercase()
        Logger.i(TAG, "Dispatching capability '$capability' to target '$targetKey'")

        return try {
            val custom = synchronized(lock) { customHandlers[targetKey] }
            if (custom != null) {
                return custom.execute(capability, params)
            }

            when (targetKey) {
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
        } catch (e: Exception) {
            Logger.e(TAG, "Unhandled exception executing target '$target' for capability '$capability'", e)
            Result.Error(
                message = "Target execution failed with exception: ${e.message}",
                cause = e,
                errorCode = "TARGET_EXECUTION_EXCEPTION"
            )
        }
    }

    /**
     * Checks if a target is currently available.
     */
    fun isTargetAvailable(target: String): Boolean {
        val targetKey = target.lowercase()
        synchronized(lock) {
            if (customHandlers.containsKey(targetKey)) return true
        }
        return when (targetKey) {
            StarlightClient.TARGET_ID -> starlightClient.isAvailable()
            SfmClient.TARGET_ID -> sfmClient.isAvailable()
            XtoolsBridge.TARGET_ID -> xtoolsBridge.isAvailable()
            else -> false
        }
    }
}
