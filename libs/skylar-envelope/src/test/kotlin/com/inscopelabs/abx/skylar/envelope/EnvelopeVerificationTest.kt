package com.inscopelabs.abx.skylar.envelope

import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.InMemoryKeyRegistry
import com.inscopelabs.abx.skylar.envelope.nonce.InMemoryNonceCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Validates Phase 1 criteria V1.1, V1.3, and V1.5:
 * - Happy path acceptance
 * - Any field alteration causes verification failure
 * - Expiry rejection
 * - Envelope version checked
 * - Replay rejection
 */
class EnvelopeVerificationTest {

    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var verifier: EnvelopeVerifier
    private lateinit var testCallerPrivateKey: PrivateKey
    private lateinit var testCallerPublicKeyBytes: ByteArray
    private val callerId = "test.caller.agent"

    @Before
    fun setUp() {
        keyRegistry = InMemoryKeyRegistry()
        verifier = EnvelopeVerifier(keyRegistry)

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        testCallerPrivateKey = kp.private
        testCallerPublicKeyBytes = kp.public.encoded
        keyRegistry.register(callerId, testCallerPublicKeyBytes)
    }

    private fun createValidEnvelope(
        capability: String = "context.retrieve",
        params: Map<String, Any?> = mapOf("query" to "user_profile"),
        nonce: String = "nonce_12345678",
        issuedAt: Long = System.currentTimeMillis(),
        expiresAt: Long = System.currentTimeMillis() + 60_000L,
        scope: String? = "read"
    ): RequestEnvelope {
        return EnvelopeBuilder()
            .callerId(callerId)
            .capability(capability)
            .params(params)
            .nonce(nonce)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .scope(scope)
            .sign(testCallerPrivateKey.encoded)
    }

    @Test
    fun testValidEnvelope_Accepted() {
        val envelope = createValidEnvelope()
        val result = verifier.verify(envelope)
        assertTrue("Expected verification success", result.isSuccess)
    }

    @Test
    fun testTamperCapability_FailsVerification() {
        val envelope = createValidEnvelope(capability = "context.retrieve")
        val tampered = envelope.copy(capability = "system.reboot")
        val result = verifier.verify(tampered)
        assertTrue(result.isFailure)
        assertEquals("INVALID_WORKFLOW_HASH", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testTamperParams_FailsVerification() {
        val envelope = createValidEnvelope(params = mapOf("key" to "original"))
        val tampered = envelope.copy(params = mapOf("key" to "tampered"))
        val result = verifier.verify(tampered)
        assertTrue(result.isFailure)
        assertEquals("INVALID_WORKFLOW_HASH", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testTamperNonce_FailsVerification() {
        val envelope = createValidEnvelope(nonce = "original_nonce_1")
        val tampered = envelope.copy(nonce = "tampered_nonce_2")
        val result = verifier.verify(tampered)
        assertTrue(result.isFailure)
        assertEquals("INVALID_WORKFLOW_HASH", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testTamperTimestamps_FailsVerification() {
        val envelope = createValidEnvelope()
        val tampered = envelope.copy(issuedAt = envelope.issuedAt - 5000L)
        val result = verifier.verify(tampered)
        assertTrue(result.isFailure)
        assertEquals("INVALID_WORKFLOW_HASH", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testTamperScope_FailsVerification() {
        val envelope = createValidEnvelope(scope = "read")
        val tampered = envelope.copy(scope = "admin")
        val result = verifier.verify(tampered)
        assertTrue(result.isFailure)
        assertEquals("INVALID_WORKFLOW_HASH", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testTamperCallerId_FailsVerification() {
        val envelope = createValidEnvelope()
        val tampered = envelope.copy(callerId = "attacker.agent")
        val result = verifier.verify(tampered)
        assertTrue(result.isFailure)
        // Since attacker is unregistered, it fails with UNKNOWN_CALLER or INVALID_WORKFLOW_HASH
        assertTrue(result.failureOrNull()?.errorCode in setOf("UNKNOWN_CALLER", "INVALID_WORKFLOW_HASH"))
    }

    @Test
    fun testTamperWorkflowHash_FailsVerification() {
        val envelope = createValidEnvelope()
        val tampered = envelope.copy(workflowHash = "0000000000000000000000000000000000000000000000000000000000000000")
        val result = verifier.verify(tampered)
        assertTrue(result.isFailure)
        assertEquals("INVALID_WORKFLOW_HASH", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testCorruptedSignature_FailsVerification() {
        val envelope = createValidEnvelope()
        val corruptedSig = Base64Codec.encode(ByteArray(64) { 0x41 })
        val tampered = envelope.copy(signature = corruptedSig)
        val result = verifier.verify(tampered)
        assertTrue(result.isFailure)
        assertEquals("INVALID_SIGNATURE", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testExpiredEnvelope_Rejected() {
        val now = System.currentTimeMillis()
        val pastIssuedAt = now - 200_000L
        val pastExpiresAt = now - 100_000L
        val expired = createValidEnvelope(issuedAt = pastIssuedAt, expiresAt = pastExpiresAt)
        val result = verifier.verify(expired)
        assertTrue(result.isFailure)
        assertEquals("ENVELOPE_EXPIRED", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testFutureIssuedAt_Rejected() {
        val now = System.currentTimeMillis()
        val futureIssuedAt = now + 120_000L
        val futureExpiresAt = now + 200_000L
        val futureEnvelope = createValidEnvelope(issuedAt = futureIssuedAt, expiresAt = futureExpiresAt)
        val result = verifier.verify(futureEnvelope)
        assertTrue(result.isFailure)
        assertEquals("ENVELOPE_FUTURE_ISSUED", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testInvertedLifetime_Rejected() {
        val now = System.currentTimeMillis()
        val invertedEnvelope = createValidEnvelope(issuedAt = now + 5000L, expiresAt = now - 5000L)
        val result = verifier.verify(invertedEnvelope)
        assertTrue(result.isFailure)
        assertEquals("ENVELOPE_INVALID_LIFETIME", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testBlankFields_Rejected() {
        val env = createValidEnvelope()
        assertTrue(verifier.verify(env.copy(callerId = "")).isFailure)
        assertTrue(verifier.verify(env.copy(capability = "")).isFailure)
        assertTrue(verifier.verify(env.copy(nonce = "")).isFailure)
        assertTrue(verifier.verify(env.copy(signature = "")).isFailure)
    }

    @Test
    fun testUnsupportedEnvelopeVersion_Rejected() {
        // V1.5: Envelope version field is present and checked
        val env = createValidEnvelope()
        val badVersion = env.copy(envelopeVersion = 999)
        val result = verifier.verify(badVersion)
        assertTrue(result.isFailure)
        assertEquals("UNSUPPORTED_ENVELOPE_VERSION", result.failureOrNull()?.errorCode)
    }

    @Test
    fun testNonceReplayRejection() {
        val nonceCache = InMemoryNonceCache()
        val replayVerifier = EnvelopeVerifier(keyRegistry, nonceCache = nonceCache)

        val env = createValidEnvelope(nonce = "unique_nonce_777")
        val firstCheck = replayVerifier.verify(env)
        assertTrue("First submission must succeed", firstCheck.isSuccess)

        val replayCheck = replayVerifier.verify(env)
        assertTrue("Replayed submission must fail", replayCheck.isFailure)
        assertEquals("REPLAY_DETECTED", replayCheck.failureOrNull()?.errorCode)
    }
}
