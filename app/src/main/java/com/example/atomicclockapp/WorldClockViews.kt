package com.example.atomicclockapp

import android.content.ClipData
import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Draws the world clocks above and below the main clock, as a list or a grid, and
 * keeps them ticking. Long-press a clock to drag it to another place; the others make
 * room as it moves, and [onRearranged] receives the new order when it is dropped.
 */
class WorldClockViews(
    private val above: LinearLayout,
    private val below: LinearLayout,
    private val onRearranged: (List<WorldClock>) -> Unit,
) {
    /** A clock plus a stable id, so two identical clocks can still be told apart while dragging. */
    private data class Item(val id: Int, val clock: WorldClock)

    private class Ticker(val zone: TimeZone, val time: TextView, val info: TextView,
                         val timeFormat: SimpleDateFormat, val dayFormat: SimpleDateFormat)

    private val context: Context = above.context
    private val dp = context.resources.displayMetrics.density
    private var items: List<Item> = emptyList()
    private var layout = ClockLayout.LIST
    private val tickers = ArrayList<Ticker>()
    private val tiles = HashMap<Int, View>()
    private var lastSecond = -1L
    private var lastNow: Long? = null

    // While dragging: the clock being moved and the arrangement before the drag began.
    private var dragId: Int? = null
    private var beforeDrag: List<Item> = emptyList()

    init {
        above.setOnDragListener { _, e -> onDrag(e, ClockPosition.ABOVE, above) }
        below.setOnDragListener { _, e -> onDrag(e, ClockPosition.BELOW, below) }
    }

    fun show(clocks: List<WorldClock>, layout: ClockLayout) {
        if (dragId != null) return
        items = clocks.mapIndexed { i, c -> Item(i, c) }
        this.layout = layout
        render()
    }

    /** [now] is the true time in Unix milliseconds, or null before the first sync. */
    fun update(now: Long?) {
        lastNow = now
        val second = (now ?: -1000L) / 1000
        if (second == lastSecond) return
        lastSecond = second
        tick(now)
    }

    private fun tick(now: Long?) {
        for (t in tickers) {
            if (now == null) {
                t.time.text = "--:--:--"
                continue
            }
            val date = Date(now)
            t.time.text = t.timeFormat.format(date)
            t.info.text = "${t.dayFormat.format(date)} · ${Cities.offsetLabel(t.zone, now)}"
        }
    }

    private fun render() {
        tickers.clear()
        tiles.clear()
        for ((container, position) in listOf(above to ClockPosition.ABOVE, below to ClockPosition.BELOW)) {
            container.removeAllViews()
            val here = items.filter { it.clock.position == position }
            val dragging = dragId != null
            container.visibility = if (here.isNotEmpty() || dragging) View.VISIBLE else View.GONE
            if (here.isEmpty()) {
                container.background = null
                if (dragging) container.addView(dropZone())
                continue
            }
            if (layout == ClockLayout.LIST) {
                container.setBackgroundResource(R.drawable.bg_card)
                container.setPadding(0, px(8), 0, px(8))
                here.forEachIndexed { i, item ->
                    if (i > 0) container.addView(divider())
                    container.addView(listRow(item))
                }
            } else {
                container.background = null
                container.setPadding(0, 0, 0, 0)
                addGrid(container, here)
            }
        }
        tick(lastNow)
    }

    // ---- List ----

    private fun listRow(item: Item): View {
        val clock = item.clock
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), px(10), px(20), px(10))
        }
        val names = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = px(12) }
        }
        names.addView(text(clock.label, R.color.text_primary, clock.size.labelSp + 3f))
        val info = text("", R.color.text_tertiary, clock.size.labelSp + 1f)
        names.addView(info)
        row.addView(names)
        val time = timeText(clock.size.timeSp)
        row.addView(time)
        register(item, row, time, info)
        return row
    }

    // ---- Grid: small and medium clocks two to a row, large ones full width ----

    private fun addGrid(container: LinearLayout, here: List<Item>) {
        val fullWidth = context.resources.displayMetrics.widthPixels - px(40)
        val gap = px(12)
        var i = 0
        var firstRow = true
        while (i < here.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (!firstRow) params.topMargin = gap
            firstRow = false
            val first = here[i]
            if (first.clock.size == ClockSize.LARGE) {
                row.addView(gridTile(first, fullWidth), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                i++
            } else {
                val half = (fullWidth - gap) / 2
                row.addView(gridTile(first, half), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = gap / 2 })
                val second = here.getOrNull(i + 1)?.takeIf { it.clock.size != ClockSize.LARGE }
                if (second != null) {
                    row.addView(gridTile(second, half), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = gap / 2 })
                    i += 2
                } else {
                    row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap / 2 })
                    i++
                }
            }
            container.addView(row, params)
        }
    }

    private fun gridTile(item: Item, width: Int): View {
        val clock = item.clock
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(px(16), px(14), px(16), px(14))
        }
        tile.addView(text(clock.label, R.color.text_secondary, 14f))
        val gridSp = when (clock.size) {
            ClockSize.SMALL -> 26f
            ClockSize.MEDIUM -> 32f
            ClockSize.LARGE -> 44f
        }
        val time = timeText(gridSp).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, fitTimeSize(textSize, width - px(32)))
            setPadding(0, px(4), 0, px(4))
        }
        tile.addView(time)
        val info = text("", R.color.text_tertiary, 12f)
        tile.addView(info)
        register(item, tile, time, info)
        return tile
    }

    /** Shrinks [sizePx] if needed so "00:00:00" fits in [widthPx]. */
    private fun fitTimeSize(sizePx: Float, widthPx: Int): Float {
        val paint = Paint().apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            textSize = sizePx
        }
        val w = paint.measureText("00:00:00")
        return if (w <= widthPx) sizePx else sizePx * widthPx / w
    }

    // ---- Shared ----

    private fun register(item: Item, view: View, time: TextView, info: TextView) {
        val zone = TimeZone.getTimeZone(item.clock.zoneId)
        tickers.add(Ticker(zone, time, info,
            SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = zone },
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply { timeZone = zone }))
        tiles[item.id] = view
        if (item.id == dragId) view.alpha = 0.25f
        view.setOnLongClickListener { startDrag(item, it) }
    }

    private fun timeText(sp: Float) = TextView(context).apply {
        text = "--:--:--"
        setTextColor(context.getColor(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
        maxLines = 1
    }

    private fun text(value: String, color: Int, sp: Float) = TextView(context).apply {
        text = value
        setTextColor(context.getColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(context.getColor(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, px(1) / 2))
            .apply { marginStart = px(20); marginEnd = px(20) }
    }

    private fun dropZone() = TextView(context).apply {
        text = "Drop here"
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.text_tertiary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setBackgroundResource(R.drawable.bg_drop_zone)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(64))
    }

    // ---- Dragging ----

    private fun startDrag(item: Item, view: View): Boolean {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val started = view.startDragAndDrop(ClipData.newPlainText("world clock", item.clock.label),
            View.DragShadowBuilder(view), null, 0)
        if (started) {
            dragId = item.id
            beforeDrag = items
            render()
        }
        return started
    }

    private fun onDrag(event: DragEvent, position: ClockPosition, container: LinearLayout): Boolean {
        val id = dragId ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION ->
                moveTo(id, position, container, event.x, event.y)
            DragEvent.ACTION_DROP -> {
                moveTo(id, position, container, event.x, event.y)
                onRearranged(items.map { it.clock })
                beforeDrag = items
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                // Both containers get this; only act once. Letting go anywhere keeps the
                // arrangement the live preview was showing.
                if (dragId != null) {
                    dragId = null
                    if (items != beforeDrag) onRearranged(items.map { it.clock })
                    render()
                }
            }
        }
        return true
    }

    /** Places the dragged clock at the spot under (x, y) in [container], if that changes anything. */
    private fun moveTo(id: Int, position: ClockPosition, container: LinearLayout, x: Float, y: Float) {
        val dragged = items.firstOrNull { it.id == id } ?: return
        val others = items.filter { it.id != id }
        val here = others.filter { it.clock.position == position }
        // How many of the clocks here come before the finger.
        val before = here.count { item ->
            val r = rectIn(container, tiles[item.id] ?: return@count false) ?: return@count false
            if (r.width() > container.width * 3 / 4) {
                // Full-width row: before the finger once the finger passes its middle.
                r.centerY() < y
            } else {
                // Grid tile: in a row above, or to the left in the same row.
                r.bottom <= y || (y >= r.top && r.centerX() < x)
            }
        }
        val insertAt = when {
            here.isEmpty() -> if (position == ClockPosition.ABOVE) 0 else others.size
            before < here.size -> others.indexOf(here[before])
            else -> others.indexOf(here.last()) + 1
        }
        val moved = dragged.copy(clock = dragged.clock.copy(position = position))
        val result = others.toMutableList().apply { add(insertAt, moved) }
        if (result != items) {
            items = result
            render()
        }
    }

    /** [view]'s bounds in [container]'s coordinates. */
    private fun rectIn(container: View, view: View): Rect? {
        var left = 0
        var top = 0
        var v: View = view
        while (v !== container) {
            left += v.left
            top += v.top
            v = v.parent as? View ?: return null
        }
        return Rect(left, top, left + view.width, top + view.height)
    }

    private fun px(v: Int) = (v * dp).toInt()
}
