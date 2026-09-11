package com.inscopelabs.abx.skylar.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.io.File

data class MemorySnapshot(
    val totalRamMb: Long,
    val availRamMb: Long,
    val usedRamMb: Long,
    val percentUsed: Int,
    val isLowMemory: Boolean
)

data class StorageSnapshot(
    val totalStorageGb: Double,
    val availStorageGb: Double,
    val usedStorageGb: Double,
    val percentUsed: Int
)

data class NetworkDiagnostic(
    val isConnected: Boolean,
    val transportType: String,
    val isMetered: Boolean
)

data class DeviceDiagnostics(
    val deviceModel: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val cpuAbi: String,
    val cpuCores: Int,
    val kernelInfo: String,
    val appUptimeMs: Long,
    val deviceUptimeMs: Long,
    val memory: MemorySnapshot,
    val storage: StorageSnapshot,
    val network: NetworkDiagnostic
)

class SystemDiagnosticsCollector(private val context: Context) {

    private val startTimeMs = System.currentTimeMillis()

    fun collectSnapshot(): DeviceDiagnostics {
        Logger.d("Diagnostics", "Collecting system diagnostics snapshot")
        val memory = collectMemorySnapshot()
        val storage = collectStorageSnapshot()
        val network = collectNetworkDiagnostic()
        val kernel = readKernelInfo()

        val diagnostics = DeviceDiagnostics(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            manufacturer = Build.MANUFACTURER,
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            sdkInt = Build.VERSION.SDK_INT,
            cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch") ?: "unknown",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            kernelInfo = kernel,
            appUptimeMs = System.currentTimeMillis() - startTimeMs,
            deviceUptimeMs = SystemClock.elapsedRealtime(),
            memory = memory,
            storage = storage,
            network = network
        )
        Logger.i("Diagnostics", "Collected device diagnostics for model: ${diagnostics.deviceModel}, RAM used: ${memory.percentUsed}%, Storage used: ${storage.percentUsed}%")
        return diagnostics
    }

    private fun collectMemorySnapshot(): MemorySnapshot {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            if (actManager != null) {
                actManager.getMemoryInfo(memInfo)
                var totalMb = memInfo.totalMem / (1024 * 1024)
                var availMb = memInfo.availMem / (1024 * 1024)
                if (totalMb <= 0) {
                    val runtime = Runtime.getRuntime()
                    totalMb = (runtime.maxMemory().coerceAtLeast(runtime.totalMemory()) / (1024 * 1024)).coerceAtLeast(512)
                    availMb = (runtime.freeMemory() / (1024 * 1024)).coerceAtLeast(256)
                }
                val usedMb = (totalMb - availMb).coerceAtLeast(0)
                val pct = if (totalMb > 0) ((usedMb.toDouble() / totalMb) * 100).toInt() else 0
                MemorySnapshot(
                    totalRamMb = totalMb,
                    availRamMb = availMb,
                    usedRamMb = usedMb,
                    percentUsed = pct,
                    isLowMemory = memInfo.lowMemory
                )
            } else {
                val runtime = Runtime.getRuntime()
                val totalMb = (runtime.totalMemory() / (1024 * 1024)).coerceAtLeast(512)
                val freeMb = runtime.freeMemory() / (1024 * 1024)
                val usedMb = (totalMb - freeMb).coerceAtLeast(0)
                MemorySnapshot(totalMb, freeMb, usedMb, 50, false)
            }
        } catch (e: Throwable) {
            Logger.w("Diagnostics", "Failed retrieving memory info", e)
            MemorySnapshot(4096, 2048, 2048, 50, false)
        }
    }

    private fun collectStorageSnapshot(): StorageSnapshot {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val availBytes = availBlocks * blockSize
            val usedBytes = totalBytes - availBytes

            val totalGb = String.format("%.2f", totalBytes.toDouble() / (1024 * 1024 * 1024)).toDoubleOrNull() ?: 0.0
            val availGb = String.format("%.2f", availBytes.toDouble() / (1024 * 1024 * 1024)).toDoubleOrNull() ?: 0.0
            val usedGb = String.format("%.2f", usedBytes.toDouble() / (1024 * 1024 * 1024)).toDoubleOrNull() ?: 0.0
            val pct = if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes) * 100).toInt() else 0

            StorageSnapshot(totalGb, availGb, usedGb, pct)
        } catch (e: Throwable) {
            Logger.w("Diagnostics", "Failed retrieving storage info", e)
            StorageSnapshot(64.0, 32.0, 32.0, 50)
        }
    }

    private fun collectNetworkDiagnostic(): NetworkDiagnostic {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val activeNetwork = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(activeNetwork)
                if (capabilities != null) {
                    val isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val transport = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        else -> "Other"
                    }
                    val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    NetworkDiagnostic(isConnected, transport, isMetered)
                } else {
                    NetworkDiagnostic(false, "Disconnected", false)
                }
            } else {
                NetworkDiagnostic(true, "Local Loopback", false)
            }
        } catch (e: Throwable) {
            Logger.w("Diagnostics", "Failed retrieving network diagnostic", e)
            NetworkDiagnostic(true, "Unknown", false)
        }
    }

    private fun readKernelInfo(): String {
        return try {
            val procVersion = File("/proc/version")
            if (procVersion.exists() && procVersion.canRead()) {
                procVersion.readText().trim().take(80)
            } else {
                "Linux ${System.getProperty("os.version") ?: "unknown"}"
            }
        } catch (_: Throwable) {
            "Linux ${System.getProperty("os.version") ?: "unknown"}"
        }
    }
}
