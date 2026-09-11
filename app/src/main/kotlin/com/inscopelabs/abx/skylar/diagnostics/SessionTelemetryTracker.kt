package com.inscopelabs.abx.skylar.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class SessionMetrics(
    val sessionId: String,
    val hostLabel: String,
    val protocol: String,
    val connectedAt: Long,
    val durationSeconds: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    val latencyMs: Long,
    val throughputBps: Long,
    val packetCount: Long
)

class SessionTelemetryTracker {

    private val sessionMetricsMap = ConcurrentHashMap<String, SessionMetrics>()
    private val _telemetryFlow = MutableStateFlow<Map<String, SessionMetrics>>(emptyMap())
    val telemetryFlow: StateFlow<Map<String, SessionMetrics>> = _telemetryFlow.asStateFlow()

    fun recordSessionConnected(
        sessionId: String,
        hostLabel: String,
        protocol: String
    ) {
        val metrics = SessionMetrics(
            sessionId = sessionId,
            hostLabel = hostLabel,
            protocol = protocol,
            connectedAt = System.currentTimeMillis(),
            durationSeconds = 0,
            bytesIn = 0,
            bytesOut = 0,
            latencyMs = 0,
            throughputBps = 0,
            packetCount = 0
        )
        sessionMetricsMap[sessionId] = metrics
        _telemetryFlow.value = sessionMetricsMap.toMap()
        Logger.d("SessionTelemetry", "Tracked connection for session $sessionId ($hostLabel)")
    }

    fun recordTraffic(sessionId: String, bytesInDelta: Long, bytesOutDelta: Long) {
        val current = sessionMetricsMap[sessionId] ?: return
        val now = System.currentTimeMillis()
        val durationSec = ((now - current.connectedAt) / 1000).coerceAtLeast(1)
        val newIn = current.bytesIn + bytesInDelta
        val newOut = current.bytesOut + bytesOutDelta
        val totalBytes = newIn + newOut
        val throughput = totalBytes / durationSec

        val updated = current.copy(
            durationSeconds = durationSec,
            bytesIn = newIn,
            bytesOut = newOut,
            throughputBps = throughput,
            packetCount = current.packetCount + 1
        )
        sessionMetricsMap[sessionId] = updated
        _telemetryFlow.value = sessionMetricsMap.toMap()
    }

    fun recordLatency(sessionId: String, latencyMs: Long) {
        val current = sessionMetricsMap[sessionId] ?: return
        val updated = current.copy(latencyMs = latencyMs)
        sessionMetricsMap[sessionId] = updated
        _telemetryFlow.value = sessionMetricsMap.toMap()
    }

    fun removeSession(sessionId: String) {
        sessionMetricsMap.remove(sessionId)
        _telemetryFlow.value = sessionMetricsMap.toMap()
        Logger.d("SessionTelemetry", "Removed telemetry for session $sessionId")
    }

    fun getMetrics(sessionId: String): SessionMetrics? = sessionMetricsMap[sessionId]

    fun getAllMetrics(): List<SessionMetrics> = sessionMetricsMap.values.toList()
}
