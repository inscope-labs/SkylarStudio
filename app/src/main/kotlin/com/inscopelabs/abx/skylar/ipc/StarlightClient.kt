package com.inscopelabs.abx.skylar.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.ipc.aidl.IStarlightService
import com.inscopelabs.abx.skylar.ipc.target.mock.MockStarlightTargetService
import org.json.JSONObject

/**
 * IPC client for Starlight target (accessibility / execution agent).
 *
 * NOTE: currently binds to [MockStarlightTargetService], an in-process
 * mock inside Skylar's own APK — not the real, separate Starlight app.
 * See that class's KDoc and
 * `docs/skylar-phase-04-real-integration-requirements.md`. This client
 * class's own logic (bind/unbind/dispatch/error-handling) is written
 * against the real target's intended shape and should not need to
 * change once wired to the real app — only the bind target does.
 *
 * Security Contract:
 * - Invokes Starlight's protected AIDL interface [IStarlightService].
 * - Enforces platform-level access control: only Skylar Core's UID is accepted.
 * - Handles service lifecycle, disconnection, and [DeadObjectException] gracefully
 *   without affecting other capability targets.
 */
class StarlightClient(
    private val context: Context,
    private var customService: IStarlightService? = null
) {
    companion object {
        private const val TAG = "SkylarStarlightClient"
        const val TARGET_ID = "starlight"
    }

    private var boundService: IStarlightService? = null
    private var isBound = false
    private val lock = Any()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                boundService = IStarlightService.Stub.asInterface(service)
                isBound = true
                Logger.i(TAG, "Connected to Starlight AIDL service")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                boundService = null
                isBound = false
                Logger.w(TAG, "Disconnected from Starlight AIDL service (remote process died / stopped)")
            }
        }
    }

    /**
     * Injects an [IStarlightService] instance directly (used in testing or direct binding).
     */
    fun setServiceForTesting(service: IStarlightService?) {
        synchronized(lock) {
            customService = service
            Logger.d(TAG, "Configured custom IStarlightService instance")
        }
    }

    /**
     * Binds to the remote Starlight AIDL service if not already connected.
     */
    fun bindService(): Boolean {
        synchronized(lock) {
            if (isBound || customService != null) return true
            val intent = Intent(MockStarlightTargetService.ACTION_BIND).apply {
                setClass(context, MockStarlightTargetService::class.java)
            }
            return try {
                val success = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                Logger.i(TAG, "Binding to Starlight service initiated: success=$success")
                success
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to bind to Starlight service", e)
                false
            }
        }
    }

    /**
     * Unbinds from the remote Starlight AIDL service.
     */
    fun unbindService() {
        synchronized(lock) {
            if (isBound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (e: Exception) {
                    Logger.w(TAG, "Error unbinding from Starlight service: ${e.message}")
                }
                boundService = null
                isBound = false
                Logger.i(TAG, "Unbound from Starlight service")
            }
        }
    }

    /**
     * Dispatches an authorized capability request to Starlight via protected AIDL.
     */
    fun execute(capability: String, params: Map<String, Any?>): Result<Map<String, Any?>> {
        Logger.i(TAG, "Dispatching capability '$capability' to Starlight via AIDL")

        val service = getActiveService()
        if (service == null) {
            Logger.w(TAG, "Starlight service unavailable / not bound")
            return Result.Error(
                message = "Starlight target service is not connected",
                errorCode = "TARGET_UNAVAILABLE"
            )
        }

        val paramsJson = try {
            JSONObject(params).toString()
        } catch (e: Exception) {
            "{}"
        }

        return try {
            val responseJson = service.executeCapability(capability, paramsJson)
            val jsonObject = JSONObject(responseJson)
            val resultMap = jsonToMap(jsonObject)
            Logger.i(TAG, "Starlight execution returned status='${resultMap["status"]}' for capability '$capability'")
            Result.Success(resultMap)
        } catch (e: SecurityException) {
            Logger.e(TAG, "Platform access rejected by Starlight AIDL service", e)
            Result.Error(
                message = "Access denied: ${e.message}",
                cause = e,
                errorCode = "PLATFORM_ACCESS_DENIED"
            )
        } catch (e: DeadObjectException) {
            Logger.e(TAG, "Starlight process crashed or was force-stopped (DeadObjectException)", e)
            synchronized(lock) { boundService = null; isBound = false }
            Result.Error(
                message = "Starlight service disconnected or crashed",
                cause = e,
                errorCode = "TARGET_CRASHED"
            )
        } catch (e: RemoteException) {
            Logger.e(TAG, "RemoteException during Starlight AIDL dispatch", e)
            Result.Error(
                message = "Starlight IPC communication failed: ${e.message}",
                cause = e,
                errorCode = "TARGET_IPC_ERROR"
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Unexpected error dispatching to Starlight", e)
            Result.Error(
                message = "Starlight execution failed: ${e.message}",
                cause = e,
                errorCode = "TARGET_EXECUTION_FAILED"
            )
        }
    }

    fun isAvailable(): Boolean {
        val service = getActiveService() ?: return false
        return try {
            service.isAvailable
        } catch (_: Exception) {
            false
        }
    }

    fun approveWorkflow(workflowId: String): Boolean {
        val service = getActiveService() ?: return false
        return try {
            service.approveWorkflow(workflowId)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to approve workflow '$workflowId' via AIDL", e)
            false
        }
    }

    private fun getActiveService(): IStarlightService? {
        synchronized(lock) {
            if (customService != null) return customService
            if (boundService != null) return boundService
        }
        // Attempt lazy bind
        bindService()
        return synchronized(lock) { boundService }
    }

    private fun jsonToMap(jsonObject: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = jsonObject.opt(key)
        }
        return map
    }
}
