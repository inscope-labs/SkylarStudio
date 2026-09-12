package com.inscopelabs.abx.skylar.core

import android.content.Context
import android.content.SharedPreferences
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Replay protection cache keyed by `caller_id:nonce`.
 *
 * Security Contract:
 * - Must survive process restart for at least max envelope lifetime + clock skew.
 * - Atomic check-and-set: lookups verify if already seen and record in a single atomic transaction.
 * - Expired entries are safely purged.
 */
class NonceCache(
    context: Context,
    private val config: SkylarConfig = SkylarConfig.DEFAULT
) {
    companion object {
        private const val TAG = "SkylarNonceCache"
        private const val PREFS_NAME = "skylar_nonce_cache"
        private const val PURGE_THRESHOLD = 50
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private var operationCountSincePurge = 0

    init {
        Logger.d(TAG, "Initializing persistent NonceCache. Initial entry count: ${prefs.all.size}")
        purgeExpiredNonces()
    }

    /**
     * Checks if a nonce for the caller has already been seen, or records it if new.
     *
     * @param callerId Stable caller identifier
     * @param nonce Request nonce
     * @param expiresAt Envelope expiration timestamp in ms
     * @return true if nonce was ACCEPTED (first time seen and recorded), false if REPLAY (already seen)
     */
    fun checkAndRecord(callerId: String, nonce: String, expiresAt: Long): Boolean {
        val key = makeKey(callerId, nonce)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val existingExpiry = prefs.getLong(key, -1L)
            if (existingExpiry != -1L) {
                if (existingExpiry > now) {
                    Logger.w(TAG, "Replay DETECTED for key '$key'. Expiry is in future ($existingExpiry > $now)")
                    return false
                } else {
                    Logger.d(TAG, "Found stale key '$key' with past expiry ($existingExpiry <= $now). Overwriting.")
                }
            }

            // Calculate expiration with TTL buffer
            val recordExpiry = maxOf(expiresAt, now + config.nonceCacheTtlMs)

            prefs.edit().putLong(key, recordExpiry).apply()
            Logger.d(TAG, "Recorded new nonce for key '$key', expiresAt=$recordExpiry")

            operationCountSincePurge++
            if (operationCountSincePurge >= PURGE_THRESHOLD) {
                purgeExpiredNonces()
                operationCountSincePurge = 0
            }

            return true
        }
    }

    /**
     * Purges expired entries from the persistent cache.
     */
    fun purgeExpiredNonces(): Int {
        val now = System.currentTimeMillis()
        var purgedCount = 0

        synchronized(lock) {
            val allEntries = prefs.all
            val editor = prefs.edit()

            for ((k, v) in allEntries) {
                val expiry = (v as? Number)?.toLong() ?: 0L
                if (expiry <= now) {
                    editor.remove(k)
                    purgedCount++
                }
            }

            if (purgedCount > 0) {
                editor.apply()
                Logger.i(TAG, "Purged $purgedCount expired nonce records from persistent cache")
            }
        }
        return purgedCount
    }

    /**
     * Clears all nonces (for testing purposes only).
     */
    fun clearForTesting() {
        synchronized(lock) {
            prefs.edit().clear().apply()
            Logger.w(TAG, "NonceCache cleared completely")
        }
    }

    private fun makeKey(callerId: String, nonce: String): String = "$callerId:$nonce"
}
