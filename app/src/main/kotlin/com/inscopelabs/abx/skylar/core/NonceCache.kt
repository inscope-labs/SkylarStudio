package com.inscopelabs.abx.skylar.core

import android.content.Context
import android.content.SharedPreferences
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.diagnostics.Logger

/**
 * Replay-protection cache keyed by `caller_id:nonce` (architecture doc §6:
 * "The cache must survive process restart... An in-memory-only cache
 * loses replay state on crash or forced stop.").
 *
 * Formalized as an interface (the prior version was a single concrete
 * class) so [PersistentNonceCache] can be swapped for a test double
 * without touching [SkylarCore].
 */
interface NonceCache {
    /** True if newly recorded (accept this nonce); false if already seen (replay — reject). */
    fun checkAndRecord(callerId: String, nonce: String, expiresAt: Long): Boolean
    fun purgeExpired(): Int
}

/**
 * `SharedPreferences`-backed persistent implementation.
 *
 * One hardening relative to the prior version: writes use `commit()`
 * (blocking, synchronous) rather than `apply()` (asynchronous). `apply()`
 * queues the write and returns immediately — a crash between that
 * return and the actual disk write would lose the just-recorded nonce,
 * which is exactly the "survive process restart" guarantee this class
 * exists to provide. If a `commit()` reports failure, this fails closed
 * (treats the nonce as a replay) rather than risk accepting the same
 * nonce twice because we couldn't prove the first record landed.
 */
class PersistentNonceCache(
    context: Context,
    private val config: SkylarConfig = SkylarConfig.DEFAULT
) : NonceCache {
    companion object {
        private const val TAG = "SkylarNonceCache"
        private const val PREFS_NAME = "skylar_nonce_cache"
        private const val PURGE_THRESHOLD = 50
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private var operationsSincePurge = 0

    init {
        Logger.d(TAG, "Initializing persistent NonceCache. Entry count: ${prefs.all.size}")
        purgeExpired()
    }

    override fun checkAndRecord(callerId: String, nonce: String, expiresAt: Long): Boolean {
        val key = makeKey(callerId, nonce)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val existingExpiry = prefs.getLong(key, -1L)
            if (existingExpiry != -1L && existingExpiry > now) {
                Logger.w(TAG, "Replay DETECTED for key '$key'")
                return false
            }

            val recordExpiry = maxOf(expiresAt, now + config.nonceCacheTtlMs)
            val written = prefs.edit().putLong(key, recordExpiry).commit()
            if (!written) {
                Logger.e(TAG, "Failed to durably persist nonce for key '$key' — failing closed (treating as replay)")
                return false
            }

            operationsSincePurge++
            if (operationsSincePurge >= PURGE_THRESHOLD) {
                purgeExpired()
                operationsSincePurge = 0
            }
            return true
        }
    }

    override fun purgeExpired(): Int {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val editor = prefs.edit()
            var purged = 0
            for ((k, v) in prefs.all) {
                val expiry = (v as? Number)?.toLong() ?: 0L
                if (expiry <= now) {
                    editor.remove(k)
                    purged++
                }
            }
            if (purged > 0) {
                editor.apply()
                Logger.i(TAG, "Purged $purged expired nonce records")
            }
            return purged
        }
    }

    fun clearForTesting() {
        synchronized(lock) {
            prefs.edit().clear().commit()
        }
        Logger.w(TAG, "NonceCache cleared completely (test-only path)")
    }

    private fun makeKey(callerId: String, nonce: String): String = "$callerId:$nonce"
}
