package com.example.atomicclockapp

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.math.abs

enum class ClockSize(val label: String, val timeSp: Float, val labelSp: Float) {
    SMALL("Small", 22f, 11f),
    MEDIUM("Medium", 34f, 12f),
    LARGE("Large", 50f, 14f),
}

enum class ClockPosition(val label: String) {
    ABOVE("Above main clock"),
    BELOW("Below main clock"),
}

data class WorldClock(
    val label: String,
    val zoneId: String,
    val size: ClockSize = ClockSize.MEDIUM,
    val position: ClockPosition = ClockPosition.BELOW,
)

/** The world clocks, in display order, saved in the app's preferences. */
object WorldClockStore {
    const val MAX = 6
    private const val PREFS = "settings"
    private const val KEY = "world_clocks"

    fun load(context: Context): List<WorldClock> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                WorldClock(
                    label = o.optString("label"),
                    zoneId = o.optString("zone").ifEmpty { return@mapNotNull null },
                    size = runCatching { ClockSize.valueOf(o.optString("size")) }.getOrDefault(ClockSize.MEDIUM),
                    position = runCatching { ClockPosition.valueOf(o.optString("position")) }
                        .getOrDefault(ClockPosition.BELOW),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, clocks: List<WorldClock>) {
        val array = JSONArray()
        for (c in clocks) {
            array.put(JSONObject().put("label", c.label).put("zone", c.zoneId)
                .put("size", c.size.name).put("position", c.position.name))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}

/** Offline city list (GeoNames, CC BY 4.0), largest population first. */
object Cities {
    const val UTC_ZONE = "UTC"

    class City(val name: String, val country: String, val region: String, val zoneId: String, internal val key: String) {
        /** "Oregon, United States" */
        val subtitle: String
            get() = listOf(region, Locale("", country).displayCountry)
                .filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
    }

    @Volatile private var all: List<City>? = null

    /** Slow the first time (reads ~70,000 cities); call off the main thread. */
    fun search(context: Context, query: String, limit: Int = 60): List<City> {
        val cities = load(context)
        val q = normalize(query)
        if (q.isEmpty()) return cities.take(limit)
        val starts = ArrayList<City>()
        val contains = ArrayList<City>()
        for (c in cities) {
            if (c.key.startsWith(q)) starts.add(c)
            else if (contains.size < limit && c.key.contains(q)) contains.add(c)
            if (starts.size >= limit) break
        }
        return (starts + contains).take(limit)
    }

    private fun load(context: Context): List<City> {
        all?.let { return it }
        synchronized(this) {
            all?.let { return it }
            val zones = TimeZone.getAvailableIDs().toHashSet()
            val list = ArrayList<City>(70_000)
            context.assets.open("cities.bin").use { raw ->
                GZIPInputStream(raw).bufferedReader().forEachLine { line ->
                    val f = line.split('\t')
                    if (f.size >= 5 && f[4] in zones) {
                        list.add(City(f[0], f[2], f[3], f[4], normalize(f[1].ifEmpty { f[0] })))
                    }
                }
            }
            all = list
            return list
        }
    }

    /** Lower case, no accents: "Zürich" and "zurich" match. */
    fun normalize(s: String): String =
        Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    /** "UTC+9", "UTC−3:30", "UTC" */
    fun offsetLabel(zone: TimeZone, at: Long): String {
        val ms = zone.getOffset(at)
        if (ms == 0) return "UTC"
        val minutes = abs(ms) / 60_000
        val sign = if (ms > 0) "+" else "−"
        return if (minutes % 60 == 0) "UTC$sign${minutes / 60}" else "UTC$sign${minutes / 60}:%02d".format(minutes % 60)
    }
}

/** Draws the world clocks above and below the main clock and keeps them ticking. */
class WorldClockViews(private val above: LinearLayout, private val below: LinearLayout) {

    private class Row(val zone: TimeZone, val time: TextView, val info: TextView,
                      val timeFormat: SimpleDateFormat, val dayFormat: SimpleDateFormat)

    private val rows = ArrayList<Row>()
    private var lastSecond = -1L

    fun show(clocks: List<WorldClock>) {
        above.removeAllViews()
        below.removeAllViews()
        rows.clear()
        lastSecond = -1
        for (clock in clocks) {
            val parent = if (clock.position == ClockPosition.ABOVE) above else below
            parent.addView(buildRow(parent.context, clock))
        }
        above.visibility = if (above.childCount > 0) android.view.View.VISIBLE else android.view.View.GONE
        below.visibility = if (below.childCount > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** [now] is the true time in Unix milliseconds, or null before the first sync. */
    fun update(now: Long?) {
        val second = (now ?: -1000L) / 1000
        if (second == lastSecond) return
        lastSecond = second
        for (row in rows) {
            if (now == null) {
                row.time.text = "--:--:--"
                continue
            }
            val date = Date(now)
            row.time.text = row.timeFormat.format(date)
            row.info.text = "${row.dayFormat.format(date)} · ${Cities.offsetLabel(row.zone, now)}"
        }
    }

    private fun buildRow(context: Context, clock: WorldClock): LinearLayout {
        val zone = TimeZone.getTimeZone(clock.zoneId)
        val dp = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        }
        val names = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        names.addView(TextView(context).apply {
            text = clock.label.uppercase(Locale.getDefault())
            setTextColor(0xFFBBBBBB.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, clock.size.labelSp)
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val info = TextView(context).apply {
            setTextColor(0xFF777777.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, clock.size.labelSp - 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        names.addView(info)
        row.addView(names)
        val time = TextView(context).apply {
            text = "--:--:--"
            setTextColor(0xFF00C864.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, clock.size.timeSp)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
        }
        row.addView(time)
        rows.add(Row(zone, time, info,
            SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = zone },
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = zone }))
        return row
    }
}
