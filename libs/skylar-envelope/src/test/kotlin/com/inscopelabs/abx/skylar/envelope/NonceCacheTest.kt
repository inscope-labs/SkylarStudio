package com.inscopelabs.abx.skylar.envelope

import com.inscopelabs.abx.skylar.envelope.nonce.InMemoryNonceCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonceCacheTest {

    @Test
    fun testReplayDetection() {
        val cache = InMemoryNonceCache()
        val callerId = "test_caller_1"
        val nonce = "random_nonce_123"
        val expiry = System.currentTimeMillis() + 60_000L

        assertFalse("Initially unseen", cache.isSeen(callerId, nonce))
        assertTrue("First mark succeeds", cache.checkAndMarkSeen(callerId, nonce, expiry))
        assertTrue("Now seen", cache.isSeen(callerId, nonce))
        assertFalse("Second mark fails (replay detected)", cache.checkAndMarkSeen(callerId, nonce, expiry))
    }

    @Test
    fun testPurgeExpired() {
        val cache = InMemoryNonceCache()
        val callerId = "test_caller_2"
        val now = System.currentTimeMillis()
        val expiredNonce = "expired_nonce_abc"
        val activeNonce = "active_nonce_xyz"

        // Mark one expired in the past, one active in the future
        cache.markSeen(callerId, expiredNonce, now - 5000L)
        cache.markSeen(callerId, activeNonce, now + 60_000L)

        // isSeen triggers purge
        assertFalse("Expired nonce should be purged", cache.isSeen(callerId, expiredNonce))
        assertTrue("Active nonce should be retained", cache.isSeen(callerId, activeNonce))
        assertEquals(1, cache.size())
    }
}
