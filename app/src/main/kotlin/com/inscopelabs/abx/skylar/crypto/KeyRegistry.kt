package com.inscopelabs.abx.skylar.crypto

/**
 * Maps a registered identity (a caller_id, or a policy-signer identifier
 * such as "policy-signer") to its public key material.
 *
 * This concept did not exist in the prior prototype: [EnvelopeVerifier]
 * checked a signature's *shape* but never looked up whose key it should
 * have been checked against. Signature verification without a registry
 * to verify against is not verification — it's formatting validation.
 *
 * Security Contract: a valid signature only proves the message was
 * signed by whoever holds the matching private key. It says nothing
 * about authorization on its own — that's
 * [com.inscopelabs.abx.skylar.policy.AuthorizationMatrix]'s job, and only
 * after signature verification has already succeeded.
 */
interface KeyRegistry {
    /** X.509-encoded public key bytes for [identityId], or null if unregistered. */
    fun publicKeyFor(identityId: String): ByteArray?
}

/**
 * Development/test-only in-memory registry.
 *
 * NEVER wire this into a production build path with real production
 * keys — it exists so unit tests and the Phase 1 integration note
 * (deliverable 3) have something concrete to register test keys into.
 * Real production key registration and rotation is Phase 6's concern
 * (request-signing credential bootstrap) and does not exist yet.
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
}
