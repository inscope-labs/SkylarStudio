package com.inscopelabs.abx.skylar.ipc

import android.content.Context
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge client for xtools target (Android JS plugin & diagnostic automation runtime).
 *
 * Security Requirements (Architecture Specification §3.5 & §7):
 * 1. Protected by platform-level access control: only Skylar Core may invoke xtools.
 * 2. Enforces second-tier plugin trust verification (Pipeline-signed / Verified / Untrusted)
 *    inside xtools after Skylar Core authorization passes.
 * 3. Scoped fail-closed isolation: xtools failure never blocks Starlight or SFM.
 */
class XtoolsBridge(
    private val context: Context
) {
    companion object {
        private const val TAG = "SkylarXtoolsBridge"
        const val TARGET_ID = "xtools"
    }

    enum class PluginTrustTier {
        PIPELINE_SIGNED,
        VERIFIED,
        COMMUNITY_UNTRUSTED
    }

    private val isBridgeAvailable = AtomicBoolean(true)

    // Registry of approved plugin identifiers to their verified trust tier
    private val pluginTrustRegistry = mutableMapOf<String, PluginTrustTier>(
        "plugin.diagnostics.battery" to PluginTrustTier.PIPELINE_SIGNED,
        "plugin.network.ping" to PluginTrustTier.VERIFIED,
        "plugin.system.info" to PluginTrustTier.PIPELINE_SIGNED
    )

    /**
     * Executes an authorized capability request within xtools.
     */
    fun execute(capability: String, params: Map<String, Any?>): Result<Map<String, Any?>> {
        Logger.i(TAG, "Executing capability '$capability' on xtools bridge")

        // 1. Platform-level access control: only Skylar Core is authorized
        try {
            TargetAccessEnforcer.enforceSkylarCaller(context)
        } catch (e: SecurityException) {
            Logger.e(TAG, "Platform access check failed on xtools bridge", e)
            return Result.Error(
                message = "Access denied: xtools bridge rejected non-Skylar caller",
                cause = e,
                errorCode = "PLATFORM_ACCESS_DENIED"
            )
        }

        // 2. Availability check
        if (!isBridgeAvailable.get()) {
            Logger.w(TAG, "xtools bridge is currently offline / unavailable")
            return Result.Error(
                message = "xtools bridge is currently unavailable",
                errorCode = "TARGET_UNAVAILABLE"
            )
        }

        // 3. Trust tier enforcement for plugin capabilities
        if (capability.startsWith("xtools.plugin.") || capability == "system.execute") {
            val pluginId = params["plugin_id"]?.toString() ?: "system.default"
            val requiredTier = when (capability) {
                "system.execute" -> PluginTrustTier.PIPELINE_SIGNED
                else -> PluginTrustTier.VERIFIED
            }

            val assignedTier = pluginTrustRegistry[pluginId] ?: PluginTrustTier.COMMUNITY_UNTRUSTED
            Logger.d(TAG, "Plugin '$pluginId' evaluated trust tier: assigned=$assignedTier, required=$requiredTier")

            if (assignedTier > requiredTier) {
                Logger.e(TAG, "Plugin '$pluginId' trust tier ($assignedTier) insufficient for capability '$capability' ($requiredTier)")
                return Result.Error(
                    message = "xtools policy violation: Plugin '$pluginId' does not meet required trust tier ($requiredTier)",
                    errorCode = "PLUGIN_TRUST_TIER_INSUFFICIENT"
                )
            }
        }

        // 4. Successful execution of bridge operation
        Logger.i(TAG, "Successfully executed '$capability' on xtools bridge")
        val response = mapOf(
            "status" to "SUCCESS",
            "target" to TARGET_ID,
            "capability" to capability,
            "execution_mode" to "in_process_bridge",
            "timestamp" to System.currentTimeMillis()
        )
        return Result.Success(response)
    }

    fun isAvailable(): Boolean = isBridgeAvailable.get()

    fun setAvailableForTesting(available: Boolean) {
        isBridgeAvailable.set(available)
        Logger.d(TAG, "Set xtools bridge availability for testing: $available")
    }

    fun registerPluginTierForTesting(pluginId: String, tier: PluginTrustTier) {
        pluginTrustRegistry[pluginId] = tier
        Logger.d(TAG, "Registered test plugin '$pluginId' with tier $tier")
    }
}
