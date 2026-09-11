package com.inscopelabs.abx.skylar.diagnostics

import android.content.Context

class FirebaseCrashReporter(private val context: Context) : CrashReporter {
    private var isEnabled = false

    override fun initialize() {
        Logger.d("FirebaseCrashReporter", "Initializing FirebaseCrashReporter")
        updateCrashlyticsState()
    }

    override fun reportCrash(thread: Thread, throwable: Throwable) {
        if (!isEnabled) {
            Logger.d("FirebaseCrashReporter", "Crash reporting skipped (reporter disabled)")
            return
        }
        try {
            val crashlyticsClass = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val getInstanceMethod = crashlyticsClass.getMethod("getInstance")
            val crashlyticsInstance = getInstanceMethod.invoke(null)
            val recordExceptionMethod = crashlyticsClass.getMethod("recordException", Throwable::class.java)
            recordExceptionMethod.invoke(crashlyticsInstance, throwable)
            Logger.i("FirebaseCrashReporter", "Recorded exception to Firebase Crashlytics via reflection")
        } catch (e: Exception) {
            Logger.e("FirebaseCrashReporter", "Failed to report crash via reflection", e)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Logger.i("FirebaseCrashReporter", "Setting Firebase crash reporting enabled: $enabled")
        updateCrashlyticsState()
    }

    private fun updateCrashlyticsState() {
        try {
            val crashlyticsClass = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val getInstanceMethod = crashlyticsClass.getMethod("getInstance")
            val crashlyticsInstance = getInstanceMethod.invoke(null)
            val setCollectionEnabledMethod = crashlyticsClass.getMethod("setCrashlyticsCollectionEnabled", Boolean::class.java)
            setCollectionEnabledMethod.invoke(crashlyticsInstance, isEnabled)
            Logger.i("FirebaseCrashReporter", "Firebase Crashlytics collection enabled: $isEnabled")
        } catch (e: Exception) {
            Logger.i("FirebaseCrashReporter", "Firebase Crashlytics not present or failed to configure: ${e.message}")
        }
    }
}
