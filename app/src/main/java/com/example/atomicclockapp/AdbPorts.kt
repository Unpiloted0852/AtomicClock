package com.example.atomicclockapp

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the ports Wireless debugging is listening on. Android picks them at random
 * each time it is switched on and announces them on the local network (mDNS).
 * Those announcements can be slow or never arrive (e.g. with a VPN running), so
 * [findConnectPort] falls back to checking the phone's own ports directly.
 */
object AdbPorts {

    /** Waits up to [timeoutMs] for this phone's own mDNS announcement of [serviceType]. */
    fun viaMdns(context: Context, serviceType: String, timeoutMs: Long): Pair<InetAddress, Int>? {
        var found: Pair<InetAddress, Int>? = null
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(context, serviceType) { host, port ->
            if (host != null && port > 0 && found == null) {
                found = host to port
                latch.countDown()
            }
        }
        try {
            mdns.start()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            try { mdns.stop() } catch (_: Exception) {}
        }
        return found
    }

    /**
     * Connects [adb] to Wireless debugging: by its mDNS announcement if that arrives
     * quickly, otherwise by trying each open port on this phone in Android's random-port
     * range. Returns false if neither finds it.
     */
    fun findConnectPort(context: Context, adb: AdbClient, onStatus: (String) -> Unit): Boolean {
        viaMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 4_000)?.let { (host, port) ->
            try {
                if (adb.connect(host.hostAddress, port)) return true
            } catch (_: Exception) {
            }
        }
        onStatus("Searching for Wireless debugging...")
        for (port in openLocalPorts()) {
            // A fresh client per try: a failed attempt can leave the old one unusable.
            val candidate = AdbClient(context).apply { setTimeout(4, TimeUnit.SECONDS) }
            val ok = try {
                candidate.connect("127.0.0.1", port)
            } catch (_: Exception) {
                false
            } finally {
                candidate.closeQuietly()
            }
            if (ok) {
                try {
                    if (adb.connect("127.0.0.1", port)) return true
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    /** Ports accepting connections on 127.0.0.1 within the system's random-port range. */
    fun openLocalPorts(): List<Int> {
        val range = try {
            File("/proc/sys/net/ipv4/ip_local_port_range").readText().trim().split(Regex("\\s+"))
                .let { it[0].toInt()..it[1].toInt() }
        } catch (_: Exception) {
            32768..60999
        }
        val open = ConcurrentLinkedQueue<Int>()
        val pool = Executors.newFixedThreadPool(64)
        for (port in range) {
            pool.execute {
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                    open.add(port)
                } catch (_: Exception) {
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return open.sorted()
    }
}
