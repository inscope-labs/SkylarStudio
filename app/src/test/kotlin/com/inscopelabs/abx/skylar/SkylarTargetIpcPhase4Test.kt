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
import com.inscopelabs.abx.skylar.ipc.SfmClient
import com.inscopelabs.abx.skylar.ipc.StarlightClient
import com.inscopelabs.abx.skylar.ipc.TargetAccessEnforcer
import com.inscopelabs.abx.skylar.ipc.TargetDispatcher
import com.inscopelabs.abx.skylar.ipc.XtoolsBridge
import com.inscopelabs.abx.skylar.ipc.aidl.IStarlightService
import com.inscopelabs.abx.skylar.ipc.target.SfmTargetService
import com.inscopelabs.abx.skylar.ipc.target.StarlightTargetService
import com.inscopelabs.abx.skylar.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.policy.RoutingTable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Phase 4 validation test suite: On-device target integration via protected AIDL / local IPC.
 * Verifies validation criteria V4.1 through V4.6.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkylarTargetIpcPhase4Test {

    private lateinit var context: Context
    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var config: SkylarConfig
    private lateinit var nonceCache: PersistentNonceCache
    private lateinit var auditLogger: AuditLogger

    private lateinit var starlightServiceController: org.robolectric.android.controller.ServiceController<StarlightTargetService>
    private lateinit var sfmServiceController: org.robolectric.android.controller.ServiceController<SfmTargetService>
    private lateinit var starlightService: StarlightTargetService
    private lateinit var sfmService: SfmTargetService

    private lateinit var starlightClient: StarlightClient
    private lateinit var sfmClient: SfmClient
    private lateinit var xtoolsBridge: XtoolsBridge
    private lateinit var dispatcher: TargetDispatcher
    private lateinit var core: SkylarCore

    private lateinit var testCallerPair: KeyPair
    private val testCallerId = "caller.agent.test"
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

        // Set allowed UID to our test process UID
        TargetAccessEnforcer.setAllowedUidForTesting(myProcessUid)

        // Instantiate target services via Robolectric
        starlightServiceController = Robolectric.buildService(StarlightTargetService::class.java).create()
        starlightService = starlightServiceController.get()

        sfmServiceController = Robolectric.buildService(SfmTargetService::class.java).create()
        sfmService = sfmServiceController.get()

        // Wire concrete AIDL clients to services
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
            verifier = EnvelopeVerifier(keyRegistry, config),
            nonceCache = nonceCache,
            auditLogger = auditLogger,
            dispatcher = dispatcher
        )

        // Configure authorization and routing
        val matrix = AuthorizationMatrix(
            version = "1.0.0",
            matrix = mapOf(
                testCallerId to mapOf(
                    "context.query" to setOf("read"),
                    "starlight.workflow.start" to setOf("execute"),
                    "storage.read" to setOf("read"),
                    "storage.write" to setOf("write"),
                    "xtools.plugin.eval" to setOf("execute")
                )
            )
        )
        val routing = RoutingTable(
            version = "1.0.0",
            routes = mapOf(
                "context.query" to StarlightClient.TARGET_ID,
                "starlight.workflow.start" to StarlightClient.TARGET_ID,
                "storage.read" to SfmClient.TARGET_ID,
                "storage.write" to SfmClient.TARGET_ID,
                "xtools.plugin.eval" to XtoolsBridge.TARGET_ID
            )
        )
        core.initialize(matrix, routing)
    }

    @After
    fun tearDown() {
        TargetAccessEnforcer.setAllowedUidForTesting(null)
    }

    @Test
    fun v4_1_authorizedRequest_reachesTargetAndProducesResult() {
        // Test Starlight dispatch
        val starlightEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            scope = "read"
        )
        val starlightResult = core.processEnvelope(starlightEnvelope)
        assertTrue("Starlight dispatch must succeed", starlightResult.isSuccess)
        val starlightData = (starlightResult as Result.Success).data
        assertEquals("SUCCESS", starlightData["status"])
        assertEquals("starlight", starlightData["target"])

        // Test SFM dispatch
        val sfmEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "storage.write",
            params = mapOf("path" to "test_doc.txt", "content" to "Hello SFM"),
            scope = "write"
        )
        val sfmResult = core.processEnvelope(sfmEnvelope)
        assertTrue("SFM dispatch must succeed", sfmResult.isSuccess)
        val sfmData = (sfmResult as Result.Success).data
        assertEquals("SUCCESS", sfmData["status"])
        assertEquals("test_doc.txt", sfmData["path"])

        // Test xtools dispatch
        val xtoolsEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "xtools.plugin.eval",
            params = mapOf("plugin_id" to "plugin.network.ping"),
            scope = "execute"
        )
        val xtoolsResult = core.processEnvelope(xtoolsEnvelope)
        assertTrue("xtools dispatch must succeed", xtoolsResult.isSuccess)
        val xtoolsData = (xtoolsResult as Result.Success).data
        assertEquals("SUCCESS", xtoolsData["status"])
        assertEquals("xtools", xtoolsData["target"])
    }

    @Test
    fun v4_2_nonSkylarUid_isRejectedByTargetAidlSurface() {
        // Set allowed UID to a different UID so that our test process is seen as unauthorized
        TargetAccessEnforcer.setAllowedUidForTesting(forbiddenUid)

        val envelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            scope = "read"
        )

        val result = core.processEnvelope(envelope)
        assertTrue("Request with unauthorized UID must fail dispatch", result.isError)
        val err = result as Result.Error
        assertEquals("PLATFORM_ACCESS_DENIED", err.errorCode)

        // Verify direct AIDL call from unauthorized UID throws SecurityException
        try {
            starlightService.onBind(null)
            val aidl = IStarlightService.Stub.asInterface(starlightService.onBind(null))
            aidl.executeCapability("context.query", "{}")
            fail("Direct call without authorized UID should have thrown SecurityException")
        } catch (e: SecurityException) {
            assertTrue("Expected SecurityException on direct call", e.message?.contains("Unauthorized caller UID") == true)
        }
    }

    @Test
    fun v4_3_unauthorizedCapability_isDeniedBeforeDispatch() {
        // Sign an envelope with capability NOT in the caller's authorized scope
        val unauthorizedEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "system.admin.format",
            scope = "admin"
        )

        val result = core.processEnvelope(unauthorizedEnvelope)
        assertTrue("Unauthorized capability must be denied by Skylar", result.isError)
        assertEquals("UNAUTHORIZED", (result as Result.Error).errorCode)

        // Verify target service was NEVER invoked
        assertEquals(0, starlightService.getPendingInboxCount())
    }

    @Test
    fun v4_4_forceStoppingStarlight_leavesSfmAndXtoolsFunctional() {
        // Simulate Starlight crash / force-stop by injecting a throwing/dead service
        val deadStarlightService = object : IStarlightService.Stub() {
            override fun executeCapability(capability: String?, paramsJson: String?): String {
                throw android.os.DeadObjectException("Starlight process died")
            }
            override fun isAvailable(): Boolean = false
            override fun requiresUserConsent(capability: String?): Boolean = false
            override fun approveWorkflow(workflowId: String?): Boolean = false
        }
        starlightClient.setServiceForTesting(deadStarlightService)

        // Request to Starlight fails with target crash error
        val starlightEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "context.query",
            scope = "read"
        )
        val starlightResult = core.processEnvelope(starlightEnvelope)
        assertTrue("Starlight dispatch should fail with crash error", starlightResult.isError)
        assertEquals("TARGET_CRASHED", (starlightResult as Result.Error).errorCode)

        // Verify SFM remains fully functional
        val sfmEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "storage.read",
            params = mapOf("path" to "notes.txt"),
            scope = "read"
        )
        val sfmResult = core.processEnvelope(sfmEnvelope)
        assertTrue("SFM must remain functional when Starlight is down", sfmResult.isSuccess)

        // Verify xtools remains fully functional
        val xtoolsEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "xtools.plugin.eval",
            params = mapOf("plugin_id" to "plugin.system.info"),
            scope = "execute"
        )
        val xtoolsResult = core.processEnvelope(xtoolsEnvelope)
        assertTrue("xtools must remain functional when Starlight is down", xtoolsResult.isSuccess)
    }

    @Test
    fun v4_5_starlightUserConsentGate_isStillEnforced() {
        val workflowId = "wf-governed-001"
        val governedEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "starlight.workflow.start",
            params = mapOf("workflow_id" to workflowId, "action" to "tap_button"),
            scope = "execute"
        )

        // Dispatch 1: Skylar authorizes, but Starlight enforces Request Inbox consent gate
        val result1 = core.processEnvelope(governedEnvelope)
        assertTrue("First dispatch succeeds at gateway level", result1.isSuccess)
        val data1 = (result1 as Result.Success).data
        assertEquals("PENDING_USER_CONSENT", data1["status"])
        assertEquals(workflowId, data1["workflow_id"])
        assertEquals(1, starlightService.getPendingInboxCount())

        // Simulate user reviewing and approving in Starlight's Request Inbox UI
        val approved = starlightClient.approveWorkflow(workflowId)
        assertTrue("Approval in Request Inbox must succeed", approved)

        // Dispatch 2: Now workflow executes to completion
        val secondEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "starlight.workflow.start",
            params = mapOf("workflow_id" to workflowId, "action" to "tap_button"),
            scope = "execute"
        )
        val result2 = core.processEnvelope(secondEnvelope)
        assertTrue("Subsequent dispatch of approved workflow succeeds", result2.isSuccess)
        val data2 = (result2 as Result.Success).data
        assertEquals("COMPLETED", data2["status"])
        assertEquals(true, data2["approved_by_user"])
    }

    @Test
    fun v4_6_auditLog_recordsFinalDecisionForAllowAndDeny() {
        // Successful allow
        val allowEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "storage.read",
            scope = "read"
        )
        core.processEnvelope(allowEnvelope)

        // Denied case
        val denyEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "unmapped.capability",
            scope = "read"
        )
        core.processEnvelope(denyEnvelope)

        val records = auditLogger.getRecentRecords()
        assertTrue("Audit log should have at least 2 records", records.size >= 2)

        val allowRecord = records[records.size - 2]
        assertEquals(AuditRecord.Decision.ALLOW, allowRecord.decision)
        assertEquals("storage.read", allowRecord.capability)
        assertEquals(SfmClient.TARGET_ID, allowRecord.target)

        val denyRecord = records.last()
        assertEquals(AuditRecord.Decision.DENY, denyRecord.decision)
        assertEquals("unmapped.capability", denyRecord.capability)
        assertTrue("Reason should state not authorized or not mapped", denyRecord.reason.isNotEmpty())
    }

    @Test
    fun xtoolsPluginTrustTier_failsClosedOnUntrustedPlugin() {
        val untrustedEnvelope = signEnvelope(
            privateKey = testCallerPair.private,
            callerId = testCallerId,
            capability = "xtools.plugin.eval",
            params = mapOf("plugin_id" to "malicious.untrusted.script"),
            scope = "execute"
        )

        val result = core.processEnvelope(untrustedEnvelope)
        assertTrue("Untrusted plugin must fail closed inside xtools", result.isError)
        val err = result as Result.Error
        assertEquals("PLUGIN_TRUST_TIER_INSUFFICIENT", err.errorCode)
    }
}
