package tech.mourid.museboard

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Calendar (issue #12): one tasks list, seen by date. A task sits on its
 * plan date ("do date"), or its due date when it has no plan. Month grid
 * with a dot on busy days, or a week list; tap a day for its tasks, a task
 * for its detail page. Paints from the cached task list, then refreshes.
 */
class CalendarActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tasks: List<BoardTask> = emptyList()
    private var weekMode = false
    /** The selected day, at noon Casablanca time. */
    private val cursor: Calendar = Calendar.getInstance(CASABLANCA).apply { atNoon() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)
        findViewById<View>(R.id.cal_back).setOnClickListener { finish() }
        findViewById<View>(R.id.cal_mode_month).setOnClickListener { weekMode = false; render() }
        findViewById<View>(R.id.cal_mode_week).setOnClickListener { weekMode = true; render() }
        findViewById<View>(R.id.cal_prev).setOnClickListener { move(-1) }
        findViewById<View>(R.id.cal_next).setOnClickListener { move(1) }
        tasks = BoardCache.loadTasks(this).orEmpty()
        render()
    }

    override fun onResume() {
        super.onResume()
        val token = TokenStore.get(this) ?: return
        scope.launch {
            val json = try {
                withContext(Dispatchers.IO) { ApiClient.getTasksJson(token) }
            } catch (_: Exception) {
                null
            } ?: return@launch
            BoardCache.saveTasks(this@CalendarActivity, json)
            val fresh = parseTasks(json)
            if (fresh != tasks) {
                tasks = fresh
                render()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun move(step: Int) {
        if (weekMode) {
            cursor.add(Calendar.DAY_OF_YEAR, 7 * step)
        } else {
            val day = cursor.get(Calendar.DAY_OF_MONTH)
            cursor.set(Calendar.DAY_OF_MONTH, 1)
            cursor.add(Calendar.MONTH, step)
            cursor.set(Calendar.DAY_OF_MONTH, minOf(day, cursor.getActualMaximum(Calendar.DAY_OF_MONTH)))
        }
        render()
    }

    /** Tasks per Casablanca day: plan date, else due date. */
    private fun byDay(): Map<Long, List<BoardTask>> = tasks
        .mapNotNull { t -> parseIso(t.planAt ?: t.dueAt)?.let { dayOf(it) to t } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) -> list.sortedBy { parseIso(it.planAt ?: it.dueAt) } }

    private fun render() {
        val month = findViewById<LinearLayout>(R.id.cal_month)
        val list = findViewById<LinearLayout>(R.id.cal_list)
        val dayLabelView = findViewById<TextView>(R.id.cal_day_label)
        findViewById<View>(R.id.cal_mode_month).background =
            if (weekMode) null else getDrawable(R.drawable.mb_segment_on)
        findViewById<View>(R.id.cal_mode_week).background =
            if (weekMode) getDrawable(R.drawable.mb_segment_on) else null
        val days = byDay()
        list.removeAllViews()

        if (weekMode) {
            month.visibility = View.GONE
            dayLabelView.visibility = View.GONE
            val start = weekStart(cursor)
            val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 6) }
            findViewById<TextView>(R.id.cal_title).text = fmt("d MMM", start) + " – " + fmt("d MMM", end)
            var any = false
            for (i in 0..6) {
                val day = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
                val items = days[dayOf(day.timeInMillis)].orEmpty()
                if (items.isEmpty()) continue
                any = true
                list.addView(sectionLabel(fmt("EEEE d MMM", day)))
                items.forEach { list.addView(taskRow(it)) }
            }
            if (!any) list.addView(note(getString(R.string.cal_nothing)))
            return
        }

        month.visibility = View.VISIBLE
        dayLabelView.visibility = View.VISIBLE
        findViewById<TextView>(R.id.cal_title).text = fmt("MMMM yyyy", cursor)
        buildMonth(month, days)
        dayLabelView.text = fmt("EEEE d MMM", cursor)
        val items = days[dayOf(cursor.timeInMillis)].orEmpty()
        if (items.isEmpty()) list.addView(note(getString(R.string.cal_nothing)))
        items.forEach { list.addView(taskRow(it)) }
    }

    private fun buildMonth(month: LinearLayout, days: Map<Long, List<BoardTask>>) {
        month.removeAllViews()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (initial in listOf("M", "T", "W", "T", "F", "S", "S")) {
            header.addView(TextView(this).apply {
                text = initial
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.mb_muted))
                textSize = 12f
                setPadding(0, dp(6), 0, dp(6))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        month.addView(header)

        val first = (cursor.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val start = weekStart(first)
        val today = dayOf(System.currentTimeMillis())
        val selected = dayOf(cursor.timeInMillis)
        val shownMonth = cursor.get(Calendar.MONTH)
        for (week in 0 until 6) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (d in 0 until 7) {
                val day = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, week * 7 + d) }
                val index = dayOf(day.timeInMillis)
                row.addView(dayCell(day, index == selected, index == today, day.get(Calendar.MONTH) == shownMonth, days.containsKey(index)),
                    LinearLayout.LayoutParams(0, dp(56), 1f))
            }
            month.addView(row)
        }
    }

    private fun dayCell(day: Calendar, selected: Boolean, today: Boolean, inMonth: Boolean, busy: Boolean): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = getDrawable(R.drawable.mb_ripple_rounded)
            alpha = if (inMonth) 1f else 0.35f
            contentDescription = fmt("EEEE d MMMM", day)
            setOnClickListener {
                cursor.timeInMillis = day.timeInMillis
                render()
            }
        }
        cell.addView(TextView(this).apply {
            text = day.get(Calendar.DAY_OF_MONTH).toString()
            gravity = Gravity.CENTER
            textSize = 15f
            when {
                selected -> {
                    background = getDrawable(R.drawable.mb_day_selected)
                    setTextColor(getColor(R.color.mb_on_primary))
                    setTypeface(typeface, Typeface.BOLD)
                }
                today -> {
                    setTextColor(getColor(R.color.mb_accent))
                    setTypeface(typeface, Typeface.BOLD)
                }
                else -> setTextColor(getColor(R.color.mb_on_surface))
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        // A dot on days with tasks; space kept on empty days so rows align.
        cell.addView(TextView(this).apply {
            text = "•"
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(getColor(R.color.mb_accent))
            visibility = if (busy && !selected) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(14)))
        return cell
    }

    private fun taskRow(task: BoardTask): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_task, findViewById(R.id.cal_list), false)
        val done = task.status == "done"
        row.findViewById<TextView>(R.id.task_title).apply {
            text = task.title
            alpha = if (done) 0.55f else 1f
        }
        val detail = if (done) null else task.firstStep?.let { getString(R.string.start_with, it) }
        row.findViewById<TextView>(R.id.task_detail).apply {
            text = detail
            visibility = if (detail == null) View.GONE else View.VISIBLE
        }
        val at = parseIso(task.planAt ?: task.dueAt)
        row.findViewById<TextView>(R.id.task_when).text = at?.let { clockTime(it) }.orEmpty()
        row.findViewById<View>(R.id.task_row).setOnClickListener {
            startActivity(TaskActivity.intent(this, task))
        }
        return row
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.mb_muted))
        textSize = 13f
        setPadding(dp(4), dp(18), 0, dp(2))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.mb_muted))
        textSize = 14f
        setPadding(dp(4), dp(12), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun fmt(pattern: String, day: Calendar): String =
        SimpleDateFormat(pattern, Locale.UK).apply { timeZone = CASABLANCA }.format(day.time)

    /** Monday of [day]'s week, at noon. */
    private fun weekStart(day: Calendar): Calendar = (day.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        atNoon()
    }

    private fun Calendar.atNoon() {
        set(Calendar.HOUR_OF_DAY, 12)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
