package com.example.rootwifiswitcher.wifi

import android.content.Context
import java.nio.charset.StandardCharsets

data class WifiNetworkSettings(
    val ssid24Ghz: String,
    val ssid5Ghz: String
)

object WifiNetworkSettingsStore {
    private const val PREFERENCES = "wifi_network_settings"
    private const val SSID_24_GHZ_KEY = "ssid_24_ghz"
    private const val SSID_5_GHZ_KEY = "ssid_5_ghz"
    private const val MAX_SSID_BYTES = 32

    fun load(context: Context): WifiNetworkSettings {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return WifiNetworkSettings(
            ssid24Ghz = preferences.getString(SSID_24_GHZ_KEY, DEFAULT_SSID_24_GHZ)
                ?: DEFAULT_SSID_24_GHZ,
            ssid5Ghz = preferences.getString(SSID_5_GHZ_KEY, DEFAULT_SSID_5_GHZ)
                ?: DEFAULT_SSID_5_GHZ
        )
    }

    fun save(context: Context, settings: WifiNetworkSettings) {
        requireValidSsid(settings.ssid24Ghz)
        requireValidSsid(settings.ssid5Ghz)
        require(settings.ssid24Ghz != settings.ssid5Ghz) {
            "Die beiden WLAN-Namen müssen unterschiedlich sein."
        }

        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(SSID_24_GHZ_KEY, settings.ssid24Ghz)
            .putString(SSID_5_GHZ_KEY, settings.ssid5Ghz)
            .commit()
        check(saved) { "Die WLAN-Namen konnten nicht gespeichert werden." }
    }

    private fun requireValidSsid(ssid: String) {
        require(ssid.isNotBlank()) { "Der WLAN-Name darf nicht leer sein." }
        require(ssid.toByteArray(StandardCharsets.UTF_8).size <= MAX_SSID_BYTES) {
            "Ein WLAN-Name darf höchstens 32 Bytes lang sein."
        }
    }

    private const val DEFAULT_SSID_24_GHZ = "FRITZ!Box 5590 WD 2,4"
    private const val DEFAULT_SSID_5_GHZ = "FRITZ!Box 5590 WD"
}
