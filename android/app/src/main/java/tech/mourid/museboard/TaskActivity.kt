package tech.mourid.museboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Task detail page (issue #5): tap a task in the app to open it.
 *
 * Notion-style: title, first step, when / where / due and notes are edited
 * in place and save on their own (text after a short pause, dates at once,
 * anything pending when leaving). Links in the notes get a tappable row.
 * Under the first step sit the blocks Muse wrote (TaskBlocks): text,
 * buttons, checklist items the owner ticks (saved like any edit), toggles.
 * Done and Later work from here too. Opened with [EXTRA_SHRINK] (the "Too
 * big?" chip, issue #7) it focuses the first step with a hint.
 */
class TaskActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val saveSoon = Runnable { saveNow() }

    private lateinit var status: TextView
    private lateinit var titleEdit: EditText
    private lateinit var stepEdit: EditText
    private lateinit var whereEdit: EditText
    private lateinit var notesEdit: EditText
    private lateinit var linkEdit: EditText
    private lateinit var groupEdit: EditText
    private lateinit var linkOpen: ImageView
    private lateinit var whenValue: TextView
    private lateinit var whenClear: ImageView
    private lateinit var dueValue: TextView
    private lateinit var dueClear: ImageView
    private lateinit var estimateValue: TextView
    private lateinit var estimateClear: ImageView
    private lateinit var links: LinearLayout
    private lateinit var blocks: TaskBlocks

    private var token: String? = null
    private var taskId = -1
    private var task: BoardTask? = null
    /** Fields edited but not saved yet (API field name → value). */
    private var pending = JSONObject()
    /** Set while fill() writes the fields, so that isn't taken as an edit. */
    private var filling = false
    /** Edited since opening: a late network refresh must not overwrite it. */
    private var edited = false
    /** Notes unfolded by "See more" (or by starting to edit them). */
    private var notesExpanded = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task)
        token = TokenStore.get(this)
        taskId = intent.getIntExtra(EXTRA_TASK_ID, -1)
        if (token.isNullOrBlank() || taskId <= 0) {
            finish()
            return
        }

        status = findViewById(R.id.task_status)
        titleEdit = findViewById(R.id.task_title_edit)
        stepEdit = findViewById(R.id.task_step_edit)
        whereEdit = findViewById(R.id.task_where_edit)
        notesEdit = findViewById(R.id.task_notes_edit)
        linkEdit = findViewById(R.id.task_link_edit)
        groupEdit = findViewById(R.id.task_group_edit)
        linkOpen = findViewById(R.id.task_link_open)
        whenValue = findViewById(R.id.task_when_value)
        whenClear = findViewById(R.id.task_when_clear)
        dueValue = findViewById(R.id.task_due_value)
        dueClear = findViewById(R.id.task_due_clear)
        estimateValue = findViewById(R.id.task_estimate_value)
        estimateClear = findViewById(R.id.task_estimate_clear)
        links = findViewById(R.id.task_links)
        blocks = TaskBlocks(this, findViewById(R.id.task_blocks)) { list -> queue("blocks", list) }

        findViewById<View>(R.id.task_back).onTap { finish() }
        findViewById<View>(R.id.task_notes_more).onTap { expandNotes() }
        notesEdit.setOnFocusChangeListener { _, focused -> if (focused) expandNotes() }
        titleEdit.onEdit { text ->
            if (text.isBlank()) {
                pending.remove("title")
                status.setText(R.string.detail_title_empty)
            } else {
                queue("title", text)
            }
        }
        stepEdit.onEdit { queue("first_step", it) }
        whereEdit.onEdit { queue("plan_where", it) }
        groupEdit.onEdit { queue("group", it) }
        linkEdit.onEdit { typed ->
            val link = normalizeLink(typed)
            linkOpen.visibility = if (link.url != null) View.VISIBLE else View.GONE
            if (link.ok) queue("url", link.url ?: JSONObject.NULL) else status.setText(R.string.link_invalid)
        }
        linkOpen.onTap { normalizeLink(linkEdit.text.toString()).url?.let { openLink(this, it) } }
        notesEdit.onEdit {
            queue("detail", it)
            showLinks(it)
        }
        findViewById<View>(R.id.task_when_row).onTap {
            pickDateTime(this, parseIso(task?.planAt)) { picked ->
                showTime(whenValue, whenClear, toIso(picked.millis))
                queue("plan_at", picked.local)
                saveNow()
            }
        }
        whenClear.onTap {
            showTime(whenValue, whenClear, null)
            queue("plan_at", JSONObject.NULL)
            saveNow()
        }
        findViewById<View>(R.id.task_due_row).onTap {
            pickDateTime(this, parseIso(task?.dueAt)) { picked ->
                showTime(dueValue, dueClear, toIso(picked.millis))
                queue("due_at", picked.local)
                saveNow()
            }
        }
        dueClear.onTap {
            showTime(dueValue, dueClear, null)
            queue("due_at", JSONObject.NULL)
            saveNow()
        }
        findViewById<View>(R.id.task_estimate_row).onTap { pickEstimate() }
        estimateClear.onTap { setEstimate(null) }
        findViewById<View>(R.id.task_done).onTap { onDoneOrUndo() }
        findViewById<View>(R.id.task_later).onTap { onLater() }
        findViewById<View>(R.id.task_do_now).onTap {
            task?.let { current -> runAndClose { t -> ApiClient.focusTask(t, current.id) } }
        }

        // Open instantly with the task the list already had, then refresh.
        intent.getStringExtra(EXTRA_TASK_JSON)?.let { json ->
            try {
                parseTask(JSONObject(json))
            } catch (_: Exception) {
                null
            }
        }?.let {
            task = it
            fill(it)
        }
        load()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saveSoon)
        saveNow()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- Load and show -------------------------------------------------------

    private fun load() {
        val t = token ?: return
        scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { ApiClient.getTask(t, taskId) }
            } catch (_: Exception) {
                null
            }
            if (loaded == null) {
                if (task == null) status.setText(R.string.detail_load_failed)
                return@launch
            }
            val shown = task
            task = loaded
            // Refill only if the server has something newer and nothing was
            // typed yet; otherwise the user's edits win (they're saving).
            if (shown == null || (loaded != shown && !edited)) fill(loaded)
        }
    }

    private fun fill(t: BoardTask) {
        filling = true
        titleEdit.setText(t.title)
        stepEdit.setText(t.firstStep.orEmpty())
        whereEdit.setText(t.planWhere.orEmpty())
        linkEdit.setText(t.url.orEmpty())
        groupEdit.setText(t.group.orEmpty())
        linkOpen.visibility = if (t.url != null) View.VISIBLE else View.GONE
        notesEdit.setText(t.detail.orEmpty())
        filling = false
        showTime(whenValue, whenClear, t.planAt)
        showTime(dueValue, dueClear, t.dueAt)
        // The deadline in its urgency colour (warmer as it nears, never red).
        dueValue.setTextColor(getColor(Urgency.of(t)?.color ?: R.color.mb_on_surface))
        showEstimate(t.estimateMin)
        showLinks(t.detail.orEmpty())
        blocks.show(t.blocks)
        foldNotes()
        val done = t.status == "done"
        findViewById<TextView>(R.id.task_done).setText(if (done) R.string.undo else R.string.done)
        findViewById<View>(R.id.task_later).visibility = if (done) View.GONE else View.VISIBLE
        val isFocus = BoardCache.load(this)?.focus?.id == t.id
        findViewById<View>(R.id.task_do_now).visibility = if (done || isFocus) View.GONE else View.VISIBLE

        if (intent.getBooleanExtra(EXTRA_SHRINK, false) && !done) {
            findViewById<View>(R.id.task_shrink_hint).visibility = View.VISIBLE
            stepEdit.requestFocus()
            stepEdit.setSelection(stepEdit.text.length)
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    private fun showTime(value: TextView, clear: ImageView, iso: String?) {
        val text = formatWhen(iso)
        value.text = if (text.isEmpty()) getString(R.string.detail_none) else text
        value.alpha = if (text.isEmpty()) 0.6f else 1f
        clear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEstimate(min: Int?) {
        estimateValue.text = min?.let { formatMinutes(it) } ?: getString(R.string.detail_none)
        estimateValue.alpha = if (min == null) 0.6f else 1f
        estimateValue.setTextColor(getColor(min?.let { TaskSize.of(it).color } ?: R.color.mb_on_surface))
        estimateClear.visibility = if (min == null) View.GONE else View.VISIBLE
    }

    /** A few common lengths in the bottom sheet; saved at once. */
    private fun pickEstimate() {
        val sheet = ActionSheet(this, getString(R.string.estimate_title), task?.title)
        for (min in ESTIMATES) {
            sheet.add(R.drawable.mb_ic_timer24, formatMinutes(min)) { setEstimate(min) }
        }
        sheet.show()
    }

    private fun setEstimate(min: Int?) {
        showEstimate(min)
        queue("estimate_min", min ?: JSONObject.NULL)
        saveNow()
    }

    /** Long notes start folded to [NOTES_FOLDED_LINES] lines with "See more"
     *  (issue #13); short ones show whole. */
    private fun foldNotes() {
        if (notesExpanded) return
        notesEdit.post {
            if (notesEdit.lineCount > NOTES_FOLDED_LINES) {
                notesEdit.maxLines = NOTES_FOLDED_LINES
                findViewById<View>(R.id.task_notes_more).visibility = View.VISIBLE
            }
        }
    }

    private fun expandNotes() {
        notesExpanded = true
        notesEdit.maxLines = Int.MAX_VALUE
        findViewById<View>(R.id.task_notes_more).visibility = View.GONE
    }

    /** One tappable row per link found in the notes (up to 5). */
    private fun showLinks(notes: String) {
        links.removeAllViews()
        val matcher = Patterns.WEB_URL.matcher(notes)
        val found = LinkedHashSet<String>()
        while (matcher.find() && found.size < 5) found.add(matcher.group())
        findViewById<View>(R.id.task_links_label).visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
        for (url in found) {
            val row = layoutInflater.inflate(R.layout.action_sheet_row, links, false)
            row.findViewById<ImageView>(R.id.sheet_icon).setImageResource(R.drawable.mb_ic_link24)
            row.findViewById<TextView>(R.id.sheet_label).apply {
                text = url.removePrefix("https://").removePrefix("http://")
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.mb_accent))
            }
            row.onTap { openLink(this, url) }
            links.addView(row)
        }
    }

    // --- Saving ----------------------------------------------------------------

    private fun queue(field: String, value: Any) {
        edited = true
        pending.put(field, value)
        handler.removeCallbacks(saveSoon)
        handler.postDelayed(saveSoon, SAVE_DELAY_MS)
    }

    private fun saveNow() {
        handler.removeCallbacks(saveSoon)
        val t = token ?: return
        if (pending.length() == 0) return
        val fields = pending
        pending = JSONObject()
        status.setText(R.string.detail_saving)
        // Not tied to the page: a save started on the way out still finishes.
        CoroutineScope(Dispatchers.IO).launch {
            val ok = try {
                ApiClient.updateTask(t, taskId, fields)
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    status.setText(R.string.detail_saved)
                    WidgetUpdater.requestRefresh(applicationContext)
                } else {
                    // Keep the edits for the next try, unless edited again since.
                    fields.keys().forEach { key -> if (!pending.has(key)) pending.put(key, fields.get(key)) }
                    status.setText(R.string.detail_not_saved)
                }
            }
        }
    }

    // --- Done / Later ------------------------------------------------------------

    private fun onDoneOrUndo() {
        val current = task ?: return
        saveNow()
        val done = current.status == "done"
        if (!done) PlanReminderReceiver.cancel(this, current.id)
        runAndClose { t ->
            if (done) ApiClient.reopenTask(t, current.id) else ApiClient.completeTask(t, current.id)
        }
    }

    private fun onLater() {
        val current = task ?: return
        saveNow()
        val sheet = ActionSheet(this, getString(R.string.later_title), current.title)
        for (choice in LaterChoice.offered()) {
            sheet.add(R.drawable.mb_ic_clock24, getString(choice.chip), clockTime(choice.at())) {
                val until = choice.at()
                PlanReminderReceiver.cancel(this, current.id)
                runAndClose { t -> ApiClient.snoozeTask(t, current.id, until) }
            }
        }
        sheet.show()
    }

    /** Runs a status change, refreshes the widget and goes back to the list. */
    private fun runAndClose(call: (String) -> Boolean) {
        val t = token ?: return
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { call(t) }
            } catch (_: Exception) {
                false
            }
            if (ok) {
                WidgetUpdater.requestRefresh(applicationContext)
                finish()
            } else {
                Toast.makeText(this@TaskActivity, R.string.action_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Calls [onChange] with the new text on every user edit. */
    private fun EditText.onEdit(onChange: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!filling) onChange(s?.toString().orEmpty())
            }
        })
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SHRINK = "shrink"
        const val EXTRA_TASK_JSON = "task_json"
        private const val SAVE_DELAY_MS = 700L
        private const val NOTES_FOLDED_LINES = 4
        private val ESTIMATES = listOf(5, 10, 15, 25, 45, 60, 90, 120)

        /** Opens [task]; the page shows it at once and refreshes behind. */
        fun intent(context: Context, task: BoardTask, shrink: Boolean = false): Intent =
            Intent(context, TaskActivity::class.java)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_TASK_JSON, task.toJson().toString())
                .putExtra(EXTRA_SHRINK, shrink)
    }
}
