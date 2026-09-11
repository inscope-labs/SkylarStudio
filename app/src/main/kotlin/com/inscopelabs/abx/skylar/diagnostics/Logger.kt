package com.inscopelabs.abx.skylar.diagnostics

import android.util.Log
import com.inscopelabs.abx.skylar.BuildConfig

/**
 * Diagnostic logger facade for Skylar.
 * Resolves to Android Log in debug builds and is a no-op in release builds.
 */
object Logger {
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) Log.d(tag, message, throwable) else Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }
}
