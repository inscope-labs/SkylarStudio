package com.inscopelabs.abx.skylar.envelope.crypto

/**
 * Maps a registered identity (a caller_id, or a policy-signer identifier
 * such as "policy-signer") to its public key material.
 */
interface KeyRegistry {
    /** X.509-encoded public key bytes for [identityId], or null if unregistered. */
    fun publicKeyFor(identityId: String): ByteArray?
}

/**
 * In-memory registry for testing and development.
 */
class InMemoryKeyRegistry : KeyRegistry {
    private val keys = mutableMapOf<String, ByteArray>()

    fun register(identityId: String, publicKeyBytes: ByteArray) {
        keys[identityId] = publicKeyBytes
    }

    fun revoke(identityId: String) {
        keys.remove(identityId)
    }

    override fun publicKeyFor(identityId: String): ByteArray? = keys[identityId]

    fun registeredIdentities(): Set<String> = keys.keys.toSet()
}
