package com.inscopelabs.abx.skylar.diagnostics

import android.content.Context

object CrashReporterManager {
    private lateinit var activeReporter: CrashReporter
    private var isFirebaseEnabled = false

    fun initialize(context: Context) {
        isFirebaseEnabled = DiagnosticPreferences.isRemoteReportingEnabled(context)
        Logger.i("CrashReporterManager", "Initializing CrashReporterManager with remote reporting = $isFirebaseEnabled")
        activeReporter = if (isFirebaseEnabled) {
            FirebaseCrashReporter(context).apply { setEnabled(true) }
        } else {
            NoOpCrashReporter()
        }
        activeReporter.initialize()
    }

    fun reportCrash(thread: Thread, throwable: Throwable) {
        Logger.d("CrashReporterManager", "Forwarding crash to active reporter: ${if (::activeReporter.isInitialized) activeReporter.javaClass.simpleName else "uninitialized"}")
        if (::activeReporter.isInitialized) {
            activeReporter.reportCrash(thread, throwable)
        }
    }

    fun updateReportingPreference(context: Context, enabled: Boolean) {
        Logger.i("CrashReporterManager", "Updating reporting preference: enabled = $enabled")
        isFirebaseEnabled = enabled
        DiagnosticPreferences.setRemoteReportingEnabled(context, enabled)

        activeReporter = if (enabled) {
            FirebaseCrashReporter(context).apply { setEnabled(true) }
        } else {
            NoOpCrashReporter()
        }
        activeReporter.initialize()
    }

    fun isFirebaseEnabled(): Boolean = isFirebaseEnabled
}
