package com.inscopelabs.abx.skylar.ipc

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Enforces platform-level access control on Capability Target IPC endpoints (AIDL services).
 *
 * Security Invariant (Architecture Specification §7):
 * "Co-resident target IPC endpoints (AIDL services, bound services, etc.) MUST be protected
 * so that only Skylar Core can invoke them. On Android this means signature-level permissions
 * or explicit caller-UID verification. The IPC endpoint must reject any caller other than
 * Skylar regardless of what the caller claims."
 */
object TargetAccessEnforcer {

    private const val TAG = "SkylarTargetAccessEnforcer"
    const val DISPATCH_PERMISSION = "com.inscopelabs.abx.skylar.permission.DISPATCH_CAPABILITY"

    @Volatile
    private var allowedUidOverrideForTesting: Int? = null

    /**
     * Sets an allowed UID override for testing environments (e.g. Robolectric JVM tests).
     */
    fun setAllowedUidForTesting(uid: Int?) {
        allowedUidOverrideForTesting = uid
        Logger.d(TAG, "Configured allowed UID test override: $uid")
    }

    /**
     * Enforces that the current Binder caller is Skylar Core.
     *
     * @param context Application context for package manager queries
     * @throws SecurityException if the caller is not authorized
     */
    fun enforceSkylarCaller(context: Context) {
        val callingUid = Binder.getCallingUid()
        val myUid = Process.myUid()

        Logger.d(TAG, "Validating Binder caller: callingUid=$callingUid, myUid=$myUid")

        // 1. Check test override if configured
        val testOverride = allowedUidOverrideForTesting
        if (testOverride != null) {
            if (callingUid == testOverride) {
                Logger.d(TAG, "Caller UID $callingUid matched test override")
                return
            } else {
                Logger.w(TAG, "Caller UID $callingUid does NOT match test override $testOverride")
                throw SecurityException(
                    "Unauthorized caller UID: $callingUid. Expected Skylar Core UID $testOverride"
                )
            }
        }

        // 2. Co-process / co-UID invocation (same application or sharedUserId)
        if (callingUid == myUid) {
            Logger.d(TAG, "Caller UID matches host process UID: $callingUid (authorized)")
            return
        }

        // 3. Signature permission check: caller must hold DISPATCH_PERMISSION
        val pm = context.packageManager
        val permCheck = pm.checkPermission(DISPATCH_PERMISSION, getPackageNameForUid(pm, callingUid))
        if (permCheck == PackageManager.PERMISSION_GRANTED) {
            Logger.i(TAG, "Caller UID $callingUid holds $DISPATCH_PERMISSION (authorized)")
            return
        }

        // 4. Signature identity check: verify caller package signature matches Skylar
        val sigMatch = pm.checkSignatures(callingUid, myUid)
        if (sigMatch == PackageManager.SIGNATURE_MATCH) {
            Logger.i(TAG, "Caller UID $callingUid signature matches host signature (authorized)")
            return
        }

        // 5. Fail-closed: caller is unauthorized
        Logger.e(TAG, "Security violation: Unauthorized caller UID $callingUid attempted target invocation")
        throw SecurityException(
            "Access denied: Caller UID $callingUid is not authorized to invoke capability targets. " +
                "Only Skylar Core holding $DISPATCH_PERMISSION or matching signature is permitted."
        )
    }

    private fun getPackageNameForUid(pm: PackageManager, uid: Int): String {
        return pm.getPackagesForUid(uid)?.firstOrNull() ?: "unknown"
    }
}
