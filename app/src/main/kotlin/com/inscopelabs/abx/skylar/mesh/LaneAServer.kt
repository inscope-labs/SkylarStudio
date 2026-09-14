package com.inscopelabs.abx.skylar.mesh

import com.inscopelabs.abx.skylar.common.Result
import com.inscopelabs.abx.skylar.core.SkylarCore
import com.inscopelabs.abx.skylar.diagnostics.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Ingress Server for Lane A (private mesh network).
 *
 * Architecture doc §1 & §2:
 * Listens for requests traveling across the private mesh.
 * Confers reachability only; passes incoming envelopes to [SkylarCore]
 * for independent cryptographic verification, policy evaluation, and dispatch.
 *
 * Role: Orchestrator per AGENTS.md §4.
 */
class LaneAServer(
    private val core: SkylarCore,
    private val requestedPort: Int = core.config.meshPort,
    private val bindAddress: InetAddress? = null,
    private val transportAuthorizer: ((Socket) -> Boolean)? = null
) {
    companion object {
        private const val TAG = "LaneAServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val running = AtomicBoolean(false)

    val totalRequests = AtomicLong(0)
    val successfulRequests = AtomicLong(0)
    val rejectedRequests = AtomicLong(0)

    val isRunning: Boolean get() = running.get()
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start(): Int {
        if (running.get()) {
            Logger.w(TAG, "LaneAServer already running on port $boundPort")
            return boundPort
        }

        try {
            val socket = if (bindAddress != null) {
                ServerSocket(requestedPort, 50, bindAddress)
            } else {
                ServerSocket(requestedPort)
            }
            serverSocket = socket
            running.set(true)

            val port = socket.localPort
            Logger.i(TAG, "LaneAServer started successfully on port $port (bind=${socket.inetAddress})")

            serverJob = scope.launch {
                acceptLoop(socket)
            }
            return port
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to start LaneAServer on port $requestedPort: ${e.message}", e)
            running.set(false)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return

        Logger.i(TAG, "Stopping LaneAServer on port $boundPort (total=$totalRequests, ok=$successfulRequests, rej=$rejectedRequests)")
        serverJob?.cancel()
        serverJob = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing server socket: ${e.message}")
        } finally {
            serverSocket = null
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        Logger.d(TAG, "Entering connection accept loop on port ${socket.localPort}")
        while (running.get() && !socket.isClosed) {
            try {
                val clientSocket = socket.accept()
                scope.launch {
                    handleConnection(clientSocket)
                }
            } catch (e: SocketException) {
                if (!running.get() || socket.isClosed) {
                    Logger.d(TAG, "ServerSocket closed, terminating accept loop")
                    break
                }
                Logger.e(TAG, "Socket exception during accept: ${e.message}", e)
            } catch (e: Exception) {
                if (running.get()) {
                    Logger.e(TAG, "Unexpected exception during accept: ${e.message}", e)
                }
            }
        }
    }

    private fun handleConnection(clientSocket: Socket) {
        val remoteEndpoint = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
        totalRequests.incrementAndGet()
        Logger.i(TAG, "Incoming Lane A connection from $remoteEndpoint")

        try {
            clientSocket.soTimeout = 10_000 // 10s socket read timeout

            // Transport-level authorization check (Work Item 6: non-mesh isolation)
            if (transportAuthorizer != null && !transportAuthorizer.invoke(clientSocket)) {
                Logger.w(TAG, "Transport authorization denied for connection from $remoteEndpoint")
                rejectedRequests.incrementAndGet()
                val denyResponse = LaneAResponse.error(
                    statusCode = 403,
                    status = LaneAResponse.STATUS_TRANSPORT_DENIED,
                    errorMessage = "Transport-level access denied: remote peer not authorized on private mesh",
                    errorCode = "TRANSPORT_UNAUTHORIZED"
                )
                writeResponse(clientSocket, denyResponse)
                return
            }

            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))
            val rawPayload = reader.readLine()
            if (rawPayload.isNullOrBlank()) {
                Logger.w(TAG, "Empty request payload received from $remoteEndpoint")
                rejectedRequests.incrementAndGet()
                val errResponse = LaneAResponse.error(
                    statusCode = 400,
                    status = LaneAResponse.STATUS_ENVELOPE_INVALID,
                    errorMessage = "Empty request payload",
                    errorCode = "EMPTY_PAYLOAD"
                )
                writeResponse(clientSocket, errResponse)
                return
            }

            // Decode LaneARequest or raw RequestEnvelope
            val envelope = try {
                if (rawPayload.contains("\"envelopeJson\"")) {
                    val req = LaneACodec.decodeRequest(rawPayload)
                    LaneACodec.decodeEnvelope(req.envelopeJson)
                } else {
                    LaneACodec.decodeEnvelope(rawPayload)
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Malformed envelope JSON from $remoteEndpoint: ${e.message}")
                rejectedRequests.incrementAndGet()
                val errResponse = LaneAResponse.error(
                    statusCode = 400,
                    status = LaneAResponse.STATUS_ENVELOPE_INVALID,
                    errorMessage = "Malformed envelope JSON: ${e.message}",
                    errorCode = "ENVELOPE_JSON_MALFORMED"
                )
                writeResponse(clientSocket, errResponse)
                return
            }

            Logger.i(TAG, "Processing envelope for caller=${envelope.callerId}, cap=${envelope.capability}, nonce=${envelope.nonce}")

            // Dispatch to Skylar Core
            val startTime = System.currentTimeMillis()
            val result = core.processEnvelope(envelope)
            val duration = System.currentTimeMillis() - startTime
            val response = when (result) {
                is Result.Success -> {
                    successfulRequests.incrementAndGet()
                    Logger.i(TAG, "Capability ${envelope.capability} authorized in ${duration}ms")
                    LaneAResponse.success(
                        data = result.data,
                        target = result.data["target"] as? String,
                        executionTimeMs = duration
                    )
                }
                is Result.Error -> {
                    val code = result.errorCode ?: "DISPATCH_FAILED"
                    rejectedRequests.incrementAndGet()
                    Logger.w(TAG, "Envelope rejected: code=$code, reason=${result.message}")
                    val statusCode = mapErrorCodeToHttpStatus(code)
                    LaneAResponse.error(
                        statusCode = statusCode,
                        status = mapErrorCodeToStatusString(code),
                        errorMessage = result.message,
                        errorCode = code,
                        target = null,
                        executionTimeMs = duration
                    )
                }
            }

            writeResponse(clientSocket, response)
        } catch (e: Exception) {
            rejectedRequests.incrementAndGet()
            Logger.e(TAG, "Error handling connection from $remoteEndpoint: ${e.message}", e)
            try {
                val errResponse = LaneAResponse.error(
                    statusCode = 500,
                    status = LaneAResponse.STATUS_DISPATCH_FAILED,
                    errorMessage = "Internal processing error: ${e.message}",
                    errorCode = "INTERNAL_ERROR"
                )
                writeResponse(clientSocket, errResponse)
            } catch (_: Exception) {}
        } finally {
            try {
                clientSocket.close()
            } catch (_: Exception) {}
        }
    }

    private fun writeResponse(socket: Socket, response: LaneAResponse) {
        val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true)
        val encoded = LaneACodec.encodeResponse(response)
        writer.println(encoded)
        writer.flush()
        Logger.d(TAG, "Wrote LaneAResponse status=${response.status}, code=${response.statusCode} to ${socket.inetAddress}")
    }

    private fun mapErrorCodeToHttpStatus(errorCode: String): Int = when (errorCode) {
        "AUTH_NO_MATCHING_POLICY", "SCOPE_INSUFFICIENT", "UNAUTHORIZED" -> 403
        "VERIFY_EXPIRED", "ENVELOPE_EXPIRED", "VERIFY_REPLAY_DETECTED", "REPLAY_DETECTED", "VERIFY_INVALID_SIGNATURE", "INVALID_SIGNATURE" -> 403
        "DISPATCH_UNMAPPED_CAPABILITY", "UNMAPPED_CAPABILITY" -> 404
        else -> 400
    }

    private fun mapErrorCodeToStatusString(errorCode: String): String = when (errorCode) {
        "AUTH_NO_MATCHING_POLICY", "SCOPE_INSUFFICIENT", "UNAUTHORIZED" -> LaneAResponse.STATUS_UNAUTHORIZED
        "VERIFY_REPLAY_DETECTED", "REPLAY_DETECTED" -> LaneAResponse.STATUS_REPLAY_DETECTED
        "VERIFY_EXPIRED", "ENVELOPE_EXPIRED" -> LaneAResponse.STATUS_EXPIRED
        "VERIFY_INVALID_SIGNATURE", "INVALID_SIGNATURE" -> LaneAResponse.STATUS_UNAUTHORIZED
        "DISPATCH_UNMAPPED_CAPABILITY", "UNMAPPED_CAPABILITY" -> LaneAResponse.STATUS_UNMAPPED_CAPABILITY
        else -> LaneAResponse.STATUS_DISPATCH_FAILED
    }
}
