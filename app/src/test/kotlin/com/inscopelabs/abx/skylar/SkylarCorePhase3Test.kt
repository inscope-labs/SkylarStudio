package com.inscopelabs.abx.skylar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abx.skylar.audit.AuditLogger
import com.inscopelabs.abx.skylar.audit.AuditRecord
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.core.SkylarCore
import com.inscopelabs.abx.skylar.envelope.EnvelopeCanonicalizer
import com.inscopelabs.abx.skylar.envelope.EnvelopeVerifier
import com.inscopelabs.abx.skylar.envelope.RequestEnvelope
import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.InMemoryKeyRegistry
import com.inscopelabs.abx.skylar.envelope.nonce.PersistentNonceCache
import com.inscopelabs.abx.skylar.envelope.policy.PolicyArtifactReader
import com.inscopelabs.abx.skylar.envelope.policy.SignedPolicyArtifact
import com.inscopelabs.abx.skylar.ipc.TargetDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Phase 3 validation test suite: verify -> authorize -> route -> dispatch -> audit.
 * Demonstrates criteria V3.1 through V3.6 network-free with in-process stubs
 * (`TargetDispatcher.registerTargetHandler`), per Phase 3's own scope — real
 * AIDL dispatch is explicitly out of scope for this phase.
 *
 * Policy is now loaded through a real signed [SignedPolicyArtifact] +
 * [PolicyArtifactReader], not the ungated `initialize(matrix, routingTable)`
 * overload this file previously called — that overload bypassed signature
 * verification and has been removed from [SkylarCore] entirely (see
 * docs/skylar-context-gateway-architecture-addenda.md, 2026-09-12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SkylarCorePhase3Test {

    private lateinit var context: Context
    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var config: SkylarConfig
    private lateinit var nonceCache: PersistentNonceCache
    private lateinit var auditLogger: AuditLogger
    private lateinit var dispatcher: TargetDispatcher
    private lateinit var core: SkylarCore

    private lateinit var testCallerPair: KeyPair
    private lateinit var policySignerPair: KeyPair
    private val testCallerId = "caller.test.client"
    private val policySignerId = "policy.signer.id"

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

    /** Signs an arbitrary JSON payload string as a [SignedPolicyArtifact] using the policy signer key. */
    private fun signPolicyArtifact(version: String, payload: String): SignedPolicyArtifact {
        val signatureBytes = EcdsaP256SignatureProvider().sign(
            policySignerPair.private.encoded,
            payload.toByteArray(StandardCharsets.UTF_8)
        )
        return SignedPolicyArtifact(
            version = version,
            canonicalPayload = payload,
            signerId = policySignerId,
            signature = Base64Codec.encode(signatureBytes)
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyRegistry = InMemoryKeyRegistry()
        config = SkylarConfig.DEFAULT

        testCallerPair = generateEcKeyPair()
        keyRegistry.register(testCallerId, testCallerPair.public.encoded)

        policySignerPair = generateEcKeyPair()
        keyRegistry.register(policySignerId, policySignerPair.public.encoded)

        nonceCache = PersistentNonceCache(context, config.nonceCacheTtlMs)
        nonceCache.clearForTesting()

        auditLogger = AuditLogger(context, config)
        auditLogger.clearInMemory()

        dispatcher = TargetDispatcher(context)

        core = SkylarCore(
            context = context,
            config = config,
            keyRegistry = keyRegistry,
            verifier = EnvelopeVerifier(keyRegistry, clockSkewToleranceMs = config.clockSkewToleranceMs),
            nonceCache = nonceCache,
            auditLogger = auditLogger,
            dispatcher = dispatcher
        )

        // Real signed authorization matrix + routing table, not raw objects
        // handed directly to a bypass method.
        val matrixPayload = JSONObject().apply {
            put(testCallerId, JSONObject().apply {
                put("context.query", JSONArray(listOf("read")))
                put("storage.read", JSONArray(listOf("read", "list")))
                put("system.execute", JSONArray(listOf("admin")))
            })
        }.toString()
        val routingPayload = JSONObject().apply {
            put("context.query", "starlight")
            put("storage.read", "sfm")
            put("system.execute", "xtools")
        }.toString()

        val matrixArtifact = signPolicyArtifact("1.0.0", matrixPayload)
        val routingArtifact = signPolicyArtifact("1.0.0", routingPayload)

        val initResult = core.initialize(
            authorityArtifact = matrixArtifact,
            routingArtifact = routingArtifact,
            parseAuthMatrix = { json ->
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { caller ->
                    val capsObj = obj.getJSONObject(caller)
                    capsObj.keys().asSequence().associateWith { cap ->
                        val arr = capsObj.getJSONArray(cap)
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                }
            },
            parseRoutingTable = { json ->
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            }
        )
        assertTrue("Test setUp policy must load and verify successfully", initResult.isSuccess)
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

        // Simulate complete process restart: instantiate a brand-new
        // PersistentNonceCache pointing to the same SharedPreferences disk storage.
        val restartedNonceCache = PersistentNonceCache(context, config.nonceCacheTtlMs)
        val restartedCore = SkylarCore(
            context = context,
            config = config,
            keyRegistry = keyRegistry,
            verifier = EnvelopeVerifier(keyRegistry, clockSkewToleranceMs = config.clockSkewToleranceMs),
            nonceCache = restartedNonceCache,
            auditLogger = auditLogger,
            dispatcher = dispatcher
        )
        val restartInit = restartedCore.initialize(
            authorityArtifact = signPolicyArtifact("1.0.0", JSONObject().apply {
                put(testCallerId, JSONObject().apply { put("context.query", JSONArray(listOf("read"))) })
            }.toString()),
            routingArtifact = signPolicyArtifact("1.0.0", JSONObject().apply {
                put("context.query", "starlight")
            }.toString()),
            parseAuthMatrix = { json ->
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { caller ->
                    val capsObj = obj.getJSONObject(caller)
                    capsObj.keys().asSequence().associateWith { cap ->
                        val arr = capsObj.getJSONArray(cap)
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    }
                }
            },
            parseRoutingTable = { json ->
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            }
        )
        assertTrue(restartInit.isSuccess)

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
            expiresAt = now - 350_000L,
            scope = "read"
        )

        val result = core.processEnvelope(expiredEnvelope)
        assertTrue("Expired envelope must be rejected", result.isError)
        assertEquals("ENVELOPE_EXPIRED", (result as Result.Error).errorCode)

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
        dispatcher.registerTargetHandler("starlight") { _, _ ->
            throw IllegalStateException("Simulated Starlight hardware crash")
        }

        var sfmExecuted = false
        dispatcher.registerTargetHandler("sfm") { _, _ ->
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
        assertTrue("Target failure returns error", starlightResult.isError)
        assertEquals("TARGET_EXECUTION_EXCEPTION", (starlightResult as Result.Error).errorCode)

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
    fun policyReader_rejectsInvalidSignatureAndFailsClosed() {
        val policyReader = PolicyArtifactReader(keyRegistry)

        val payload = "caller.a:cap.x:read"
        val validArtifact = signPolicyArtifact("2.0.0", payload)
        val loadValid = policyReader.readAuthorizationMatrix(validArtifact) {
            mapOf("caller.a" to mapOf("cap.x" to setOf("read")))
        }
        assertTrue("Valid artifact must load successfully", loadValid.isSuccess)

        // Tampered artifact (altered payload after signing)
        val tamperedArtifact = validArtifact.copy(canonicalPayload = "caller.a:cap.x:admin")
        val loadTampered = policyReader.readAuthorizationMatrix(tamperedArtifact) {
            mapOf("caller.a" to mapOf("cap.x" to setOf("admin")))
        }
        assertTrue("Tampered policy artifact must be rejected", loadTampered.isError)

        // Fallback default-deny check via SkylarCore.initialize with no artefacts
        val denyOnlyCore = SkylarCore(
            context = context,
            config = config,
            keyRegistry = keyRegistry,
            verifier = EnvelopeVerifier(keyRegistry, clockSkewToleranceMs = config.clockSkewToleranceMs),
            nonceCache = PersistentNonceCache(context, config.nonceCacheTtlMs).apply { clearForTesting() },
            auditLogger = auditLogger,
            dispatcher = TargetDispatcher(context)
        )
        val failClosedInit = denyOnlyCore.initialize()
        assertTrue(failClosedInit.isSuccess) // "succeeds" into the safe fail-closed state
        val deniedEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            scope = "read"
        )
        val deniedResult = denyOnlyCore.processEnvelope(deniedEnvelope)
        assertTrue("No policy loaded must mean everything is denied", deniedResult.isError)
        assertEquals("UNAUTHORIZED", (deniedResult as Result.Error).errorCode)
    }
}
