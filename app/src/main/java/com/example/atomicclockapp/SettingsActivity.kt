package com.example.atomicclockapp

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
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
            setBackgroundColor(getColor(R.color.bg))
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(16), px(8))
        }
        header.addView(TextView(this).apply {
            text = "←"
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(px(12), px(4), px(16), px(4))
            contentDescription = "Back"
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(24))
        }
        content.addView(sectionTitle("World clocks"))
        content.addView(note("Tap a clock to change its label, size or position. On the main screen, long-press a clock and drag it to move it.").apply {
            setPadding(0, 0, 0, px(12))
        })
        content.addView(layoutToggle(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(12) })
        list = card()
        content.addView(list)
        addButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Add clock"
            setOnClickListener { pickCity() }
        }
        content.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)).apply {
            topMargin = px(12)
        })

        content.addView(sectionTitle("Phone clock").apply { setPadding(0, px(28), 0, px(10)) })
        val phone = card()
        phone.addView(autoSyncRow())
        phone.addView(divider())
        phone.addView(linkRow("Check every ${AutoSync.describe(AutoSync.intervalMinutes(this))}",
            "How often to check, while on Wi-Fi") { pickInterval() })
        if (SystemClockSetter.isSetUp(this)) {
            phone.addView(divider())
            phone.addView(linkRow("Check now", "Runs the background check once, on Wi-Fi") {
                AutoSync.runNow(this)
                android.widget.Toast.makeText(this, "Checking… the result appears here", android.widget.Toast.LENGTH_SHORT).show()
                list.postDelayed({ if (!isFinishing) recreate() }, 8000)
            })
        }
        phone.addView(divider())
        phone.addView(linkRow("Date & time settings", "Android's own time and time zone settings") {
            startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
        })
        content.addView(phone)

        content.addView(sectionTitle("About").apply { setPadding(0, px(28), 0, px(10)) })
        val about = card()
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        about.addView(linkRow("Atomic Clock $version", "Source code and releases on GitHub") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Unpiloted0852/AtomicClock")))
        })
        content.addView(about)
        content.addView(note("City data © GeoNames (geonames.org), CC BY 4.0.").apply {
            setPadding(0, px(16), 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        if (clocks.isEmpty()) list.addView(note("No world clocks yet.").apply { setPadding(px(20), px(14), px(20), px(14)) })
        clocks.forEachIndexed { i, clock ->
            if (i > 0) list.addView(divider())
            list.addView(clockRow(i, clock))
        }
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
            setPadding(px(20), px(12), px(6), px(12))
            setBackgroundResource(selectableBackground())
            setOnClickListener { editClock(index, clock) }
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = clock.label
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        text.addView(TextView(this).apply {
            val offset = Cities.offsetLabel(java.util.TimeZone.getTimeZone(clock.zoneId), System.currentTimeMillis())
            val where = if (clock.position == ClockPosition.ABOVE) "above" else "below"
            this.text = "$offset · ${clock.size.label} · $where"
            setTextColor(getColor(R.color.text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
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
            .setTitle(if (index < 0) "Add ${clock.label}" else clock.label)
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
            setTextColor(getColor(if (enabled) R.color.text_secondary else R.color.divider))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(px(14), px(8), px(14), px(8))
            isEnabled = enabled
            setOnClickListener { onClick() }
        }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.accent))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, px(12), 0, px(6))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    /** List or Grid, for how the world clocks are drawn on the main screen. */
    private fun layoutToggle(): View {
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val current = WorldClockStore.loadLayout(this)
        for (layout in ClockLayout.entries) {
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = layout.label
                tag = layout
            }
            group.addView(button, LinearLayout.LayoutParams(0, px(48), 1f))
            if (layout == current) group.check(button.id)
        }
        group.addOnButtonCheckedListener { g, checkedId, isChecked ->
            if (isChecked) WorldClockStore.saveLayout(this, g.findViewById<View>(checkedId).tag as ClockLayout)
        }
        return group
    }

    /** Switch for [AutoSync], with what it does and how the last check went. */
    private fun autoSyncRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(14), px(16), px(14))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = px(12) }
        }
        texts.addView(TextView(this).apply {
            text = "Keep phone clock accurate"
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        val detail = when {
            !SystemClockSetter.isSetUp(this) ->
                "Needs the one-time setup first: press Set phone clock on the main screen."
            else -> "Android only corrects its clock once it's 2 seconds off. This checks in the " +
                "background on Wi-Fi and corrects it at ${AutoSync.THRESHOLD_MS} ms." +
                (AutoSync.lastResult(this)?.let { "\nLast check $it" } ?: "")
        }
        texts.addView(TextView(this).apply {
            text = detail
            setTextColor(getColor(R.color.text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        row.addView(texts)
        row.addView(MaterialSwitch(this).apply {
            isChecked = AutoSync.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> AutoSync.setEnabled(this@SettingsActivity, on) }
        })
        return row
    }

    private fun pickInterval() {
        val options = AutoSync.INTERVALS_MINUTES
        val current = options.indexOf(AutoSync.intervalMinutes(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Check every")
            .setSingleChoiceItems(options.map { AutoSync.describe(it) }.toTypedArray(), current) { d, which ->
                AutoSync.setInterval(this, options[which])
                d.dismiss()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        clipToOutline = true
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, px(1) / 2))
            .apply { marginStart = px(20) }
    }

    private fun linkRow(title: String, subtitle: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(14), px(20), px(14))
        setBackgroundResource(selectableBackground())
        setOnClickListener { onClick() }
        addView(TextView(context).apply {
            text = title
            setTextColor(getColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        addView(TextView(context).apply {
            text = subtitle
            setTextColor(getColor(R.color.text_tertiary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
    }

    private fun selectableBackground(): Int {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun px(v: Int) = (v * dp).toInt()
}
