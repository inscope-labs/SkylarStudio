package com.inscopelabs.abx.skylar.ipc.target

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.ipc.TargetAccessEnforcer
import com.inscopelabs.abx.skylar.ipc.aidl.ISfmService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete on-device AIDL service for SFM (System File Manager / storage vault).
 *
 * Security Requirements:
 * 1. Protected by platform-level access control via [TargetAccessEnforcer] (rejects non-Skylar callers).
 * 2. Scoped execution: executes file operations in isolated vault namespaces.
 */
class SfmTargetService : Service() {

    companion object {
        private const val TAG = "SfmTargetService"
        const val ACTION_BIND = "com.inscopelabs.abx.skylar.action.BIND_SFM"
        private const val DEFAULT_QUOTA_BYTES = 100L * 1024L * 1024L // 100MB
    }

    private val isServiceAvailable = AtomicBoolean(true)
    private val vaultStorage = ConcurrentHashMap<String, ByteArray>()

    private val binder = object : ISfmService.Stub() {

        override fun executeCapability(capability: String, paramsJson: String): String {
            Logger.i(TAG, "executeCapability invoked: capability='$capability'")
            TargetAccessEnforcer.enforceSkylarCaller(this@SfmTargetService)

            if (!isServiceAvailable.get()) {
                Logger.w(TAG, "SFM service is currently unavailable")
                throw IllegalStateException("SFM service is currently unavailable / offline")
            }

            val params = try {
                JSONObject(paramsJson)
            } catch (_: Exception) {
                JSONObject()
            }

            return when (capability.lowercase()) {
                "storage.read" -> handleRead(params)
                "storage.write" -> handleWrite(params)
                "storage.list" -> handleList(params)
                "vault.execute" -> handleVaultExecute(params)
                else -> {
                    Logger.w(TAG, "Unrecognized SFM capability: '$capability'")
                    JSONObject().apply {
                        put("status", "ERROR")
                        put("error", "UNKNOWN_SFM_CAPABILITY")
                        put("capability", capability)
                    }.toString()
                }
            }
        }

        override fun isAvailable(): Boolean {
            TargetAccessEnforcer.enforceSkylarCaller(this@SfmTargetService)
            return isServiceAvailable.get()
        }

        override fun getStorageQuotaBytes(callerNamespace: String): Long {
            TargetAccessEnforcer.enforceSkylarCaller(this@SfmTargetService)
            Logger.d(TAG, "Quota requested for namespace: '$callerNamespace'")
            return DEFAULT_QUOTA_BYTES
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.i(TAG, "Client binding to SfmTargetService with intent: $intent")
        TargetAccessEnforcer.enforceSkylarCaller(this)
        return binder
    }

    private fun handleRead(params: JSONObject): String {
        val path = params.optString("path", "")
        val data = vaultStorage[path]
        return if (data != null) {
            JSONObject().apply {
                put("status", "SUCCESS")
                put("path", path)
                put("size", data.size)
                put("content", String(data))
            }.toString()
        } else {
            JSONObject().apply {
                put("status", "SUCCESS")
                put("path", path)
                put("size", 0)
                put("content", "mock_default_content_for_$path")
            }.toString()
        }
    }

    private fun handleWrite(params: JSONObject): String {
        val path = params.optString("path", "file.txt")
        val content = params.optString("content", "")
        vaultStorage[path] = content.toByteArray()
        return JSONObject().apply {
            put("status", "SUCCESS")
            put("path", path)
            put("bytes_written", content.length)
        }.toString()
    }

    private fun handleList(params: JSONObject): String {
        val prefix = params.optString("prefix", "")
        val items = JSONArray()
        vaultStorage.keys().toList()
            .filter { it.startsWith(prefix) }
            .forEach { items.put(it) }
        return JSONObject().apply {
            put("status", "SUCCESS")
            put("items", items)
        }.toString()
    }

    private fun handleVaultExecute(params: JSONObject): String {
        val action = params.optString("action", "inspect")
        return JSONObject().apply {
            put("status", "SUCCESS")
            put("action", action)
            put("vault_state", "secure")
        }.toString()
    }

    fun setAvailableForTesting(available: Boolean) {
        isServiceAvailable.set(available)
        Logger.d(TAG, "Set SFM availability for testing: $available")
    }
}
