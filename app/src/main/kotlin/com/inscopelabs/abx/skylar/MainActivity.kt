package com.inscopelabs.abx.skylar

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.inscopelabs.abx.skylar.diagnostics.Logger

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.i(TAG, "onCreate entry: initializing MainActivity")

        try {
            enableEdgeToEdge()
            setContentView(R.layout.activity_main)
            Logger.d(TAG, "ContentView set to activity_main")

            val mainView = findViewById<View>(R.id.main_root)
            if (mainView != null) {
                Logger.d(TAG, "main_root view located, attaching WindowInsets listener")
                ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                    Logger.d(TAG, "WindowInsets applied: left=${systemBars.left}, top=${systemBars.top}, right=${systemBars.right}, bottom=${systemBars.bottom}")
                    insets
                }
            } else {
                Logger.w(TAG, "main_root view not found during onCreate")
            }

            // Verify Skylar Core initialization status
            val app = application as? SkylarApplication
            if (app != null && app.skylarCore.isReady()) {
                Logger.i(TAG, "Skylar Core is verified READY and initialized")
            } else {
                Logger.w(TAG, "Skylar Core is NOT initialized or application instance is null")
            }

            Logger.i(TAG, "onCreate completed successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Fatal error during MainActivity onCreate", e)
            throw e
        }
    }

    override fun onStart() {
        super.onStart()
        Logger.d(TAG, "onStart")
    }

    override fun onResume() {
        super.onResume()
        Logger.d(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Logger.d(TAG, "onPause")
    }

    override fun onStop() {
        super.onStop()
        Logger.d(TAG, "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG, "onDestroy: MainActivity tearing down")
    }
}

