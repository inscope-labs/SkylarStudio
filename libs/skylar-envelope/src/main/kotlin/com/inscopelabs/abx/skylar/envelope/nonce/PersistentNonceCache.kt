package com.inscopelabs.abx.skylar.envelope.nonce

import android.content.Context
import android.content.SharedPreferences
import com.inscopelabs.abx.skylar.envelope.EnvelopeLog

/**
 * `SharedPreferences`-backed persistent [NonceCache] (architecture doc §6:
 * "The cache must survive process restart... An in-memory-only cache
 * loses replay state on crash or forced stop.").
 *
 * Writes use `commit()` (blocking, synchronous) rather than `apply()`
 * (asynchronous): `apply()` queues the write and returns immediately, so
 * a crash between that return and the actual disk write would lose the
 * just-recorded nonce — exactly the guarantee this class exists to
 * provide. If a `commit()` reports failure, this fails closed (treats
 * the nonce as a replay) rather than risk accepting the same nonce
 * twice because the first record's durability couldn't be confirmed.
 */
class PersistentNonceCache(
    context: Context,
    private val nonceCacheTtlMs: Long = DEFAULT_NONCE_CACHE_TTL_MS
) : NonceCache {
    companion object {
        private const val TAG = "PersistentNonceCache"
        private const val PREFS_NAME = "skylar_nonce_cache"
        private const val PURGE_THRESHOLD = 50
        const val DEFAULT_NONCE_CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private var operationsSincePurge = 0

    init {
        EnvelopeLog.d(TAG, "Initializing persistent NonceCache. Entry count: ${prefs.all.size}")
        purgeExpired()
    }

    private fun key(callerId: String, nonce: String) = "$callerId:$nonce"

    override fun isSeen(callerId: String, nonce: String): Boolean {
        val existingExpiry = prefs.getLong(key(callerId, nonce), -1L)
        return existingExpiry != -1L && existingExpiry > System.currentTimeMillis()
    }

    override fun markSeen(callerId: String, nonce: String, expiresAt: Long) {
        val recordExpiry = maxOf(expiresAt, System.currentTimeMillis() + nonceCacheTtlMs)
        val written = prefs.edit().putLong(key(callerId, nonce), recordExpiry).commit()
        if (!written) {
            EnvelopeLog.e(TAG, "Failed to durably persist nonce for caller '$callerId' — record may not survive a crash")
        }
    }

    override fun checkAndMarkSeen(callerId: String, nonce: String, expiresAt: Long): Boolean {
        val k = key(callerId, nonce)
        synchronized(lock) {
            val existingExpiry = prefs.getLong(k, -1L)
            if (existingExpiry != -1L && existingExpiry > System.currentTimeMillis()) {
                EnvelopeLog.w(TAG, "Replay DETECTED for caller '$callerId'")
                return false
            }

            val recordExpiry = maxOf(expiresAt, System.currentTimeMillis() + nonceCacheTtlMs)
            val written = prefs.edit().putLong(k, recordExpiry).commit()
            if (!written) {
                // Fail closed: if durable persistence can't be confirmed,
                // treat this as a replay rather than risk a duplicate
                // acceptance on a subsequent attempt.
                EnvelopeLog.e(TAG, "Failed to durably persist nonce for caller '$callerId' — failing closed (treating as replay)")
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

    fun purgeExpired(): Int {
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
                EnvelopeLog.i(TAG, "Purged $purged expired nonce records")
            }
            return purged
        }
    }

    fun clearForTesting() {
        synchronized(lock) {
            prefs.edit().clear().commit()
        }
        EnvelopeLog.w(TAG, "NonceCache cleared completely (test-only path)")
    }
}
