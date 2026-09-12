package com.inscopelabs.abx.skylar

import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.core.EnvelopeCanonicalizer
import com.inscopelabs.abx.skylar.core.EnvelopeVerifier
import com.inscopelabs.abx.skylar.core.RequestEnvelope
import com.inscopelabs.abx.skylar.crypto.Base64Codec
import com.inscopelabs.abx.skylar.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.crypto.InMemoryKeyRegistry
import com.inscopelabs.abx.skylar.mesh.TransportCredential
import com.inscopelabs.abx.skylar.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.policy.RoutingTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Rewritten against the real [EnvelopeVerifier]/[EcdsaP256SignatureProvider]
 * (see agent-reports/2026-09-12T07-30-00Z-guiding-prototype-security-contract-core.md).
 *
 * The prior version's envelope tests used a fake fixed signature string
 * ("sig_valid_test_signature_123") that the old fail-open verifier
 * accepted unconditionally. That string is not a valid ECDSA signature
 * over anything, so it can no longer pass — correctly. These tests use
 * real generated P-256 keypairs and real signatures instead, and add
 * cases the old suite had no way to express (tampering, impersonation)
 * since its verifier couldn't actually distinguish a real signature
 * from a fake one.
 */
class SkylarCoreTest {

    /** Generates a fresh P-256 keypair and registers the public half under [callerId]. */
    private fun registerTestCaller(registry: InMemoryKeyRegistry, callerId: String): PrivateKey {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()
        registry.register(callerId, keyPair.public.encoded)
        return keyPair.private
    }

    private fun signEnvelope(
        privateKey: PrivateKey,
        callerId: String,
        capability: String,
        params: Map<String, Any?>,
        nonce: String,
        issuedAt: Long,
        expiresAt: Long,
        scope: String? = null
    ): RequestEnvelope {
        val workflowHash = EnvelopeCanonicalizer.computeWorkflowHash(
            envelopeVersion = RequestEnvelope.CURRENT_ENVELOPE_VERSION,
            callerId = callerId,
            capability = capability,
            params = params,
            nonce = nonce,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            scope = scope
        )
        val canonicalBytes = EnvelopeCanonicalizer.canonicalBytes(
            envelopeVersion = RequestEnvelope.CURRENT_ENVELOPE_VERSION,
            callerId = callerId,
            capability = capability,
            params = params,
            nonce = nonce,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            scope = scope
        )
        val signatureBytes = EcdsaP256SignatureProvider().sign(privateKey.encoded, canonicalBytes)
        return RequestEnvelope(
            callerId = callerId,
            capability = capability,
            params = params,
            nonce = nonce,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            workflowHash = workflowHash,
            signature = Base64Codec.encode(signatureBytes),
            scope = scope
        )
    }

    @Test
    fun testAuthorizationMatrix_DefaultDeny() {
        val matrix = AuthorizationMatrix(
            version = "0.5.0",
            matrix = mapOf(
                "caller.alpha" to mapOf(
                    "context.query" to setOf("read"),
                    "action.execute" to setOf("write")
                )
            )
        )

        assertTrue(matrix.isAuthorized("caller.alpha", "context.query", "read"))
        assertTrue(matrix.isAuthorized("caller.alpha", "action.execute", "write"))
        assertFalse(matrix.isAuthorized("caller.alpha", "context.query", "admin"))
        assertFalse(matrix.isAuthorized("caller.alpha", "storage.access"))
        assertFalse(matrix.isAuthorized("caller.unknown", "context.query"))
    }

    @Test
    fun testRoutingTable_ResolutionAndDefaultDeny() {
        val routing = RoutingTable(
            version = "0.5.0",
            routes = mapOf(
                "context.query" to "starlight",
                "storage.access" to "sfm",
                "system.tools" to "xtools"
            )
        )

        assertEquals("starlight", routing.resolveTarget("context.query"))
        assertEquals("sfm", routing.resolveTarget("storage.access"))
        assertEquals("xtools", routing.resolveTarget("system.tools"))
        assertNull(routing.resolveTarget("unregistered.capability"))
    }

    @Test
    fun testEnvelopeVerifier_ValidSignatureAccepted() {
        val registry = InMemoryKeyRegistry()
        val privateKey = registerTestCaller(registry, "caller.test")
        val verifier = EnvelopeVerifier(registry, SkylarConfig.DEFAULT)

        val now = System.currentTimeMillis()
        val envelope = signEnvelope(
            privateKey = privateKey,
            callerId = "caller.test",
            capability = "context.query",
            params = mapOf("query" to "user_status"),
            nonce = "nonce-12345",
            issuedAt = now,
            expiresAt = now + 60_000L
        )

        val result = verifier.verify(envelope)
        assertTrue(result.isSuccess)
    }

