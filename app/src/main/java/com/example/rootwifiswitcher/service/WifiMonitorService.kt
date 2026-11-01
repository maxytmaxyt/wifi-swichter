package com.example.rootwifiswitcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.rootwifiswitcher.security.CredentialStore
import com.example.rootwifiswitcher.security.HotspotCredentials
import com.example.rootwifiswitcher.wifi.HotspotState
import com.example.rootwifiswitcher.wifi.RootWifiController
import com.example.rootwifiswitcher.wifi.WifiNetworkMonitor
import java.security.GeneralSecurityException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WifiMonitorService : Service() {
    private val monitorExecutor = Executors.newSingleThreadScheduledExecutor()
    private val rootExecutor = Executors.newSingleThreadExecutor()
    private val rootWifiController = RootWifiController()
    private lateinit var wifiManager: WifiManager
    private lateinit var networkMonitor: WifiNetworkMonitor
    private var wifiPassword: String? = null
    private var hotspotCredentials: HotspotCredentials? = null
    private var lastSwitchAttempt = 0L
    private var monitoringStarted = false

    @Volatile
    private var switchInProgress = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            monitorExecutor.execute { evaluateNetworks() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        networkMonitor = WifiNetworkMonitor(wifiManager)

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(scanReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification("Überwachung wird gestartet.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            wifiPassword = CredentialStore.loadWifiPassword(this)
        } catch (exception: GeneralSecurityException) {
            return stopAfterStartupError(startId, "FRITZ!Box-Passwort kann nicht entschlüsselt werden.")
        } catch (exception: IllegalArgumentException) {
            return stopAfterStartupError(startId, "Gespeicherte Zugangsdaten sind ungültig.")
        }
        if (wifiPassword.isNullOrEmpty()) {
            return stopAfterStartupError(startId, "Bitte zuerst das FRITZ!Box-Passwort eingeben.")
        }

        hotspotCredentials = try {
            CredentialStore.loadHotspotCredentials(this)
        } catch (exception: GeneralSecurityException) {
            null
        } catch (exception: IllegalArgumentException) {
            null
        }

        if (!monitoringStarted) {
            monitoringStarted = true
            monitorExecutor.scheduleWithFixedDelay(
                { requestScan() },
                0,
                SCAN_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
        updateStatus("Überwachung aktiv. Suche nach den beiden FRITZ!Box-Netzen.")
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(scanReceiver)
        monitorExecutor.shutdownNow()
        rootExecutor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopAfterStartupError(startId: Int, message: String): Int {
        updateStatus(message)
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun requestScan() {
        try {
            if (!wifiManager.isWifiEnabled) {
                updateStatus("WLAN ist ausgeschaltet.")
                return
            }
            if (!networkMonitor.requestScan()) {
                evaluateNetworks()
            }
        } catch (exception: SecurityException) {
            updateStatus("Standortberechtigung fehlt oder wurde entzogen.")
            stopSelf()
        }
    }

    private fun evaluateNetworks() {
        try {
            val evaluation = networkMonitor.evaluate()
            updateStatus(evaluation.status)
            val targetSsid = evaluation.targetSsid ?: return
            if (!evaluation.shouldSwitch || switchInProgress) {
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastSwitchAttempt < SWITCH_COOLDOWN_MILLIS) {
                updateStatus("Wechselpause aktiv; Verbindung bleibt bestehen.")
                return
            }

            val password = wifiPassword ?: return
            lastSwitchAttempt = now
            switchInProgress = true
            rootExecutor.execute { switchNetwork(targetSsid, password) }
        } catch (exception: SecurityException) {
            updateStatus("WLAN-Scan nicht erlaubt. Standortberechtigung prüfen.")
            stopSelf()
        } catch (exception: IllegalStateException) {
            updateStatus("WLAN-Status konnte nicht gelesen werden: ${exception.message}")
        }
    }

    private fun switchNetwork(targetSsid: String, password: String) {
        try {
            val hotspotWasEnabled = when (rootWifiController.readHotspotState()) {
                HotspotState.ENABLED -> true
                HotspotState.DISABLED -> false
                HotspotState.UNKNOWN -> {
                    updateStatus(
                        "Hotspot-Status nicht lesbar; WLAN-Wechsel ausgesetzt, damit ein " +
                            "aktiver Hotspot nicht verloren geht."
                    )
                    return
                }
            }
            val savedHotspotCredentials = hotspotCredentials
            if (hotspotWasEnabled && savedHotspotCredentials == null) {
                updateStatus(
                    "Hotspot ist aktiv. Hinterlege zuerst seine SSID und sein Passwort, " +
                        "damit er nach dem WLAN-Wechsel wiederhergestellt werden kann."
                )
                return
            }

            val result = rootWifiController.connectToNetwork(targetSsid, password)
            if (!result.succeeded) {
                updateStatus(result.message)
                return
            }
            updateStatus("Wechsel zu \"$targetSsid\" wurde angefordert.")

            if (hotspotWasEnabled && savedHotspotCredentials != null) {
                restoreHotspotAfterSwitch(targetSsid, savedHotspotCredentials)
            }
        } catch (exception: SecurityException) {
            updateStatus("WLAN-Berechtigung während des Wechsels entzogen.")
        } catch (exception: IllegalStateException) {
            updateStatus("WLAN-Wechsel fehlgeschlagen: ${exception.message}")
        } finally {
            switchInProgress = false
        }
    }

    private fun restoreHotspotAfterSwitch(
        targetSsid: String,
        credentials: HotspotCredentials
    ) {
        waitForNetworkSwitch(targetSsid)
        when (rootWifiController.readHotspotState()) {
            HotspotState.ENABLED -> updateStatus("Der Hotspot ist weiterhin eingeschaltet.")
            HotspotState.UNKNOWN -> updateStatus(
                "WLAN-Wechsel angefordert, aber der Hotspot-Status ist nicht lesbar."
            )
            HotspotState.DISABLED -> {
                val result = rootWifiController.startHotspot(credentials)
                if (result.succeeded) {
                    updateStatus(
                        "Der Hotspot-Neustart wurde angefordert: \"${credentials.ssid}\"."
                    )
                    monitorExecutor.schedule(
                        { verifyHotspotRestart() },
                        HOTSPOT_VERIFY_DELAY_SECONDS,
                        TimeUnit.SECONDS
                    )
                } else {
                    updateStatus(result.message)
                }
            }
        }
    }

    private fun waitForNetworkSwitch(targetSsid: String) {
        val deadline = SystemClock.elapsedRealtime() + NETWORK_SWITCH_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (wifiManager.connectionInfo.ssid.removeSurrounding("\"") == targetSsid) {
                return
            }
            try {
                Thread.sleep(NETWORK_SWITCH_POLL_MILLIS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        updateStatus("WLAN-Wechsel zu \"$targetSsid\" konnte nicht bestätigt werden.")
    }

    private fun verifyHotspotRestart() {
        rootExecutor.execute {
            val status = when (rootWifiController.readHotspotState()) {
                HotspotState.ENABLED -> "Der Hotspot ist wieder eingeschaltet."
                HotspotState.DISABLED -> "Der Hotspot konnte nicht wieder eingeschaltet werden."
                HotspotState.UNKNOWN -> "Hotspot-Neustart angefordert; Status nicht überprüfbar."
            }
            updateStatus(status)
        }
    }

    private fun updateStatus(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS, message)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WLAN-Überwachung",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Root WiFi Switcher")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_STATUS = "com.example.rootwifiswitcher.WIFI_STATUS"
        const val EXTRA_STATUS = "status"

        private const val CHANNEL_ID = "wifi_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val SCAN_INTERVAL_SECONDS = 30L
        private const val SWITCH_COOLDOWN_MILLIS = 60_000L
        private const val NETWORK_SWITCH_TIMEOUT_MILLIS = 30_000L
        private const val NETWORK_SWITCH_POLL_MILLIS = 1_000L
        private const val HOTSPOT_VERIFY_DELAY_SECONDS = 5L
    }
}
