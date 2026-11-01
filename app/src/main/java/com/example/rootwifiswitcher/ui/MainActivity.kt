package com.example.rootwifiswitcher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.rootwifiswitcher.R
import com.example.rootwifiswitcher.security.CredentialStore
import com.example.rootwifiswitcher.service.WifiMonitorService
import com.example.rootwifiswitcher.wifi.WifiNetworkSettings
import com.example.rootwifiswitcher.wifi.WifiNetworkSettingsStore
import java.security.GeneralSecurityException

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var savedStateView: TextView
    private lateinit var ssid24GhzInput: EditText
    private lateinit var ssid5GhzInput: EditText
    private lateinit var wifiPasswordInput: EditText
    private lateinit var hotspotSsidInput: EditText
    private lateinit var hotspotPasswordInput: EditText

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            statusView.text = intent.getStringExtra(WifiMonitorService.EXTRA_STATUS)
                ?: "Unbekannter Status"
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasRequiredPermissions()) {
                startMonitoring()
            } else {
                statusView.text =
                    "Standortberechtigung und ggf. Benachrichtigungsberechtigung werden benötigt."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.txtStatus)
        savedStateView = findViewById(R.id.txtSavedState)
        ssid24GhzInput = findViewById(R.id.editSsid24Ghz)
        ssid5GhzInput = findViewById(R.id.editSsid5Ghz)
        wifiPasswordInput = findViewById(R.id.editWifiPassword)
        hotspotSsidInput = findViewById(R.id.editHotspotSsid)
        hotspotPasswordInput = findViewById(R.id.editHotspotPassword)

        val networkSettings = WifiNetworkSettingsStore.load(this)
        ssid24GhzInput.setText(networkSettings.ssid24Ghz)
        ssid5GhzInput.setText(networkSettings.ssid5Ghz)
        refreshSavedState()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (hasRequiredPermissions()) {
                startMonitoring()
            } else {
                permissionLauncher.launch(requiredPermissions())
            }
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, WifiMonitorService::class.java))
            statusView.text = "Überwachung gestoppt."
        }

        val filter = IntentFilter(WifiMonitorService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private fun startMonitoring() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isLocationEnabled) {
            statusView.text = "Aktiviere die Standortdienste, damit Android WLAN-Scans erlaubt."
            return
        }

        val wifiPassword = wifiPasswordInput.text.toString()
        val ssid24Ghz = ssid24GhzInput.text.toString()
        val ssid5Ghz = ssid5GhzInput.text.toString()
        val hotspotSsid = hotspotSsidInput.text.toString().trim()
        val hotspotPassword = hotspotPasswordInput.text.toString()
        if (hotspotSsid.isEmpty() != hotspotPassword.isEmpty()) {
            statusView.text = "Für den Hotspot müssen SSID und Passwort gemeinsam angegeben werden."
            return
        }

        try {
            if (wifiPassword.isNotEmpty()) {
                CredentialStore.saveWifiPassword(this, wifiPassword)
            } else if (CredentialStore.loadWifiPassword(this) == null) {
                statusView.text = "Bitte gib das gemeinsame FRITZ!Box-WLAN-Passwort ein."
                return
            }

            if (hotspotSsid.isNotEmpty()) {
                CredentialStore.saveHotspotCredentials(this, hotspotSsid, hotspotPassword)
            }
            WifiNetworkSettingsStore.save(
                this,
                WifiNetworkSettings(ssid24Ghz, ssid5Ghz)
            )
        } catch (exception: GeneralSecurityException) {
            showCredentialError("Zugangsdaten konnten nicht sicher gespeichert werden.")
            return
        } catch (exception: IllegalArgumentException) {
            showCredentialError(exception.message ?: "Die Zugangsdaten sind ungültig.")
            return
        } catch (exception: IllegalStateException) {
            showCredentialError(exception.message ?: "Die Zugangsdaten konnten nicht gespeichert werden.")
            return
        }

        wifiPasswordInput.text.clear()
        hotspotPasswordInput.text.clear()
        refreshSavedState()
        statusView.text = "WLAN-Namen und Zugangsdaten wurden gespeichert."

        val serviceIntent = Intent(this, WifiMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        statusView.text = "Gespeichert. WLAN-Überwachung wird gestartet."
    }

    private fun refreshSavedState() {
        val wifiState = if (CredentialStore.hasWifiPassword(this)) {
            "FRITZ!Box-Passwort gespeichert"
        } else {
            "FRITZ!Box-Passwort fehlt"
        }
        val hotspotState = if (CredentialStore.hasHotspotCredentials(this)) {
            "Hotspot-Daten gespeichert"
        } else {
            "Hotspot-Daten fehlen"
        }
        savedStateView.text = "WLAN-Namen gespeichert · $wifiState · $hotspotState"
        wifiPasswordInput.hint = if (CredentialStore.hasWifiPassword(this)) {
            "WPA2-Passwort (gespeichert; leer lassen zum Beibehalten)"
        } else {
            "Gemeinsames WPA2-Passwort"
        }
        hotspotPasswordInput.hint = if (CredentialStore.hasHotspotCredentials(this)) {
            "Hotspot-Passwort (gespeichert; leer lassen zum Beibehalten)"
        } else {
            "Hotspot-WPA2-Passwort"
        }
        hotspotSsidInput.hint = if (CredentialStore.hasHotspotCredentials(this)) {
            "Hotspot-SSID (gespeichert; leer lassen zum Beibehalten)"
        } else {
            "Hotspot-SSID"
        }
    }

    private fun showCredentialError(message: String) {
        statusView.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
}
