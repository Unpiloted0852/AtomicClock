package com.example.atomicclockapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import io.github.muntashirakon.adb.AdbPairingRequiredException

/**
 * Sets the phone's system clock to a given true time.
 *
 * A normal app is not allowed to do this, so the app connects to the phone's own
 * adb daemon (the same service a USB cable talks to) and runs the time commands
 * with adb's rights.
 *
 * Android only moves its clock when a suggested time is 2 s or more away from
 * the current one. To get past that, the clock is first pushed 3 s away from the
 * true time and then set to the true time, so it is wrong by 3 s for a fraction
 * of a second.
 *
 * Needs the one-time setup in [PairingSetup] (or, from a computer, the steps in the README).
 */
object SystemClockSetter {

    /** The one-time setup has not been done (or was undone); show [PairingSetup]. */
    class NotSetUpException : Exception("One-time setup needed")

    private const val LEGACY_ADB_PORT = 5555
    private const val WIFI_DEBUG_SETTING = "adb_wifi_enabled"
    private const val PRIORITY_FLAG = "system_time time_detector_origin_priorities_override"

    /** True once the app has been paired and allowed to switch Wireless debugging on. */
    fun isSetUp(context: Context) = canWriteSecureSettings(context)

    /**
     * Sets the system clock so that [trueUnixMillis] was the time at the moment
     * the phone's uptime clock read [elapsedRealtimeMillis].
     * [phoneIsFast] says which way the clock is currently wrong.
     *
     * Blocking; call from a background thread. Throws [NotSetUpException] if the
     * one-time setup is needed, otherwise throws with a readable message on failure.
     */
    fun setClock(
        context: Context,
        elapsedRealtimeMillis: Long,
        trueUnixMillis: Long,
        phoneIsFast: Boolean,
        onStatus: (String) -> Unit
    ) {
        val app = context.applicationContext
        val adb = AdbClient(app)
        var turnedOnWifiDebugging = false
        try {
            onStatus("Connecting to adb...")
            turnedOnWifiDebugging = connect(app, adb, onStatus)

            // Push the clock 3 s to the far side of the true time, then set the true time.
            val away = trueUnixMillis + if (phoneIsFast) -3000 else 3000
            val suggest = "cmd time_detector suggest_external_time --elapsed_realtime $elapsedRealtimeMillis --unix_epoch_time"
            val script = "device_config put $PRIORITY_FLAG external" +
                " && $suggest $away && $suggest $trueUnixMillis && echo CLOCK_SET_OK;" +
                // Always go back to internet time only, even if a step above failed.
                " device_config put $PRIORITY_FLAG network"

            onStatus("Setting clock...")
            val output = adb.shell(script)
            if (!output.contains("CLOCK_SET_OK")) {
                throw IllegalStateException("Phone refused the time: ${output.trim().take(200)}")
            }
        } finally {
            adb.closeQuietly()
            if (turnedOnWifiDebugging) setWifiDebugging(app, false)
        }
    }

    /** Returns true if this call switched Wireless debugging on (so it can be switched off again). */
    private fun connect(context: Context, adb: AdbClient, onStatus: (String) -> Unit): Boolean {
        // 1. adb over TCP on port 5555 (on after "adb tcpip 5555" until the next reboot).
        try {
            if (adb.connect("127.0.0.1", LEGACY_ADB_PORT)) return false
        } catch (_: Exception) {
        }

        // 2. Wireless debugging. Needs Wi-Fi; the app switches it on itself once set up.
        if (!canWriteSecureSettings(context)) throw NotSetUpException()
        var turnedOn = false
        if (!isWifiDebuggingOn(context)) {
            onStatus("Turning on Wireless debugging...")
            setWifiDebugging(context, true)
            turnedOn = true
            Thread.sleep(1500)
            if (!isWifiDebuggingOn(context)) {
                throw IllegalStateException("Wireless debugging would not turn on. Is Wi-Fi connected?")
            }
        }
        try {
            onStatus("Finding Wireless debugging...")
            if (AdbPorts.findConnectPort(context, adb, onStatus)) return turnedOn
            throw IllegalStateException("Couldn't find Wireless debugging. Is Wi-Fi connected?")
        } catch (e: AdbPairingRequiredException) {
            if (turnedOn) setWifiDebugging(context, false)
            throw NotSetUpException()
        } catch (e: Exception) {
            if (turnedOn) setWifiDebugging(context, false)
            throw IllegalStateException(e.message ?: "Could not connect to adb (${e.javaClass.simpleName})", e)
        }
    }

    private fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun isWifiDebuggingOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, WIFI_DEBUG_SETTING, 0) == 1

    internal fun setWifiDebugging(context: Context, on: Boolean) {
        try {
            Settings.Global.putInt(context.contentResolver, WIFI_DEBUG_SETTING, if (on) 1 else 0)
        } catch (_: SecurityException) {
        }
    }
}
