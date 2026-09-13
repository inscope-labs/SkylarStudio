package com.inscopelabs.abx.skylar.ipc.target.mock

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
 * ================================ NOT REAL PHASE 4 ================================
 * THIS IS AN IN-PROCESS MOCK, NOT THE REAL STARLIGHT APP.
 *
 * This class runs inside Skylar's own APK/process/UID — declared as a
 * `<service>` in Skylar's own AndroidManifest.xml. It is NOT the real
 * `inscope-labs/Starlight` app, which per the canonical repo structure
 * doc must remain a physically separate installed app (Android's UID
 * isolation is a platform constraint, not a documentation one).
 *
 * Phase 4's actual objective ("Connect Skylar Core to the real
 * Capability Targets... using platform-enforced local IPC only" —
 * phased-development-plan Phase 4 §1) and its validation criteria
 * (V4.1-V4.6, especially V4.2: "Request from any non-Skylar UID is
 * rejected by the target's AIDL surface") require a genuinely separate
 * app with a genuinely different UID. A same-process mock can never
 * exercise that boundary, no matter how the enforcement logic reads —
 * see [TargetAccessEnforcer], whose `callingUid == myUid` fast path
 * means calls from this mock will always short-circuit as "authorized"
 * without ever reaching the signature/permission checks meant for a
 * real external caller.
 *
 * Tests against this class validate the dispatch pipeline's wiring and
 * the AIDL interface contract shape — genuinely useful — but they do
 * NOT satisfy any Phase 4 validation criterion. See
 * `docs/skylar-phase-04-real-integration-requirements.md` for what
 * actually completing Phase 4 requires.
 * =====================================================================
 *
 * Concrete in-process AIDL-shaped mock for Starlight (UI execution & accessibility agent).
 *
 * Security & Governance Requirements (as designed, not yet validated cross-process):
 * 1. Protected by platform-level access control via [TargetAccessEnforcer] (rejects non-Skylar callers).
 * 2. Governed Workflow Gate: Skylar authorization does NOT bypass Starlight's user-consent gate.
 *    High-impact capabilities queue in the Request Inbox and require explicit user approval.
 *
 * Note on the consent gate specifically: the REAL Starlight app already
 * has its own existing user-consent gate (per Phase 4 §2: "Confirmation
 * that Starlight's existing user-consent gate remains mandatory and is
 * not bypassed by Skylar authorization" — implying an existing
 * mechanism to confirm, not one to invent). The Request Inbox logic
 * below is a plausible reimplementation for pipeline-shape testing, but
 * it is not a substitute for testing against Starlight's actual gate.
 */
class MockStarlightTargetService : Service() {

    companion object {
        private const val TAG = "MockStarlightTargetService"
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
            TargetAccessEnforcer.enforceSkylarCaller(this@MockStarlightTargetService)

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
            TargetAccessEnforcer.enforceSkylarCaller(this@MockStarlightTargetService)
            return isServiceAvailable.get()
        }

        override fun requiresUserConsent(capability: String): Boolean {
            TargetAccessEnforcer.enforceSkylarCaller(this@MockStarlightTargetService)
            val requires = GOVERNED_CAPABILITIES.contains(capability.lowercase())
            Logger.d(TAG, "Checking consent requirement for '$capability': $requires")
            return requires
        }

        override fun approveWorkflow(workflowId: String): Boolean {
            TargetAccessEnforcer.enforceSkylarCaller(this@MockStarlightTargetService)
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
        Logger.i(TAG, "Client binding to MockStarlightTargetService with intent: $intent")
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
