package com.example.atomicclockapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.TimeUnit

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
 * One-time setup over USB: grant WRITE_SECURE_SETTINGS (see AndroidManifest.xml)
 * and pair the app once by pressing the button while "adb tcpip 5555" is active.
 */
object SystemClockSetter {

    private const val LEGACY_ADB_PORT = 5555
    private const val WIFI_DEBUG_SETTING = "adb_wifi_enabled"
    private const val PRIORITY_FLAG = "system_time time_detector_origin_priorities_override"

    /**
     * Sets the system clock so that [trueUnixMillis] was the time at the moment
     * the phone's uptime clock read [elapsedRealtimeMillis].
     * [phoneIsFast] says which way the clock is currently wrong.
     *
     * Blocking; call from a background thread. Throws with a readable message on failure.
     */
    fun setClock(
        context: Context,
        elapsedRealtimeMillis: Long,
        trueUnixMillis: Long,
        phoneIsFast: Boolean,
        onStatus: (String) -> Unit
    ) {
        val app = context.applicationContext
        val manager = Adb(app)
        var turnedOnWifiDebugging = false
        try {
            onStatus("Connecting to adb...")
            turnedOnWifiDebugging = connect(app, manager, onStatus)

            // Push the clock 3 s to the far side of the true time, then set the true time.
            val away = trueUnixMillis + if (phoneIsFast) -3000 else 3000
            val suggest = "cmd time_detector suggest_external_time --elapsed_realtime $elapsedRealtimeMillis --unix_epoch_time"
            val script = "device_config put $PRIORITY_FLAG external" +
                " && $suggest $away && $suggest $trueUnixMillis && echo CLOCK_SET_OK;" +
                // Always go back to internet time only, even if a step above failed.
                " device_config put $PRIORITY_FLAG network"

            onStatus("Setting clock...")
            val output = manager.shell(script)
            if (!output.contains("CLOCK_SET_OK")) {
                throw IllegalStateException("Phone refused the time: ${output.trim().take(200)}")
            }
        } finally {
            try { manager.close() } catch (_: Exception) {}
            if (turnedOnWifiDebugging) setWifiDebugging(app, false)
        }
    }

    /** Returns true if this call switched Wireless debugging on (so it can be switched off again). */
    private fun connect(context: Context, manager: Adb, onStatus: (String) -> Unit): Boolean {
        // 1. adb over TCP on port 5555 (on after "adb tcpip 5555" until the next reboot).
        //    The first connection shows the "Allow USB debugging?" prompt, which pairs this app.
        try {
            if (manager.connect("127.0.0.1", LEGACY_ADB_PORT)) return false
        } catch (_: Exception) {
        }

        // 2. Wireless debugging. Needs Wi-Fi; the app switches it on itself if allowed to.
        var turnedOn = false
        if (!isWifiDebuggingOn(context)) {
            if (!canWriteSecureSettings(context)) {
                throw IllegalStateException("Turn on Wireless debugging in Developer options, then try again")
            }
            onStatus("Turning on Wireless debugging...")
            setWifiDebugging(context, true)
            turnedOn = true
            Thread.sleep(1500)
            if (!isWifiDebuggingOn(context)) {
                throw IllegalStateException("Wireless debugging would not turn on (Wi-Fi connected and network allowed?)")
            }
        }
        try {
            if (manager.connectTls(context, 15_000)) return turnedOn
            throw IllegalStateException("Could not reach adb over Wireless debugging")
        } catch (e: Exception) {
            if (turnedOn) setWifiDebugging(context, false)
            throw IllegalStateException(
                e.message ?: "Could not connect to adb (${e.javaClass.simpleName}). Has the app been paired?", e
            )
        }
    }

    private fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun isWifiDebuggingOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, WIFI_DEBUG_SETTING, 0) == 1

    private fun setWifiDebugging(context: Context, on: Boolean) {
        try {
            Settings.Global.putInt(context.contentResolver, WIFI_DEBUG_SETTING, if (on) 1 else 0)
        } catch (_: SecurityException) {
        }
    }

    /** adb client with a key pair that is created once and kept in the app's private storage. */
    private class Adb(context: Context) : AbsAdbConnectionManager() {
        private val privateKey: PrivateKey
        private val certificate: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(60, TimeUnit.SECONDS)
            val keyFile = File(context.filesDir, "adb_key.pk8")
            val certFile = File(context.filesDir, "adb_key.der")
            if (keyFile.exists() && certFile.exists()) {
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                certificate = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                }
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val name = X500Name("CN=AtomicClockApp")
                val now = Date()
                val tenYears = Date(now.time + 10L * 365 * 24 * 3600 * 1000)
                val algorithm = "SHA512withRSA"
                val info = X509CertInfo().apply {
                    set("version", CertificateVersion(2))
                    set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                    set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
                    set("subject", CertificateSubjectName(name))
                    set("key", CertificateX509Key(pair.public))
                    set("validity", CertificateValidity(now, tenYears))
                    set("issuer", CertificateIssuerName(name))
                }
                val cert = X509CertImpl(info)
                cert.sign(pair.private, algorithm)
                privateKey = pair.private
                certificate = cert
                keyFile.writeBytes(pair.private.encoded)
                certFile.writeBytes(cert.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = privateKey
        override fun getCertificate(): Certificate = certificate
        override fun getDeviceName(): String = "AtomicClockApp"

        /** Runs a shell command as adb and returns everything it printed. */
        fun shell(command: String): String {
            val out = StringBuilder()
            openStream("shell:$command").use { stream ->
                val input = stream.openInputStream()
                val buffer = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.append(String(buffer, 0, n))
                    }
                } catch (_: java.io.IOException) {
                    // The stream closes when the command finishes.
                }
            }
            return out.toString()
        }
    }
}
