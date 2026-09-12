package com.inscopelabs.abx.skylar.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Deterministic canonicalization and workflow-hash computation for
 * [RequestEnvelope] (architecture doc §2: "Deterministic canonical
 * serialization and workflow_hash computation").
 *
 * The prior prototype canonicalized `params` via
 * `params.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }`
 * — flat only (no nested maps/lists), and with no escaping of the `,`/`=`
 * delimiters it relied on. A param value containing those characters
 * could be crafted so two different logical payloads produce the same
 * canonical string, and therefore the same hash. This version
 * recursively canonicalizes nested structures and escapes every
 * delimiter it uses.
 */
object EnvelopeCanonicalizer {

    /**
     * The exact byte sequence that gets hashed (by [computeWorkflowHash])
     * and signed (by [com.inscopelabs.abx.skylar.crypto.SignatureProvider]).
     * Binds every field that must be immutable post-signing.
     */
    fun canonicalBytes(
        envelopeVersion: Int,
        callerId: String,
        capability: String,
        params: Map<String, Any?>,
        nonce: String,
        issuedAt: Long,
        expiresAt: Long,
        scope: String?
    ): ByteArray {
        val sb = StringBuilder()
        sb.append("v=").append(envelopeVersion).append('&')
        sb.append("caller=").append(escape(callerId)).append('&')
        sb.append("capability=").append(escape(capability)).append('&')
        sb.append("scope=").append(escape(scope ?: "")).append('&')
        sb.append("params=").append(canonicalizeValue(params)).append('&')
        sb.append("nonce=").append(escape(nonce)).append('&')
        sb.append("issued_at=").append(issuedAt).append('&')
        sb.append("expires_at=").append(expiresAt)
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun computeWorkflowHash(
        envelopeVersion: Int,
        callerId: String,
        capability: String,
        params: Map<String, Any?>,
        nonce: String,
        issuedAt: Long,
        expiresAt: Long,
        scope: String?
    ): String {
        val bytes = canonicalBytes(envelopeVersion, callerId, capability, params, nonce, issuedAt, expiresAt, scope)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalizeValue(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .joinToString(",", prefix = "{", postfix = "}") { (k, v) ->
                "${escape(k.toString())}:${canonicalizeValue(v)}"
            }
        is List<*> -> value.joinToString(",", prefix = "[", postfix = "]") { canonicalizeValue(it) }
        is Number, is Boolean -> value.toString()
        else -> escape(value.toString())
    }

    /**
     * Escapes the delimiter characters this format relies on (`&`, `,`,
     * `:`, `\`), so a value containing them can't be crafted to produce a
     * different logical structure with the same canonical string.
     */
    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("&", "\\&").replace(",", "\\,").replace(":", "\\:")
}
