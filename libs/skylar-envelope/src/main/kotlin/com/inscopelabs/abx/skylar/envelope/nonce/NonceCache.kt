package com.inscopelabs.abx.skylar.envelope.nonce

import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for nonce tracking to prevent replay attacks (Phase 1 work item 5).
 */
interface NonceCache {
    fun isSeen(nonce: String): Boolean
    fun markSeen(nonce: String, expiresAt: Long)
    fun checkAndMarkSeen(nonce: String, expiresAt: Long): Boolean {
        if (isSeen(nonce)) return false
        markSeen(nonce, expiresAt)
        return true
    }
}

/**
 * In-memory thread-safe nonce cache with expiry purging.
 */
class InMemoryNonceCache : NonceCache {
    private val nonces = ConcurrentHashMap<String, Long>()

    override fun isSeen(nonce: String): Boolean {
        purgeExpired()
        return nonces.containsKey(nonce)
    }

    override fun markSeen(nonce: String, expiresAt: Long) {
        purgeExpired()
        nonces[nonce] = expiresAt
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
