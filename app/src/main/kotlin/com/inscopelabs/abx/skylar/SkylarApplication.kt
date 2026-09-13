package com.inscopelabs.abx.skylar

import android.app.Application
import com.inscopelabs.abx.skylar.core.SkylarCore
import com.inscopelabs.abx.skylar.envelope.crypto.InMemoryKeyRegistry
import com.inscopelabs.abx.skylar.diagnostics.GlobalExceptionHandler
import com.inscopelabs.abx.skylar.diagnostics.Logger
import com.inscopelabs.abx.skylar.envelope.EnvelopeLog
import com.inscopelabs.abx.skylar.envelope.EnvelopeLogger

/**
 * Android Application entry point for Skylar Context Gateway.
 * Coordinates system diagnostic handlers, crash hooks, and initializes Skylar Core.
 */
class SkylarApplication : Application() {

    companion object {
        private const val TAG = "SkylarApplication"

        @Volatile
        private var instance: SkylarApplication? = null

        fun getInstance(): SkylarApplication {
            return checkNotNull(instance) { "SkylarApplication is not initialized yet" }
        }
    }

    lateinit var skylarCore: SkylarCore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize diagnostic logger and crash reporting
        Logger.i(TAG, "SkylarApplication onCreate starting...")
        initCrashHandling()
        initEnvelopeLogging()

        // 2. Initialize Skylar Core and policy engines
        //
        // KeyRegistry: intentionally an empty InMemoryKeyRegistry. Real
        // caller public-key registration/rotation is Phase 6's concern
        // (request-signing credential bootstrap) and doesn't exist yet.
        // An empty registry means EnvelopeVerifier rejects every caller
        // as unregistered — that's the correct default-deny state for a
        // build with no real key provisioning wired in, not a bug to
        // route around here.
        skylarCore = SkylarCore(applicationContext, keyRegistry = InMemoryKeyRegistry())
        val initResult = skylarCore.initialize()

        if (initResult.isSuccess) {
            Logger.i(TAG, "SkylarApplication: Skylar Core policy engine initialized successfully")
        } else {
            Logger.e(TAG, "SkylarApplication: Skylar Core failed to initialize cleanly")
        }

        Logger.i(TAG, "SkylarApplication initialization complete")
    }

    private fun initCrashHandling() {
        try {
            val handler = GlobalExceptionHandler(applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Logger.d(TAG, "GlobalExceptionHandler registered as default uncaught exception handler")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to register GlobalExceptionHandler", e)
        }
    }

    private fun initEnvelopeLogging() {
        EnvelopeLog.delegate = EnvelopeLogger { level, tag, message, throwable ->
            when (level) {
                EnvelopeLogger.Level.DEBUG -> Logger.d(tag, message)
                EnvelopeLogger.Level.INFO -> Logger.i(tag, message)
                EnvelopeLogger.Level.WARN -> if (throwable != null) Logger.e(tag, message, throwable) else Logger.w(tag, message)
                EnvelopeLogger.Level.ERROR -> if (throwable != null) Logger.e(tag, message, throwable) else Logger.e(tag, message)
            }
        }
    }
}
