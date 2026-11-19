package com.example.atomicclockapp

import android.content.Context
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Keeps the phone's clock accurate in the background.
 *
 * Android itself only corrects its clock once it is 2 seconds or more off, and phones
 * drift between 0.1 and 1.5 seconds a day, so on its own a phone sits anywhere up to
 * 2 seconds off. This checks true time regularly (on Wi-Fi, which setting the clock
 * needs) and corrects the clock whenever it is [THRESHOLD_MS] or more off.
 */
object AutoSync {
    /** Smaller differences are within what a single NTP measurement can tell apart. */
    const val THRESHOLD_MS = 25L
    val INTERVALS_MINUTES = listOf(15L, 30L, 60L, 180L, 360L)

    private const val PREFS = "settings"
    private const val KEY_ENABLED = "auto_sync_enabled"
    private const val KEY_INTERVAL = "auto_sync_interval_minutes"
    private const val KEY_LAST = "auto_sync_last"
    private const val WORK_NAME = "auto_sync"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)
    fun intervalMinutes(context: Context) = prefs(context).getLong(KEY_INTERVAL, 60L)
    fun lastResult(context: Context): String? = prefs(context).getString(KEY_LAST, null)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        schedule(context, replace = true)
    }

    fun setInterval(context: Context, minutes: Long) {
        prefs(context).edit().putLong(KEY_INTERVAL, minutes).apply()
        schedule(context, replace = true)
    }

    /** "1 hour", "15 minutes" */
    fun describe(minutes: Long) = when {
        minutes % 60 == 0L -> (minutes / 60).let { if (it == 1L) "1 hour" else "$it hours" }
        else -> "$minutes minutes"
    }

    /**
     * Starts (or stops) the regular check to match the settings. Safe to call often:
     * without [replace], an existing schedule is left alone.
     */
    fun schedule(context: Context, replace: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!isEnabled(context) || !SystemClockSetter.isSetUp(context)) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<Worker>(intervalMinutes(context), TimeUnit.MINUTES)
            // Setting the clock needs Wireless debugging, which only runs on Wi-Fi.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One check right away (still waits for Wi-Fi). */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK_NAME-now", ExistingWorkPolicy.REPLACE, request)
    }

    private fun record(context: Context, text: String) {
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        prefs(context).edit().putString(KEY_LAST, "$time: $text").apply()
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val app = applicationContext
            if (!SystemClockSetter.isSetUp(app)) return@withContext Result.success()
            // With automatic time off, Android ignores the time the app suggests.
            if (Settings.Global.getInt(app.contentResolver, Settings.Global.AUTO_TIME, 1) == 0) {
                record(app, "skipped, \"Set time automatically\" is off")
                return@withContext Result.success()
            }
            try {
                val reading = TrueTime.measure()
                val error = -reading.offsetMs
                if (abs(error) < THRESHOLD_MS) {
                    record(app, "${abs(error)} ms off, left as is")
                } else {
                    SystemClockSetter.setClock(app, reading.elapsedRealtime, reading.trueUnixMillis,
                        phoneIsFast = error > 0) {}
                    record(app, "${abs(error)} ms ${if (error > 0) "fast" else "slow"}, corrected")
                }
                Result.success()
            } catch (e: SystemClockSetter.NotSetUpException) {
                record(app, "setup needed")
                Result.success()
            } catch (e: Exception) {
                record(app, "failed (${e.message ?: e.javaClass.simpleName})")
                if (runAttemptCount < 2) Result.retry() else Result.success()
            }
        }
    }
}
