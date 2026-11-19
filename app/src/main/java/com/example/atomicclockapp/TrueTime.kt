package com.example.atomicclockapp

import android.os.SystemClock
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress

/** One measurement of true time against an NTP server. */
object TrueTime {
    const val SERVER = "time.google.com"

    /**
     * [offsetMs]: true time minus the phone's clock (negative = phone is fast).
     * [delayMs]: network round trip. [elapsedRealtime] and [trueUnixMillis] are the same
     * instant on the phone's uptime clock and in true time, for setting the clock.
     */
    class Reading(val offsetMs: Long, val delayMs: Long, val elapsedRealtime: Long, val trueUnixMillis: Long)

    /** Blocking; call off the main thread. Throws if the server doesn't answer. */
    fun measure(): Reading {
        val client = NTPUDPClient()
        client.defaultTimeout = 5000
        try {
            client.open()
            val info = client.getTime(InetAddress.getByName(SERVER))
            info.computeDetails()
            val offset = info.offset ?: throw IllegalStateException("No answer from $SERVER")
            val elapsed = SystemClock.elapsedRealtime()
            val trueTime = System.currentTimeMillis() + offset
            return Reading(offset, info.delay ?: 0, elapsed, trueTime)
        } finally {
            client.close()
        }
    }
}
