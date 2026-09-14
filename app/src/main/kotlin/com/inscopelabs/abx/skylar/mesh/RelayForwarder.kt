package com.inscopelabs.abx.skylar.mesh

import com.inscopelabs.abx.skylar.diagnostics.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * OCI VM Relay Forwarder for Lane B (public edge-gated ingress).
 *
 * Architectural Invariants (Architecture doc §4 & Infrastructure doc):
 * 1. Opaque Forwarding: The relay does NOT inspect, decrypt, or mutate signed request envelopes.
 *    It treats them as opaque byte streams.
 * 2. Edge Gating: Enforces Cloudflare Access service-token verification at the edge boundary.
 * 3. Header Neutrality: Access-asserted identity headers are never trusted as authorization proof
 *    and are stripped or recorded solely for forensic audit correlation.
 * 4. Rate Limiting & DoS Shield: Shields the downstream Android device from connection bursts.
 * 5. Privilege Containment: Compromise of the Relay Forwarder cannot enlarge caller privileges
 *    because independent cryptographic signature verification occurs on the destination device.
 *
 * Role: Orchestrator per AGENTS.md §4.
 */
class RelayForwarder(
    private val targetHost: InetAddress,
    private val targetPort: Int,
    private val validServiceTokens: Map<String, String> = emptyMap(),
    private val maxRequestsPerMinute: Int = 120,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
    private val socketFactory: ((InetAddress, Int) -> Socket)? = null
) {
    companion object {
        private const val TAG = "RelayForwarder"
        const val HEADER_CF_RAY = "CF-Ray"
        const val HEADER_CF_EMAIL = "Cf-Access-Authenticated-User-Email"
    }

    val totalRequests = AtomicLong(0)
    val accessDeniedCount = AtomicLong(0)
    val rateLimitedCount = AtomicLong(0)
    val forwardedCount = AtomicLong(0)
    val forwardErrorsCount = AtomicLong(0)

    // Rate limiting tracking
    private val requestTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val activeConnections = AtomicInteger(0)

    /**
     * Handles an incoming Lane B request from the edge (e.g. cloudflared).
     *
     * Validates Cloudflare Access service token, applies rate limiting,
     * strips untrusted identity assertions, and opaquely proxies the payload
     * to the Skylar mesh listener.
     */
    fun handleRequest(request: LaneBRequest): LaneBResponse {
        val requestId = UUID.randomUUID().toString()
        val cfRay = request.cfAccessHeaders[HEADER_CF_RAY] ?: requestId
        totalRequests.incrementAndGet()

        Logger.i(TAG, "[$cfRay] Received Lane B request (total=${totalRequests.get()})")

        // 1. Edge Gating: Verify Cloudflare Access service token
        val edgeAuthResult = verifyCloudflareAccess(request, cfRay)
        if (!edgeAuthResult.first) {
            accessDeniedCount.incrementAndGet()
            Logger.w(TAG, "[$cfRay] Cloudflare Access denied: ${edgeAuthResult.second}")
            return LaneBResponse.error(
                statusCode = 403,
                status = LaneBResponse.STATUS_CF_ACCESS_DENIED,
                errorMessage = edgeAuthResult.second,
                errorCode = "CF_ACCESS_DENIED",
                correlationId = cfRay
            )
        }

        // 2. Connection-level Rate Limiting (Architecture doc §4)
        val callerKey = request.cfAccessClientId ?: "anonymous-edge"
        if (isRateLimited(callerKey)) {
            rateLimitedCount.incrementAndGet()
            Logger.w(TAG, "[$cfRay] Rate limit exceeded for caller $callerKey")
            return LaneBResponse.error(
                statusCode = 429,
                status = LaneBResponse.STATUS_RATE_LIMITED,
                errorMessage = "Too many requests: rate limit exceeded on relay forwarder",
                errorCode = "RATE_LIMIT_EXCEEDED",
                correlationId = cfRay
            )
        }

        // 3. Header Stripping: Log identity headers for forensics only, do NOT trust as authorization
        if (request.cfAccessHeaders.containsKey(HEADER_CF_EMAIL)) {
            Logger.d(TAG, "[$cfRay] Forensic note: Access asserted email=${request.cfAccessHeaders[HEADER_CF_EMAIL]} (not trusted for authorization)")
        }

        // 4. Opaque Forwarding across private mesh
        return forwardOpaquePayload(request.payload, cfRay)
    }

    private fun verifyCloudflareAccess(request: LaneBRequest, cfRay: String): Pair<Boolean, String> {
        val clientId = request.cfAccessClientId
        val clientSecret = request.cfAccessClientSecret

        if (validServiceTokens.isEmpty()) {
            // If no service tokens registered, allow (e.g., test pass-through if explicitly configured)
            Logger.d(TAG, "[$cfRay] No service token restrictions configured on forwarder")
            return Pair(true, "")
        }

        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            Logger.w(TAG, "[$cfRay] Missing Cloudflare Access service token headers")
            return Pair(false, "Missing Cloudflare Access service token (CF-Access-Client-Id / CF-Access-Client-Secret)")
        }

        val expectedSecret = validServiceTokens[clientId]
        if (expectedSecret == null || expectedSecret != clientSecret) {
            Logger.w(TAG, "[$cfRay] Invalid Cloudflare Access service token for client $clientId")
            return Pair(false, "Invalid Cloudflare Access service token credentials")
        }

        Logger.d(TAG, "[$cfRay] Cloudflare Access service token validated for client $clientId")
        return Pair(true, "")
    }

    @Synchronized
    private fun isRateLimited(callerKey: String): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = now - 60_000L
        val timestamps = requestTimestamps.getOrPut(callerKey) { mutableListOf() }

        // Prune old timestamps
        timestamps.removeAll { it < windowStart }

        if (timestamps.size >= maxRequestsPerMinute) {
            return true
        }

        timestamps.add(now)
        return false
    }

    private fun forwardOpaquePayload(opaquePayload: String, cfRay: String): LaneBResponse {
        val startTime = System.currentTimeMillis()
        activeConnections.incrementAndGet()
        var socket: Socket? = null

        try {
            Logger.d(TAG, "[$cfRay] Opening connection to Skylar mesh target at $targetHost:$targetPort")
            socket = if (socketFactory != null) {
                socketFactory.invoke(targetHost, targetPort)
            } else {
                Socket(targetHost, targetPort)
            }

            socket.soTimeout = readTimeoutMs

            // Send opaque payload across the private mesh without inspection or modification
            val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true)
            writer.println(opaquePayload)
            writer.flush()

            Logger.d(TAG, "[$cfRay] Forwarded opaque payload (${opaquePayload.length} bytes) to mesh target")

            // Read response from Skylar mesh listener
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val rawResponse = reader.readLine()
            val duration = System.currentTimeMillis() - startTime

            if (rawResponse.isNullOrBlank()) {
                forwardErrorsCount.incrementAndGet()
                Logger.e(TAG, "[$cfRay] Empty response received from Skylar mesh target after ${duration}ms")
                return LaneBResponse.error(
                    statusCode = 502,
                    status = LaneBResponse.STATUS_RELAY_ERROR,
                    errorMessage = "Empty response received from downstream gateway",
                    errorCode = "BAD_GATEWAY",
                    executionTimeMs = duration,
                    correlationId = cfRay
                )
            }

            forwardedCount.incrementAndGet()
            Logger.i(TAG, "[$cfRay] Received response from Skylar mesh target in ${duration}ms")

            // Decode response using LaneACodec to return structured LaneBResponse
            val laneAResponse = try {
                LaneACodec.decodeResponse(rawResponse)
            } catch (e: Exception) {
                Logger.w(TAG, "[$cfRay] Response is not standard LaneAResponse, wrapping raw: ${e.message}")
                null
            }

            return if (laneAResponse != null) {
                LaneBResponse(
                    success = laneAResponse.success,
                    statusCode = laneAResponse.statusCode,
                    status = laneAResponse.status,
                    data = laneAResponse.data,
                    errorMessage = laneAResponse.errorMessage,
                    errorCode = laneAResponse.errorCode,
                    target = laneAResponse.target,
                    executionTimeMs = duration,
                    forensicCorrelationId = cfRay
                )
            } else {
                LaneBResponse.success(
                    data = mapOf("raw" to rawResponse),
                    target = null,
                    executionTimeMs = duration,
                    correlationId = cfRay
                )
            }
        } catch (e: Exception) {
            forwardErrorsCount.incrementAndGet()
            val duration = System.currentTimeMillis() - startTime
            Logger.e(TAG, "[$cfRay] Failed to forward request to Skylar mesh target: ${e.message}", e)
            return LaneBResponse.error(
                statusCode = 504,
                status = LaneBResponse.STATUS_RELAY_ERROR,
                errorMessage = "Failed to reach Skylar mesh endpoint: ${e.message}",
                errorCode = "GATEWAY_TIMEOUT",
                executionTimeMs = duration,
                correlationId = cfRay
            )
        } finally {
            activeConnections.decrementAndGet()
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }
}
