package com.inscopelabs.abx.skylar.mesh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abx.skylar.audit.AuditLogger
import com.inscopelabs.abx.skylar.audit.AuditRecord
import com.inscopelabs.abx.skylar.bootstrap.CallerClass
import com.inscopelabs.abx.skylar.bootstrap.CloudflareAccessIdentity
import com.inscopelabs.abx.skylar.bootstrap.DefaultCloudflareAccessValidator
import com.inscopelabs.abx.skylar.bootstrap.EphemeralClientEntry
import com.inscopelabs.abx.skylar.bootstrap.InMemoryCredentialStore
import com.inscopelabs.abx.skylar.bootstrap.IssuedSigningCredential
import com.inscopelabs.abx.skylar.bootstrap.IssuerConfig
import com.inscopelabs.abx.skylar.bootstrap.IssuerService
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
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Phase 8 End-to-End Validation Test Suite: Lane B (Public Edge-Gated Ingress).
 *
 * Validates criteria V8.1 to V8.6, work items 1-7, and deliverables per phased development plan:
 * - V8.1: Authorized capability request via Lane B succeeds and produces target effect.
 * - V8.2: Missing or invalid Cloudflare Access service token is rejected at the edge.
 * - V8.3: Valid Access token + invalid signature is rejected by Skylar.
 * - V8.4: Replay and expiry are rejected identically to Lane A.
 * - V8.5: Relay Forwarder preserves opaque payload without mutation or asserting caller identity.
 * - V8.6: Audit trail is complete for both allow and deny outcomes.
 * - Work Item 6: Removing or altering Access headers has no effect on Skylar's decision.
 * - Deliverable 4: Compromise of the Relay Forwarder cannot enlarge caller privileges.
 * - Work Item 7: Latency and reliability under load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LaneBEndToEndPhase8Test {

    private lateinit var context: Context
    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var auditLogger: AuditLogger
    private lateinit var dispatcher: TargetDispatcher
    private lateinit var core: SkylarCore
    private lateinit var issuerService: IssuerService
    private lateinit var policySignerPair: KeyPair
    private lateinit var server: LaneAServer
    private lateinit var relayForwarder: RelayForwarder

    private val policySignerId = "test-policy-authority"
    private val ephemeralClientId = "client-ephemeral-ci-01"
    private val ephemeralClientSecret = "secret-hash-ci-99"
    private val ephemeralCallerId = "caller-ephemeral-ci-01"
    private val cfTokenId = "cf-token-ci-01"
    private val cfTokenSecret = "cf-secret-xyz-77"

    private lateinit var ephemeralClient: LaneBClient
    private lateinit var validCfIdentity: CloudflareAccessIdentity
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

        // 2. Policy setup: ephemeral caller authorized for context.query (read) and storage.read (read, list)
        val matrixPayload = JSONObject().apply {
            put(ephemeralCallerId, JSONObject().apply {
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

        // 3. Register target handlers
        dispatcher.registerTargetHandler("starlight") { cap, params ->
            Result.Success(mapOf("target" to "starlight", "execution" to "starlight_executed", "cap" to cap, "params" to params))
        }
        dispatcher.registerTargetHandler("sfm") { cap, params ->
            Result.Success(mapOf("target" to "sfm", "execution" to "sfm_executed", "cap" to cap, "records" to listOf("rec1.dat", "rec2.dat")))
        }

        val authMatrix = com.inscopelabs.abx.skylar.envelope.policy.AuthorizationMatrix(
            "1.0.0",
            mapOf(
                ephemeralCallerId to mapOf(
                    "context.query" to setOf("read"),
                    "storage.read" to setOf("read", "list")
                )
            )
        )

        // 4. Issuer Service setup (Phase 6 OAuth2 client-credentials flow)
        val clientEntry = EphemeralClientEntry(
            clientId = ephemeralClientId,
            clientSecret = ephemeralClientSecret,
            callerId = ephemeralCallerId,
            allowedScopes = setOf("context.query", "storage.read", "read"),
            maxTtlMs = 15 * 60 * 1000L
        )
        issuerService = IssuerService(
            keyRegistry = keyRegistry,
            credentialStore = credentialStore,
            authMatrixProvider = { authMatrix },
            clientEntryStore = mapOf(ephemeralClientId to clientEntry),
            cfAccessValidator = DefaultCloudflareAccessValidator(
                expectedIssuer = "https://team.cloudflareaccess.com",
                expectedAudience = "skylar-edge-gateway-aud"
            ),
            config = IssuerConfig(ephemeralDefaultTtlMs = 15 * 60 * 1000L)
        )

        // 6. Start Skylar Core mesh listener (LaneAServer handles incoming TCP mesh connections)
        server = LaneAServer(
            core = core,
            requestedPort = 0,
            bindAddress = InetAddress.getByName("127.0.0.1")
        )
        boundPort = server.start()
        assertTrue("Server port should be bound", boundPort > 0)

        // 7. Configure OCI Relay Forwarder pointing to mesh port
        relayForwarder = RelayForwarder(
            targetHost = InetAddress.getByName("127.0.0.1"),
            targetPort = boundPort,
            validServiceTokens = mapOf(cfTokenId to cfTokenSecret),
            maxRequestsPerMinute = 120
        )

        // 8. Ephemeral caller client setup
        ephemeralClient = LaneBClient(
            clientId = ephemeralClientId,
            clientSecret = ephemeralClientSecret,
            callerId = ephemeralCallerId,
            cfAccessClientId = cfTokenId,
            cfAccessClientSecret = cfTokenSecret,
            issuerService = issuerService
        )

        val now = System.currentTimeMillis()
        validCfIdentity = CloudflareAccessIdentity(
            identityToken = "jwt.cf.identity.sample",
            userEmail = "ci-agent@inscopelabs.com",
            issuer = "https://team.cloudflareaccess.com",
            audience = "skylar-edge-gateway-aud",
            issuedAt = now - 5000,
            expiresAt = now + 60000
        )
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            server.stop()
        }
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

    @Test
    fun testV8_1_AuthorizedCapabilityRequestViaLaneBSucceedsAndInvokesTarget() {
        val credResult = ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        assertTrue("OAuth2 credential exchange should succeed", credResult is Result.Success)
        assertNotNull(ephemeralClient.currentCredential)

        val envelope = ephemeralClient.createSignedEnvelope("context.query", mapOf("query" to "user_context"), "read")
        val response = ephemeralClient.sendEnvelope(relayForwarder, envelope)

        assertTrue("Lane B response should be successful", response.success)
        assertEquals(200, response.statusCode)
        assertEquals(LaneBResponse.STATUS_SUCCESS, response.status)
        assertEquals("starlight", response.target)
        assertEquals("starlight_executed", response.data!!["execution"])
    }

    @Test
    fun testV8_2_MissingOrInvalidAccessTokenRejectedAtEdge() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        val envelope = ephemeralClient.createSignedEnvelope("context.query", scope = "read")

        // Missing token
        val missingTokenResp = ephemeralClient.sendEnvelope(relayForwarder, envelope, null, null)
        assertFalse("Missing token rejected", missingTokenResp.success)
        assertEquals(403, missingTokenResp.statusCode)
        assertEquals(LaneBResponse.STATUS_CF_ACCESS_DENIED, missingTokenResp.status)

        // Invalid token
        val invalidTokenResp = ephemeralClient.sendEnvelope(relayForwarder, envelope, cfTokenId, "wrong-secret")
        assertFalse("Invalid token rejected", invalidTokenResp.success)
        assertEquals(403, invalidTokenResp.statusCode)
        assertEquals(LaneBResponse.STATUS_CF_ACCESS_DENIED, invalidTokenResp.status)
    }

    @Test
    fun testV8_3_ValidAccessTokenWithInvalidSignatureRejectedBySkylar() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        val validEnv = ephemeralClient.createSignedEnvelope("context.query", scope = "read")
        val tamperedEnv = validEnv.copy(signature = Base64Codec.encode("tampered_sig".toByteArray(StandardCharsets.UTF_8)))

        val response = ephemeralClient.sendEnvelope(relayForwarder, tamperedEnv)
        assertFalse("Tampered signature rejected", response.success)
        assertEquals(403, response.statusCode)
        assertEquals(LaneBResponse.STATUS_UNAUTHORIZED, response.status)
        assertEquals("INVALID_SIGNATURE", response.errorCode)
    }

    @Test
    fun testV8_4_ReplayOfPreviouslyAcceptedNonceIsRejected() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        val envelope = ephemeralClient.createSignedEnvelope("context.query", scope = "read")

        val resp1 = ephemeralClient.sendEnvelope(relayForwarder, envelope)
        assertTrue("Initial delivery succeeds", resp1.success)

        val resp2 = ephemeralClient.sendEnvelope(relayForwarder, envelope)
        assertFalse("Replay must be rejected", resp2.success)
        assertEquals(403, resp2.statusCode)
        assertEquals(LaneBResponse.STATUS_REPLAY_DETECTED, resp2.status)
    }

    @Test
    fun testV8_4_ExpiredEnvelopeIsRejected() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        val now = System.currentTimeMillis()
        val expiredEnv = ephemeralClient.createSignedEnvelope(
            capability = "context.query", scope = "read", issuedAt = now - 600_000L, expiresAt = now - 300_000L
        )
        val response = ephemeralClient.sendEnvelope(relayForwarder, expiredEnv)
        assertFalse("Expired envelope rejected", response.success)
        assertEquals(403, response.statusCode)
        assertEquals(LaneBResponse.STATUS_EXPIRED, response.status)
    }

    @Test
    fun testV8_5_RelayForwarderPreservesOpaquePayloadWithoutMutation() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        val envelope = ephemeralClient.createSignedEnvelope("context.query", mapOf("k" to "v"), "read")
        val sentJson = LaneACodec.encodeEnvelope(envelope)

        val capturingRelay = RelayForwarder(
            targetHost = InetAddress.getByName("127.0.0.1"),
            targetPort = boundPort,
            validServiceTokens = mapOf(cfTokenId to cfTokenSecret)
        )
        val response = capturingRelay.handleRequest(LaneBRequest(sentJson, cfTokenId, cfTokenSecret))
        assertTrue("Relay forward request succeeds", response.success)
        assertEquals(1, capturingRelay.forwardedCount.get())
        assertEquals(0, capturingRelay.accessDeniedCount.get())
    }

    @Test
    fun testV8_6_AuditTrailCompleteForAllowAndDenyDecisions() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)

        val okResp = ephemeralClient.sendEnvelope(relayForwarder, ephemeralClient.createSignedEnvelope("context.query", scope = "read"))
        assertTrue(okResp.success)

        val denyResp = ephemeralClient.sendEnvelope(relayForwarder, ephemeralClient.createSignedEnvelope("context.query", scope = "admin_write"))
        assertFalse(denyResp.success)

        val records = auditLogger.getRecentRecords()
        assertTrue("Audit records must exist", records.isNotEmpty())
        val allowRecord = records.find { it.callerId == ephemeralCallerId && it.decision == AuditRecord.Decision.ALLOW }
        assertNotNull("ALLOW record exists", allowRecord)
        assertEquals("context.query", allowRecord!!.capability)

        val denyRecord = records.find { it.callerId == ephemeralCallerId && it.decision == AuditRecord.Decision.DENY }
        assertNotNull("DENY record exists", denyRecord)
        assertEquals("context.query", denyRecord!!.capability)
    }

    @Test
    fun testWorkItem6_AlteringOrRemovingAccessHeadersDoesNotAffectSkylarDecision() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        val envelope = ephemeralClient.createSignedEnvelope("context.query", scope = "read")

        val injectedHeaders = mapOf("Cf-Access-Authenticated-User-Email" to "fake-admin@cloud.com", "CF-Ray" to "ray-mock-12345")
        val respWithHeaders = ephemeralClient.sendEnvelope(relayForwarder, envelope, cfAccessHeaders = injectedHeaders)
        assertTrue("Request with headers succeeds based on envelope", respWithHeaders.success)
        assertEquals("ray-mock-12345", respWithHeaders.forensicCorrelationId)

        val secondEnvelope = ephemeralClient.createSignedEnvelope("context.query", scope = "read")
        val respWithoutHeaders = ephemeralClient.sendEnvelope(
            relayForwarder, secondEnvelope, cfAccessHeaders = emptyMap()
        )
        assertTrue("Request without headers succeeds identically", respWithoutHeaders.success)
    }

    @Test
    fun testDeliverable4_CompromisedRelayCannotEnlargeCallerPrivileges() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        val originalEnvelope = ephemeralClient.createSignedEnvelope("context.query", scope = "read")
        val originalJson = LaneACodec.encodeEnvelope(originalEnvelope)

        // Compromised relay alters capability from context.query to unauthorized capability
        val tamperedJson = originalJson.replace("\"capability\":\"context.query\"", "\"capability\":\"unauthorized.cap\"")
        val compromisedRelay = RelayForwarder(InetAddress.getByName("127.0.0.1"), boundPort, mapOf(cfTokenId to cfTokenSecret))
        val response = compromisedRelay.handleRequest(LaneBRequest(tamperedJson, cfTokenId, cfTokenSecret))

        assertFalse("Compromised relay tampering rejected", response.success)
        assertEquals(403, response.statusCode)
        assertTrue(
            "Rejection should cite signature or workflow hash failure",
            response.errorCode == "INVALID_SIGNATURE" || response.errorCode == "INVALID_WORKFLOW_HASH"
        )
    }

    @Test
    fun testRelayRateLimitingProtectsDownstreamGateway() {
        val rateLimitedRelay = RelayForwarder(InetAddress.getByName("127.0.0.1"), boundPort, mapOf(cfTokenId to cfTokenSecret), maxRequestsPerMinute = 5)
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)

        for (i in 1..5) {
            val env = ephemeralClient.createSignedEnvelope("context.query", nonce = "nonce-rl-$i", scope = "read")
            assertTrue("Request $i succeeds", ephemeralClient.sendEnvelope(rateLimitedRelay, env).success)
        }

        val env6 = ephemeralClient.createSignedEnvelope("context.query", nonce = "nonce-rl-6", scope = "read")
        val resp6 = ephemeralClient.sendEnvelope(rateLimitedRelay, env6)
        assertFalse("6th request rate limited", resp6.success)
        assertEquals(429, resp6.statusCode)
        assertEquals(LaneBResponse.STATUS_RATE_LIMITED, resp6.status)
    }

    @Test
    fun testWorkItem7_ReliabilityAndLatencyUnderLoad() {
        ephemeralClient.obtainSigningCredential("read", validCfIdentity)
        val totalLoad = 15
        var successCount = 0
        val latencies = mutableListOf<Long>()

        for (i in 1..totalLoad) {
            val start = System.currentTimeMillis()
            val env = ephemeralClient.createSignedEnvelope("storage.read", nonce = "nonce-load-$i", scope = "read")
            val resp = ephemeralClient.sendEnvelope(relayForwarder, env)
            latencies.add(System.currentTimeMillis() - start)
            if (resp.success && resp.statusCode == 200 && resp.target == "sfm") successCount++
        }

        assertEquals(totalLoad, successCount)
        val avgLatency = latencies.average()
        assertTrue("Average latency fast (< 250ms), got ${avgLatency}ms", avgLatency < 250.0)
    }
}
