package com.inscopelabs.abx.skylar.envelope

import com.inscopelabs.abx.skylar.envelope.crypto.InMemoryKeyRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Validates Phase 1 criterion V1.2:
 * "A second module / repository can construct, sign, and verify an envelope using only the shared library"
 */
class EnvelopeBuilderTest {

    @Test
    fun testConstructSignAndVerify_CrossModuleConsumerPattern() {
        // 1. Consumer setup: generate keys (e.g. Starlight or SFM client)
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val clientPrivateKey = kp.private
        val clientPublicKey = kp.public

        // 2. Skylar Core setup: register client public key
        val registry = InMemoryKeyRegistry()
        val clientId = "starlight.service.client"
        registry.register(clientId, clientPublicKey.encoded)
        val verifier = EnvelopeVerifier(registry)

        // 3. Client constructs and signs envelope using EnvelopeBuilder
        val envelope = EnvelopeBuilder()
            .callerId(clientId)
            .capability("starlight.inference.execute")
            .param("model", "gemini-flash")
            .param("temperature", 0.7)
            .param("metadata", mapOf("session_id" to "sess_987", "priority" to 1))
            .scope("execute")
            .sign(clientPrivateKey.encoded)

        // 4. Verify envelope structure
        assertEquals(clientId, envelope.callerId)
        assertEquals("starlight.inference.execute", envelope.capability)
        assertEquals("gemini-flash", envelope.params["model"])
        assertNotNull(envelope.nonce)
        assertNotNull(envelope.workflowHash)
        assertNotNull(envelope.signature)

        // 5. Skylar Core verifies envelope
        val verification = verifier.verify(envelope)
        assertTrue("Verification must succeed using only the shared library API", verification.isSuccess)
    }

    @Test
    fun testBuilderDefaults() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        val envelope = EnvelopeBuilder()
            .callerId("default.tester")
            .capability("action.ping")
            .sign(kp.private.encoded)

        assertEquals(RequestEnvelope.CURRENT_ENVELOPE_VERSION, envelope.envelopeVersion)
        assertTrue(envelope.issuedAt > 0)
        assertTrue(envelope.expiresAt > envelope.issuedAt)
        assertTrue(envelope.nonce.isNotEmpty())
        assertTrue(envelope.workflowHash.isNotEmpty())
    }
}
