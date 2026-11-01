package com.example.rootwifiswitcher.wifi

import com.example.rootwifiswitcher.security.HotspotCredentials
import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class HotspotState {
    ENABLED,
    DISABLED,
    UNKNOWN
}

internal data class RootOperationResult(
    val succeeded: Boolean,
    val message: String
)

internal class RootWifiController {
    fun readHotspotState(): HotspotState {
        val result = executeShell(
            "dumpsys wifi | grep -iE " +
                "'Dump of SoftApManager|Dump of ClientModeManager|current StateMachine mode:'; " +
                "echo $DUMP_SENTINEL"
        )
        if (!result.succeeded) {
            return HotspotState.UNKNOWN
        }

        val dump = result.output.substringBefore(DUMP_SENTINEL)
        val managerIndex = dump.indexOf(SOFT_AP_DUMP_MARKER, ignoreCase = true)
        if (managerIndex < 0) {
            return if (dump.contains(CLIENT_MODE_DUMP_MARKER, ignoreCase = true)) {
                HotspotState.DISABLED
            } else {
                HotspotState.UNKNOWN
            }
        }

        val managerDump = dump.substring(managerIndex)
        val state = STATE_PATTERN.find(managerDump)?.groupValues?.getOrNull(1)
        return when (state) {
            "StartedState" -> HotspotState.ENABLED
            "IdleState" -> HotspotState.DISABLED
            else -> HotspotState.UNKNOWN
        }
    }

    fun connectToNetwork(ssid: String, password: String): RootOperationResult =
        runOperation(
            "cmd wifi connect-network ${shellQuote(ssid)} wpa2 ${shellQuote(password)}",
            "WLAN-Wechsel"
        )

    fun startHotspot(credentials: HotspotCredentials): RootOperationResult =
        runOperation(
            "cmd wifi start-softap ${shellQuote(credentials.ssid)} " +
                "wpa2 ${shellQuote(credentials.password)}",
            "Hotspot-Neustart"
        )

    private fun runOperation(command: String, operation: String): RootOperationResult {
        val result = executeShell(command)
        if (result.succeeded) {
            return RootOperationResult(true, "$operation wurde vom System angenommen.")
        }
        val reason = if (result.timedOut) {
            "Zeitlimit überschritten"
        } else if (result.exitCode >= 0) {
            "Exit ${result.exitCode}"
        } else {
            "Root-Shell konnte nicht ausgeführt werden"
        }
        return RootOperationResult(false, "$operation fehlgeschlagen ($reason).")
    }

    private fun executeShell(command: String): ShellResult {
        var process: Process? = null
        try {
            process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.write("$command\n")
                writer.write("exit\n")
            }
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ShellResult(false, -1, "", timedOut = true)
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.exitValue()
            return ShellResult(exitCode == 0, exitCode, output)
        } catch (exception: IOException) {
            return ShellResult(false, -1, "")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            return ShellResult(false, -1, "")
        } finally {
            process?.destroy()
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private data class ShellResult(
        val succeeded: Boolean,
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false
    )

    companion object {
        private const val COMMAND_TIMEOUT_SECONDS = 30L
        private const val DUMP_SENTINEL = "WIFI_SWITCHER_DUMP_END"
        private const val SOFT_AP_DUMP_MARKER = "--Dump of SoftApManager--"
        private const val CLIENT_MODE_DUMP_MARKER = "--Dump of ClientModeManager--"
        private val STATE_PATTERN =
            Regex("current StateMachine mode:\\s*(\\w+)", RegexOption.IGNORE_CASE)
    }
}
