package com.example.atomicclockapp

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var txtTime: TextView
    private lateinit var txtMillis: TextView
    private lateinit var txtDate: TextView
    private lateinit var statusDot: View
    private lateinit var txtNetworkStats: TextView
    private lateinit var txtOffset: TextView
    private lateinit var txtOffsetWords: TextView
    private lateinit var btnSync: Button
    private lateinit var btnSetClock: Button
    private lateinit var btnSound: ImageButton
    private lateinit var txtUpdate: TextView
    private lateinit var worldClocks: WorldClockViews

    private val updater by lazy { AppUpdater(this) }

    // Setup needs the notification (the pairing code is typed into it), so ask first.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) PairingSetup.start(this)
            else Toast.makeText(this, "Setup needs notifications: the pairing code is typed into one.", Toast.LENGTH_LONG).show()
        }

    private val currentServer = TrueTime.SERVER

    // True time is kept against the phone's uptime counter, which never jumps when the
    // phone's clock is set, so the display stays right even while the clock is corrected.
    private var baseElapsed = 0L
    private var baseTrue = 0L
    private var roundTripDelay: Long = 0
    private var isSynced = false
    private var lastCardSecond = -1L

    private fun trueNow() = baseTrue + (SystemClock.elapsedRealtime() - baseElapsed)

    private var isSoundOn = false
    private var lastPlayedSecond: Long = 0
    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private var lastDateText = ""

    private val handler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (isSynced) {
                val now = trueNow()
                // How far the phone's clock is off right now, so a correction shows at once.
                val phoneError = System.currentTimeMillis() - now
                val date = Date(now)
                txtTime.text = timeFormat.format(date)
                txtMillis.text = ".%03d".format(now % 1000)
                val dateText = dateFormat.format(date)
                if (dateText != lastDateText) {
                    lastDateText = dateText
                    txtDate.text = dateText
                }
                worldClocks.update(now)

                val currentSecond = now / 1000
                if (currentSecond != lastCardSecond) {
                    lastCardSecond = currentSecond
                    showPhoneClock(phoneError)
                }
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

        txtTime = findViewById(R.id.txtTime)
        txtMillis = findViewById(R.id.txtMillis)
        txtDate = findViewById(R.id.txtDate)
        statusDot = findViewById(R.id.statusDot)
        txtNetworkStats = findViewById(R.id.txtNetworkStats)
        txtOffset = findViewById(R.id.txtOffset)
        txtOffsetWords = findViewById(R.id.txtOffsetWords)
        btnSync = findViewById(R.id.btnSync)
        btnSetClock = findViewById(R.id.btnSetClock)
        btnSound = findViewById(R.id.btnSound)
        txtUpdate = findViewById(R.id.txtUpdate)
        worldClocks = WorldClockViews(findViewById<LinearLayout>(R.id.worldAbove), findViewById<LinearLayout>(R.id.worldBelow)) {
            WorldClockStore.save(this, it)
        }

        fitMainClock()
        handler.post(clockRunnable)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSync.setOnClickListener { fetchAtomicTime() }
        btnSetClock.setOnClickListener { setSystemClock() }
        btnSound.setOnClickListener {
            isSoundOn = !isSoundOn
            btnSound.setImageResource(if (isSoundOn) R.drawable.ic_sound_on else R.drawable.ic_sound_off)
            btnSound.contentDescription = if (isSoundOn) "Audio tick on" else "Audio tick off"
            if (isSoundOn) lastPlayedSecond = trueNow() / 1000
            Toast.makeText(this, if (isSoundOn) "Audio tick on" else "Audio tick off", Toast.LENGTH_SHORT).show()
        }

        fetchAtomicTime()
        checkForUpdate()
        AutoSync.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        // Picks up changes made in Settings.
        worldClocks.show(WorldClockStore.load(this), WorldClockStore.loadLayout(this))
        worldClocks.update(if (isSynced) trueNow() else null)
        // The uptime counter drifts like the phone's clock, so measure again after a while.
        if (isSynced && SystemClock.elapsedRealtime() - baseElapsed > 10 * 60_000) fetchAtomicTime()
    }

    /** Sizes the main clock so "00:00:00.000" fills most of the screen width. */
    private fun fitMainClock() {
        val available = resources.displayMetrics.widthPixels - 40 * resources.displayMetrics.density
        val paint = Paint().apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            textSize = 100f
        }
        // Milliseconds are drawn at 40% size beside the main digits.
        val width = paint.measureText("00:00:00") + 0.4f * paint.measureText(".000")
        val size = 100f * available * 0.96f / width
        txtTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        txtMillis.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.4f)
    }

    /** Once per launch: if GitHub has a newer release, offer it as a tappable pill at the top. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = updater.checkForUpdate() ?: return@launch
            val offer = "Update available: v${release.versionName} · Tap to install"
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

    private fun setStatus(text: String, colorRes: Int = R.color.text_tertiary) {
        txtNetworkStats.text = text
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun fetchAtomicTime() {
        setStatus("Checking $currentServer…")
        btnSync.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reading = TrueTime.measure()

                withContext(Dispatchers.Main) {
                    baseElapsed = reading.elapsedRealtime
                    baseTrue = reading.trueUnixMillis
                    roundTripDelay = reading.delayMs
                    isSynced = true
                    btnSync.isEnabled = true
                    updateInfoDisplay()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    btnSync.isEnabled = true
                    setStatus("Couldn't reach $currentServer", R.color.status_bad)
                }
            }
        }
    }

    /**
     * Measures the true time, sets the phone's system clock to it, then
     * measures again so the readout shows the result.
     */
    private fun setSystemClock() {
        if (!SystemClockSetter.isSetUp(this)) {
            showSetupDialog()
            return
        }
        // With automatic time off, Android ignores the app's time suggestion.
        if (Settings.Global.getInt(contentResolver, Settings.Global.AUTO_TIME, 1) == 0) {
            AlertDialog.Builder(this)
                .setTitle("Turn on automatic time")
                .setMessage("Atomic Clock sets the clock through Android's automatic time. " +
                    "Turn on \"Set time automatically\", then try again.")
                .setPositiveButton("Open Date & time") { _, _ ->
                    startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        btnSetClock.isEnabled = false
        btnSetClock.text = "Setting…"
        setStatus("Measuring true time…")

        CoroutineScope(Dispatchers.IO).launch {
            var needsSetup = false
            val result = try {
                // True time, paired with the phone's uptime clock at the same instant,
                // so the time it takes to apply it does not matter.
                val reading = TrueTime.measure()
                SystemClockSetter.setClock(this@MainActivity, reading.elapsedRealtime, reading.trueUnixMillis,
                    phoneIsFast = reading.offsetMs < 0) { status ->
                    runOnUiThread { setStatus(status) }
                }
                null
            } catch (e: SystemClockSetter.NotSetUpException) {
                needsSetup = true
                e.message
            } catch (e: Exception) {
                e.printStackTrace()
                e.message ?: e.javaClass.simpleName
            }

            withContext(Dispatchers.Main) {
                btnSetClock.isEnabled = true
                btnSetClock.text = "Set phone clock"
                if (needsSetup) {
                    setStatus("One-time setup needed")
                    showSetupDialog()
                } else if (result == null) {
                    Toast.makeText(this@MainActivity, "Phone clock set", Toast.LENGTH_SHORT).show()
                    AutoSync.schedule(this@MainActivity)
                    fetchAtomicTime()
                } else {
                    setStatus("Clock not set: $result", R.color.status_bad)
                }
            }
        }
    }

    /** Walks the user through pairing the app with Wireless debugging (see PairingSetup). */
    private fun showSetupDialog() {
        val devOn = PairingSetup.isDeveloperOptionsOn(this)
        val wifiOn = PairingSetup.isOnWifi(this)
        val done = "  ✓"
        val message = "Android doesn't let apps change the clock, so Atomic Clock does it through " +
            "Wireless debugging. This one-time setup takes about a minute and needs no computer.\n\n" +
            "1. Connect to Wi-Fi." + (if (wifiOn) done else "") + "\n\n" +
            "2. Turn on Developer options: in Settings › About phone, tap \"Build number\" 7 times." +
            (if (devOn) done else "") + "\n\n" +
            "3. Tap Start pairing. In Developer options, scroll down to Wireless debugging, " +
            "turn it on, open it, then tap \"Pair device with pairing code\".\n\n" +
            "4. Keep that box open, pull down the Atomic Clock notification (expand it if " +
            "you don't see Enter code), tap Enter code and type the 6-digit code."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Set up clock setting")
            .setMessage(message)
            .setPositiveButton("Start pairing") { _, _ ->
                if (PairingSetup.needsNotificationPermission(this)) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    PairingSetup.start(this)
                }
            }
            .setNegativeButton("Cancel", null)
        if (!devOn) dialog.setNeutralButton("About phone") { _, _ -> PairingSetup.openAboutPhone(this) }
        dialog.show()
    }

    /** Status line and the "Phone clock" card, after each measurement. */
    private fun updateInfoDisplay() {
        val margin = roundTripDelay / 2
        setStatus("Synced with $currentServer · ±$margin ms", R.color.status_good)
        showPhoneClock(System.currentTimeMillis() - trueNow())
    }

    /** The "Phone clock" card. [error]: positive = the phone's clock is fast. */
    private fun showPhoneClock(error: Long) {
        val color = when {
            abs(error) <= 50 -> R.color.status_good
            abs(error) <= 500 -> R.color.status_warn
            else -> R.color.status_bad
        }

        txtOffset.text = formatError(error)
        txtOffset.setTextColor(ContextCompat.getColor(this, color))
        txtOffsetWords.text = when {
            error == 0L -> "Exactly on time"
            error > 0 -> "${formatMagnitude(error)} fast"
            else -> "${formatMagnitude(error)} slow"
        }
    }

    /** "+7 ms", "−1.234 s", "0 ms" */
    private fun formatError(ms: Long): String {
        val sign = when {
            ms > 0 -> "+"
            ms < 0 -> "−"
            else -> ""
        }
        return sign + formatMagnitude(ms)
    }

    private fun formatMagnitude(ms: Long): String =
        if (abs(ms) < 1000) "${abs(ms)} ms" else String.format(Locale.US, "%.3f s", abs(ms) / 1000.0)
}