    @Test
    fun testEnvelopeVerifier_TamperedParamsRejected() {
        // A signature only means something if changing the signed content
        // invalidates it. The prior fail-open verifier had no way to fail
        // this test, since it never actually checked the signature against
        // the payload at all.
        val registry = InMemoryKeyRegistry()
        val privateKey = registerTestCaller(registry, "caller.test")
        val verifier = EnvelopeVerifier(registry, SkylarConfig.DEFAULT)

        val now = System.currentTimeMillis()
        val signed = signEnvelope(
            privateKey = privateKey,
            callerId = "caller.test",
            capability = "context.query",
            params = mapOf("query" to "user_status"),
            nonce = "nonce-tamper",
            issuedAt = now,
            expiresAt = now + 60_000L
        )
        val tampered = signed.copy(params = mapOf("query" to "admin_status"))

        val result = verifier.verify(tampered)
        assertTrue(result.isError)
    }

    @Test
    fun testEnvelopeVerifier_UnregisteredCallerRejected() {
        val registry = InMemoryKeyRegistry() // caller.test is intentionally never registered
        val verifier = EnvelopeVerifier(registry, SkylarConfig.DEFAULT)

        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val unregisteredKeyPair = keyPairGenerator.generateKeyPair()

        val now = System.currentTimeMillis()
        val envelope = signEnvelope(
            privateKey = unregisteredKeyPair.private,
            callerId = "caller.test",
            capability = "context.query",
            params = emptyMap(),
            nonce = "nonce-unreg",
            issuedAt = now,
            expiresAt = now + 60_000L
        )

        val result = verifier.verify(envelope)
        assertTrue(result.isError)
    }

    @Test
    fun testEnvelopeVerifier_ImpersonationRejected() {
        // Envelope claims to be caller.test (whose real public key IS
        // registered) but is actually signed by a different private key.
        // This is the case a signature scheme exists to prevent, and the
        // old fail-open verifier had no way to catch it.
        val registry = InMemoryKeyRegistry()
        registerTestCaller(registry, "caller.test") // registers the REAL key; discard the private half

        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val attackerKeyPair = keyPairGenerator.generateKeyPair()

        val verifier = EnvelopeVerifier(registry, SkylarConfig.DEFAULT)
        val now = System.currentTimeMillis()
        val envelope = signEnvelope(
            privateKey = attackerKeyPair.private, // wrong key for the claimed caller_id
            callerId = "caller.test",
            capability = "context.query",
            params = emptyMap(),
            nonce = "nonce-impersonate",
            issuedAt = now,
            expiresAt = now + 60_000L
        )

        val result = verifier.verify(envelope)
        assertTrue(result.isError)
    }

    @Test
    fun testEnvelopeVerifier_ExpiredEnvelopeRejected() {
        val registry = InMemoryKeyRegistry()
        val privateKey = registerTestCaller(registry, "caller.test")
        val verifier = EnvelopeVerifier(registry, SkylarConfig(clockSkewToleranceMs = 1000L))

        val now = System.currentTimeMillis()
        val expiredTime = now - 100_000L
        val envelope = signEnvelope(
            privateKey = privateKey,
            callerId = "caller.test",
            capability = "context.query",
            params = emptyMap(),
            nonce = "nonce-expired",
            issuedAt = expiredTime - 60_000L,
            expiresAt = expiredTime
        )

        val result = verifier.verify(envelope)
        assertTrue(result.isError)
    }

    @Test
    fun testTransportCredential_Validity() {
        val now = System.currentTimeMillis()
        val validCred = TransportCredential(
            credentialId = "cred-1",
            authKey = "tskey-auth-mock",
            meshNodeId = "node-1",
            issuedAt = now,
            expiresAt = now + 3600_000L
        )
        assertTrue(validCred.isValid(now))

        val expiredCred = TransportCredential(
            credentialId = "cred-2",
            authKey = "tskey-auth-mock",
            meshNodeId = "node-2",
            issuedAt = now - 7200_000L,
            expiresAt = now - 3600_000L
        )
        assertFalse(expiredCred.isValid(now))

        val revokedCred = validCred.copy(isRevoked = true)
        assertFalse(revokedCred.isValid(now))
    }
}
