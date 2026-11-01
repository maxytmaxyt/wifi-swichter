package com.example.rootwifiswitcher.wifi

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock

internal data class NetworkEvaluation(
    val status: String,
    val targetSsid: String? = null,
    val shouldSwitch: Boolean = false
)

internal class WifiNetworkMonitor(private val wifiManager: WifiManager) {
    fun requestScan(): Boolean = wifiManager.startScan()

    fun evaluate(): NetworkEvaluation {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val scans = wifiManager.scanResults
            .filter { nowNanos - it.timestamp <= MAX_SCAN_AGE_MICROS * 1_000L }
        val current = wifiManager.connectionInfo
        val currentSsid = current.ssid.removeSurrounding("\"")
        val targetSsid = when (currentSsid) {
            SSID_24_GHZ -> SSID_5_GHZ
            SSID_5_GHZ -> SSID_24_GHZ
            else -> {
                return NetworkEvaluation(
                    "Aktuelles WLAN nicht erkannt. Prüfe Standortfreigabe und SSID."
                )
            }
        }
        val target = scans
            .filter { it.SSID == targetSsid }
            .maxByOrNull { it.level }
            ?: return NetworkEvaluation("Zielnetz \"$targetSsid\" ist aktuell nicht sichtbar.")

        val currentRssi = current.rssi
        val currentRate = current.linkSpeed
        val targetRate = estimateSingleStreamRate(target)
        val hasWeakSignal = currentRssi <= WEAK_SIGNAL_THRESHOLD_DBM
        val isSignificantlyBetter =
            target.level >= currentRssi + SIGNAL_ADVANTAGE_DB ||
                (target.level >= MINIMUM_USABLE_TARGET_DBM &&
                    targetRate > currentRate * RATE_ADVANTAGE_FACTOR)

        val status =
            "Verbunden mit $currentSsid ($currentRssi dBm, ${currentRate} Mbit/s); " +
                "$targetSsid: ${target.level} dBm, ca. $targetRate Mbit/s."
        return NetworkEvaluation(
            status = status,
            targetSsid = targetSsid,
            shouldSwitch = hasWeakSignal && isSignificantlyBetter
        )
    }

    private fun estimateSingleStreamRate(scan: ScanResult): Int {
        val widthIndex = when (scan.channelWidth) {
            ScanResult.CHANNEL_WIDTH_40MHZ -> 1
            ScanResult.CHANNEL_WIDTH_80MHZ -> 2
            ScanResult.CHANNEL_WIDTH_160MHZ,
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 3
            else -> 0
        }
        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scan.wifiStandard
        } else {
            ScanResult.WIFI_STANDARD_LEGACY
        }
        val rates = when (standard) {
            ScanResult.WIFI_STANDARD_11N -> intArrayOf(72, 150, 0, 0)
            ScanResult.WIFI_STANDARD_11AC -> intArrayOf(86, 200, 433, 867)
            ScanResult.WIFI_STANDARD_11AX -> intArrayOf(143, 287, 600, 1_201)
            ScanResult.WIFI_STANDARD_11BE -> intArrayOf(180, 360, 720, 1_441)
            else -> intArrayOf(54, 54, 54, 54)
        }
        return rates[widthIndex]
    }

    companion object {
        private const val MAX_SCAN_AGE_MICROS = 120_000_000L
        private const val WEAK_SIGNAL_THRESHOLD_DBM = -72
        private const val SIGNAL_ADVANTAGE_DB = 8
        private const val MINIMUM_USABLE_TARGET_DBM = -80
        private const val RATE_ADVANTAGE_FACTOR = 1.25
        private const val SSID_24_GHZ = "FRITZ!Box 5590 WD 2,4"
        private const val SSID_5_GHZ = "FRITZ!Box 5590 WD"
    }
}
