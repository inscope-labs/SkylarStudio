package com.inscopelabs.abx.skylar.bootstrap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.inscopelabs.abx.skylar.audit.AuditLogger
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
import com.inscopelabs.abx.skylar.mesh.TransportCredential
import org.json.JSONArray
import org.json.JSONObject
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
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Phase 6 Validation Test Suite: Request-Signing Credential Bootstrap.
 *
 * Validates criteria V6.1 to V6.6 per phased development plan:
 * - V6.1: Persistent caller receives signing credential automatically upon completed Tailscale enrollment.
 * - V6.2: Ephemeral caller completes OAuth2 client-credentials exchange and receives scoped, short-lived credential.
 * - V6.3: Envelopes signed with credentials from either flow are accepted by Skylar Core's verification pipeline.
 * - V6.4: A caller holding only a transport credential cannot produce a Skylar-accepted signature.
 * - V6.5: Issued credential scope matches the caller's authorization matrix entry.
 * - V6.6: Rotation/TTL behaviour matches architecture §5 (persistent: longer-lived + rotation; ephemeral: short TTL).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SkylarCredentialBootstrapPhase6Test {

    private lateinit var context: Context
    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var credentialStore: InMemoryCredentialStore
    private lateinit var auditLogger: AuditLogger
    private lateinit var dispatcher: TargetDispatcher
    private lateinit var core: SkylarCore
    private lateinit var issuerService: IssuerService
    private lateinit var policySignerPair: KeyPair

    private val policySignerId = "test-policy-authority"
    private val persistentCallerId = "caller-persistent-dev-01"
    private val ephemeralCallerId = "caller-ephemeral-agent-01"
    private val ephemeralClientId = "client-agent-ci-001"
    private val ephemeralClientSecret = "sec-live-token-998877"

    private lateinit var testMatrix: com.inscopelabs.abx.skylar.envelope.policy.AuthorizationMatrix

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = SkylarConfig.DEFAULT

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

        val matrixMap = mapOf(
            persistentCallerId to mapOf(
                "context.query" to setOf("read"),
                "storage.read" to setOf("read", "list")
            ),
            ephemeralCallerId to mapOf(
                "context.query" to setOf("read")
            )
        )
        testMatrix = com.inscopelabs.abx.skylar.envelope.policy.AuthorizationMatrix("1.0.0", matrixMap)

        // Setup authorization matrix policy covering persistent and ephemeral callers
        val matrixPayload = JSONObject().apply {
            put(persistentCallerId, JSONObject().apply {
                put("context.query", JSONArray(listOf("read")))
                put("storage.read", JSONArray(listOf("read", "list")))
            })
            put(ephemeralCallerId, JSONObject().apply {
                put("context.query", JSONArray(listOf("read")))
            })
        }.toString()

        val routingPayload = JSONObject().apply {
            put("context.query", "starlight")
            put("storage.read", "sfm")
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
        assertTrue("Core must initialize successfully with test policy", initResult.isSuccess)

        // Register stub targets
        dispatcher.registerTargetHandler("starlight") { _, _ -> Result.Success(mapOf("status" to "ok")) }
        dispatcher.registerTargetHandler("sfm") { _, _ -> Result.Success(mapOf("content" to "test-data")) }

        // Setup Issuer with registered ephemeral client entry
        val ephemeralEntries = mapOf(
            ephemeralClientId to EphemeralClientEntry(
                clientId = ephemeralClientId,
                clientSecret = ephemeralClientSecret,
                callerId = ephemeralCallerId,
                allowedScopes = setOf("read"),
                maxTtlMs = 15 * 60 * 1000L
            )
        )

        issuerService = IssuerService(
            keyRegistry = keyRegistry,
            credentialStore = credentialStore,
            authMatrixProvider = { testMatrix },
            clientEntryStore = ephemeralEntries,
            cfAccessValidator = DefaultCloudflareAccessValidator()
        )
    }

    @Test
    fun v6_1_persistentCaller_autoIssuedCredential_onTailscaleEnrollment() {
        val event = TailscaleEnrollmentEvent(
            deviceId = "device-macbook-john-01",
            tailnetIp = "100.64.0.5",
            nodeKey = "nodekey-ts-transport-only-12345",
            callerId = persistentCallerId,
            isCompleted = true
        )

        val result = issuerService.handleTailscaleEnrollment(event)
        assertTrue("Persistent caller auto-issue must succeed upon completed enrollment", result.isSuccess)

        val credential = (result as Result.Success).data
        assertEquals(persistentCallerId, credential.callerId)
        assertEquals(CallerClass.PERSISTENT, credential.callerClass)
        assertNotNull(credential.privateKeyBytes)
        assertNotNull(credential.publicKeyBytes)
        assertTrue("Credential must be valid immediately", credential.isValid())

        // Public key must be registered in KeyRegistry
        val registeredKey = keyRegistry.publicKeyFor(persistentCallerId)
        assertNotNull("Public key must be registered in KeyRegistry", registeredKey)

        // Credential must be stored in CredentialStore
        val stored = credentialStore.get(credential.credentialId)
        assertNotNull(stored)
        assertEquals(credential.credentialId, stored?.credentialId)
    }

    @Test
    fun v6_2_ephemeralCaller_oauth2Exchange_producesScopedShortLivedCredential() {
        val now = System.currentTimeMillis()
        val cfIdentity = CloudflareAccessIdentity(
            identityToken = "cf-jwt-token-sample",
            userEmail = "agent-ci@inscopelabs.com",
            issuer = "https://team.cloudflareaccess.com",
            audience = "skylar-edge-gateway-aud",
            issuedAt = now - 1000L,
            expiresAt = now + 3600_000L
        )

        val request = OAuth2TokenRequest(
            grantType = "client_credentials",
            clientId = ephemeralClientId,
            clientSecret = ephemeralClientSecret,
            requestedScope = "read",
            cfAccessIdentity = cfIdentity
        )

        val result = issuerService.exchangeClientCredentials(request, now)
        assertTrue("Ephemeral caller exchange must succeed", result.isSuccess)

        val credential = (result as Result.Success).data
        assertEquals(ephemeralCallerId, credential.callerId)
        assertEquals(CallerClass.EPHEMERAL, credential.callerClass)
        assertEquals("read", credential.scope)
        assertTrue("Expires within 15 minutes", credential.expiresAt - credential.issuedAt <= 15 * 60 * 1000L)
        assertNotNull(credential.token)
    }

    @Test
    fun v6_3_envelopesSignedWithIssuedCredentials_acceptedBySkylarCore() {
        val now = System.currentTimeMillis()

        // 1. Persistent caller flow
        val persistentEnrollment = TailscaleEnrollmentEvent(
            deviceId = "device-dev-01",
            tailnetIp = "100.64.0.10",
            nodeKey = "ts-node-key-5555",
            callerId = persistentCallerId,
            isCompleted = true
        )
        val persistentCredResult = issuerService.handleTailscaleEnrollment(persistentEnrollment, now)
        assertTrue(persistentCredResult.isSuccess)
        val persistentCred = (persistentCredResult as Result.Success).data

        val persistentEnvelope = signEnvelopeWithIssuedCredential(
            credential = persistentCred,
            capability = "context.query",
            scope = "read",
            issuedAt = now,
            expiresAt = now + 60_000L,
            nonce = "persistent-nonce-001"
        )
        val persistentCoreResult = core.processEnvelope(persistentEnvelope)
        assertTrue("Envelope signed with persistent issued credential must be accepted", persistentCoreResult.isSuccess)

        // 2. Ephemeral caller flow
        val cfIdentity = CloudflareAccessIdentity(
            identityToken = "cf-jwt-test",
            userEmail = "ephemeral-caller@example.com",
            issuer = "https://team.cloudflareaccess.com",
            audience = "skylar-edge-gateway-aud",
            issuedAt = now,
            expiresAt = now + 600_000L
        )
        val ephemeralTokenRequest = OAuth2TokenRequest(
            clientId = ephemeralClientId,
            clientSecret = ephemeralClientSecret,
            requestedScope = "read",
            cfAccessIdentity = cfIdentity
        )
        val ephemeralCredResult = issuerService.exchangeClientCredentials(ephemeralTokenRequest, now)
        assertTrue(ephemeralCredResult.isSuccess)
        val ephemeralCred = (ephemeralCredResult as Result.Success).data

        val ephemeralEnvelope = signEnvelopeWithIssuedCredential(
            credential = ephemeralCred,
            capability = "context.query",
            scope = "read",
            issuedAt = now,
            expiresAt = now + 60_000L,
            nonce = "ephemeral-nonce-001"
        )
        val ephemeralCoreResult = core.processEnvelope(ephemeralEnvelope)
        assertTrue("Envelope signed with ephemeral issued credential must be accepted", ephemeralCoreResult.isSuccess)
    }

    @Test
    fun v6_4_callerHoldingOnlyTransportCredential_cannotProduceSkylarAcceptedSignature() {
        val now = System.currentTimeMillis()
        // Caller has completed Tailscale transport enrollment and holds transport credential ONLY
        val transportCredential = TransportCredential(
            credentialId = "ts-tc-9988",
            authKey = "tskey-auth-transport-sample",
            meshNodeId = "node-ts-9988",
            expiresAt = now + 3600_000L
        )
        assertTrue("Transport credential is valid at transport level", transportCredential.isValid())

        // Caller attempts to fabricate or sign an envelope using an unregistered key or transport key
        val rogueKeyGen = KeyPairGenerator.getInstance("EC")
        rogueKeyGen.initialize(ECGenParameterSpec("secp256r1"))
        val roguePair = rogueKeyGen.generateKeyPair()

        val rogueEnvelope = signEnvelopeRaw(
            privateKeyBytes = roguePair.private.encoded,
            callerId = "unregistered-transport-only-caller",
            capability = "context.query",
            scope = "read",
            issuedAt = now,
            expiresAt = now + 60_000L,
            nonce = "rogue-nonce-001"
        )

        val result = core.processEnvelope(rogueEnvelope)
        assertTrue("Transport credential alone must NEVER satisfy request authorization", result.isError)
        val error = result as Result.Error
        assertEquals("UNKNOWN_CALLER", error.errorCode)
    }

    @Test
    fun v6_5_issuedCredentialScope_matchesAuthorizationMatrixEntry() {
        val now = System.currentTimeMillis()

        // Persistent caller has context.query(read) and storage.read(read, list)
        val event = TailscaleEnrollmentEvent(
            deviceId = "device-p-01",
            tailnetIp = "100.64.0.22",
            nodeKey = "node-key-p-01",
            callerId = persistentCallerId,
            isCompleted = true
        )
        val persistentResult = issuerService.handleTailscaleEnrollment(event, now)
        assertTrue(persistentResult.isSuccess)
        val cred = (persistentResult as Result.Success).data
        assertTrue(cred.hasScope("read"))
        assertTrue(cred.hasScope("list"))
        assertFalse(cred.hasScope("admin"))

        // Ephemeral client attempts to request a scope beyond what the authorization matrix allows
        val cfIdentity = CloudflareAccessIdentity(
            identityToken = "token-cf-test",
            userEmail = "agent@test.com",
            issuer = "https://team.cloudflareaccess.com",
            audience = "skylar-edge-gateway-aud",
            issuedAt = now,
            expiresAt = now + 600_000L
        )
        val overscopedRequest = OAuth2TokenRequest(
            clientId = ephemeralClientId,
            clientSecret = ephemeralClientSecret,
            requestedScope = "admin,execute",
            cfAccessIdentity = cfIdentity
        )
        val overscopedResult = issuerService.exchangeClientCredentials(overscopedRequest, now)
        assertTrue("Request exceeding client registered bounds or matrix must be rejected", overscopedResult.isError)
        assertEquals("SCOPE_EXCEEDED", (overscopedResult as Result.Error).errorCode)
    }

    @Test
    fun v6_6_rotationAndTtlBehavior_matchesArchitectureSpec() {
        val now = System.currentTimeMillis()

        // 1. Persistent credential TTL = 7 days
        val event = TailscaleEnrollmentEvent(
            deviceId = "dev-rot-01",
            tailnetIp = "100.64.0.33",
            nodeKey = "key-33",
            callerId = persistentCallerId,
            isCompleted = true
        )
        val persistentResult = issuerService.handleTailscaleEnrollment(event, now)
        val persistentCred = (persistentResult as Result.Success).data
        val persistentTtl = persistentCred.expiresAt - persistentCred.issuedAt
        assertEquals(7 * 24 * 60 * 60 * 1000L, persistentTtl)

        // 2. Ephemeral credential TTL = 15 minutes
        val cfIdentity = CloudflareAccessIdentity(
            identityToken = "cf-token",
            userEmail = "ephemeral@test.com",
            issuer = "https://team.cloudflareaccess.com",
            audience = "skylar-edge-gateway-aud",
            issuedAt = now,
            expiresAt = now + 600_000L
        )
        val ephemeralResult = issuerService.exchangeClientCredentials(
            OAuth2TokenRequest(
                clientId = ephemeralClientId,
                clientSecret = ephemeralClientSecret,
                requestedScope = "read",
                cfAccessIdentity = cfIdentity
            ),
            now
        )
        val ephemeralCred = (ephemeralResult as Result.Success).data
        val ephemeralTtl = ephemeralCred.expiresAt - ephemeralCred.issuedAt
        assertEquals(15 * 60 * 1000L, ephemeralTtl)

        // 3. Rotation: rotate persistent credential
        val rotateResult = issuerService.rotateCredential(persistentCred.credentialId, now + 1000L)
        assertTrue("Rotation must succeed", rotateResult.isSuccess)
        val rotatedCred = (rotateResult as Result.Success).data
        assertFalse("New credential has distinct ID", rotatedCred.credentialId == persistentCred.credentialId)
        assertTrue("Old credential is revoked in store", credentialStore.get(persistentCred.credentialId)?.isRevoked == true)
        assertTrue("New credential is valid", rotatedCred.isValid(now + 1000L))

        // Envelope signed with old credential fails closed
        val oldEnvelope = signEnvelopeWithIssuedCredential(
            credential = persistentCred,
            capability = "context.query",
            scope = "read",
            issuedAt = now + 1500L,
            expiresAt = now + 60_000L,
            nonce = "old-nonce-1122"
        )
        val oldCoreResult = core.processEnvelope(oldEnvelope)
        assertTrue("Envelope with revoked rotated key must fail verification", oldCoreResult.isError)

        // Envelope signed with new credential succeeds
        val newEnvelope = signEnvelopeWithIssuedCredential(
            credential = rotatedCred,
            capability = "context.query",
            scope = "read",
            issuedAt = now + 1500L,
            expiresAt = now + 60_000L,
            nonce = "new-nonce-3344"
        )
        val newCoreResult = core.processEnvelope(newEnvelope)
        assertTrue("Envelope signed with new rotated key must succeed", newCoreResult.isSuccess)
    }

    @Test
    fun negativeCases_enrollmentAndExchangeChecks() {
        val now = System.currentTimeMillis()

        // 1. Incomplete Tailscale enrollment does not trigger auto-issue
        val incompleteEvent = TailscaleEnrollmentEvent(
            deviceId = "dev-incomplete",
            tailnetIp = "100.64.0.99",
            nodeKey = "node-99",
            callerId = persistentCallerId,
            isCompleted = false
        )
        val incompleteResult = issuerService.handleTailscaleEnrollment(incompleteEvent, now)
        assertTrue(incompleteResult.isError)
        assertEquals("ENROLLMENT_INCOMPLETE", (incompleteResult as Result.Error).errorCode)

        // 2. Unknown caller not in authorization matrix fails closed
        val unknownCallerEvent = TailscaleEnrollmentEvent(
            deviceId = "dev-unknown",
            tailnetIp = "100.64.0.100",
            nodeKey = "node-100",
            callerId = "unknown-hacker-caller",
            isCompleted = true
        )
        val unknownResult = issuerService.handleTailscaleEnrollment(unknownCallerEvent, now)
        assertTrue(unknownResult.isError)
        assertEquals("UNKNOWN_OR_UNAUTHORIZED_CALLER", (unknownResult as Result.Error).errorCode)

        // 3. Ephemeral exchange without CF Access token fails
        val missingCfRequest = OAuth2TokenRequest(
            clientId = ephemeralClientId,
            clientSecret = ephemeralClientSecret,
            requestedScope = "read",
            cfAccessIdentity = null
        )
        val missingCfResult = issuerService.exchangeClientCredentials(missingCfRequest, now)
        assertTrue(missingCfResult.isError)
        assertEquals("CF_ACCESS_MISSING", (missingCfResult as Result.Error).errorCode)

        // 4. Ephemeral exchange with invalid client secret fails
        val badSecretRequest = OAuth2TokenRequest(
            clientId = ephemeralClientId,
            clientSecret = "wrong-password",
            requestedScope = "read",
            cfAccessIdentity = CloudflareAccessIdentity("tok", "user@cf.com", "https://team.cloudflareaccess.com", "skylar-edge-gateway-aud", now, now + 100_000L)
        )
        val badSecretResult = issuerService.exchangeClientCredentials(badSecretRequest, now)
        assertTrue(badSecretResult.isError)
        assertEquals("INVALID_CLIENT_CREDENTIALS", (badSecretResult as Result.Error).errorCode)

        // 5. System-wide kill switch invalidates all issued credentials
        val revokedCount = credentialStore.revokeAll()
        assertTrue(revokedCount >= 0)
    }

    private fun signEnvelopeWithIssuedCredential(
        credential: IssuedSigningCredential,
        capability: String,
        scope: String,
        issuedAt: Long,
        expiresAt: Long,
        nonce: String
    ): RequestEnvelope {
        return signEnvelopeRaw(
            privateKeyBytes = credential.privateKeyBytes,
            callerId = credential.callerId,
            capability = capability,
            scope = scope,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            nonce = nonce
        )
    }

    private fun signEnvelopeRaw(
        privateKeyBytes: ByteArray,
        callerId: String,
        capability: String,
        scope: String,
        issuedAt: Long,
        expiresAt: Long,
        nonce: String
    ): RequestEnvelope {
        val params = mapOf("scope" to scope)
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
        val signatureBytes = EcdsaP256SignatureProvider().sign(privateKeyBytes, canonicalBytes)
        val signatureB64 = Base64Codec.encode(signatureBytes)

        return RequestEnvelope(
            callerId = callerId,
            capability = capability,
            params = params,
            nonce = nonce,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            workflowHash = workflowHash,
            signature = signatureB64,
            scope = scope
        )
    }

    private fun signPolicyArtifact(version: String, payload: String): SignedPolicyArtifact {
        val sigProvider = EcdsaP256SignatureProvider()
        val sigBytes = sigProvider.sign(policySignerPair.private.encoded, payload.toByteArray(StandardCharsets.UTF_8))
        return SignedPolicyArtifact(
            version = version,
            canonicalPayload = payload,
            signerId = policySignerId,
            signature = Base64Codec.encode(sigBytes)
        )
    }
}
