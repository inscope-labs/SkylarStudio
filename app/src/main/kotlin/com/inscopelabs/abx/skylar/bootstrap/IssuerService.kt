package com.inscopelabs.abx.skylar.bootstrap

import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.InMemoryKeyRegistry
import com.inscopelabs.abx.skylar.envelope.policy.AuthorizationMatrix
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Request-Signing Credential Issuer Service (architecture §5).
 *
 * Implements the two request-signing credential bootstrapping flows:
 * 1. Persistent-caller auto-issue triggered by confirmed Tailscale mesh enrollment.
 * 2. Ephemeral-caller OAuth2 client-credentials exchange, verifying both pre-registered
 *    client credentials and Cloudflare Access identity assertions.
 *
 * Security Contract:
 * - Transport credentials confer reachability only; request-signing credentials anchor authorization.
 * - Centralized key registration binds issued credentials to the authorization matrix.
 */
class IssuerService(
    private val keyRegistry: InMemoryKeyRegistry,
    private val credentialStore: CredentialStore,
    private val authMatrixProvider: () -> AuthorizationMatrix,
    private val clientEntryStore: Map<String, EphemeralClientEntry> = emptyMap(),
    private val cfAccessValidator: CloudflareAccessValidator = DefaultCloudflareAccessValidator(),
    private val config: IssuerConfig = IssuerConfig()
) {
    /**
     * Handles confirmed Tailscale mesh enrollment events for persistent callers.
     *
     * Automatically mints and registers a request-signing credential scoped per
     * the caller's authorization matrix entry.
     */
    fun handleTailscaleEnrollment(
        event: TailscaleEnrollmentEvent,
        currentTime: Long = System.currentTimeMillis()
    ): Result<IssuedSigningCredential> {
        Logger.i(TAG, "Processing Tailscale enrollment event for callerId=${event.callerId}, device=${event.deviceId}")

        if (!event.isCompleted) {
            Logger.w(TAG, "Tailscale enrollment for caller=${event.callerId} is not completed — auto-issue aborted")
            return Result.Error("Tailscale enrollment is not completed", errorCode = "ENROLLMENT_INCOMPLETE")
        }

        val matrix = authMatrixProvider()
        val callerCaps = matrix.asMap()[event.callerId]
        if (callerCaps == null || callerCaps.isEmpty()) {
            Logger.w(TAG, "Caller ${event.callerId} has no entries in active authorization matrix — auto-issue rejected (default-deny)")
            return Result.Error("Caller is not authorized in matrix", errorCode = "UNKNOWN_OR_UNAUTHORIZED_CALLER")
        }

        val grantedScopes = callerCaps.values.flatten().toSet().ifEmpty { setOf("read") }
        val scopeString = grantedScopes.sorted().joinToString(",")

        val keyPair = generateP256KeyPair()
        val publicKeyEncoded = keyPair.public.encoded
        val privateKeyEncoded = keyPair.private.encoded

        val credentialId = "cred-persistent-" + UUID.randomUUID().toString()
        val expiresAt = currentTime + config.persistentDefaultTtlMs

        val credential = IssuedSigningCredential(
            credentialId = credentialId,
            callerId = event.callerId,
            callerClass = CallerClass.PERSISTENT,
            privateKeyBytes = privateKeyEncoded,
            publicKeyBytes = publicKeyEncoded,
            issuedAt = currentTime,
            expiresAt = expiresAt,
            scope = scopeString,
            isRevoked = false
        )

        // Bind public key in KeyRegistry for Skylar Core envelope verification
        keyRegistry.register(event.callerId, publicKeyEncoded)
        credentialStore.save(credential)

        Logger.i(TAG, "Successfully auto-issued persistent signing credential $credentialId for caller=${event.callerId}, scopes=$scopeString, ttlMs=${config.persistentDefaultTtlMs}")
        return Result.Success(credential)
    }

    /**
     * Executes OAuth2 client-credentials-style exchange for ephemeral callers.
     *
     * Requires both pre-registered client credentials and a valid Cloudflare Access identity assertion.
     */
    fun exchangeClientCredentials(
        request: OAuth2TokenRequest,
        currentTime: Long = System.currentTimeMillis()
    ): Result<IssuedSigningCredential> {
        Logger.i(TAG, "Processing OAuth2 client-credentials exchange for clientId=${request.clientId}")

        if (request.grantType != OAuth2TokenRequest.GRANT_TYPE_CLIENT_CREDENTIALS) {
            Logger.w(TAG, "Unsupported grant type: ${request.grantType}")
            return Result.Error("Unsupported grant type", errorCode = "UNSUPPORTED_GRANT_TYPE")
        }

        // Validate Cloudflare Access identity (edge gate validation)
        val cfResult = cfAccessValidator.validate(request.cfAccessIdentity, currentTime)
        if (cfResult.isError) {
            val error = cfResult as Result.Error
            Logger.w(TAG, "Cloudflare Access validation failed for clientId=${request.clientId}: ${error.message} (${error.errorCode})")
            return Result.Error(error.message, cause = error.cause, errorCode = error.errorCode)
        }

        // Validate pre-registered client credentials
        val clientEntry = clientEntryStore[request.clientId]
        if (clientEntry == null || clientEntry.clientSecret != request.clientSecret) {
            Logger.w(TAG, "Invalid or unknown client credentials for clientId=${request.clientId}")
            return Result.Error("Invalid client credentials", errorCode = "INVALID_CLIENT_CREDENTIALS")
        }

        // Validate requested scope against client allowed scopes
        val requestedScopes = request.requestedScope.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (requestedScopes.isEmpty()) {
            Logger.w(TAG, "Requested scope is empty for clientId=${request.clientId}")
            return Result.Error("Requested scope is empty", errorCode = "INVALID_SCOPE")
        }

        if (!clientEntry.allowedScopes.containsAll(requestedScopes) && "*" !in clientEntry.allowedScopes) {
            Logger.w(TAG, "Requested scopes $requestedScopes exceed client allowed scopes ${clientEntry.allowedScopes}")
            return Result.Error("Requested scope exceeds registered bounds", errorCode = "SCOPE_EXCEEDED")
        }

        // Validate requested scope against active authorization matrix entry for caller
        val matrix = authMatrixProvider()
        val callerCaps = matrix.asMap()[clientEntry.callerId]
        if (callerCaps == null || callerCaps.isEmpty()) {
            Logger.w(TAG, "Caller ${clientEntry.callerId} has no entries in active authorization matrix")
            return Result.Error("Caller is not authorized in matrix", errorCode = "UNKNOWN_OR_UNAUTHORIZED_CALLER")
        }

        val matrixAllowedScopes = callerCaps.values.flatten().toSet()
        val authorizedScopes = requestedScopes.filter { it in matrixAllowedScopes || "*" in matrixAllowedScopes }.toSet()
        if (authorizedScopes.isEmpty()) {
            Logger.w(TAG, "None of the requested scopes $requestedScopes are authorized in matrix for ${clientEntry.callerId}")
            return Result.Error("Requested scopes not authorized by policy", errorCode = "UNAUTHORIZED_SCOPE")
        }

        val effectiveTtlMs = minOf(config.ephemeralDefaultTtlMs, clientEntry.maxTtlMs)
        val expiresAt = currentTime + effectiveTtlMs

        val keyPair = generateP256KeyPair()
        val publicKeyEncoded = keyPair.public.encoded
        val privateKeyEncoded = keyPair.private.encoded

        val credentialId = "cred-ephemeral-" + UUID.randomUUID().toString()
        val tokenString = "skylar-tok-" + Base64Codec.encode(UUID.randomUUID().toString().toByteArray())

        val credential = IssuedSigningCredential(
            credentialId = credentialId,
            callerId = clientEntry.callerId,
            callerClass = CallerClass.EPHEMERAL,
            privateKeyBytes = privateKeyEncoded,
            publicKeyBytes = publicKeyEncoded,
            issuedAt = currentTime,
            expiresAt = expiresAt,
            scope = authorizedScopes.sorted().joinToString(","),
            isRevoked = false,
            token = tokenString
        )

        // Register public key under the caller identity
        keyRegistry.register(clientEntry.callerId, publicKeyEncoded)
        credentialStore.save(credential)

        Logger.i(TAG, "Successfully minted ephemeral signing credential $credentialId for caller=${clientEntry.callerId}, scopes=${credential.scope}, ttlMs=$effectiveTtlMs")
        return Result.Success(credential)
    }

    /**
     * Rotates an existing credential by issuing a fresh keypair and expiring the old one.
     */
    fun rotateCredential(
        credentialId: String,
        currentTime: Long = System.currentTimeMillis()
    ): Result<IssuedSigningCredential> {
        Logger.i(TAG, "Rotating credential $credentialId")
        val existing = credentialStore.get(credentialId)
        if (existing == null) {
            Logger.w(TAG, "Cannot rotate non-existent credential $credentialId")
            return Result.Error("Credential not found", errorCode = "CREDENTIAL_NOT_FOUND")
        }

        val ttl = if (existing.callerClass == CallerClass.PERSISTENT) {
            config.persistentDefaultTtlMs
        } else {
            config.ephemeralDefaultTtlMs
        }

        val newKeyPair = generateP256KeyPair()
        val newPublicKey = newKeyPair.public.encoded
        val newPrivateKey = newKeyPair.private.encoded

        val newCredId = "cred-${existing.callerClass.name.lowercase()}-" + UUID.randomUUID().toString()
        val newCredential = existing.copy(
            credentialId = newCredId,
            privateKeyBytes = newPrivateKey,
            publicKeyBytes = newPublicKey,
            issuedAt = currentTime,
            expiresAt = currentTime + ttl,
            isRevoked = false
        )

        keyRegistry.register(existing.callerId, newPublicKey)
        credentialStore.save(newCredential)
        credentialStore.revoke(credentialId)

        Logger.i(TAG, "Rotated credential $credentialId to $newCredId for caller=${existing.callerId}")
        return Result.Success(newCredential)
    }

    /**
     * Revokes a specific credential.
     */
    fun revokeCredential(credentialId: String): Result<Unit> {
        Logger.i(TAG, "Revoking credential $credentialId")
        val revoked = credentialStore.revoke(credentialId)
        return if (revoked) {
            Result.Success(Unit)
        } else {
            Result.Error("Credential not found", errorCode = "CREDENTIAL_NOT_FOUND")
        }
    }

    private fun generateP256KeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(ECGenParameterSpec("secp256r1"))
        return keyGen.generateKeyPair()
    }

    companion object {
        private const val TAG = "IssuerService"
    }
}
