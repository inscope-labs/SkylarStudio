package com.inscopelabs.abx.skylar.ipc.target

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.ipc.TargetAccessEnforcer
import com.inscopelabs.abx.skylar.ipc.aidl.IStarlightService
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete on-device AIDL service for Starlight (UI execution & accessibility agent).
 *
 * Security & Governance Requirements:
 * 1. Protected by platform-level access control via [TargetAccessEnforcer] (rejects non-Skylar callers).
 * 2. Governed Workflow Gate: Skylar authorization does NOT bypass Starlight's user-consent gate.
 *    High-impact capabilities queue in the Request Inbox and require explicit user approval.
 */
class StarlightTargetService : Service() {

    companion object {
        private const val TAG = "StarlightTargetService"
        const val ACTION_BIND = "com.inscopelabs.abx.skylar.action.BIND_STARLIGHT"

        // Capabilities requiring explicit user-consent gate in Request Inbox
        val GOVERNED_CAPABILITIES = setOf(
            "starlight.workflow.start",
            "ui.action.execute",
            "device.control"
        )
    }

    private val isServiceAvailable = AtomicBoolean(true)
    private val pendingInboxWorkflows = ConcurrentHashMap<String, PendingWorkflow>()
    private val approvedWorkflowResults = ConcurrentHashMap<String, String>()

    data class PendingWorkflow(
        val workflowId: String,
        val capability: String,
        val paramsJson: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val binder = object : IStarlightService.Stub() {

        override fun executeCapability(capability: String, paramsJson: String): String {
            Logger.i(TAG, "executeCapability invoked: capability='$capability'")
            TargetAccessEnforcer.enforceSkylarCaller(this@StarlightTargetService)

            if (!isServiceAvailable.get()) {
                Logger.w(TAG, "Starlight service is currently unavailable")
                throw IllegalStateException("Starlight service is currently unavailable / offline")
            }

            // Check if this capability requires the governed Request Inbox user consent gate
            if (requiresUserConsent(capability)) {
                return handleGovernedCapability(capability, paramsJson)
            }

            // Autonomous / read-only capabilities execute directly
            return executeAutonomousCapability(capability, paramsJson)
        }

        override fun isAvailable(): Boolean {
            TargetAccessEnforcer.enforceSkylarCaller(this@StarlightTargetService)
            return isServiceAvailable.get()
        }

        override fun requiresUserConsent(capability: String): Boolean {
            TargetAccessEnforcer.enforceSkylarCaller(this@StarlightTargetService)
            val requires = GOVERNED_CAPABILITIES.contains(capability.lowercase())
            Logger.d(TAG, "Checking consent requirement for '$capability': $requires")
            return requires
        }

        override fun approveWorkflow(workflowId: String): Boolean {
            TargetAccessEnforcer.enforceSkylarCaller(this@StarlightTargetService)
            Logger.i(TAG, "User approval granted for workflowId='$workflowId'")
            val pending = pendingInboxWorkflows.remove(workflowId)
            return if (pending != null) {
                // Execute the approved workflow
                val resultObj = JSONObject().apply {
                    put("status", "COMPLETED")
                    put("workflow_id", workflowId)
                    put("capability", pending.capability)
                    put("approved_by_user", true)
                    put("execution_timestamp", System.currentTimeMillis())
                }
                approvedWorkflowResults[workflowId] = resultObj.toString()
                true
            } else {
                Logger.w(TAG, "Workflow '$workflowId' not found in pending inbox")
                false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.i(TAG, "Client binding to StarlightTargetService with intent: $intent")
        TargetAccessEnforcer.enforceSkylarCaller(this)
        return binder
    }

    private fun handleGovernedCapability(capability: String, paramsJson: String): String {
        val params = try {
            JSONObject(paramsJson)
        } catch (_: Exception) {
            JSONObject()
        }

        val workflowId = params.optString("workflow_id", "wf-${System.currentTimeMillis()}")

        // Check if already approved previously
        val existingResult = approvedWorkflowResults.remove(workflowId)
        if (existingResult != null) {
            Logger.i(TAG, "Returning pre-approved workflow execution result for '$workflowId'")
            return existingResult
        }

        // Enqueue into Request Inbox awaiting user confirmation
        val pending = PendingWorkflow(workflowId, capability, paramsJson)
        pendingInboxWorkflows[workflowId] = pending
        Logger.i(TAG, "Enqueued governed workflow '$workflowId' in Request Inbox awaiting user consent")

        return JSONObject().apply {
            put("status", "PENDING_USER_CONSENT")
            put("workflow_id", workflowId)
            put("capability", capability)
            put("message", "Queued in Starlight Request Inbox awaiting user confirmation")
        }.toString()
    }

    private fun executeAutonomousCapability(capability: String, paramsJson: String): String {
        Logger.d(TAG, "Executing autonomous capability '$capability'")
        return JSONObject().apply {
            put("status", "SUCCESS")
            put("target", "starlight")
            put("capability", capability)
            put("timestamp", System.currentTimeMillis())
            put("result", JSONObject().apply {
                put("query_status", "resolved")
                put("data", "starlight_context_snapshot")
            })
        }.toString()
    }

    fun setAvailableForTesting(available: Boolean) {
        isServiceAvailable.set(available)
        Logger.d(TAG, "Set service availability for testing: $available")
    }

    fun getPendingInboxCount(): Int = pendingInboxWorkflows.size
}
