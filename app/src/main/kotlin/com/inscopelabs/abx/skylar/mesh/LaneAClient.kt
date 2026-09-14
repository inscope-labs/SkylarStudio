package com.inscopelabs.abx.skylar.mesh

import com.inscopelabs.abx.skylar.bootstrap.IssuedSigningCredential
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.envelope.EnvelopeBuilder
import com.inscopelabs.abx.skylar.envelope.RequestEnvelope
import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.SignatureProvider
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Client for persistent callers communicating over Lane A (private mesh).
 *
 * Architecture doc §1, §2 & §5:
 * Holds both:
 * 1. A transport credential conferring reachability on the private mesh.
 * 2. A request-signing credential minted via the Phase 6 auto-issue flow.
 *
 * Signs requests with its issued private key and dispatches them over the mesh network
 * directly to Skylar Core's listening port without traversing the Relay Forwarder.
 *
 * Role: Module per AGENTS.md §4.
 */
class LaneAClient(
    val callerId: String,
    val signingCredential: IssuedSigningCredential,
    val transportCredential: TransportCredential? = null,
    private val signatureProvider: SignatureProvider = EcdsaP256SignatureProvider()
) {
    companion object {
        private const val TAG = "LaneAClient"
    }

    /**
     * Constructs and cryptographically signs a [RequestEnvelope] using the client's
     * issued private key and specified parameters.
     */
    fun createSignedEnvelope(
        capability: String,
        params: Map<String, Any?> = emptyMap(),
        scope: String? = null,
        nonce: String? = null,
        issuedAt: Long? = null,
        expiresAt: Long? = null
    ): RequestEnvelope {
        val privateKeyBytes = signingCredential.privateKeyBytes
        val builder = EnvelopeBuilder()
            .callerId(callerId)
            .capability(capability)
            .params(params)
            .scope(scope ?: signingCredential.scope)

        if (nonce != null) builder.nonce(nonce)
        if (issuedAt != null) builder.issuedAt(issuedAt)
        if (expiresAt != null) builder.expiresAt(expiresAt)

        return builder.sign(privateKeyBytes, signatureProvider)
    }

    /**
     * Sends a signed [RequestEnvelope] across the network to Skylar's Lane A ingress server.
     */
    fun sendEnvelope(
        host: String,
        port: Int,
        envelope: RequestEnvelope,
        timeoutMs: Int = 5000
    ): LaneAResponse {
        Logger.i(TAG, "Sending envelope callerId=${envelope.callerId}, capability=${envelope.capability} to $host:$port")
        val socket = Socket()

        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val writer = PrintWriter(
                BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)),
                true
            )
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            )

            // Construct and send LaneARequest
            val request = LaneARequest(
                envelopeJson = LaneACodec.encodeEnvelope(envelope),
                callerTransportId = transportCredential?.meshNodeId,
                transportAuthToken = transportCredential?.authKey
            )
            val encodedRequest = LaneACodec.encodeRequest(request)
            writer.println(encodedRequest)
            writer.flush()

            // Read response
            val responseLine = reader.readLine()
            if (responseLine.isNullOrBlank()) {
                Logger.w(TAG, "Received empty response from $host:$port")
                return LaneAResponse.error(
                    statusCode = 502,
                    status = LaneAResponse.STATUS_DISPATCH_FAILED,
                    errorMessage = "Empty response from Lane A server",
                    errorCode = "EMPTY_RESPONSE"
                )
            }

            val response = LaneACodec.decodeResponse(responseLine)
            Logger.i(TAG, "Received response status=${response.status}, statusCode=${response.statusCode}, target=${response.target}")
            response
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to send envelope to $host:$port: ${e.message}", e)
            LaneAResponse.error(
                statusCode = 500,
                status = LaneAResponse.STATUS_DISPATCH_FAILED,
                errorMessage = "Network transport failure: ${e.message}",
                errorCode = "NETWORK_ERROR"
            )
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * High-level convenience method: creates, signs, and sends an envelope for the capability.
     */
    fun invokeCapability(
        host: String,
        port: Int,
        capability: String,
        params: Map<String, Any?> = emptyMap(),
        scope: String? = null,
        timeoutMs: Int = 5000
    ): LaneAResponse {
        val envelope = createSignedEnvelope(
            capability = capability,
            params = params,
            scope = scope
        )
        return sendEnvelope(host, port, envelope, timeoutMs)
    }
}
