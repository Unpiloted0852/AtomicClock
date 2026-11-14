package com.example.atomicclockapp

import android.content.Context
import android.os.Build
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
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.IOException
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
 * Client for the phone's own adb daemon. Its key pair is created once and kept in the
 * app's private storage, so a pairing survives app restarts (not an uninstall).
 */
class AdbClient(context: Context) : AbsAdbConnectionManager() {
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
            } catch (_: IOException) {
                // The stream closes when the command finishes.
            }
        }
        return out.toString()
    }

    /** Closes the connection, ignoring the library's harmless "key could not be destroyed" error. */
    fun closeQuietly() {
        try { close() } catch (_: Exception) {}
    }
}
