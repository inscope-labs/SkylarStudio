package com.inscopelabs.abx.skylar.mesh

/**
 * Data contracts for Lane B (public edge-gated ingress) network communication.
 *
 * Architecture doc §3 & §4:
 * Ingress Lane B delivers requests through Cloudflare Access and Cloudflare Tunnel,
 * terminating at the OCI Relay Forwarder, which forwards over the private mesh
 * to the Android device.
 *
 * Ephemeral callers authenticate to the edge via Cloudflare Access service tokens,
 * but independent request authorization is anchored strictly by the signed RequestEnvelope.
 */
data class LaneBRequest(
    val payload: String,
    val cfAccessClientId: String? = null,
    val cfAccessClientSecret: String? = null,
    val cfAccessHeaders: Map<String, String> = emptyMap(),
    val metadata: Map<String, String> = emptyMap()
)

data class LaneBResponse(
    val success: Boolean,
    val statusCode: Int,
    val status: String,
    val data: Map<String, Any?>? = null,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val target: String? = null,
    val executionTimeMs: Long = 0L,
    val forensicCorrelationId: String? = null
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_UNAUTHORIZED = "UNAUTHORIZED"
        const val STATUS_CF_ACCESS_DENIED = "CF_ACCESS_DENIED"
        const val STATUS_REPLAY_DETECTED = "REPLAY_DETECTED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_ENVELOPE_INVALID = "ENVELOPE_INVALID"
        const val STATUS_RATE_LIMITED = "RATE_LIMITED"
        const val STATUS_RELAY_ERROR = "RELAY_FORWARD_ERROR"
        const val STATUS_DISPATCH_FAILED = "DISPATCH_FAILED"

        fun success(
            data: Map<String, Any?>,
            target: String?,
            executionTimeMs: Long,
            correlationId: String? = null
        ): LaneBResponse = LaneBResponse(
            success = true,
            statusCode = 200,
            status = STATUS_SUCCESS,
            data = data,
            target = target,
            executionTimeMs = executionTimeMs,
            forensicCorrelationId = correlationId
        )

        fun error(
            statusCode: Int,
            status: String,
            errorMessage: String,
            errorCode: String,
            target: String? = null,
            executionTimeMs: Long = 0L,
            correlationId: String? = null
        ): LaneBResponse = LaneBResponse(
            success = false,
            statusCode = statusCode,
            status = status,
            errorMessage = errorMessage,
            errorCode = errorCode,
            target = target,
            executionTimeMs = executionTimeMs,
            forensicCorrelationId = correlationId
        )
    }
}
