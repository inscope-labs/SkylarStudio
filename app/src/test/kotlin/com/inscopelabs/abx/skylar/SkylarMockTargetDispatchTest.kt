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
import com.inscopelabs.abx.skylar.envelope.policy.SignedPolicyArtifact
import com.inscopelabs.abx.skylar.ipc.SfmClient
import com.inscopelabs.abx.skylar.ipc.StarlightClient
import com.inscopelabs.abx.skylar.ipc.TargetAccessEnforcer
import com.inscopelabs.abx.skylar.ipc.TargetDispatcher
import com.inscopelabs.abx.skylar.ipc.XtoolsBridge
import com.inscopelabs.abx.skylar.ipc.aidl.IStarlightService
import com.inscopelabs.abx.skylar.ipc.target.mock.MockSfmTargetService
import com.inscopelabs.abx.skylar.ipc.target.mock.MockStarlightTargetService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * ================================ SCOPE NOTE ================================
 * THIS TEST DOES NOT VALIDATE PHASE 4 (V4.1-V4.6).
 *
 * It exercises the dispatch pipeline's wiring (SkylarCore -> TargetDispatcher
 * -> *Client -> AIDL Stub interface) against IN-PROCESS MOCKS
 * ([MockStarlightTargetService], [MockSfmTargetService]) instantiated via
 * Robolectric in this same JVM test process, with
 * [TargetAccessEnforcer]'s UID check manually overridden. That is useful
 * for confirming the pipeline and AIDL contract shapes are wired
 * correctly — it is NOT a substitute for real cross-app, cross-UID
 * integration testing, which Phase 4 actually requires (device/emulator
 * install of Skylar + the real, separate Starlight and SFM apps).
 *
 * See `docs/skylar-phase-04-real-integration-requirements.md` for what
 * real Phase 4 completion needs. This file was previously named
 * `SkylarTargetIpcPhase4Test.kt` and its tests previously asserted they
 * satisfied V4.1-V4.6 — that claim has been corrected; the test bodies
 * are unchanged in substance, only the framing.
 * ==============================================================================
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkylarMockTargetDispatchTest {

    private lateinit var context: Context
    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var config: SkylarConfig
    private lateinit var nonceCache: PersistentNonceCache
    private lateinit var auditLogger: AuditLogger

    private lateinit var starlightServiceController: org.robolectric.android.controller.ServiceController<MockStarlightTargetService>
    private lateinit var sfmServiceController: org.robolectric.android.controller.ServiceController<MockSfmTargetService>
    private lateinit var starlightService: MockStarlightTargetService
    private lateinit var sfmService: MockSfmTargetService

    private lateinit var starlightClient: StarlightClient
    private lateinit var sfmClient: SfmClient
    private lateinit var xtoolsBridge: XtoolsBridge
    private lateinit var dispatcher: TargetDispatcher
    private lateinit var core: SkylarCore

    private lateinit var testCallerPair: KeyPair
    private lateinit var policySignerPair: KeyPair
    private val testCallerId = "caller.agent.test"
    private val policySignerId = "policy.signer.id"
    private val myProcessUid = android.os.Process.myUid()
    private val forbiddenUid = 99999

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
            callerId = callerId, capability = capability, params = params,
            nonce = nonce, issuedAt = issuedAt, expiresAt = expiresAt, scope = scope
        )
        val canonicalBytes = EnvelopeCanonicalizer.canonicalBytes(
            envelopeVersion = RequestEnvelope.CURRENT_ENVELOPE_VERSION,
            callerId = callerId, capability = capability, params = params,
            nonce = nonce, issuedAt = issuedAt, expiresAt = expiresAt, scope = scope
        )
        val signatureBytes = EcdsaP256SignatureProvider().sign(privateKey.encoded, canonicalBytes)
        return RequestEnvelope(
            callerId = callerId, capability = capability, params = params, nonce = nonce,
            issuedAt = issuedAt, expiresAt = expiresAt, workflowHash = workflowHash,
            signature = Base64Codec.encode(signatureBytes), scope = scope
        )
    }

    private fun signPolicyArtifact(version: String, payload: String): SignedPolicyArtifact {
        val signatureBytes = EcdsaP256SignatureProvider().sign(
            policySignerPair.private.encoded,
            payload.toByteArray(StandardCharsets.UTF_8)
        )
        return SignedPolicyArtifact(version, payload, policySignerId, Base64Codec.encode(signatureBytes))
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

        // Test-only UID override — see class doc: this is exactly why this
        // suite can't validate V4.2 against a real external caller.
        TargetAccessEnforcer.setAllowedUidForTesting(myProcessUid)

        starlightServiceController = Robolectric.buildService(MockStarlightTargetService::class.java).create()
        starlightService = starlightServiceController.get()

        sfmServiceController = Robolectric.buildService(MockSfmTargetService::class.java).create()
        sfmService = sfmServiceController.get()

        val starlightBinder = starlightService.onBind(null)
        val starlightAidl = IStarlightService.Stub.asInterface(starlightBinder)
        starlightClient = StarlightClient(context, customService = starlightAidl)

        val sfmBinder = sfmService.onBind(null)
        val sfmAidl = com.inscopelabs.abx.skylar.ipc.aidl.ISfmService.Stub.asInterface(sfmBinder)
        sfmClient = SfmClient(context, customService = sfmAidl)

        xtoolsBridge = XtoolsBridge(context)

        dispatcher = TargetDispatcher(
            context = context,
            starlightClient = starlightClient,
            sfmClient = sfmClient,
            xtoolsBridge = xtoolsBridge
        )

        core = SkylarCore(
            context = context,
            config = config,
            keyRegistry = keyRegistry,
            verifier = EnvelopeVerifier(keyRegistry, clockSkewToleranceMs = config.clockSkewToleranceMs),
            nonceCache = nonceCache,
            auditLogger = auditLogger,
            dispatcher = dispatcher
        )

        val matrixPayload = JSONObject().apply {
            put(testCallerId, JSONObject().apply {
                put("context.query", JSONArray(listOf("read")))
                put("starlight.workflow.start", JSONArray(listOf("execute")))
                put("storage.read", JSONArray(listOf("read")))
                put("storage.write", JSONArray(listOf("write")))
                put("xtools.plugin.eval", JSONArray(listOf("execute")))
            })
        }.toString()
        val routingPayload = JSONObject().apply {
            put("context.query", StarlightClient.TARGET_ID)
            put("starlight.workflow.start", StarlightClient.TARGET_ID)
            put("storage.read", SfmClient.TARGET_ID)
            put("storage.write", SfmClient.TARGET_ID)
            put("xtools.plugin.eval", XtoolsBridge.TARGET_ID)
        }.toString()

        val initResult = core.initialize(
            authorityArtifact = signPolicyArtifact("1.0.0", matrixPayload),
            routingArtifact = signPolicyArtifact("1.0.0", routingPayload),
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

    @After
    fun tearDown() {
        TargetAccessEnforcer.setAllowedUidForTesting(null)
    }

    @Test
    fun dispatchPipeline_reachesEachMockTargetAndProducesResult() {
        val starlightEnvelope = signEnvelope(testCallerPair.private, testCallerId, "context.query", scope = "read")
        val starlightResult = core.processEnvelope(starlightEnvelope)
        assertTrue("Starlight dispatch must succeed", starlightResult.isSuccess)
        assertEquals("SUCCESS", (starlightResult as Result.Success).data["status"])

        val sfmEnvelope = signEnvelope(
            testCallerPair.private, testCallerId, "storage.write",
            params = mapOf("path" to "test_doc.txt", "content" to "Hello SFM"), scope = "write"
        )
        val sfmResult = core.processEnvelope(sfmEnvelope)
        assertTrue("SFM dispatch must succeed", sfmResult.isSuccess)
        assertEquals("test_doc.txt", (sfmResult as Result.Success).data["path"])

        val xtoolsEnvelope = signEnvelope(
            testCallerPair.private, testCallerId, "xtools.plugin.eval",
            params = mapOf("plugin_id" to "plugin.network.ping"), scope = "execute"
        )
        val xtoolsResult = core.processEnvelope(xtoolsEnvelope)
        assertTrue("xtools dispatch must succeed", xtoolsResult.isSuccess)
        assertEquals("xtools", (xtoolsResult as Result.Success).data["target"])
    }

    @Test
    fun accessEnforcer_rejectsWhenTestOverrideUidDoesNotMatch() {
        // NOTE: this tests TargetAccessEnforcer's own comparison logic in
        // isolation via its test-only override — it does NOT exercise the
        // real signature/permission-check paths a genuinely different app
        // would hit, since nothing here is actually a different app.
        TargetAccessEnforcer.setAllowedUidForTesting(forbiddenUid)

        val envelope = signEnvelope(testCallerPair.private, testCallerId, "context.query", scope = "read")
        val result = core.processEnvelope(envelope)
        assertTrue("Request with mismatched test-override UID must fail dispatch", result.isError)
        assertEquals("PLATFORM_ACCESS_DENIED", (result as Result.Error).errorCode)

        try {
            val aidl = IStarlightService.Stub.asInterface(starlightService.onBind(null))
            aidl.executeCapability("context.query", "{}")
            fail("Direct call without matching test-override UID should have thrown SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Unauthorized caller UID") == true)
        }
    }

    @Test
    fun unauthorizedCapability_isDeniedBySkylarBeforeReachingMock() {
        val unauthorizedEnvelope = signEnvelope(testCallerPair.private, testCallerId, "system.admin.format", scope = "admin")
        val result = core.processEnvelope(unauthorizedEnvelope)
        assertTrue("Unauthorized capability must be denied by Skylar", result.isError)
        assertEquals("UNAUTHORIZED", (result as Result.Error).errorCode)
        assertEquals(0, starlightService.getPendingInboxCount())
    }

    @Test
    fun crashedMockTarget_leavesOtherMockTargetsFunctional() {
        val deadStarlightService = object : IStarlightService.Stub() {
            override fun executeCapability(capability: String?, paramsJson: String?): String {
                throw android.os.DeadObjectException("Starlight process died")
            }
            override fun isAvailable(): Boolean = false
            override fun requiresUserConsent(capability: String?): Boolean = false
            override fun approveWorkflow(workflowId: String?): Boolean = false
        }
        starlightClient.setServiceForTesting(deadStarlightService)

        val starlightEnvelope = signEnvelope(testCallerPair.private, testCallerId, "context.query", scope = "read")
        val starlightResult = core.processEnvelope(starlightEnvelope)
        assertTrue(starlightResult.isError)
        assertEquals("TARGET_CRASHED", (starlightResult as Result.Error).errorCode)

        val sfmEnvelope = signEnvelope(
            testCallerPair.private, testCallerId, "storage.read",
            params = mapOf("path" to "notes.txt"), scope = "read"
        )
        assertTrue(core.processEnvelope(sfmEnvelope).isSuccess)

        val xtoolsEnvelope = signEnvelope(
            testCallerPair.private, testCallerId, "xtools.plugin.eval",
            params = mapOf("plugin_id" to "plugin.system.info"), scope = "execute"
        )
        assertTrue(core.processEnvelope(xtoolsEnvelope).isSuccess)
    }

    @Test
    fun mockStarlightConsentGate_queuesAndCompletesOnApproval() {
        // See class/file doc: this validates the mock's own reimplemented
        // gate logic, not Starlight's real, already-existing consent gate.
        val workflowId = "wf-governed-001"
        val governedEnvelope = signEnvelope(
            testCallerPair.private, testCallerId, "starlight.workflow.start",
            params = mapOf("workflow_id" to workflowId, "action" to "tap_button"), scope = "execute"
        )

        val result1 = core.processEnvelope(governedEnvelope)
        assertTrue(result1.isSuccess)
        assertEquals("PENDING_USER_CONSENT", (result1 as Result.Success).data["status"])
        assertEquals(1, starlightService.getPendingInboxCount())

        assertTrue(starlightClient.approveWorkflow(workflowId))

        val secondEnvelope = signEnvelope(
            testCallerPair.private, testCallerId, "starlight.workflow.start",
            params = mapOf("workflow_id" to workflowId, "action" to "tap_button"), scope = "execute"
        )
        val result2 = core.processEnvelope(secondEnvelope)
        assertTrue(result2.isSuccess)
        assertEquals("COMPLETED", (result2 as Result.Success).data["status"])
        assertEquals(true, (result2).data["approved_by_user"])
    }

    @Test
    fun auditLog_recordsFinalDecisionForAllowAndDeny() {
        val allowEnvelope = signEnvelope(testCallerPair.private, testCallerId, "storage.read", scope = "read")
        core.processEnvelope(allowEnvelope)

        val denyEnvelope = signEnvelope(testCallerPair.private, testCallerId, "unmapped.capability", scope = "read")
        core.processEnvelope(denyEnvelope)

        val records = auditLogger.getRecentRecords()
        assertTrue(records.size >= 2)

        val allowRecord = records[records.size - 2]
        assertEquals(AuditRecord.Decision.ALLOW, allowRecord.decision)
        assertEquals(SfmClient.TARGET_ID, allowRecord.target)

        val denyRecord = records.last()
        assertEquals(AuditRecord.Decision.DENY, denyRecord.decision)
        assertTrue(denyRecord.reason.isNotEmpty())
    }

    @Test
    fun xtoolsPluginTrustTier_failsClosedOnUntrustedPlugin() {
        val untrustedEnvelope = signEnvelope(
            testCallerPair.private, testCallerId, "xtools.plugin.eval",
            params = mapOf("plugin_id" to "malicious.untrusted.script"), scope = "execute"
        )
        val result = core.processEnvelope(untrustedEnvelope)
        assertTrue(result.isError)
        assertEquals("PLUGIN_TRUST_TIER_INSUFFICIENT", (result as Result.Error).errorCode)
    }
}
