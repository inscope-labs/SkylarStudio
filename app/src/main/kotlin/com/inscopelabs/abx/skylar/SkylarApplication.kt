package com.inscopelabs.abx.skylar

import android.app.Application
import com.inscopelabs.abx.skylar.core.SkylarCore
import com.inscopelabs.abx.skylar.diagnostics.GlobalExceptionHandler
import com.inscopelabs.abx.skylar.diagnostics.Logger

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

        // 2. Initialize Skylar Core and policy engines
        skylarCore = SkylarCore(applicationContext)
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
}
