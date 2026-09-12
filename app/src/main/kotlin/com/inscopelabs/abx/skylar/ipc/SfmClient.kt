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
import com.inscopelabs.abx.skylar.ipc.aidl.ISfmService
import com.inscopelabs.abx.skylar.ipc.target.SfmTargetService
import org.json.JSONObject

/**
 * IPC client for SFM (System File Manager / storage vault) target.
 *
 * Security Contract:
 * - Invokes SFM's protected AIDL interface [ISfmService].
 * - Enforces platform-level access control: only Skylar Core's UID is accepted.
 * - Handles service lifecycle, disconnection, and [DeadObjectException] gracefully
 *   without affecting other capability targets.
 */
class SfmClient(
    private val context: Context,
    private var customService: ISfmService? = null
) {
    companion object {
        private const val TAG = "SkylarSfmClient"
        const val TARGET_ID = "sfm"
    }

    private var boundService: ISfmService? = null
    private var isBound = false
    private val lock = Any()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) {
                boundService = ISfmService.Stub.asInterface(service)
                isBound = true
                Logger.i(TAG, "Connected to SFM AIDL service")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                boundService = null
                isBound = false
                Logger.w(TAG, "Disconnected from SFM AIDL service (remote process died / stopped)")
            }
        }
    }

    /**
     * Injects an [ISfmService] instance directly (used in testing or direct binding).
     */
    fun setServiceForTesting(service: ISfmService?) {
        synchronized(lock) {
            customService = service
            Logger.d(TAG, "Configured custom ISfmService instance")
        }
    }

    /**
     * Binds to the remote SFM AIDL service if not already connected.
     */
    fun bindService(): Boolean {
        synchronized(lock) {
            if (isBound || customService != null) return true
            val intent = Intent(SfmTargetService.ACTION_BIND).apply {
                setClass(context, SfmTargetService::class.java)
            }
            return try {
                val success = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                Logger.i(TAG, "Binding to SFM service initiated: success=$success")
                success
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to bind to SFM service", e)
                false
            }
        }
    }

    /**
     * Unbinds from the remote SFM AIDL service.
     */
    fun unbindService() {
        synchronized(lock) {
            if (isBound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (e: Exception) {
                    Logger.w(TAG, "Error unbinding from SFM service: ${e.message}")
                }
                boundService = null
                isBound = false
                Logger.i(TAG, "Unbound from SFM service")
            }
        }
    }

    /**
     * Dispatches an authorized capability request to SFM via protected AIDL.
     */
    fun execute(capability: String, params: Map<String, Any?>): Result<Map<String, Any?>> {
        Logger.i(TAG, "Dispatching capability '$capability' to SFM via AIDL")

        val service = getActiveService()
        if (service == null) {
            Logger.w(TAG, "SFM service unavailable / not bound")
            return Result.Error(
                message = "SFM target service is not connected",
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
            Logger.i(TAG, "SFM execution returned status='${resultMap["status"]}' for capability '$capability'")
            Result.Success(resultMap)
        } catch (e: SecurityException) {
            Logger.e(TAG, "Platform access rejected by SFM AIDL service", e)
            Result.Error(
                message = "Access denied: ${e.message}",
                cause = e,
                errorCode = "PLATFORM_ACCESS_DENIED"
            )
        } catch (e: DeadObjectException) {
            Logger.e(TAG, "SFM process crashed or was force-stopped (DeadObjectException)", e)
            synchronized(lock) { boundService = null; isBound = false }
            Result.Error(
                message = "SFM service disconnected or crashed",
                cause = e,
                errorCode = "TARGET_CRASHED"
            )
        } catch (e: RemoteException) {
            Logger.e(TAG, "RemoteException during SFM AIDL dispatch", e)
            Result.Error(
                message = "SFM IPC communication failed: ${e.message}",
                cause = e,
                errorCode = "TARGET_IPC_ERROR"
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Unexpected error dispatching to SFM", e)
            Result.Error(
                message = "SFM execution failed: ${e.message}",
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

    fun getQuotaBytes(namespace: String): Long {
        val service = getActiveService() ?: return -1L
        return try {
            service.getStorageQuotaBytes(namespace)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to query SFM quota", e)
            -1L
        }
    }

    private fun getActiveService(): ISfmService? {
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
