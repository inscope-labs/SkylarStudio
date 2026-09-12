package com.inscopelabs.abx.skylar.envelope

import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.SignatureProvider
import java.security.SecureRandom

/**
 * Fluent builder for constructing, canonicalizing, and signing [RequestEnvelope]s.
 *
 * Implements Phase 1 deliverable 1 ("Envelope data classes and builders").
 */
class EnvelopeBuilder {
    private var envelopeVersion: Int = RequestEnvelope.CURRENT_ENVELOPE_VERSION
    private var callerId: String = ""
    private var capability: String = ""
    private var params: MutableMap<String, Any?> = mutableMapOf()
    private var nonce: String? = null
    private var issuedAt: Long? = null
    private var expiresAt: Long? = null
    private var scope: String? = null

    fun version(version: Int) = apply { this.envelopeVersion = version }
    fun callerId(callerId: String) = apply { this.callerId = callerId }
    fun capability(capability: String) = apply { this.capability = capability }
    fun param(key: String, value: Any?) = apply { this.params[key] = value }
    fun params(params: Map<String, Any?>) = apply { this.params.putAll(params) }
    fun nonce(nonce: String) = apply { this.nonce = nonce }
    fun issuedAt(issuedAt: Long) = apply { this.issuedAt = issuedAt }
    fun expiresAt(expiresAt: Long) = apply { this.expiresAt = expiresAt }
    fun scope(scope: String?) = apply { this.scope = scope }

    /**
     * Signs the canonical envelope payload using [privateKeyBytes] and [signatureProvider],
     * returning an immutable, fully verified [RequestEnvelope].
     */
    fun sign(
        privateKeyBytes: ByteArray,
        signatureProvider: SignatureProvider = EcdsaP256SignatureProvider()
    ): RequestEnvelope {
        val now = System.currentTimeMillis()
        val effectiveIssuedAt = issuedAt ?: now
        val effectiveExpiresAt = expiresAt ?: (effectiveIssuedAt + 60_000L)
        val effectiveNonce = nonce ?: generateRandomNonce()

        val workflowHash = EnvelopeCanonicalizer.computeWorkflowHash(
            envelopeVersion = envelopeVersion,
            callerId = callerId,
            capability = capability,
            params = params,
            nonce = effectiveNonce,
            issuedAt = effectiveIssuedAt,
            expiresAt = effectiveExpiresAt,
            scope = scope
        )

        val canonicalBytes = EnvelopeCanonicalizer.canonicalBytes(
            envelopeVersion = envelopeVersion,
            callerId = callerId,
            capability = capability,
            params = params,
            nonce = effectiveNonce,
            issuedAt = effectiveIssuedAt,
            expiresAt = effectiveExpiresAt,
            scope = scope
        )

        val signatureBytes = signatureProvider.sign(privateKeyBytes, canonicalBytes)
        val encodedSignature = Base64Codec.encode(signatureBytes)

        return RequestEnvelope(
            envelopeVersion = envelopeVersion,
            callerId = callerId,
            capability = capability,
            params = params.toMap(),
            nonce = effectiveNonce,
            issuedAt = effectiveIssuedAt,
            expiresAt = effectiveExpiresAt,
            workflowHash = workflowHash,
            signature = encodedSignature,
            scope = scope
        )
    }

    private fun generateRandomNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
