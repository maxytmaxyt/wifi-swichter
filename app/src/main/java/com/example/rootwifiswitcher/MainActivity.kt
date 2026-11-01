package com.example.rootwifiswitcher
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.DataOutputStream

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val btnSwitch = findViewById<Button>(R.id.btnSwitch)
        val txtStatus = findViewById<TextView>(R.id.txtStatus)
        btnSwitch.setOnClickListener {
            val targetBSSID = "00:11:22:33:44:55" // Hier deine Repeater MAC eintragen
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val networkId = wifiManager.connectionInfo.networkId
            if (networkId != -1) {
                txtStatus.text = "Sende Root-Befehl..."
                if (forceBssidWithRoot(networkId, targetBSSID)) {
                    Toast.makeText(this, "Erfolgreich geschubst!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Root-Fehler!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun forceBssidWithRoot(netId: Int, bssid: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("cmd wlan connect-to-network  bssid \n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) { false }
    }
}
