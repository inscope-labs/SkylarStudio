package com.inscopelabs.abx.skylar.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.inscopelabs.abx.skylar.R

/**
 * On-screen crash report shown immediately after an uncaught exception,
 * launched by GlobalExceptionHandler instead of letting the OS show the
 * bare "App has stopped" dialog.
 *
 * Reads everything from Intent extras rather than a shared store, since
 * this activity is started from inside the crash handler itself and must
 * not depend on anything that could also be in a broken state.
 */
class CrashActivity : ComponentActivity() {
    private companion object {
        const val TAG = "SKYLAR_CRASH_UI"

        const val EXTRA_EXCEPTION_TYPE = "extra_exception_type"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_METADATA = "extra_metadata"
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
        const val EXTRA_FULL_REPORT = "extra_full_report"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i(TAG, "CrashActivity launched, initializing crash display")
        try {
            setContentView(R.layout.activity_crash)
            setupUI()
            Logger.d(TAG, "CrashActivity UI setup completed successfully")
        } catch (t: Throwable) {
            // If even this screen fails to render, never let that become a second crash
            Logger.e(TAG, "Failed to initialize CrashActivity UI safely", t)
            try {
                Toast.makeText(this, getString(R.string.error_fallback_toast), Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
            finish()
        }
    }

    private fun setupUI() {
        val tvExceptionType: TextView = findViewById(R.id.tvExceptionType)
        val tvMessage: TextView = findViewById(R.id.tvMessage)
        val tvMetadata: TextView = findViewById(R.id.tvMetadata)
        val tvStackTrace: TextView = findViewById(R.id.tvStackTrace)
        val btnCopy: Button = findViewById(R.id.btnCopy)
        val btnRestart: Button = findViewById(R.id.btnRestart)

        val exceptionType = intent.getStringExtra(EXTRA_EXCEPTION_TYPE)
            ?: getString(R.string.crash_unknown_type)
        val message = intent.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.crash_unknown_message)
        val metadata = intent.getStringExtra(EXTRA_METADATA) ?: ""
        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: ""
        val fullReport = intent.getStringExtra(EXTRA_FULL_REPORT) ?: ""

        Logger.i(TAG, "Displaying crash details for exception: $exceptionType")
        tvExceptionType.text = exceptionType
        tvMessage.text = message
        tvMetadata.text = metadata
        tvStackTrace.text = stackTrace

        btnCopy.setOnClickListener {
            Logger.d(TAG, "Copy button clicked, placing crash report in clipboard")
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Skylar Crash Report", fullReport)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
                Logger.i(TAG, "Crash report successfully copied to clipboard")
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to copy crash report", t)
                Toast.makeText(this, "Failed to copy report", Toast.LENGTH_SHORT).show()
            }
        }

        btnRestart.setOnClickListener {
            Logger.i(TAG, "Restart button clicked, relaunching Skylar application")
            try {
                val relaunch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (relaunch != null) {
                    startActivity(relaunch)
                    Logger.i(TAG, "Restart launch intent dispatched")
                } else {
                    Logger.w(TAG, "Launch intent for package was null")
                }
                finishAffinity()
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to restart application", t)
                Toast.makeText(this, R.string.error_restart_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
