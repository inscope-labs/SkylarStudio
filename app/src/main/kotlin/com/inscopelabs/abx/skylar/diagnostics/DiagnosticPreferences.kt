package com.inscopelabs.abx.skylar.diagnostics

import android.content.Context

object DiagnosticPreferences {
    private const val PREFS_NAME = "abx_diagnostics_prefs"

    fun isRemoteReportingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(DiagnosticSettings.PREF_REMOTE_REPORTING, false)
    }

    fun setRemoteReportingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(DiagnosticSettings.PREF_REMOTE_REPORTING, enabled).apply()
        Logger.d("DiagnosticPreferences", "Updated remote reporting preference to: $enabled")
    }
}
