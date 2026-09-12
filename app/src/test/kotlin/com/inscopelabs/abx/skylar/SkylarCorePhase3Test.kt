package com.inscopelabs.abx.skylar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abx.skylar.audit.AuditLogger
import com.inscopelabs.abx.skylar.audit.AuditRecord
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.core.EnvelopeCanonicalizer
import com.inscopelabs.abx.skylar.core.EnvelopeVerifier
import com.inscopelabs.abx.skylar.core.PersistentNonceCache
import com.inscopelabs.abx.skylar.core.RequestEnvelope
import com.inscopelabs.abx.skylar.core.SkylarCore
import com.inscopelabs.abx.skylar.crypto.Base64Codec
import com.inscopelabs.abx.skylar.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.crypto.InMemoryKeyRegistry
import com.inscopelabs.abx.skylar.ipc.TargetDispatcher
import com.inscopelabs.abx.skylar.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.policy.PolicyLoader
import com.inscopelabs.abx.skylar.policy.RoutingTable
import com.inscopelabs.abx.skylar.policy.SignedPolicyArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Phase 3 validation test suite: verify -> authorize -> route -> dispatch -> audit.
 * Demonstrates criteria V3.1 through V3.6 network-free with in-process stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkylarCorePhase3Test {

    private lateinit var context: Context
    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var config: SkylarConfig
    private lateinit var nonceCache: PersistentNonceCache
    private lateinit var auditLogger: AuditLogger
    private lateinit var dispatcher: TargetDispatcher
    private lateinit var core: SkylarCore

    private lateinit var testCallerPair: KeyPair
    private val testCallerId = "caller.test.client"

    private fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun signEnvelope(
        privateKey: PrivateKey,
        callerId: String,
        capability: String,
        params: Map<String, Any?> = emptyMap(),
        nonce: String = "nonce-${System.nanoTime()}",
        issuedAt: Long = System.currentTimeMillis(),
        expiresAt: Long = System.currentTimeMillis() + 60_000L,
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyRegistry = InMemoryKeyRegistry()
        config = SkylarConfig.DEFAULT

        testCallerPair = generateEcKeyPair()
        keyRegistry.register(testCallerId, testCallerPair.public.encoded)

        nonceCache = PersistentNonceCache(context, config)
        nonceCache.clearForTesting()

        auditLogger = AuditLogger(context, config)
        auditLogger.clearInMemory()

        dispatcher = TargetDispatcher(context)

        core = SkylarCore(
            context = context,
            config = config,
            keyRegistry = keyRegistry,
            verifier = EnvelopeVerifier(keyRegistry, config),
            nonceCache = nonceCache,
            auditLogger = auditLogger,
            dispatcher = dispatcher
        )

        val matrix = AuthorizationMatrix(
            version = "1.0.0",
            matrix = mapOf(
                testCallerId to mapOf(
                    "context.query" to setOf("read"),
                    "storage.read" to setOf("read", "list"),
                    "system.execute" to setOf("admin")
                )
            )
        )
        val routing = RoutingTable(
            version = "1.0.0",
            routes = mapOf(
                "context.query" to "starlight",
                "storage.read" to "sfm",
                "system.execute" to "xtools"
            )
        )
        core.initialize(matrix, routing)
    }

    @Test
    fun v3_1_validEnvelope_acceptedAndRoutedToStub() {
        var stubExecuted = false
        dispatcher.registerTargetHandler("starlight") { cap, params ->
            stubExecuted = true
            assertEquals("context.query", cap)
            assertEquals("user_location", params["query"])
            Result.Success(mapOf("status" to "ok", "result" to "mock_location"))
        }

        val envelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            params = mapOf("query" to "user_location"),
            scope = "read"
        )

        val result = core.processEnvelope(envelope)
        assertTrue("Expected dispatch success", result.isSuccess)
        assertTrue("Stub was executed", stubExecuted)

        val recent = auditLogger.getRecentRecords()
        assertFalse(recent.isEmpty())
        val lastAudit = recent.last()
        assertEquals(AuditRecord.Decision.ALLOW, lastAudit.decision)
        assertEquals("starlight", lastAudit.target)
    }

    @Test
    fun v3_2_envelopeWithUnknownCapabilityOrInsufficientScope_denied() {
        val unknownCapEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "unknown.forbidden.cap"
        )
        val unknownResult = core.processEnvelope(unknownCapEnvelope)
        assertTrue("Expected failure on unmapped capability", unknownResult.isError)
        assertEquals("UNAUTHORIZED", (unknownResult as Result.Error).errorCode)

        val insufficientScopeEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            scope = "write" // Only 'read' is allowed in matrix
        )
        val scopeResult = core.processEnvelope(insufficientScopeEnvelope)
        assertTrue("Expected failure on insufficient scope", scopeResult.isError)
        assertEquals("UNAUTHORIZED", (scopeResult as Result.Error).errorCode)
    }

    @Test
    fun v3_3_replayOfPreviouslySeenNonce_rejectedEvenAfterRestart() {
        dispatcher.registerTargetHandler("starlight") { _, _ -> Result.Success(emptyMap()) }

        val fixedNonce = "replay-nonce-999"
        val envelope1 = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            nonce = fixedNonce,
            scope = "read"
        )

        val result1 = core.processEnvelope(envelope1)
        assertTrue("First attempt should succeed", result1.isSuccess)

        // Replay attempt 1: Immediate in same process
        val envelopeReplay = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            nonce = fixedNonce,
            scope = "read"
        )
        val resultReplay = core.processEnvelope(envelopeReplay)
        assertTrue("Immediate replay must be rejected", resultReplay.isError)
        assertEquals("REPLAY_DETECTED", (resultReplay as Result.Error).errorCode)

        // Simulate complete process restart: instantiate a brand-new PersistentNonceCache
        // pointing to the same SharedPreferences disk storage
        val restartedNonceCache = PersistentNonceCache(context, config)
        val restartedCore = SkylarCore(
            context = context,
            config = config,
            keyRegistry = keyRegistry,
            verifier = EnvelopeVerifier(keyRegistry, config),
            nonceCache = restartedNonceCache,
            auditLogger = auditLogger,
            dispatcher = dispatcher
        )
        restartedCore.initialize(
            AuthorizationMatrix("1.0.0", mapOf(testCallerId to mapOf("context.query" to setOf("read")))),
            RoutingTable("1.0.0", mapOf("context.query" to "starlight"))
        )

        val resultAfterRestart = restartedCore.processEnvelope(envelopeReplay)
        assertTrue("Replay after simulated restart must be rejected", resultAfterRestart.isError)
        assertEquals("REPLAY_DETECTED", (resultAfterRestart as Result.Error).errorCode)
    }

    @Test
    fun v3_4_expiredEnvelope_rejectedBeforeAuthorizationEvaluated() {
        val now = System.currentTimeMillis()
        val expiredEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            issuedAt = now - 600_000L,
            expiresAt = now - 300_000L,
            scope = "read"
        )

        val result = core.processEnvelope(expiredEnvelope)
        assertTrue("Expired envelope must be rejected", result.isError)
        assertEquals("ENVELOPE_INVALID", (result as Result.Error).errorCode)

        // Verify audit log captures the rejection
        val lastAudit = auditLogger.getRecentRecords().last()
        assertEquals(AuditRecord.Decision.DENY, lastAudit.decision)
        assertTrue(lastAudit.reason.contains("expired", ignoreCase = true))
    }

    @Test
    fun v3_5_auditRecord_containsAllMandatoryFields() {
        dispatcher.registerTargetHandler("starlight") { _, _ -> Result.Success(emptyMap()) }

        val nonce = "audit-verify-nonce-001"
        val envelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            nonce = nonce,
            scope = "read"
        )

        core.processEnvelope(envelope)

        val recent = auditLogger.getRecentRecords()
        assertFalse(recent.isEmpty())
        val record = recent.last()

        assertEquals(testCallerId, record.callerId)
        assertEquals("context.query", record.capability)
        assertEquals(AuditRecord.Decision.ALLOW, record.decision)
        assertEquals(nonce, record.nonce)
        assertEquals(envelope.workflowHash, record.envelopeHash)
        assertEquals("starlight", record.target)
        assertEquals("1.0.0", record.policyVersion)
        assertTrue(record.timestamp > 0)
        assertNotNull(record.toFormattedLogLine())
    }

    @Test
    fun v3_6_failureOfOneTargetStub_leavesOtherStubsReachable() {
        // Starlight stub fails with an error/exception
        dispatcher.registerTargetHandler("starlight") { _, _ ->
            throw IllegalStateException("Simulated Starlight hardware crash")
        }

        // SFM stub functions normally
        var sfmExecuted = false
        dispatcher.registerTargetHandler("sfm") { cap, _ ->
            sfmExecuted = true
            Result.Success(mapOf("file" to "content.txt"))
        }

        val starlightEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            scope = "read"
        )
        val starlightResult = core.processEnvelope(starlightEnvelope)
        // Pipeline allowed and dispatched, but target execution failed
        assertTrue("Target failure returns error", starlightResult.isError)
        assertEquals("TARGET_EXECUTION_EXCEPTION", (starlightResult as Result.Error).errorCode)

        // Verify SFM request is completely unaffected and succeeds
        val sfmEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "storage.read",
            scope = "read"
        )
        val sfmResult = core.processEnvelope(sfmEnvelope)
        assertTrue("SFM target must remain operational", sfmResult.isSuccess)
        assertTrue("SFM stub was executed successfully", sfmExecuted)
    }

    @Test
    fun policyLoader_rejectsInvalidSignatureAndFailsClosed() {
        val policySignerPair = generateEcKeyPair()
        val signerId = "policy.signer.id"
        keyRegistry.register(signerId, policySignerPair.public.encoded)

        val policyLoader = PolicyLoader(context, keyRegistry)

        val payload = "caller.a:cap.x:read"
        val validSig = EcdsaP256SignatureProvider().sign(
            policySignerPair.private.encoded,
            payload.toByteArray(StandardCharsets.UTF_8)
        )

        // Valid artifact
        val validArtifact = SignedPolicyArtifact(
            version = "2.0.0",
            canonicalPayload = payload,
            signerId = signerId,
            signature = Base64Codec.encode(validSig)
        )
        val loadValid = policyLoader.loadAuthorizationMatrixFromArtifact(validArtifact) {
            mapOf("caller.a" to mapOf("cap.x" to setOf("read")))
        }
        assertTrue("Valid artifact must load successfully", loadValid.isSuccess)

        // Tampered artifact (altered payload)
        val tamperedArtifact = validArtifact.copy(canonicalPayload = "caller.a:cap.x:admin")
        val loadTampered = policyLoader.loadAuthorizationMatrixFromArtifact(tamperedArtifact) {
            mapOf("caller.a" to mapOf("cap.x" to setOf("admin")))
        }
        assertTrue("Tampered policy artifact must be rejected", loadTampered.isError)

        // Fallback default-deny check
        val (defaultMatrix, defaultRouting) = policyLoader.failClosedDefaults()
        assertFalse(defaultMatrix.isAuthorized("caller.a", "cap.x"))
        assertEquals(0, defaultMatrix.callerCount())
        assertEquals(0, defaultRouting.routeCount())
    }
}
