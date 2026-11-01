package com.example.rootwifiswitcher.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class HotspotCredentials(val ssid: String, val password: String)

object CredentialStore {
    private const val PREFERENCES = "wifi_credentials"
    private const val WIFI_PASSWORD_KEY = "encrypted_password"
    private const val HOTSPOT_SSID_KEY = "encrypted_hotspot_ssid"
    private const val HOTSPOT_PASSWORD_KEY = "encrypted_hotspot_password"
    private const val KEY_ALIAS = "wifi_password_key"
    private const val IV_LENGTH = 12

    fun saveWifiPassword(context: Context, password: String) {
        val byteLength = password.toByteArray(StandardCharsets.UTF_8).size
        val isRawPsk = password.length == RAW_PSK_LENGTH && password.all {
            it in '0'..'9' || it.lowercaseChar() in 'a'..'f'
        }
        require(byteLength in WPA2_PASSWORD_MIN_BYTES..WPA2_PASSWORD_MAX_BYTES || isRawPsk) {
            "Das WPA2-Passwort muss 8 bis 63 Bytes oder ein 64-stelliger hexadezimaler Schlüssel sein."
        }
        saveEncrypted(context, WIFI_PASSWORD_KEY, password)
    }

    fun loadWifiPassword(context: Context): String? =
        loadEncrypted(context, WIFI_PASSWORD_KEY)

    fun hasWifiPassword(context: Context): Boolean =
        preferences(context).contains(WIFI_PASSWORD_KEY)

    fun saveHotspotCredentials(context: Context, ssid: String, password: String) {
        require(ssid.isNotBlank()) { "Die Hotspot-SSID darf nicht leer sein." }
        require(ssid.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_SSID_BYTES) {
            "Die Hotspot-SSID darf höchstens 32 Bytes lang und nicht leer sein."
        }
        require(password.toByteArray(StandardCharsets.UTF_8).size in WPA2_PASSWORD_MIN_BYTES..WPA2_PASSWORD_MAX_BYTES) {
            "Das Hotspot-WPA2-Passwort muss zwischen 8 und 63 Bytes lang sein."
        }
        saveEncrypted(context, HOTSPOT_SSID_KEY, ssid)
        saveEncrypted(context, HOTSPOT_PASSWORD_KEY, password)
    }

    fun loadHotspotCredentials(context: Context): HotspotCredentials? {
        val preferences = preferences(context)
        val hasSsid = preferences.contains(HOTSPOT_SSID_KEY)
        val hasPassword = preferences.contains(HOTSPOT_PASSWORD_KEY)
        if (!hasSsid && !hasPassword) {
            return null
        }
        require(hasSsid && hasPassword) {
            "Gespeicherte Hotspot-Zugangsdaten sind unvollständig."
        }
        val ssid = loadEncrypted(context, HOTSPOT_SSID_KEY)
            ?: error("Gespeicherte Hotspot-SSID fehlt.")
        val password = loadEncrypted(context, HOTSPOT_PASSWORD_KEY)
            ?: error("Gespeichertes Hotspot-Passwort fehlt.")
        require(ssid.isNotBlank()) { "Die gespeicherte Hotspot-SSID ist ungültig." }
        return HotspotCredentials(ssid, password)
    }

    fun hasHotspotCredentials(context: Context): Boolean {
        val preferences = preferences(context)
        return preferences.contains(HOTSPOT_SSID_KEY) &&
            preferences.contains(HOTSPOT_PASSWORD_KEY)
    }

    private fun saveEncrypted(context: Context, preferenceKey: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        val saved = preferences(context).edit().putString(preferenceKey, encoded).commit()
        check(saved) { "Die verschlüsselten Zugangsdaten konnten nicht gespeichert werden." }
    }

    private fun loadEncrypted(context: Context, preferenceKey: String): String? {
        val encoded = preferences(context).getString(preferenceKey, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "Gespeicherte Zugangsdaten sind ungültig." }

        val iv = payload.copyOfRange(0, IV_LENGTH)
        val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private const val WPA2_PASSWORD_MIN_BYTES = 8
    private const val WPA2_PASSWORD_MAX_BYTES = 63
    private const val RAW_PSK_LENGTH = 64
    private const val MAX_SSID_BYTES = 32
}
