package com.example.atomicclockapp

import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ntp.NTPUDPClient
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var txtHours: TextView
    private lateinit var txtColon1: TextView
    private lateinit var txtMinutes: TextView
    private lateinit var txtColon2: TextView
    private lateinit var txtSeconds: TextView
    private lateinit var txtMillis: TextView

    private lateinit var txtNetworkStats: TextView
    private lateinit var txtOffset: TextView
    private lateinit var btnSync: Button
    private lateinit var btnFixTime: Button
    private lateinit var btnSetClock: Button
    private lateinit var txtUpdate: TextView

    private val updater by lazy { AppUpdater(this) }
    private lateinit var btnSound: Button

    // Configuration
    private val currentServer = "time.google.com"

    // Time Variables
    private var timeOffset: Long = 0
    private var roundTripDelay: Long = 0
    private var isSynced = false

    // Sound Variables
    private var isSoundOn = false
    private var lastPlayedSecond: Long = 0
    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    // Formatter
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (isSynced) {
                // Calculate Real Time
                val currentAtomicTime = System.currentTimeMillis() + timeOffset

                // Format the full string: "12:00:00.123"
                val fullTimeString = timeFormat.format(Date(currentAtomicTime))

                // SPLIT the string rigidly
                // "12:00:00.123" -> Indices: 01 2 34 5 67 8...
                val hours = fullTimeString.substring(0, 2)
                val minutes = fullTimeString.substring(3, 5)
                val seconds = fullTimeString.substring(6, 8)
                val millis = fullTimeString.substring(8) // Includes the dot

                // Update separate boxes
                txtHours.text = hours
                txtMinutes.text = minutes
                txtSeconds.text = seconds
                txtMillis.text = millis

                // Sound Logic
                val currentSecond = currentAtomicTime / 1000
                if (isSoundOn && currentSecond > lastPlayedSecond) {
                    if (currentSecond % 60 == 0L) {
                        toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
                    } else {
                        toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 50)
                    }
                    lastPlayedSecond = currentSecond
                }
            }
            handler.postDelayed(this, 10)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Connect variables
        txtHours = findViewById(R.id.txtHours)
        txtColon1 = findViewById(R.id.txtColon1)
        txtMinutes = findViewById(R.id.txtMinutes)
        txtColon2 = findViewById(R.id.txtColon2)
        txtSeconds = findViewById(R.id.txtSeconds)
        txtMillis = findViewById(R.id.txtMillis)

        txtNetworkStats = findViewById(R.id.txtNetworkStats)
        txtOffset = findViewById(R.id.txtOffset)
        btnSync = findViewById(R.id.btnSync)
        btnFixTime = findViewById(R.id.btnFixTime)
        btnSetClock = findViewById(R.id.btnSetClock)
        txtUpdate = findViewById(R.id.txtUpdate)
        btnSound = findViewById(R.id.btnSound)

        // Lock text size immediately
        calculatePerfectTextSize()

        // Start ticker
        handler.post(clockRunnable)

        // Buttons
        btnSync.setOnClickListener { fetchAtomicTime() }
        btnSetClock.setOnClickListener { setSystemClock() }

        btnFixTime.setOnClickListener {
            startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
        }

        btnSound.setOnClickListener {
            isSoundOn = !isSoundOn
            if (isSoundOn) {
                btnSound.text = "🔊  Audio Tick: ON"
                btnSound.setTextColor(resources.getColor(android.R.color.white, null))
                btnSound.background.setTint(0xFF222222.toInt())
                lastPlayedSecond = (System.currentTimeMillis() + timeOffset) / 1000
            } else {
                btnSound.text = "🔇  Audio Tick: OFF"
                btnSound.setTextColor(resources.getColor(android.R.color.darker_gray, null))
                btnSound.background.setTint(0xFF1A1A1A.toInt())
            }
        }

        fetchAtomicTime()
        checkForUpdate()
    }

    /** Once per launch: if GitHub has a newer release, offer it as a tappable pill at the top. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = updater.checkForUpdate() ?: return@launch
            val offer = "Update available: v${release.versionName}. Tap to install."
            var busy = false
            txtUpdate.text = offer
            txtUpdate.visibility = View.VISIBLE
            txtUpdate.setOnClickListener {
                if (busy) return@setOnClickListener
                busy = true
                lifecycleScope.launch {
                    val error = updater.downloadAndInstall(release) { pct ->
                        txtUpdate.text = if (pct < 100) "Downloading update… $pct%" else "Installing update…"
                    }
                    // Only reached if the update did not replace the running app.
                    busy = false
                    txtUpdate.text = offer
                    if (error != null && error != "cancelled") {
                        Toast.makeText(this@MainActivity, "Update failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun calculatePerfectTextSize() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()

        // We calculate size so it fills the screen width based on our Grid
        // A "safe" divisor for this 6-element grid layout
        val mainTextSize = screenWidth / 7.5f
        val millisTextSize = mainTextSize / 2.0f

        // Apply size to the grid elements
        txtHours.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainTextSize)
        txtColon1.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainTextSize)
        txtMinutes.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainTextSize)
        txtColon2.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainTextSize)
        txtSeconds.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainTextSize)

        // Millis is smaller
        txtMillis.setTextSize(TypedValue.COMPLEX_UNIT_PX, millisTextSize)
    }

    private fun fetchAtomicTime() {
        txtNetworkStats.text = "Ping: $currentServer..."
        txtNetworkStats.alpha = 1.0f

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = NTPUDPClient()
                client.defaultTimeout = 5000
                client.open()

                val hostAddr = InetAddress.getByName(currentServer)
                val info = client.getTime(hostAddr)
                client.close()

                info.computeDetails()
                val calculatedOffset = info.offset
                val delay = info.delay

                withContext(Dispatchers.Main) {
                    timeOffset = calculatedOffset ?: 0
                    roundTripDelay = delay ?: 0
                    isSynced = true
                    updateInfoDisplay()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    txtNetworkStats.text = "Connection Error"
                }
                e.printStackTrace()
            }
        }
    }

    /**
     * Measures the true time, sets the phone's system clock to it, then
     * measures again so the offset display shows the result.
     */
    private fun setSystemClock() {
        btnSetClock.isEnabled = false
        txtNetworkStats.alpha = 1.0f
        txtNetworkStats.text = "Measuring true time..."

        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                val client = NTPUDPClient()
                client.defaultTimeout = 5000
                client.open()
                val info = client.getTime(InetAddress.getByName(currentServer))
                client.close()
                info.computeDetails()
                val offset = info.offset ?: throw IllegalStateException("No answer from $currentServer")

                // True time, paired with the phone's uptime clock at the same instant,
                // so the time it takes to apply it does not matter.
                val elapsed = SystemClock.elapsedRealtime()
                val trueTime = System.currentTimeMillis() + offset

                SystemClockSetter.setClock(this@MainActivity, elapsed, trueTime, phoneIsFast = offset < 0) { status ->
                    runOnUiThread { txtNetworkStats.text = status }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                e.message ?: e.javaClass.simpleName
            }

            withContext(Dispatchers.Main) {
                btnSetClock.isEnabled = true
                if (result == null) {
                    fetchAtomicTime()
                } else {
                    txtNetworkStats.text = "Clock not set: $result"
                }
            }
        }
    }

    private fun updateInfoDisplay() {
        txtNetworkStats.alpha = 0.8f
        val margin = roundTripDelay / 2

        txtNetworkStats.text = "Server: $currentServer   |   Margin: ±${margin}ms"

        if (timeOffset == 0L) {
            txtOffset.text = "PERFECT SYNC"
            txtOffset.setTextColor(0xFF00E676.toInt())
        } else {
            val offsetSeconds = abs(timeOffset) / 1000.0
            val formattedSeconds = String.format("%.3f", offsetSeconds)

            val symbol = if (timeOffset > 0) "+" else "-"
            txtOffset.text = "$symbol$formattedSeconds s"

            if (abs(timeOffset) > 500) {
                txtOffset.setTextColor(0xFFFF5252.toInt())
            } else {
                txtOffset.setTextColor(0xFFFFAB40.toInt())
            }
        }
    }
}