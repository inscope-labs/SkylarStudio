package com.inscopelabs.abx.skylar.diagnostics

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.inscopelabs.abx.skylar.R

/**
 * Release-build counterpart to CrashActivity. Shown to end users after an
 * uncaught exception instead of the bare OS "App has stopped" dialog, or
 * CrashActivity's developer-facing stack trace view.
 *
 * Deliberately does NOT render exception type, message, or stack trace —
 * only a short opaque reference code the user can quote to support, which
 * correlates with the matching entry already written to crash_logs.txt by
 * GlobalExceptionHandler.
 */
class UserFacingErrorActivity : ComponentActivity() {
    private companion object {
        const val TAG = "SKYLAR_ERROR_UI"

        const val EXTRA_REFERENCE_CODE = "extra_reference_code"
        const val EXTRA_FULL_REPORT = "extra_full_report"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i(TAG, "UserFacingErrorActivity launched, initializing recovery screen")
        try {
            setContentView(R.layout.activity_user_facing_error)
            setupUI()
            Logger.d(TAG, "UserFacingErrorActivity UI setup completed")
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to initialize UserFacingErrorActivity UI safely", t)
            try {
                Toast.makeText(this, getString(R.string.error_fallback_toast), Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
            finish()
        }
    }

    private fun setupUI() {
        val tvReferenceCode: TextView = findViewById(R.id.tvReferenceCode)
        val btnRestart: Button = findViewById(R.id.btnErrorRestart)
        val btnShare: Button = findViewById(R.id.btnErrorShare)

        val referenceCode = intent.getStringExtra(EXTRA_REFERENCE_CODE)
            ?: getString(R.string.error_unknown_reference)
        val fullReport = intent.getStringExtra(EXTRA_FULL_REPORT) ?: ""

        Logger.i(TAG, "Displaying recovery reference code: $referenceCode")
        tvReferenceCode.text = referenceCode

        btnRestart.setOnClickListener {
            Logger.i(TAG, "User clicked restart button")
            try {
                val relaunch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (relaunch != null) {
                    startActivity(relaunch)
                    Logger.i(TAG, "Application relaunch intent started")
                } else {
                    Logger.w(TAG, "Launch intent for package was null")
                }
                finishAffinity()
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to restart application", t)
                Toast.makeText(this, R.string.error_restart_failed, Toast.LENGTH_SHORT).show()
            }
        }

        btnShare.setOnClickListener {
            Logger.i(TAG, "User clicked share button for reference code: $referenceCode")
            try {
                val shareText = getString(R.string.error_share_body, referenceCode, fullReport)
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.error_share_subject, referenceCode))
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.error_share_chooser_title)))
                Logger.d(TAG, "Share chooser opened successfully")
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to share diagnostic report", t)
                Toast.makeText(this, R.string.error_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
