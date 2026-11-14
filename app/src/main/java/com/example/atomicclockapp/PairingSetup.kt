package com.example.atomicclockapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.android.AdbMdns

/**
 * One-time setup on the phone itself, no computer needed.
 *
 * The user turns on Wireless debugging and opens "Pair device with pairing code".
 * That box has to stay open, so the 6-digit code is typed into a reply field on
 * this app's notification. [PairingCodeReceiver] then pairs the app with the
 * phone's adb daemon, and uses that adb connection to grant the app
 * WRITE_SECURE_SETTINGS, so from then on the app can switch Wireless debugging
 * on and off by itself.
 */
object PairingSetup {

    private const val CHANNEL = "adb_setup"
    private const val NOTIFICATION_ID = 1
    internal const val KEY_CODE = "pairing_code"

    fun isDeveloperOptionsOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun isOnWifi(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: SecurityException) {
        false
    }

    fun needsNotificationPermission(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun openAboutPhone(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Posts the code-entry notification and opens Developer options at Wireless debugging. */
    fun start(activity: Activity) {
        notify(activity, "Enter the pairing code here",
            "In Wireless debugging, tap \"Pair device with pairing code\". Keep that box open, " +
                "then tap Enter code below and type the 6-digit code.", askForCode = true)
        // Own task, so returning to the app doesn't bring Settings back with it.
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Scrolls to Wireless debugging on some phones (not on all, e.g. Motorola).
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Pairs with the phone's adb daemon using [code], then grants this app
     * WRITE_SECURE_SETTINGS. Blocking. Throws with a message for the user on failure.
     */
    internal fun pairAndGrant(context: Context, code: String) {
        val adb = AdbClient(context)
        try {
            if (!pair(context, adb, code)) {
                throw IllegalStateException("Pairing failed. Check the code, and keep the pairing box open while you enter it.")
            }
            if (!AdbPorts.findConnectPort(context, adb) {}) {
                throw IllegalStateException("Paired, but couldn't connect. Is Wireless debugging still on?")
            }
            val out = adb.shell("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS} && echo GRANT_OK")
            if (!out.contains("GRANT_OK")) throw IllegalStateException("Couldn't finish setup: ${out.trim().take(120)}")
        } finally {
            adb.closeQuietly()
        }
        // The app turns Wireless debugging on by itself whenever it needs it from now on.
        SystemClockSetter.setWifiDebugging(context, false)
    }

    /**
     * Pairs using the pairing box's mDNS announcement, or if that doesn't arrive, by
     * trying each open port on this phone (wrong ports just fail; the code isn't used up).
     */
    private fun pair(context: Context, adb: AdbClient, code: String): Boolean {
        AdbPorts.viaMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 5_000)?.let { (host, port) ->
            try {
                if (adb.pair(host.hostAddress, port, code)) return true
            } catch (_: Exception) {
            }
        }
        for (port in AdbPorts.openLocalPorts()) {
            try {
                if (adb.pair("127.0.0.1", port, code)) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    @SuppressLint("MissingPermission") // Checked by areNotificationsEnabled()
    internal fun notify(context: Context, title: String, text: String, askForCode: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Clock setup", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!askForCode)
        if (askForCode) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
            // Mutable so the system can attach the typed code.
            val reply = PendingIntent.getBroadcast(
                context, 0, Intent(context, PairingCodeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Enter code", reply)
                    .addRemoteInput(input)
                    .build()
            )
        }
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) manager.notify(NOTIFICATION_ID, builder.build())
    }
}

/** Receives the pairing code typed into the setup notification and finishes the setup. */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingSetup.KEY_CODE)?.toString()?.filter { it.isDigit() }.orEmpty()
        if (code.length != 6) {
            PairingSetup.notify(app, "That wasn't a 6-digit code",
                "Type the 6 digits shown under \"Wi-Fi pairing code\".", askForCode = true)
            return
        }
        PairingSetup.notify(app, "Pairing…", "Setting up Atomic Clock.", askForCode = false)
        val pending = goAsync()
        Thread {
            try {
                PairingSetup.pairAndGrant(app, code)
                PairingSetup.notify(app, "Atomic Clock is set up",
                    "Go back to Atomic Clock and press Set system clock. Wireless debugging was " +
                        "switched off again; the app turns it on by itself when needed.", askForCode = false)
            } catch (e: Exception) {
                PairingSetup.notify(app, "Setup didn't finish",
                    (e.message ?: "Something went wrong.") + " Enter a code to try again.", askForCode = true)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
