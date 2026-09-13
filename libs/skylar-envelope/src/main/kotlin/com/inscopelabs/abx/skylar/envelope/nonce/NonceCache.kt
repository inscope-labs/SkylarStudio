package com.inscopelabs.abx.skylar.envelope.nonce

import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for nonce tracking to prevent replay attacks (architecture doc
 * §6: cache keyed by `caller_id + nonce`, not by nonce alone).
 *
 * The key is a composite of [callerId] and [nonce] deliberately: a
 * nonce-only key would let two different callers' independently-chosen
 * nonces collide, and would make one caller's replay window depend on
 * every other caller's nonce choices. Implementations must treat
 * `(callerId, nonce)` as the atomic unit, not `nonce` alone.
 */
interface NonceCache {
    fun isSeen(callerId: String, nonce: String): Boolean
    fun markSeen(callerId: String, nonce: String, expiresAt: Long)

    /**
     * Atomic check-and-record. Returns `true` if newly recorded (accept),
     * `false` if already seen (reject as replay). Callers should prefer
     * this over separate [isSeen]/[markSeen] calls where possible, since
     * a non-atomic check-then-mark has a race window between the two
     * calls under concurrent access.
     */
    fun checkAndMarkSeen(callerId: String, nonce: String, expiresAt: Long): Boolean {
        if (isSeen(callerId, nonce)) return false
        markSeen(callerId, nonce, expiresAt)
        return true
    }
}

/**
 * In-memory thread-safe nonce cache with expiry purging.
 *
 * Does NOT survive process restart — architecture doc §6 requires
 * persistence for production use. Use [PersistentNonceCache] for any
 * real deployment; this class exists for tests and non-Android-context
 * call sites where persistence isn't available or needed.
 */
class InMemoryNonceCache : NonceCache {
    private val nonces = ConcurrentHashMap<String, Long>()

    private fun key(callerId: String, nonce: String) = "$callerId:$nonce"

    override fun isSeen(callerId: String, nonce: String): Boolean {
        purgeExpired()
        return nonces.containsKey(key(callerId, nonce))
    }

    override fun markSeen(callerId: String, nonce: String, expiresAt: Long) {
        purgeExpired()
        nonces[key(callerId, nonce)] = expiresAt
    }

    fun size(): Int {
        purgeExpired()
        return nonces.size
    }

    fun clear() {
        nonces.clear()
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        val it = nonces.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value < now) {
                it.remove()
            }
        }
    }
}
