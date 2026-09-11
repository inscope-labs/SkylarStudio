package com.inscopelabs.abx.skylar.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.inscopelabs.abx.skylar.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught exception handler for Skylar.
 *
 * Responsibilities:
 * - Log every uncaught Java/Kotlin exception.
 * - Persist crash reports to internal storage safely with log rotation.
 * - Dynamically forward crashes to pluggable reporters via CrashReporterManager.
 * - Chained to Android's default exception handler to ensure standard system crash behavior.
 */
class GlobalExceptionHandler(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val CRASH_LOG_FILE = "crash_logs.txt"
        private const val MAX_LOG_SIZE = 5L * 1024L * 1024L // 5 MB

        private val fileLock = Any()
    }

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    private val appContext: Context = context.applicationContext

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable
    ) {
        try {
            val referenceCode = generateReferenceCode()
            val crashReport = buildCrashReport(thread, throwable, referenceCode)

            // Log crash structured to diagnostic logger
            Logger.e("SKYLAR_CRASH", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)

            // Persist the crash report to crash_logs.txt (for error screens)
            writeCrashLog(crashReport)

            // Send crash dynamically to pluggable crash reporters (e.g. Firebase)
            CrashReporterManager.reportCrash(thread, throwable)

            launchErrorActivity(throwable, crashReport, referenceCode)
        } catch (e: Exception) {
            try {
                Log.e("SKYLAR_CRASH", "Exception inside exception handler", e)
            } catch (_: Throwable) {}
        }

        val startedCrashActivity = crashActivityLaunched
        if (!startedCrashActivity) {
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Thread.sleep(300) // give the startActivity() IPC time to land before we self-terminate
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess()
    }

    @Volatile
    private var crashActivityLaunched = false

    private fun generateReferenceCode(): String =
        "SKYLAR-" + java.lang.Long.toString(System.currentTimeMillis(), 36).uppercase(Locale.US)

    private fun launchErrorActivity(throwable: Throwable, fullReport: String, referenceCode: String) {
        val intent = if (BuildConfig.DEBUG) {
            val metadata = buildString {
                appendLine("Manufacturer: ${Build.MANUFACTURER}")
                appendLine("Model: ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                append("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            }
            val stackWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(stackWriter))

            Intent(appContext, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("extra_exception_type", throwable.javaClass.name)
                putExtra("extra_message", throwable.message ?: "No message")
                putExtra("extra_metadata", metadata)
                putExtra("extra_stack_trace", stackWriter.toString())
                putExtra("extra_full_report", fullReport)
            }
        } else {
            Intent(appContext, UserFacingErrorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("extra_reference_code", referenceCode)
                putExtra("extra_full_report", fullReport)
            }
        }
        appContext.startActivity(intent)
        crashActivityLaunched = true
    }

    @Suppress("DEPRECATION")
    private fun buildCrashReport(
        thread: Thread,
        throwable: Throwable,
        referenceCode: String
    ): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))

        return buildString {
            appendLine("====================================================")
            appendLine("SKYLAR GLOBAL CRASH REPORT")
            appendLine("====================================================")

            appendLine("Reference Code : $referenceCode")
            appendLine("Timestamp      : ${
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.US
                ).format(Date())
            }")

            appendLine("Thread         : ${thread.name}")
            appendLine("Thread ID      : ${thread.id}")

            appendLine()
            appendLine("APPLICATION")

            appendLine("Package        : ${appContext.packageName}")
            appendLine("Version Name   : ${BuildConfig.VERSION_NAME}")
            appendLine("Version Code   : ${BuildConfig.VERSION_CODE}")
            appendLine("Debug Build    : ${BuildConfig.DEBUG}")

            appendLine()
            appendLine("DEVICE")

            appendLine("Manufacturer   : ${Build.MANUFACTURER}")
            appendLine("Brand          : ${Build.BRAND}")
            appendLine("Model          : ${Build.MODEL}")
            appendLine("Device         : ${Build.DEVICE}")
            appendLine("Product        : ${Build.PRODUCT}")

            appendLine()
            appendLine("ANDROID")

            appendLine("Release        : ${Build.VERSION.RELEASE}")
            appendLine("SDK            : ${Build.VERSION.SDK_INT}")
            appendLine("Codename       : ${Build.VERSION.CODENAME}")

            appendLine()
            appendLine("EXCEPTION")

            appendLine("Type           : ${throwable.javaClass.name}")
            appendLine("Message        : ${throwable.message}")

            appendLine()
            appendLine("STACK TRACE")
            appendLine(writer.toString())

            var cause = throwable.cause
            while (cause != null) {
                appendLine()
                appendLine("CAUSED BY")
                appendLine(Log.getStackTraceString(cause))
                cause = cause.cause
            }

            appendLine("====================================================")
            appendLine()
        }
    }

    private fun writeCrashLog(report: String) {
        synchronized(fileLock) {
            try {
                val file = File(appContext.filesDir, CRASH_LOG_FILE)

                if (file.exists() && (file.length() + report.length > MAX_LOG_SIZE)) {
                    val rotatedFile = File(appContext.filesDir, "$CRASH_LOG_FILE.1")
                    if (rotatedFile.exists()) {
                        rotatedFile.delete()
                    }
                    file.renameTo(rotatedFile)
                }

                FileOutputStream(file, true).bufferedWriter().use { writer ->
                    writer.write(report)
                    writer.flush()
                }
            } catch (e: Exception) {
                try {
                    Log.e("SKYLAR_CRASH", "Failed to write crash log to file", e)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun exitProcess() {
        kotlin.system.exitProcess(10)
    }
}
