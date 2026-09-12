package com.inscopelabs.abx.skylar.audit

import android.content.Context
import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.diagnostics.Logger
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Collections
import java.util.LinkedList

/**
 * Append-only audit logging sink for Skylar Context Gateway.
 * Persists audit decisions to durable local storage and emits structured diagnostic logs.
 */
class AuditLogger(
    private val context: Context,
    private val config: SkylarConfig = SkylarConfig.DEFAULT
) {
    companion object {
        private const val TAG = "SkylarAuditLogger"
        private const val MAX_IN_MEMORY_BUFFER = 100
    }

    private val lock = Any()
    private val inMemoryRecentRecords: MutableList<AuditRecord> =
        Collections.synchronizedList(LinkedList<AuditRecord>())

    private val logFile: File by lazy {
        File(context.filesDir, config.auditLogFileName)
    }

    init {
        Logger.d(TAG, "Initializing AuditLogger. Storage path: ${logFile.absolutePath}")
    }

    /**
     * Appends an audit record to the persistent log and memory cache.
     */
    fun record(record: AuditRecord) {
        val logLine = record.toFormattedLogLine()

        when (record.decision) {
            AuditRecord.Decision.ALLOW -> Logger.i(TAG, "AUDIT ALLOW: $logLine")
            AuditRecord.Decision.DENY -> Logger.w(TAG, "AUDIT DENY: $logLine")
        }

        synchronized(lock) {
            // Buffer in memory for quick diagnostics / inspection
            inMemoryRecentRecords.add(record)
            if (inMemoryRecentRecords.size > MAX_IN_MEMORY_BUFFER) {
                inMemoryRecentRecords.removeAt(0)
            }

            // Append to disk file
            try {
                FileWriter(logFile, true).use { writer ->
                    writer.append(logLine).append("\n")
                }
            } catch (e: IOException) {
                Logger.e(TAG, "Failed to persist audit log entry to file", e)
            }
        }
    }

    /**
     * Retrieves an unmodifiable snapshot of recent audit records.
     */
    fun getRecentRecords(): List<AuditRecord> {
        synchronized(lock) {
            return ArrayList(inMemoryRecentRecords)
        }
    }

    /**
     * Clears in-memory buffer (for testing or memory pressure).
     */
    fun clearInMemory() {
        synchronized(lock) {
            inMemoryRecentRecords.clear()
        }
        Logger.d(TAG, "In-memory audit cache cleared")
    }
}
