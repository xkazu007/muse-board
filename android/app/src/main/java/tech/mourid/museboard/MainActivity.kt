package tech.mourid.museboard

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The app screen: the same focus card as the widget, then the queue in
 * three short sections (Then / Later / Done today).
 *
 * Native gestures: tick the round checkbox or swipe a card right for Done,
 * swipe left for Later (asks when), long-press for a menu; haptics on each.
 * Every Done gets an Undo bar for a few seconds. The widget and icon
 * shortcuts are refreshed after every change.
 *
 * Feels instant: the last board and task list paint from the phone's cache
 * at once and the network refreshes behind them (both calls in parallel);
 * the screen redraws only when the data changed; Done / Later / Undo
 * update the screen first and the server catches up (like the widget).
 *
 * Framework Activity only (no AppCompat): the app carries no AndroidX.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tokenBox: View
    private lateinit var boardBox: View
    private lateinit var tokenInput: EditText
    private lateinit var tokenError: TextView
    private lateinit var dateView: TextView
    private lateinit var focusRow: SwipeRow
    private lateinit var focusMetaView: TextView
    private lateinit var focusStep: TextView
    private lateinit var focusTask: TextView
    private lateinit var focusCheck: CheckBox
    private lateinit var focusLater: TextView
    private lateinit var undoBar: View
    private lateinit var undoText: TextView
    private lateinit var undoButton: TextView
    private lateinit var gestureHint: TextView

    private var token: String? = null
    private var board: BoardResponse? = null
    private var tasks: List<BoardTask> = emptyList()
    /** Group filter chip (issue #12); null = All. */
    private var groupFilter: String? = null
    private lateinit var offlineNote: View
    /** Set while render() resets the checkbox, so that isn't taken as a tap. */
    private var rendering = false
    private val hideUndo = Runnable {
        undoBar.animate().alpha(0f).translationY(dp(16).toFloat()).setDuration(160)
            .withEndAction { undoBar.visibility = View.GONE }.start()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenBox = findViewById(R.id.token_box)
        boardBox = findViewById(R.id.board_box)
        tokenInput = findViewById(R.id.token_input)
        tokenError = findViewById(R.id.token_error)
        dateView = findViewById(R.id.date)
        focusRow = findViewById(R.id.focus_row)
        focusMetaView = findViewById(R.id.focus_meta)
        focusStep = findViewById(R.id.focus_step)
        focusTask = findViewById(R.id.focus_task)
        focusCheck = findViewById(R.id.focus_check)
        focusLater = findViewById(R.id.focus_later)
        undoBar = findViewById(R.id.undo_bar)
        undoText = findViewById(R.id.undo_text)
        undoButton = findViewById(R.id.undo_button)
        gestureHint = findViewById(R.id.gesture_hint)
        offlineNote = findViewById(R.id.offline_note)

        styleSwipe(focusRow, resources.getDimension(R.dimen.mb_app_card_radius))
        focusCheck.setOnCheckedChangeListener { _, checked ->
            if (!checked || rendering) return@setOnCheckedChangeListener
            val focus = board?.focus ?: return@setOnCheckedChangeListener
            haptic(focusCheck, confirm = true)
            // Let the tick animation play before the card changes.
            focusCheck.postDelayed({ complete(focus) }, TICK_MS)
        }
        focusLater.onTap { board?.focus?.let { askLater(it, focusRow) } }
        findViewById<View>(R.id.focus_card).setOnLongClickListener {
            board?.focus?.let { showMenu(it) }
            true
        }
        findViewById<View>(R.id.focus_card).onTap { board?.focus?.let { openTask(it) } }
        findViewById<View>(R.id.focus_too_big).onTap {
            board?.focus?.let { openTask(it, shrink = true) }
        }
        findViewById<View>(R.id.focus_open).onTap {
            board?.focus?.url?.let { openLink(this, it) }
        }

        findViewById<Button>(R.id.token_save).onTap { saveToken() }
        findViewById<View>(R.id.btn_calendar).onTap {
            startActivity(Intent(this, CalendarActivity::class.java))
        }
        findViewById<View>(R.id.btn_more).onTap { showMoreMenu() }
        findViewById<Button>(R.id.btn_add_widget).onTap { requestPinWidget() }
        findViewById<View>(R.id.btn_add_task).onTap { showQuickAdd() }

        token = TokenStore.get(this)
        if (token.isNullOrBlank()) {
            showTokenBox()
        } else {
            showBoard()
            showCached()
        }
    }

    override fun onResume() {
        super.onResume()
        // Fresh every time: the widget, a reminder or a shortcut may have
        // changed the board while the app was in the background.
        if (!token.isNullOrBlank()) loadAll()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** ⋮ in the header: the less used things, in the bottom sheet. */
    private fun showMoreMenu() {
        val mode = AppTheme.get(this)
        val sheet = ActionSheet(this, getString(R.string.app_name))
        sheet.add(R.drawable.mb_ic_history24, getString(R.string.history)) {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        sheet.add(AppTheme.icon(mode), getString(AppTheme.label(mode))) { AppTheme.cycle(this) }
        if (!hasWidget()) sheet.add(R.drawable.mb_ic_widget24, getString(R.string.add_widget)) { requestPinWidget() }
        sheet.add(R.drawable.mb_ic_refresh24, getString(R.string.refresh)) { loadAll() }
        if (!token.isNullOrBlank()) {
            sheet.add(R.drawable.mb_ic_key24, getString(R.string.change_token)) {
                TokenStore.clear(this)
                token = null
                showTokenBox()
                WidgetUpdater.requestRefresh(this)
            }
        }
        sheet.show()
    }

    // --- Loading and rendering -----------------------------------------------

    /** The last board and task list, straight from the cache: no wait. */
    private fun showCached() {
        val b = BoardCache.load(this) ?: return
        val ts = BoardCache.loadTasks(this) ?: return
        show(b, ts)
    }

    /** Board and task list from the API, fetched in parallel, cached, and
     *  shown if they changed. Offline: the cached view stays, with a note. */
    private fun loadAll() {
        val t = token ?: return
        scope.launch {
            val fetched = try {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val b = async { ApiClient.getBoardJson(t) }
                        val ts = async { ApiClient.getTasksJson(t) }
                        b.await() to ts.await()
                    }
                }
            } catch (_: UnauthorizedException) {
                onTokenRejected()
                return@launch
            } catch (_: Exception) {
                null
            }
            val (boardJson, tasksJson) = fetched ?: (null to null)
            if (boardJson == null || tasksJson == null) {
                if (board != null) {
                    offlineNote.visibility = View.VISIBLE
                } else {
                    Toast.makeText(this@MainActivity, R.string.board_unreachable, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            offlineNote.visibility = View.GONE
            BoardCache.save(this@MainActivity, boardJson)
            BoardCache.saveTasks(this@MainActivity, tasksJson)
            show(parseBoard(JSONObject(boardJson)), parseTasks(tasksJson))
        }
    }

    /** Draws [b] and [ts] unless they are what's already on screen: a
     *  refresh with no changes never rebuilds the list (no flash). */
    private fun show(b: BoardResponse, ts: List<BoardTask>) {
        if (b == board && ts == tasks && boardBox.tag == RENDERED) return
        board = b
        tasks = ts
        boardBox.tag = RENDERED
        render(b, ts)
    }

    /** Applies [change] to what's on screen right away (optimistic), before
     *  the API call; the next loadAll() brings the real state. */
    private fun optimistic(change: (BoardResponse, List<BoardTask>) -> Pair<BoardResponse, List<BoardTask>>) {
        val b = board ?: return
        val (nb, nts) = change(b, tasks)
        show(nb, nts)
    }

    private fun render(b: BoardResponse, tasks: List<BoardTask>) {
        dateView.text = shortDate(b.date)
        findViewById<View>(R.id.btn_add_widget).visibility = if (hasWidget()) View.GONE else View.VISIBLE

        val focus = b.focus
        rendering = true
        focusCheck.isChecked = false
        rendering = false
        focusRow.reset()
        if (focus != null) {
            focusMetaView.text = planLine(focus)
            findViewById<TextView>(R.id.focus_due).showDueChip(focus)
            findViewById<TextView>(R.id.focus_estimate).showEstimateChip(focus)
            val progress = checkProgress(focus.blocks)
            findViewById<TextView>(R.id.focus_progress).text =
                progress?.let { getString(R.string.progress, it.first, it.second) }.orEmpty()
            findViewById<View>(R.id.focus_bottom).visibility =
                if (progress != null || focus.estimateMin != null) View.VISIBLE else View.GONE
            focusStep.text = focus.firstStep ?: focus.title
            focusTask.text = if (focus.firstStep != null) focus.title else focus.detail.orEmpty()
            focusCheck.visibility = View.VISIBLE
            focusLater.visibility = View.VISIBLE
            findViewById<View>(R.id.focus_too_big).visibility = if (tooBig(focus)) View.VISIBLE else View.GONE
            findViewById<View>(R.id.focus_open).visibility = if (focus.url != null) View.VISIBLE else View.GONE
            focusRow.onSwipeRight = { complete(focus) }
            focusRow.onSwipeLeft = { askLater(focus, focusRow) }
        } else {
            focusMetaView.text = ""
            findViewById<View>(R.id.focus_due).visibility = View.GONE
            findViewById<View>(R.id.focus_bottom).visibility = View.GONE
            focusStep.setText(R.string.all_clear)
            val comingBack = b.plans.firstOrNull()
            focusTask.text = if (comingBack != null) {
                getString(R.string.next_plan, thenLine(comingBack))
            } else {
                getString(R.string.all_clear_detail)
            }
            focusCheck.visibility = View.GONE
            focusLater.visibility = View.GONE
            findViewById<View>(R.id.focus_too_big).visibility = View.GONE
            findViewById<View>(R.id.focus_open).visibility = View.GONE
            focusRow.onSwipeRight = null
            focusRow.onSwipeLeft = null
        }
        gestureHint.visibility = if (focus != null && !hintSeen()) View.VISIBLE else View.GONE
        showSuggestion(b.suggestion)

        val now = System.currentTimeMillis()
        val open = tasks.filter { it.status == "open" }
        val snoozed = { t: BoardTask -> (parseIso(t.snoozedUntil) ?: 0L) > now }
        // Groups (issue #12), in queue order. The filter only shows with 2+.
        val groups = open.sortedBy { it.position }.mapNotNull { it.group }.distinct()
        if (groupFilter != null && groupFilter !in groups) groupFilter = null
        showGroupFilter(groups)
        val inFilter = { t: BoardTask -> groupFilter == null || t.group == groupFilter }
        fill(
            R.id.list_then, R.id.label_then, Kind.OPEN,
            open.filter { !snoozed(it) && it.id != focus?.id && inFilter(it) }.sortedBy { it.position },
            grouped = groups.isNotEmpty() && groupFilter == null,
        )
        fill(
            R.id.list_later, R.id.label_later, Kind.LATER,
            open.filter { snoozed(it) && inFilter(it) }.sortedBy { parseIso(it.snoozedUntil) },
        )
        fill(
            R.id.list_done, R.id.label_done, Kind.DONE,
            tasks.filter { it.status == "done" && isToday(it.completedAt) }
                .sortedByDescending { parseIso(it.completedAt) },
        )
        fillWeek(tasks)
    }

    /**
     * Weekly recap (issue #8): what got done earlier this week (Monday on),
     * grouped by day, under a neutral count. No streaks, nothing about what
     * wasn't done. Hidden until there is an earlier day to show.
     */
    private fun fillWeek(tasks: List<BoardTask>) {
        val list = findViewById<LinearLayout>(R.id.list_week)
        val label = findViewById<TextView>(R.id.label_week)
        list.removeAllViews()
        val today = dayOf(System.currentTimeMillis())
        val weekStart = weekStartDay()
        val thisWeek = tasks.filter { it.status == "done" }
            .mapNotNull { t -> parseIso(t.completedAt)?.let { t to dayOf(it) } }
            .filter { (_, day) -> day >= weekStart }
        val earlier = thisWeek.filter { (_, day) -> day < today }
        if (earlier.isEmpty()) {
            label.visibility = View.GONE
            return
        }
        label.text = getString(R.string.week_label, thisWeek.size)
        label.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        // Newest day first; each day's label comes from its first task.
        for ((_, items) in earlier.groupBy { it.second }.toSortedMap(compareByDescending { it })) {
            list.addView(subTitle(dayLabel(items.first().first.completedAt), first = list.childCount == 0))
            val card = newListCard(list)
            for ((task, _) in items.sortedBy { parseIso(it.first.completedAt) }) {
                val row = inflater.inflate(R.layout.item_task, card, false) as SwipeRow
                row.findViewById<TextView>(R.id.task_title).apply {
                    text = task.title
                    alpha = 0.7f
                }
                row.findViewById<View>(R.id.task_row).onTap { openTask(task) }
                addCardRow(card, row)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** A group or day name above its card: sentence case, muted. */
    private fun subTitle(text: String, first: Boolean) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.mb_muted))
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(if (first) 0 else 16), 0, dp(8))
    }

    /** Muse's suggestion card: what it proposes and what applying changes.
     *  Apply / Not now hide it at once; the server catches up. */
    private fun showSuggestion(s: Suggestion?) {
        val card = findViewById<View>(R.id.suggestion_card)
        if (s == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        findViewById<TextView>(R.id.suggestion_title).text = s.title
        findViewById<TextView>(R.id.suggestion_detail).apply {
            text = s.detail
            visibility = if (s.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.suggestion_lines).apply {
            text = s.lines.joinToString("\n")
            visibility = if (s.lines.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.suggestion_apply).onTap { resolveSuggestion(s, apply = true) }
        findViewById<View>(R.id.suggestion_dismiss).onTap { resolveSuggestion(s, apply = false) }
    }

    private fun resolveSuggestion(s: Suggestion, apply: Boolean) {
        optimistic { b, ts -> b.copy(suggestion = null) to ts }
        showUndoBar(getString(if (apply) R.string.suggestion_applied else R.string.suggestion_dismissed), null)
        runAction { t -> if (apply) ApiClient.applySuggestion(t, s.id) else ApiClient.dismissSuggestion(t, s.id) }
    }

    /** "Too big?" (issue #7): Later used twice since the step last changed. */
    private fun tooBig(task: BoardTask): Boolean = task.status == "open" && task.snoozeCount >= 2

    private fun openTask(task: BoardTask, shrink: Boolean = false) {
        startActivity(TaskActivity.intent(this, task, shrink))
    }

    private enum class Kind { OPEN, LATER, DONE }

    /** All / one chip per group; tapping one filters Then and Later. */
    private fun showGroupFilter(groups: List<String>) {
        val bar = findViewById<View>(R.id.group_filter)
        val chips = findViewById<LinearLayout>(R.id.group_chips)
        chips.removeAllViews()
        if (groups.size < 2) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        for (name in listOf<String?>(null) + groups) {
            val selected = name == groupFilter
            chips.addView(TextView(this).apply {
                text = name ?: getString(R.string.group_all)
                gravity = Gravity.CENTER
                textSize = 14f
                // Background first: the inset drawable's own padding would
                // otherwise replace ours.
                background = getDrawable(if (selected) R.drawable.mb_chip_selected_inset else R.drawable.mb_chip_tonal_inset)
                setPadding(dp(16), 0, dp(16), 0)
                setTextColor(getColor(if (selected) R.color.mb_on_primary else R.color.mb_on_surface))
                pressable()
                onTap {
                    groupFilter = name
                    board?.let { render(it, tasks) }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(6) })
        }
    }

    private fun fill(listId: Int, labelId: Int, kind: Kind, tasks: List<BoardTask>, grouped: Boolean = false) {
        val list = findViewById<LinearLayout>(listId)
        list.removeAllViews()
        findViewById<View>(labelId).visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        // Grouped: each group under its heading, groups in queue order,
        // ungrouped tasks last under "Tasks".
        val ordered = if (grouped) {
            tasks.groupBy { it.group }.entries.sortedWith(compareBy({ it.key == null }, { e -> e.value.minOf { it.position } }))
                .flatMap { it.value }
        } else {
            tasks
        }
        // One card per group (or one for the whole section), rows inside
        // with thin dividers, like the Clock app's alarm list.
        var lastGroup: String? = null
        var card: LinearLayout? = null
        for (task in ordered) {
            if (card == null || (grouped && task.group != lastGroup)) {
                if (grouped) list.addView(subTitle(task.group ?: getString(R.string.group_none), first = card == null))
                card = newListCard(list)
                lastGroup = task.group
            }
            val row = inflater.inflate(R.layout.item_task, card, false) as SwipeRow
            styleSwipe(row, 0f)
            val title = row.findViewById<TextView>(R.id.task_title)
            title.text = task.title
            val detail = task.firstStep?.let { getString(R.string.start_with, it) } ?: task.detail
            row.findViewById<TextView>(R.id.task_detail).apply {
                text = detail
                visibility = if (detail.isNullOrEmpty() || kind == Kind.DONE) View.GONE else View.VISIBLE
            }
            row.findViewById<TextView>(R.id.task_when).showRowWhen(task, when (kind) {
                Kind.OPEN -> formatWhen(task.planAt ?: task.dueAt)
                Kind.LATER -> getString(R.string.back_at, formatWhen(task.snoozedUntil))
                Kind.DONE -> ""
            })
            row.findViewById<TextView>(R.id.task_estimate).apply {
                if (kind == Kind.DONE) visibility = View.GONE else showEstimateChip(task)
            }
            row.findViewById<TextView>(R.id.task_progress).apply {
                val progress = if (kind == Kind.DONE) null else checkProgress(task.blocks)
                visibility = if (progress == null) View.GONE else View.VISIBLE
                text = progress?.let { getString(R.string.progress, it.first, it.second) }
            }
            row.findViewById<View>(R.id.task_too_big).apply {
                visibility = if (tooBig(task)) View.VISIBLE else View.GONE
                onTap { openTask(task, shrink = true) }
            }
            row.findViewById<View>(R.id.task_open).apply {
                val url = task.url
                visibility = if (url != null && kind != Kind.DONE) View.VISIBLE else View.GONE
                onTap { url?.let { openLink(this@MainActivity, it) } }
            }
            row.findViewById<View>(R.id.task_row).onTap { openTask(task) }
            if (kind == Kind.DONE) {
                title.alpha = 0.55f
                row.findViewById<TextView>(R.id.swipe_label_start).setText(R.string.undo)
                row.onSwipeRight = { reopen(task) }
            } else {
                row.onSwipeRight = { complete(task) }
                row.onSwipeLeft = { askLater(task, row) }
            }
            row.findViewById<View>(R.id.task_row).setOnLongClickListener {
                showMenu(task)
                true
            }
            addCardRow(card, row)
        }
    }

    /** Swipe colours; rows in a card get radius 0 (the card rounds them). */
    private fun styleSwipe(row: SwipeRow, radius: Float) {
        row.rightColor = getColor(R.color.mb_primary)
        row.leftColor = getColor(R.color.mb_inset)
        row.cornerRadius = radius
    }

    // --- Actions -------------------------------------------------------------

    private fun complete(task: BoardTask) {
        markHintSeen()
        PlanReminderReceiver.cancel(this, task.id)
        val count = (board?.doneToday ?: 0) + 1
        val now = toIso(System.currentTimeMillis())
        optimistic { b, ts ->
            val nts = ts.map { if (it.id == task.id) it.copy(status = "done", completedAt = now) else it }
            val nb = if (b.focus?.id == task.id) {
                b.copy(focus = b.next, next = null, upcoming = b.upcoming.filter { it.id != b.next?.id }, doneToday = count)
            } else {
                b.copy(doneToday = count)
            }
            nb to nts
        }
        showUndoBar(getString(R.string.done_feedback, count), task)
        runAction { t -> ApiClient.completeTask(t, task.id) }
    }

    private fun reopen(task: BoardTask) {
        optimistic { b, ts ->
            val wasToday = isToday(task.completedAt)
            val nb = b.copy(doneToday = if (wasToday) maxOf(0, b.doneToday - 1) else b.doneToday)
            nb to ts.map { if (it.id == task.id) it.copy(status = "open", completedAt = null) else it }
        }
        showUndoBar(getString(R.string.undone), null)
        runAction { t -> ApiClient.reopenTask(t, task.id) }
    }

    /** Quick-add (issue #6): the task lands at the end of the queue. */
    private fun showQuickAdd() {
        val t = token ?: return
        QuickAddSheet(this) { fields, onResult ->
            scope.launch {
                val ok = try {
                    withContext(Dispatchers.IO) { ApiClient.createTask(t, fields) }
                } catch (_: UnauthorizedException) {
                    onTokenRejected()
                    return@launch
                } catch (_: Exception) {
                    false
                }
                onResult(ok)
                if (ok) {
                    showUndoBar(getString(R.string.added), null)
                    loadAll()
                    WidgetUpdater.requestRefresh(this@MainActivity)
                }
            }
        }.show()
    }

    /** "Later" asks when, in the bottom sheet; cancelling puts a swiped
     *  card back. */
    private fun askLater(task: BoardTask, row: SwipeRow?) {
        markHintSeen()
        val sheet = ActionSheet(this, getString(R.string.later_title), task.title)
        addLaterRows(sheet, task)
        sheet.onCancel { row?.reset() }.show()
    }

    private fun addLaterRows(sheet: ActionSheet, task: BoardTask) {
        for (choice in LaterChoice.offered()) {
            sheet.add(R.drawable.mb_ic_clock24, getString(choice.chip), clockTime(choice.at())) {
                snooze(task, choice)
            }
        }
    }

    private fun snooze(task: BoardTask, choice: LaterChoice) {
        val until = choice.at()
        val untilIso = toIso(until)
        PlanReminderReceiver.cancel(this, task.id)
        optimistic { b, ts ->
            val nts = ts.map {
                if (it.id == task.id) it.copy(snoozedUntil = untilIso, planAt = untilIso, snoozeCount = it.snoozeCount + 1) else it
            }
            val nb = if (b.focus?.id == task.id) {
                b.copy(focus = b.next, next = null, upcoming = b.upcoming.filter { it.id != b.next?.id })
            } else {
                b
            }
            nb to nts
        }
        showUndoBar(getString(R.string.later_feedback, formatWhen(untilIso)), null)
        runAction { t -> ApiClient.snoozeTask(t, task.id, until) }
    }

    /** Long-press: the same actions as the gestures, in the bottom sheet. */
    private fun showMenu(task: BoardTask) {
        val sheet = ActionSheet(
            this,
            task.firstStep ?: task.title,
            if (task.firstStep != null) task.title else null,
        )
        if (task.status == "done") {
            sheet.add(R.drawable.mb_ic_undo24, getString(R.string.undo)) { reopen(task) }
        } else {
            sheet.add(R.drawable.mb_ic_done24, getString(R.string.done_label)) { complete(task) }
            addLaterRows(sheet, task)
        }
        sheet.show()
    }

    /** Calls the API (the screen already shows the result), then reloads
     *  the real state and refreshes the widget, icon shortcuts and reminders.
     *  A failure says so and the reload puts things back. */
    private fun runAction(call: (String) -> Boolean) {
        val t = token ?: return
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { call(t) }
            } catch (_: UnauthorizedException) {
                onTokenRejected()
                return@launch
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                undoBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, R.string.action_failed, Toast.LENGTH_SHORT).show()
            }
            loadAll()
            WidgetUpdater.requestRefresh(this@MainActivity)
        }
    }

    /** "✓ Done. 2 today. [Undo]" floating at the bottom for a few seconds. */
    private fun showUndoBar(text: String, undoTask: BoardTask?) {
        undoBar.removeCallbacks(hideUndo)
        undoText.text = text
        if (undoTask != null) {
            undoButton.visibility = View.VISIBLE
            undoButton.onTap {
                undoBar.visibility = View.GONE
                reopen(undoTask)
            }
        } else {
            undoButton.visibility = View.GONE
        }
        // Slides up into place like a snackbar (only when it wasn't showing).
        if (undoBar.visibility != View.VISIBLE) {
            undoBar.alpha = 0f
            undoBar.translationY = dp(16).toFloat()
            undoBar.visibility = View.VISIBLE
        }
        undoBar.animate().cancel()
        undoBar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        undoBar.postDelayed(hideUndo, UNDO_MS)
    }

    private fun haptic(view: View, confirm: Boolean) {
        val constant = if (confirm && Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    private fun hintSeen(): Boolean =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean("gesture_hint_seen", false)

    /** The gesture hint shows until the first swipe, tick or Later. */
    private fun markHintSeen() {
        if (hintSeen()) return
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean("gesture_hint_seen", true).apply()
        gestureHint.visibility = View.GONE
    }

    // --- Sign-in, widget pin, permissions --------------------------------------

    private fun showTokenBox() {
        tokenBox.visibility = View.VISIBLE
        boardBox.visibility = View.GONE
        findViewById<View>(R.id.btn_add_task).visibility = View.GONE
    }

    private fun showBoard() {
        tokenBox.visibility = View.GONE
        boardBox.visibility = View.VISIBLE
        findViewById<View>(R.id.btn_add_task).visibility = View.VISIBLE
        askForNotificationsOnce()
    }

    private fun saveToken() {
        val value = tokenInput.text.toString().trim()
        if (value.isEmpty()) {
            tokenError.visibility = View.VISIBLE
            tokenError.text = getString(R.string.token_empty)
            return
        }
        token = value
        TokenStore.set(this, value)
        tokenError.visibility = View.GONE
        showBoard()
        loadAll()
        WidgetUpdater.requestRefresh(this)
    }

    /** The saved token was refused by the API: forget it and ask again. */
    private fun onTokenRejected() {
        TokenStore.clear(this)
        token = null
        tokenError.text = getString(R.string.token_invalid)
        tokenError.visibility = View.VISIBLE
        showTokenBox()
        WidgetUpdater.requestRefresh(this)
    }

    /** Pin the widget from inside the app. requestPinAppWidget only opens
     *  the launcher's confirmation dialog; the widget is placed once the
     *  user taps Add there. */
    private fun requestPinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        val provider = ComponentName(this, MuseBoardWidget::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            val shown = manager.requestPinAppWidget(provider, null, null)
            Toast.makeText(
                this,
                getString(if (shown) R.string.widget_pin_ok else R.string.widget_pin_failed),
                if (shown) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(this, R.string.widget_pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    private fun hasWidget(): Boolean = AppWidgetManager.getInstance(this)
        .getAppWidgetIds(ComponentName(this, MuseBoardWidget::class.java))
        .isNotEmpty()

    /** Plan reminders need the notification permission on Android 13+.
     *  Asked once, after sign-in; a "no" is respected (reminders stay off). */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** "2026-10-28" → "Wed 28 Oct". */
    private fun shortDate(day: String): String = try {
        val utc = TimeZone.getTimeZone("UTC")
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.parse(day)
        SimpleDateFormat("EEEE d MMMM", Locale.UK).apply { timeZone = utc }.format(parsed!!)
    } catch (_: Exception) {
        day
    }

    private companion object {
        const val UI_PREFS = "muse_board_ui"
        const val RENDERED = "rendered"
        const val TICK_MS = 420L
        const val UNDO_MS = 6_000L
    }
}
