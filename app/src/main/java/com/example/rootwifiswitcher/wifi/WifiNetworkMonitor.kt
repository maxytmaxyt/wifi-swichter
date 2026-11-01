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

internal class WifiNetworkMonitor(
    private val wifiManager: WifiManager,
    private val networkSettings: WifiNetworkSettings
) {
    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = wifiManager.startScan()

    fun evaluate(): NetworkEvaluation {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        // ScanResult.timestamp is microseconds since last boot; multiply by 1_000 to convert to
        // nanoseconds before comparing with elapsedRealtimeNanos().
        val scans = wifiManager.scanResults
            .filter { nowNanos - it.timestamp * 1_000L <= MAX_SCAN_AGE_NANOS }

        @Suppress("DEPRECATION")
        val current = wifiManager.connectionInfo
        val currentSsid = current.ssid.removeSurrounding("\"")
        val targetSsid = when (currentSsid) {
            networkSettings.ssid24Ghz -> networkSettings.ssid5Ghz
            networkSettings.ssid5Ghz -> networkSettings.ssid24Ghz
            else -> {
                return NetworkEvaluation(
                    "Aktuelles WLAN „$currentSsid" stimmt mit keinem der eingestellten " +
                        "WLAN-Namen überein. Prüfe die SSID-Einstellungen und Standortfreigabe."
                )
            }
        }
        val target = scans
            .filter { normalizeSsid(it.SSID) == targetSsid }
            .maxByOrNull { it.level }
            ?: return NetworkEvaluation(
                missingTargetStatus(targetSsid, scans.map { normalizeSsid(it.SSID) })
            )

        val currentRssi = current.rssi
        @Suppress("DEPRECATION")
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

    /** Returns the SSID of the currently connected network, or null if not connected. */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? =
        wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")

    private fun normalizeSsid(ssid: String): String = ssid.removeSurrounding("\"")

    private fun missingTargetStatus(targetSsid: String, visibleSsids: List<String>): String {
        val visibleNetworks = visibleSsids
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_VISIBLE_NETWORKS)
        val visibleSummary = when {
            visibleNetworks.isNotEmpty() -> " Sichtbare WLAN-Namen: ${visibleNetworks.joinToString()}."
            visibleSsids.isNotEmpty() -> " Es wurden nur WLANs mit verborgener SSID gefunden."
            else -> " Es liegen keine aktuellen Scanergebnisse vor."
        }
        return "Zielnetz \"$targetSsid\" ist in den aktuellen Scanergebnissen nicht enthalten." +
            visibleSummary
    }

    private fun estimateSingleStreamRate(scan: ScanResult): Int {
        val widthIndex = when (scan.channelWidth) {
            ScanResult.CHANNEL_WIDTH_40MHZ -> 1
            ScanResult.CHANNEL_WIDTH_80MHZ -> 2
            ScanResult.CHANNEL_WIDTH_160MHZ,
            @Suppress("DEPRECATION")
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
        // 120 seconds expressed in nanoseconds for comparison with elapsedRealtimeNanos()
        // (ScanResult.timestamp is in microseconds and is converted before the comparison)
        private const val MAX_SCAN_AGE_NANOS = 120_000_000_000L
        private const val WEAK_SIGNAL_THRESHOLD_DBM = -72
        private const val SIGNAL_ADVANTAGE_DB = 8
        private const val MINIMUM_USABLE_TARGET_DBM = -80
        private const val RATE_ADVANTAGE_FACTOR = 1.25
        private const val MAX_VISIBLE_NETWORKS = 6
    }
}
