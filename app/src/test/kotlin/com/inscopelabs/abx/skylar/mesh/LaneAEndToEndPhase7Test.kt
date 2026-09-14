package com.inscopelabs.abx.skylar.mesh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abx.skylar.audit.AuditLogger
import com.inscopelabs.abx.skylar.audit.AuditRecord
import com.inscopelabs.abx.skylar.bootstrap.CallerClass
import com.inscopelabs.abx.skylar.bootstrap.DefaultCloudflareAccessValidator
import com.inscopelabs.abx.skylar.bootstrap.InMemoryCredentialStore
import com.inscopelabs.abx.skylar.bootstrap.IssuedSigningCredential
import com.inscopelabs.abx.skylar.bootstrap.IssuerConfig
import com.inscopelabs.abx.skylar.bootstrap.IssuerService
import com.inscopelabs.abx.skylar.bootstrap.TailscaleEnrollmentEvent
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
import com.inscopelabs.abx.skylar.envelope.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.envelope.policy.SignedPolicyArtifact
import com.inscopelabs.abx.skylar.ipc.TargetDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Phase 7 End-to-End Validation Test Suite: Lane A (Private Mesh).
 *
 * Validates criteria V7.1 to V7.6 per phased development plan:
 * - V7.1: Authorized capability request from a mesh member succeeds and produces target effect.
 * - V7.2: Request with valid signature but insufficient scope is denied by Skylar.
 * - V7.3: Replay of a previously accepted nonce is rejected.
 * - V7.4: Expired envelope is rejected.
 * - V7.5: Skylar's mesh node remains userspace-only (no VpnService declaration/usage).
 * - V7.6: Audit trail is complete for both allow and deny outcomes.
 *
 * Also validates Work Item 6 (transport isolation) & Work Item 7 (modest load).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LaneAEndToEndPhase7Test {

    private lateinit var context: Context
    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var auditLogger: AuditLogger
    private lateinit var dispatcher: TargetDispatcher
    private lateinit var core: SkylarCore
    private lateinit var issuerService: IssuerService
    private lateinit var policySignerPair: KeyPair
    private lateinit var server: LaneAServer

    private val policySignerId = "test-policy-authority"
    private val persistentCallerId = "caller-persistent-dev-01"
    private val testDeviceId = "mesh-node-laptop-01"

    private lateinit var persistentCallerCredential: IssuedSigningCredential
    private lateinit var persistentCallerClient: LaneAClient
    private var boundPort: Int = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = SkylarConfig.DEFAULT

        // 1. Generate policy signer keys
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"))
        policySignerPair = keyGen.generateKeyPair()

        keyRegistry = InMemoryKeyRegistry()
        keyRegistry.register(policySignerId, policySignerPair.public.encoded)

        credentialStore = InMemoryCredentialStore()
        auditLogger = AuditLogger(context, config)
        dispatcher = TargetDispatcher(context)

        core = SkylarCore(
            context = context,
            config = config,
            keyRegistry = keyRegistry,
            verifier = EnvelopeVerifier(keyRegistry, clockSkewToleranceMs = config.clockSkewToleranceMs),
            nonceCache = PersistentNonceCache(context, config.nonceCacheTtlMs).apply { clearForTesting() },
            auditLogger = auditLogger,
            dispatcher = dispatcher
        )

        // 2. Policy setup: persistent caller authorized for context.query (read) and storage.read (read, list)
        val matrixMap = mapOf(
            persistentCallerId to mapOf(
                "context.query" to setOf("read"),
                "storage.read" to setOf("read", "list")
            )
        )
        val authMatrix = AuthorizationMatrix("1.0.0", matrixMap)

        val matrixPayload = JSONObject().apply {
            put(persistentCallerId, JSONObject().apply {
                put("context.query", JSONArray(listOf("read")))
                put("storage.read", JSONArray(listOf("read", "list")))
            })
        }.toString()

        val routingPayload = JSONObject().apply {
            put("context.query", "starlight")
            put("storage.read", "sfm")
        }.toString()

        val matrixArtifact = signPolicyArtifact("1.0.0", matrixPayload)
        val routingArtifact = signPolicyArtifact("1.0.0", routingPayload)

        core.initialize(
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

        // 3. Register target execution handlers
        dispatcher.registerTargetHandler("starlight") { cap, params ->
            Result.Success(mapOf("target" to "starlight", "execution" to "starlight_executed", "cap" to cap, "params" to params))
        }
        dispatcher.registerTargetHandler("sfm") { cap, params ->
            Result.Success(mapOf("target" to "sfm", "execution" to "sfm_executed", "cap" to cap, "records" to listOf("file1.txt", "file2.txt")))
        }

        // 4. Issuer Service setup (Phase 6 bootstrap auto-issue flow)
        issuerService = IssuerService(
            keyRegistry = keyRegistry,
            credentialStore = credentialStore,
            authMatrixProvider = { authMatrix },
            clientEntryStore = emptyMap(),
            cfAccessValidator = DefaultCloudflareAccessValidator(),
            config = IssuerConfig()
        )

        // 5. Work Item 1: Auto-issue persistent caller signing credential via Phase 6 flow
        val enrollmentEvent = TailscaleEnrollmentEvent(
            deviceId = testDeviceId,
            tailnetIp = "100.64.0.15",
            nodeKey = "nodekey-ts-persistent-dev-01",
            callerId = persistentCallerId,
            isCompleted = true
        )
        val issueResult = issuerService.handleTailscaleEnrollment(enrollmentEvent)
        assertTrue("Phase 6 auto-issue flow must succeed for persistent caller", issueResult.isSuccess)
        persistentCallerCredential = (issueResult as Result.Success).data

        // 6. Setup persistent caller client
        val transportCredential = TransportCredential(
            credentialId = "transport-cred-01",
            authKey = "tskey-auth-live-01",
            meshNodeId = testDeviceId,
            expiresAt = System.currentTimeMillis() + 86_400_000L
        )
        persistentCallerClient = LaneAClient(
            callerId = persistentCallerId,
            signingCredential = persistentCallerCredential,
            transportCredential = transportCredential
        )

        // 7. Start LaneAServer on dynamic port (port 0) bound to loopback
        server = LaneAServer(
            core = core,
            requestedPort = 0,
            bindAddress = InetAddress.getByName("127.0.0.1")
        )
        boundPort = server.start()
        assertTrue("Server port must be bound and positive", boundPort > 0)
        assertTrue("Server must be running", server.isRunning)
    }

    @After
    fun tearDown() {
        if (::server.isInitialized && server.isRunning) {
            server.stop()
        }
    }

    @Test
    fun testV7_1_AuthorizedCapabilityRequestFromMeshMemberSucceeds() {
        val params = mapOf("query" to "user_context_summary")
        val response = persistentCallerClient.invokeCapability(
            host = "127.0.0.1",
            port = boundPort,
            capability = "context.query",
            params = params,
            scope = "read"
        )

        assertTrue("V7.1: Request must succeed", response.success)
        assertEquals(200, response.statusCode)
        assertEquals(LaneAResponse.STATUS_SUCCESS, response.status)
        assertEquals("starlight", response.target)
        assertNotNull(response.data)
        assertEquals("starlight_executed", response.data?.get("execution"))

        // Confirm server stats
        assertEquals(1L, server.totalRequests.get())
        assertEquals(1L, server.successfulRequests.get())
        assertEquals(0L, server.rejectedRequests.get())
    }

    @Test
    fun testV7_2_RequestWithValidSignatureButInsufficientScopeIsDenied() {
        // storage.write is not in the caller's authorization matrix
        val response = persistentCallerClient.invokeCapability(
            host = "127.0.0.1",
            port = boundPort,
            capability = "storage.read",
            params = mapOf("path" to "/root/admin"),
            scope = "admin_write" // caller only has read, list
        )

        assertFalse("V7.2: Request with insufficient scope must be denied", response.success)
        assertEquals(403, response.statusCode)
        assertEquals(LaneAResponse.STATUS_UNAUTHORIZED, response.status)
        assertEquals("UNAUTHORIZED", response.errorCode)

        val records = auditLogger.getRecentRecords()
        val denyRecord = records.find { it.capability == "storage.read" && it.decision == AuditRecord.Decision.DENY }
        assertNotNull("Audit record must log DENY decision for unauthorized scope", denyRecord)
        assertEquals(persistentCallerId, denyRecord?.callerId)
        assertTrue(denyRecord?.reason?.contains("not authorized") == true)
    }

    @Test
    fun testV7_3_ReplayOfPreviouslyAcceptedNonceIsRejected() {
        val fixedNonce = "replay-test-nonce-12345"
        val envelope = persistentCallerClient.createSignedEnvelope(
            capability = "context.query",
            params = mapOf("query" to "first_attempt"),
            scope = "read",
            nonce = fixedNonce
        )

        // First attempt: should succeed
        val firstResponse = persistentCallerClient.sendEnvelope("127.0.0.1", boundPort, envelope)
        assertTrue("First attempt with nonce must succeed", firstResponse.success)
        assertEquals(200, firstResponse.statusCode)

        // Second attempt: replay of exact same nonce should be rejected
        val secondResponse = persistentCallerClient.sendEnvelope("127.0.0.1", boundPort, envelope)
        assertFalse("V7.3: Replayed nonce must be rejected", secondResponse.success)
        assertEquals(403, secondResponse.statusCode)
        assertEquals(LaneAResponse.STATUS_REPLAY_DETECTED, secondResponse.status)
        assertEquals("REPLAY_DETECTED", secondResponse.errorCode)
    }

    @Test
    fun testV7_4_ExpiredEnvelopeIsRejected() {
        val now = System.currentTimeMillis()
        val expiredEnvelope = persistentCallerClient.createSignedEnvelope(
            capability = "context.query",
            params = mapOf("query" to "stale_request"),
            scope = "read",
            issuedAt = now - 700_000L, // 11.6 minutes ago
            expiresAt = now - 360_000L  // expired 6 minutes ago (exceeds 5-minute clock skew tolerance)
        )

        val response = persistentCallerClient.sendEnvelope("127.0.0.1", boundPort, expiredEnvelope)
        assertFalse("V7.4: Expired envelope must be rejected", response.success)
        assertEquals(403, response.statusCode)
        assertEquals(LaneAResponse.STATUS_EXPIRED, response.status)
        assertEquals("ENVELOPE_EXPIRED", response.errorCode)
    }

    @Test
    fun testV7_5_UserspaceOnlyMeshNodeConfirmation() {
        // V7.5: Confirm Skylar's mesh node remains userspace-only with no VpnService declaration
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
            File("/app/applet/app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }

        assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val manifestContent = manifestFile!!.readText()

        assertFalse(
            "V7.5: AndroidManifest must NOT declare android.permission.BIND_VPN_SERVICE",
            manifestContent.contains("android.permission.BIND_VPN_SERVICE")
        )
        assertFalse(
            "V7.5: AndroidManifest must NOT declare VpnService implementation",
            manifestContent.contains("VpnService")
        )

        // Confirm TsnetMeshNode state surface behaves without requiring system VPN
        val tsnetNode = TsnetMeshNode()
        assertFalse("Node must start stopped", tsnetNode.isRunning())
        assertEquals(MeshNodeState.Stopped, tsnetNode.state.value)
    }

    @Test
    fun testV7_6_AuditTrailCompleteForAllowAndDenyOutcomes() {
        // 1. Trigger ALLOW
        val allowResp = persistentCallerClient.invokeCapability(
            host = "127.0.0.1",
            port = boundPort,
            capability = "storage.read",
            params = mapOf("path" to "/docs"),
            scope = "read"
        )
        assertTrue(allowResp.success)

        // 2. Trigger DENY (unauthorized capability)
        val denyResp = persistentCallerClient.invokeCapability(
            host = "127.0.0.1",
            port = boundPort,
            capability = "admin.shutdown",
            params = emptyMap(),
            scope = "admin"
        )
        assertFalse(denyResp.success)

        // 3. Inspect audit trail
        val records = auditLogger.getRecentRecords()
        val allowRecord = records.find { it.capability == "storage.read" && it.decision == AuditRecord.Decision.ALLOW }
        val denyRecord = records.find { it.capability == "admin.shutdown" && it.decision == AuditRecord.Decision.DENY }

        assertNotNull("V7.6: Audit log must contain ALLOW record", allowRecord)
        assertEquals(persistentCallerId, allowRecord?.callerId)
        assertEquals("sfm", allowRecord?.target)

        assertNotNull("V7.6: Audit log must contain DENY record", denyRecord)
        assertEquals(persistentCallerId, denyRecord?.callerId)
        assertTrue(denyRecord?.reason?.contains("not authorized") == true)
    }

    @Test
    fun testWorkItem6_NonMeshOrUnauthorizedTransportIsRejectedAtBoundary() {
        server.stop()

        // Configure server with transport access filter that rejects unapproved sockets
        val secureServer = LaneAServer(
            core = core,
            requestedPort = 0,
            bindAddress = InetAddress.getByName("127.0.0.1"),
            transportAuthorizer = { socket ->
                // Simulate transport boundary rejection for non-mesh callers
                false
            }
        )
        val securePort = secureServer.start()

        try {
            val response = persistentCallerClient.invokeCapability(
                host = "127.0.0.1",
                port = securePort,
                capability = "context.query",
                scope = "read"
            )

            assertFalse("Non-mesh transport must be rejected", response.success)
            assertEquals(403, response.statusCode)
            assertEquals(LaneAResponse.STATUS_TRANSPORT_DENIED, response.status)
            assertEquals("TRANSPORT_UNAUTHORIZED", response.errorCode)
        } finally {
            secureServer.stop()
        }
    }

    @Test
    fun testWorkItem7_TimingAndReliabilityUnderModestLoad() {
        val iterations = 20
        var successCount = 0
        val latencies = mutableListOf<Long>()

        for (i in 1..iterations) {
            val start = System.currentTimeMillis()
            val response = persistentCallerClient.invokeCapability(
                host = "127.0.0.1",
                port = boundPort,
                capability = "context.query",
                params = mapOf("iteration" to i),
                scope = "read"
            )
            val elapsed = System.currentTimeMillis() - start
            latencies.add(elapsed)
            if (response.success) successCount++
        }

        assertEquals("All $iterations requests under load must succeed", iterations, successCount)
        val avgLatency = latencies.average()
        assertTrue("Average round-trip latency should be rapid (< 100ms)", avgLatency < 100.0)
    }

    @Test
    fun testLaneADirectMeshPathDoesNotDependOnRelayForwarder() {
        // Architecture §1 & §2: Lane A requests travel directly to Skylar, never passing through OCI Relay Forwarder
        val envelope = persistentCallerClient.createSignedEnvelope(
            capability = "context.query",
            scope = "read"
        )
        val response = persistentCallerClient.sendEnvelope("127.0.0.1", boundPort, envelope)
        assertTrue(response.success)
        assertEquals("starlight", response.target)
    }

    private fun signPolicyArtifact(version: String, payload: String): SignedPolicyArtifact {
        val canonicalPayload = payload.trim()
        val signer = EcdsaP256SignatureProvider()
        val signatureBytes = signer.sign(
            policySignerPair.private.encoded,
            canonicalPayload.toByteArray(StandardCharsets.UTF_8)
        )
        return SignedPolicyArtifact(
            version = version,
            canonicalPayload = canonicalPayload,
            signature = Base64Codec.encode(signatureBytes),
            signerId = policySignerId
        )
    }
}
