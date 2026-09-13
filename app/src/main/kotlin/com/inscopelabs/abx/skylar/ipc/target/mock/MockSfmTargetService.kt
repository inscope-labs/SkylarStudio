package com.inscopelabs.abx.skylar.ipc.target.mock

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
 * ================================ NOT REAL PHASE 4 ================================
 * THIS IS AN IN-PROCESS MOCK, NOT THE REAL SFM APP.
 *
 * This class runs inside Skylar's own APK/process/UID. It is NOT the
 * real `inscope-labs/abx-sfm-1` app, which per the canonical repo
 * structure doc must remain a physically separate installed app.
 * See [MockStarlightTargetService]'s KDoc for the full rationale — the
 * same reasoning applies here. Vault storage below is an in-memory
 * `ConcurrentHashMap`, not SFM's real SAF/AIDL-backed vault.
 *
 * See `docs/skylar-phase-04-real-integration-requirements.md` for what
 * actually completing Phase 4 requires.
 * =====================================================================
 *
 * Concrete in-process AIDL-shaped mock for SFM (System File Manager / storage vault).
 *
 * Security Requirements (as designed, not yet validated cross-process):
 * 1. Protected by platform-level access control via [TargetAccessEnforcer] (rejects non-Skylar callers).
 * 2. Scoped execution: executes file operations in isolated vault namespaces.
 */
class MockSfmTargetService : Service() {

    companion object {
        private const val TAG = "MockSfmTargetService"
        const val ACTION_BIND = "com.inscopelabs.abx.skylar.action.BIND_SFM"
        private const val DEFAULT_QUOTA_BYTES = 100L * 1024L * 1024L // 100MB
    }

    private val isServiceAvailable = AtomicBoolean(true)
    private val vaultStorage = ConcurrentHashMap<String, ByteArray>()

    private val binder = object : ISfmService.Stub() {

        override fun executeCapability(capability: String, paramsJson: String): String {
            Logger.i(TAG, "executeCapability invoked: capability='$capability'")
            TargetAccessEnforcer.enforceSkylarCaller(this@MockSfmTargetService)

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
            TargetAccessEnforcer.enforceSkylarCaller(this@MockSfmTargetService)
            return isServiceAvailable.get()
        }

        override fun getStorageQuotaBytes(callerNamespace: String): Long {
            TargetAccessEnforcer.enforceSkylarCaller(this@MockSfmTargetService)
            Logger.d(TAG, "Quota requested for namespace: '$callerNamespace'")
            return DEFAULT_QUOTA_BYTES
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.i(TAG, "Client binding to MockSfmTargetService with intent: $intent")
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
