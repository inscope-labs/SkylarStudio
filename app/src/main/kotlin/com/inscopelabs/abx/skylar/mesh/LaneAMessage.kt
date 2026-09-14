package com.inscopelabs.abx.skylar.mesh

/**
 * Data contracts for Lane A (private mesh) network communication.
 *
 * Architecture doc §1 & §2:
 * Ingress Lane A delivers requests directly across the private Tailscale mesh.
 * Transport credentials confer reachability only; every request carries a
 * canonical RequestEnvelope that anchors independent authorization.
 */
data class LaneARequest(
    val envelopeJson: String,
    val callerTransportId: String? = null,
    val transportAuthToken: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class LaneAResponse(
    val success: Boolean,
    val statusCode: Int,
    val status: String,
    val data: Map<String, Any?>? = null,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val target: String? = null,
    val executionTimeMs: Long = 0L
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_UNAUTHORIZED = "UNAUTHORIZED"
        const val STATUS_REPLAY_DETECTED = "REPLAY_DETECTED"
        const val STATUS_EXPIRED = "EXPIRED"
        const val STATUS_ENVELOPE_INVALID = "ENVELOPE_INVALID"
        const val STATUS_UNMAPPED_CAPABILITY = "UNMAPPED_CAPABILITY"
        const val STATUS_DISPATCH_FAILED = "DISPATCH_FAILED"
        const val STATUS_TRANSPORT_DENIED = "TRANSPORT_DENIED"

        fun success(
            data: Map<String, Any?>,
            target: String?,
            executionTimeMs: Long
        ): LaneAResponse = LaneAResponse(
            success = true,
            statusCode = 200,
            status = STATUS_SUCCESS,
            data = data,
            target = target,
            executionTimeMs = executionTimeMs
        )

        fun error(
            statusCode: Int,
            status: String,
            errorMessage: String,
            errorCode: String,
            target: String? = null,
            executionTimeMs: Long = 0L
        ): LaneAResponse = LaneAResponse(
            success = false,
            statusCode = statusCode,
            status = status,
            errorMessage = errorMessage,
            errorCode = errorCode,
            target = target,
            executionTimeMs = executionTimeMs
        )
    }
}
