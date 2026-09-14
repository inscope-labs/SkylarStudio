package com.inscopelabs.abx.skylar.mesh

import com.inscopelabs.abx.skylar.bootstrap.CloudflareAccessIdentity
import com.inscopelabs.abx.skylar.bootstrap.IssuedSigningCredential
import com.inscopelabs.abx.skylar.bootstrap.IssuerService
import com.inscopelabs.abx.skylar.bootstrap.OAuth2TokenRequest
import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.envelope.EnvelopeBuilder
import com.inscopelabs.abx.skylar.envelope.RequestEnvelope
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.SignatureProvider

/**
 * Client for ephemeral callers communicating over Lane B (public edge-gated ingress).
 *
 * Architecture doc §3, §4 & §5:
 * 1. Authenticates to the edge using a Cloudflare Access service token.
 * 2. Obtains a short-lived request-signing credential via Phase 6 OAuth2 client-credentials flow.
 * 3. Signs canonical request envelopes with its issued private key.
 * 4. Transmits requests via the Relay Forwarder, which forwards across the private mesh
 *    to Skylar Core for independent cryptographic verification.
 *
 * Role: Module per AGENTS.md §4.
 */
class LaneBClient(
    val clientId: String,
    val clientSecret: String,
    val callerId: String,
    val cfAccessClientId: String,
    val cfAccessClientSecret: String,
    private val issuerService: IssuerService,
    private val signatureProvider: SignatureProvider = EcdsaP256SignatureProvider()
) {
    companion object {
        private const val TAG = "LaneBClient"
    }

    private var cachedSigningCredential: IssuedSigningCredential? = null

    val currentCredential: IssuedSigningCredential?
        get() = cachedSigningCredential

    /**
     * Executes the Phase 6 OAuth2 client-credentials-style token exchange against [IssuerService]
     * to obtain a short-lived, scope-limited request-signing credential.
     */
    fun obtainSigningCredential(
        requestedScope: String,
        cfAccessIdentity: CloudflareAccessIdentity? = null,
        currentTime: Long = System.currentTimeMillis()
    ): Result<IssuedSigningCredential> {
        Logger.i(TAG, "Initiating Phase 6 OAuth2 token exchange for clientId=$clientId, scope=$requestedScope")

        val tokenRequest = OAuth2TokenRequest(
            grantType = OAuth2TokenRequest.GRANT_TYPE_CLIENT_CREDENTIALS,
            clientId = clientId,
            clientSecret = clientSecret,
            requestedScope = requestedScope,
            cfAccessIdentity = cfAccessIdentity
        )

        val result = issuerService.exchangeClientCredentials(tokenRequest, currentTime)
        return when (result) {
            is Result.Success<IssuedSigningCredential> -> {
                cachedSigningCredential = result.data
                Logger.i(TAG, "Successfully obtained signing credential id=${result.data.credentialId}, ttlMs=${result.data.expiresAt - result.data.issuedAt}")
                Result.Success(result.data)
            }
            is Result.Error -> {
                Logger.w(TAG, "OAuth2 token exchange failed: ${result.message} (${result.errorCode})")
                Result.Error(result.message, result.cause, result.errorCode)
            }
        }
    }

    /**
     * Constructs and cryptographically signs a [RequestEnvelope] using the issued private key.
     */
    fun createSignedEnvelope(
        capability: String,
        params: Map<String, Any?> = emptyMap(),
        scope: String? = null,
        nonce: String? = null,
        issuedAt: Long? = null,
        expiresAt: Long? = null,
        customCredential: IssuedSigningCredential? = null
    ): RequestEnvelope {
        val credential = customCredential ?: cachedSigningCredential
            ?: throw IllegalStateException("No active signing credential. Call obtainSigningCredential() first.")

        Logger.d(TAG, "Creating signed envelope for caller=$callerId, cap=$capability")
        val builder = EnvelopeBuilder()
            .callerId(callerId)
            .capability(capability)
            .params(params)
            .scope(scope ?: credential.scope)

        if (nonce != null) builder.nonce(nonce)
        if (issuedAt != null) builder.issuedAt(issuedAt)
        if (expiresAt != null) builder.expiresAt(expiresAt)

        return builder.sign(credential.privateKeyBytes, signatureProvider)
    }

    /**
     * Sends an envelope through the Lane B [RelayForwarder].
     */
    fun sendEnvelope(
        relayForwarder: RelayForwarder,
        envelope: RequestEnvelope,
        customCfClientId: String? = cfAccessClientId,
        customCfClientSecret: String? = cfAccessClientSecret,
        cfAccessHeaders: Map<String, String> = emptyMap()
    ): LaneBResponse {
        Logger.i(TAG, "Dispatching Lane B envelope for cap=${envelope.capability}, nonce=${envelope.nonce}")

        val payload = LaneACodec.encodeEnvelope(envelope)
        val request = LaneBRequest(
            payload = payload,
            cfAccessClientId = customCfClientId,
            cfAccessClientSecret = customCfClientSecret,
            cfAccessHeaders = cfAccessHeaders
        )

        val response = relayForwarder.handleRequest(request)
        Logger.i(TAG, "Received Lane B response status=${response.status}, code=${response.statusCode}, success=${response.success}")
        return response
    }

    /**
     * High-level convenience method: obtains credentials if needed, signs, and executes via relay.
     */
    fun invokeCapability(
        relayForwarder: RelayForwarder,
        capability: String,
        params: Map<String, Any?> = emptyMap(),
        scope: String = capability,
        cfAccessIdentity: CloudflareAccessIdentity? = null,
        cfAccessHeaders: Map<String, String> = emptyMap()
    ): LaneBResponse {
        if (cachedSigningCredential == null || !cachedSigningCredential!!.isValid()) {
            val credResult = obtainSigningCredential(scope, cfAccessIdentity)
            if (credResult is Result.Error) {
                return LaneBResponse.error(
                    statusCode = 401,
                    status = LaneBResponse.STATUS_UNAUTHORIZED,
                    errorMessage = "Failed to bootstrap signing credential: ${credResult.message}",
                    errorCode = credResult.errorCode ?: "BOOTSTRAP_FAILED"
                )
            }
        }

        val envelope = createSignedEnvelope(capability, params, scope)
        return sendEnvelope(relayForwarder, envelope, cfAccessHeaders = cfAccessHeaders)
    }
}
