package tech.mourid.museboard

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * History (issue #12): every completed task, grouped by day, newest first.
 * Nothing is ever deleted; Done just moves a task here. Swipe a row right
 * (or long-press) to reopen it; tap to open its detail page. Paints from
 * the cached task list first, then refreshes.
 */
class HistoryActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var shown: List<BoardTask>? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        findViewById<View>(R.id.history_back).setOnClickListener { finish() }
        BoardCache.loadTasks(this)?.let { render(it) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load() {
        val token = TokenStore.get(this) ?: return
        scope.launch {
            val json = try {
                withContext(Dispatchers.IO) { ApiClient.getTasksJson(token) }
            } catch (_: Exception) {
                null
            } ?: return@launch
            BoardCache.saveTasks(this@HistoryActivity, json)
            render(parseTasks(json))
        }
    }

    private fun render(tasks: List<BoardTask>) {
        val done = tasks.filter { it.status == "done" }
            .mapNotNull { t -> parseIso(t.completedAt)?.let { t to it } }
            .sortedByDescending { it.second }
        if (done.map { it.first } == shown) return
        shown = done.map { it.first }
        val list = findViewById<LinearLayout>(R.id.history_list)
        list.removeAllViews()
        findViewById<View>(R.id.history_empty).visibility = if (done.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        var lastDay: Long? = null
        for ((task, at) in done) {
            val day = dayOf(at)
            if (day != lastDay) {
                lastDay = day
                list.addView(TextView(this).apply {
                    text = dayLabel(task.completedAt)
                    setTextColor(getColor(R.color.mb_muted))
                    textSize = 13f
                    val dp = resources.displayMetrics.density
                    setPadding((4 * dp).toInt(), ((if (list.childCount == 0) 8 else 20) * dp).toInt(), 0, 0)
                })
            }
            val row = inflater.inflate(R.layout.item_task, list, false) as SwipeRow
            row.rightColor = getColor(R.color.mb_primary)
            row.cornerRadius = resources.getDimension(R.dimen.mb_app_row_radius)
            row.findViewById<TextView>(R.id.task_title).apply {
                text = task.title
                alpha = 0.75f
            }
            row.findViewById<TextView>(R.id.task_when).text = clockTime(at)
            row.findViewById<TextView>(R.id.swipe_label_start).setText(R.string.reopen)
            row.onSwipeRight = { reopen(task) }
            row.findViewById<View>(R.id.task_row).apply {
                setOnClickListener { startActivity(TaskActivity.intent(this@HistoryActivity, task)) }
                setOnLongClickListener {
                    ActionSheet(this@HistoryActivity, task.title)
                        .add(R.drawable.mb_ic_undo24, getString(R.string.reopen)) { reopen(task) }
                        .show()
                    true
                }
            }
            list.addView(row)
        }
    }

    private fun reopen(task: BoardTask) {
        val token = TokenStore.get(this) ?: return
        // Instant: it leaves the list now; the server catches up.
        BoardCache.loadTasks(this)?.let { cached ->
            render(cached.map { if (it.id == task.id) it.copy(status = "open", completedAt = null) else it })
        }
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { ApiClient.reopenTask(token, task.id) }
            } catch (_: Exception) {
                false
            }
            if (!ok) Toast.makeText(this@HistoryActivity, R.string.action_failed, Toast.LENGTH_SHORT).show()
            WidgetUpdater.requestRefresh(this@HistoryActivity)
            load()
        }
    }
}
