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
    SMALL("Small", 24f, 11f),
    MEDIUM("Medium", 32f, 12f),
    LARGE("Large", 44f, 14f),
}

/** How the world clocks are drawn on the main screen. */
enum class ClockLayout(val label: String) {
    LIST("List"),
    GRID("Grid"),
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
    private const val KEY_LAYOUT = "world_clock_layout"

    fun loadLayout(context: Context): ClockLayout =
        runCatching {
            ClockLayout.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)!!)
        }.getOrDefault(ClockLayout.LIST)

    fun saveLayout(context: Context, layout: ClockLayout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAYOUT, layout.name).apply()
    }

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
