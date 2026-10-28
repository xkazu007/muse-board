package tech.mourid.museboard

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** Set while render() resets the checkbox, so that isn't taken as a tap. */
    private var rendering = false
    private val hideUndo = Runnable { undoBar.visibility = View.GONE }

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

        styleSwipe(focusRow, R.dimen.mb_app_card_radius)
        focusCheck.setOnCheckedChangeListener { _, checked ->
            if (!checked || rendering) return@setOnCheckedChangeListener
            val focus = board?.focus ?: return@setOnCheckedChangeListener
            haptic(focusCheck, confirm = true)
            // Let the tick animation play before the card changes.
            focusCheck.postDelayed({ complete(focus) }, TICK_MS)
        }
        focusLater.setOnClickListener { board?.focus?.let { askLater(it, focusRow) } }
        findViewById<View>(R.id.focus_card).setOnLongClickListener {
            board?.focus?.let { showMenu(it) }
            true
        }

        findViewById<Button>(R.id.token_save).setOnClickListener { saveToken() }
        findViewById<View>(R.id.btn_refresh).setOnClickListener { loadAll() }
        findViewById<View>(R.id.btn_change_token).setOnClickListener {
            TokenStore.clear(this)
            token = null
            showTokenBox()
            WidgetUpdater.requestRefresh(this)
        }
        findViewById<Button>(R.id.btn_add_widget).setOnClickListener { requestPinWidget() }

        token = TokenStore.get(this)
        if (token.isNullOrBlank()) showTokenBox() else showBoard()
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

    // --- Loading and rendering -----------------------------------------------

    private fun loadAll() {
        val t = token ?: return
        scope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) { ApiClient.getBoard(t) to ApiClient.getTasks(t) }
            } catch (_: UnauthorizedException) {
                onTokenRejected()
                return@launch
            } catch (_: Exception) {
                null
            }
            val (b, tasks) = loaded ?: (null to null)
            if (b == null || tasks == null) {
                Toast.makeText(this@MainActivity, R.string.board_unreachable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            board = b
            render(b, tasks)
        }
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
            focusMetaView.text = focusMeta(focus)
            focusStep.text = focus.firstStep ?: focus.title
            focusTask.text = if (focus.firstStep != null) focus.title else focus.detail.orEmpty()
            focusCheck.visibility = View.VISIBLE
            focusLater.visibility = View.VISIBLE
            focusRow.onSwipeRight = { complete(focus) }
            focusRow.onSwipeLeft = { askLater(focus, focusRow) }
        } else {
            focusMetaView.text = ""
            focusStep.setText(R.string.all_clear)
            val comingBack = b.plans.firstOrNull()
            focusTask.text = if (comingBack != null) {
                getString(R.string.next_plan, thenLine(comingBack))
            } else {
                getString(R.string.all_clear_detail)
            }
            focusCheck.visibility = View.GONE
            focusLater.visibility = View.GONE
            focusRow.onSwipeRight = null
            focusRow.onSwipeLeft = null
        }
        gestureHint.visibility = if (focus != null && !hintSeen()) View.VISIBLE else View.GONE

        val now = System.currentTimeMillis()
        val open = tasks.filter { it.status == "open" }
        val snoozed = { t: BoardTask -> (parseIso(t.snoozedUntil) ?: 0L) > now }
        fill(
            R.id.list_then, R.id.label_then, Kind.OPEN,
            open.filter { !snoozed(it) && it.id != focus?.id }.sortedBy { it.position },
        )
        fill(
            R.id.list_later, R.id.label_later, Kind.LATER,
            open.filter(snoozed).sortedBy { parseIso(it.snoozedUntil) },
        )
        fill(
            R.id.list_done, R.id.label_done, Kind.DONE,
            tasks.filter { it.status == "done" && isToday(it.completedAt) }
                .sortedByDescending { parseIso(it.completedAt) },
        )
    }

    private enum class Kind { OPEN, LATER, DONE }

    private fun fill(listId: Int, labelId: Int, kind: Kind, tasks: List<BoardTask>) {
        val list = findViewById<LinearLayout>(listId)
        list.removeAllViews()
        findViewById<View>(labelId).visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (task in tasks) {
            val row = inflater.inflate(R.layout.item_task, list, false) as SwipeRow
            styleSwipe(row, R.dimen.mb_app_row_radius)
            val title = row.findViewById<TextView>(R.id.task_title)
            title.text = task.title
            val detail = task.firstStep?.let { getString(R.string.start_with, it) } ?: task.detail
            row.findViewById<TextView>(R.id.task_detail).apply {
                text = detail
                visibility = if (detail.isNullOrEmpty() || kind == Kind.DONE) View.GONE else View.VISIBLE
            }
            row.findViewById<TextView>(R.id.task_when).text = when (kind) {
                Kind.OPEN -> formatWhen(task.planAt ?: task.dueAt)
                Kind.LATER -> getString(R.string.back_at, formatWhen(task.snoozedUntil))
                Kind.DONE -> ""
            }
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
            list.addView(row)
        }
    }

    private fun styleSwipe(row: SwipeRow, radius: Int) {
        row.rightColor = getColor(R.color.mb_primary)
        row.leftColor = getColor(R.color.mb_inset)
        row.cornerRadius = resources.getDimension(radius)
    }

    // --- Actions -------------------------------------------------------------

    private fun complete(task: BoardTask) {
        markHintSeen()
        PlanReminderReceiver.cancel(this, task.id)
        runAction({ t -> ApiClient.completeTask(t, task.id) }) {
            val count = (board?.doneToday ?: 0) + 1
            showUndoBar(getString(R.string.done_feedback, count), task)
        }
    }

    private fun reopen(task: BoardTask) {
        runAction({ t -> ApiClient.reopenTask(t, task.id) }) {
            showUndoBar(getString(R.string.undone), null)
        }
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
        PlanReminderReceiver.cancel(this, task.id)
        runAction({ t -> ApiClient.snoozeTask(t, task.id, until) }) {
            showUndoBar(getString(R.string.later_feedback, formatWhen(toIso(until))), null)
        }
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

    /** Calls the API, then reloads the app and refreshes the widget (and
     *  with it the icon shortcuts and reminders). */
    private fun runAction(call: (String) -> Boolean, onOk: () -> Unit) {
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
            if (ok) onOk() else Toast.makeText(this@MainActivity, R.string.action_failed, Toast.LENGTH_SHORT).show()
            loadAll()
            WidgetUpdater.requestRefresh(this@MainActivity)
        }
    }

    /** "✓ Done. 2 today. [Undo]" under the focus card for a few seconds. */
    private fun showUndoBar(text: String, undoTask: BoardTask?) {
        undoBar.removeCallbacks(hideUndo)
        undoText.text = text
        if (undoTask != null) {
            undoButton.visibility = View.VISIBLE
            undoButton.setOnClickListener {
                undoBar.visibility = View.GONE
                reopen(undoTask)
            }
        } else {
            undoButton.visibility = View.GONE
        }
        undoBar.visibility = View.VISIBLE
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
    }

    private fun showBoard() {
        tokenBox.visibility = View.GONE
        boardBox.visibility = View.VISIBLE
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
        SimpleDateFormat("EEE d MMM", Locale.UK).apply { timeZone = utc }.format(parsed!!)
    } catch (_: Exception) {
        day
    }

    private companion object {
        const val UI_PREFS = "muse_board_ui"
        const val TICK_MS = 420L
        const val UNDO_MS = 6_000L
    }
}
