package com.example.atomicclockapp

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: the list of world clocks (city, label, size, position, order). */
class SettingsActivity : AppCompatActivity() {

    private val clocks = ArrayList<WorldClock>()
    private lateinit var list: LinearLayout
    private lateinit var addButton: Button
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clocks.addAll(WorldClockStore.load(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF050505.toInt())
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(16), px(8))
        }
        header.addView(TextView(this).apply {
            text = "←"
            setTextColor(0xFFDDDDDD.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(px(12), px(4), px(16), px(4))
            contentDescription = "Back"
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(0xFFDDDDDD.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(24))
        }
        content.addView(sectionTitle("WORLD CLOCKS"))
        content.addView(note("Tap a clock to change its label, size or position. They run on the same corrected time as the main clock."))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        addButton = Button(this).apply {
            text = "Add clock"
            setOnClickListener { pickCity() }
        }
        content.addView(addButton)
        content.addView(note("City data © GeoNames (geonames.org), CC BY 4.0.").apply {
            setPadding(0, px(24), 0, 0)
        })
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        if (clocks.isEmpty()) list.addView(note("No world clocks yet.").apply { setPadding(0, px(12), 0, px(12)) })
        clocks.forEachIndexed { i, clock -> list.addView(clockRow(i, clock)) }
        addButton.isEnabled = clocks.size < WorldClockStore.MAX
        addButton.text = if (addButton.isEnabled) "Add clock" else "Add clock (${WorldClockStore.MAX} max)"
    }

    private fun save() {
        WorldClockStore.save(this, clocks)
        refresh()
    }

    private fun clockRow(index: Int, clock: WorldClock): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(10))
            setOnClickListener { editClock(index, clock) }
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = clock.label
            setTextColor(0xFFEEEEEE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        text.addView(TextView(this).apply {
            this.text = "${clock.zoneId} · ${clock.size.label} · ${clock.position.label}"
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        row.addView(text)
        row.addView(arrow("▲", "Move up", index > 0) { move(index, -1) })
        row.addView(arrow("▼", "Move down", index < clocks.size - 1) { move(index, 1) })
        return row
    }

    private fun move(index: Int, by: Int) {
        val target = index + by
        if (target !in clocks.indices) return
        clocks.add(target, clocks.removeAt(index))
        save()
    }

    /** Label, size and position; also deletes. [index] is -1 for a new clock. */
    private fun editClock(index: Int, clock: WorldClock) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), 0)
        }
        val label = EditText(this).apply {
            setText(clock.label)
            hint = "Label"
            setSingleLine()
        }
        form.addView(label)
        val sizes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        ClockSize.entries.forEach { s ->
            sizes.addView(RadioButton(this).apply { text = s.label; id = View.generateViewId(); tag = s; isChecked = s == clock.size })
        }
        form.addView(sizes)
        val positions = RadioGroup(this)
        ClockPosition.entries.forEach { p ->
            positions.addView(RadioButton(this).apply { text = p.label; id = View.generateViewId(); tag = p; isChecked = p == clock.position })
        }
        form.addView(positions)

        val dialog = AlertDialog.Builder(this)
            .setTitle(clock.zoneId)
            .setView(form)
            .setPositiveButton(if (index < 0) "Add" else "Save") { _, _ ->
                val updated = WorldClock(
                    label = label.text.toString().trim().ifEmpty { clock.label },
                    zoneId = clock.zoneId,
                    size = sizes.findViewById<RadioButton>(sizes.checkedRadioButtonId)?.tag as? ClockSize ?: clock.size,
                    position = positions.findViewById<RadioButton>(positions.checkedRadioButtonId)?.tag as? ClockPosition
                        ?: clock.position,
                )
                if (index < 0) clocks.add(updated) else clocks[index] = updated
                save()
            }
            .setNegativeButton("Cancel", null)
        if (index >= 0) dialog.setNeutralButton("Delete") { _, _ -> clocks.removeAt(index); save() }
        dialog.show()
    }

    /** Searchable list of UTC and ~70,000 cities; picking one opens [editClock]. */
    private fun pickCity() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), 0)
        }
        val search = EditText(this).apply {
            hint = "Search city, or UTC"
            setSingleLine()
        }
        box.addView(search)
        val adapter = CityAdapter()
        val results = ListView(this).apply { this.adapter = adapter }
        box.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(380)))
        val dialog = AlertDialog.Builder(this).setTitle("Add clock").setView(box)
            .setNegativeButton("Cancel", null).show()

        var job: Job? = null
        fun runSearch(query: String) {
            job?.cancel()
            job = lifecycleScope.launch {
                val found = withContext(Dispatchers.Default) {
                    runCatching { Cities.search(this@SettingsActivity, query) }.getOrDefault(emptyList())
                }
                val q = Cities.normalize(query)
                val utc = q.isEmpty() || "utc".startsWith(q) || "gmt".startsWith(q) || q == "z" || "zulu".startsWith(q)
                adapter.set(utc, found)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = runSearch(s.toString())
        })
        results.setOnItemClickListener { _, _, position, _ ->
            val (label, zone) = adapter.pick(position)
            dialog.dismiss()
            editClock(-1, WorldClock(label, zone))
        }
        runSearch("")
    }

    private inner class CityAdapter : BaseAdapter() {
        private var showUtc = true
        private var cities: List<Cities.City> = emptyList()

        fun set(utc: Boolean, found: List<Cities.City>) {
            showUtc = utc
            cities = found
            notifyDataSetChanged()
        }

        fun pick(position: Int): Pair<String, String> =
            if (showUtc && position == 0) "UTC" to Cities.UTC_ZONE
            else cities[position - if (showUtc) 1 else 0].let { it.name to it.zoneId }

        override fun getCount() = cities.size + if (showUtc) 1 else 0
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView as? LinearLayout ?: LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(8), px(10), px(8), px(10))
                addView(TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
                addView(TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); alpha = 0.7f })
            }
            val (title, sub) = if (showUtc && position == 0) {
                "UTC" to "Coordinated Universal Time"
            } else {
                val c = cities[position - if (showUtc) 1 else 0]
                c.name to "${c.subtitle} · ${c.zoneId}"
            }
            (view.getChildAt(0) as TextView).text = title
            (view.getChildAt(1) as TextView).text = sub
            return view
        }
    }

    private fun arrow(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            setTextColor(if (enabled) 0xFFDDDDDD.toInt() else 0xFF333333.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(px(14), px(8), px(14), px(8))
            isEnabled = enabled
            setOnClickListener { onClick() }
        }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF00E676.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        letterSpacing = 0.15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, px(8), 0, px(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF888888.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun px(v: Int) = (v * dp).toInt()
}
