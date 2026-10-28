package tech.mourid.museboard

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
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

/** Companion app: first-run token entry, then the full task list.
 *  The home-screen widget itself is MuseBoardWidget.
 *
 *  Framework Activity (no AppCompat) and a plain LinearLayout task list —
 *  the app carries no AndroidX dependencies, only the Kotlin stdlib and
 *  kotlinx-coroutines. */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tokenBox: View
    private lateinit var boardBox: View
    private lateinit var tokenInput: EditText
    private lateinit var tokenError: TextView
    private lateinit var focusTitle: TextView
    private lateinit var focusDetail: TextView
    private lateinit var doneCount: TextView
    private lateinit var taskList: LinearLayout

    private var token: String? = null
    private var focusId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenBox = findViewById(R.id.token_box)
        boardBox = findViewById(R.id.board_box)
        tokenInput = findViewById(R.id.token_input)
        tokenError = findViewById(R.id.token_error)
        focusTitle = findViewById(R.id.focus_title)
        focusDetail = findViewById(R.id.focus_detail)
        doneCount = findViewById(R.id.done_count)
        taskList = findViewById(R.id.task_list)

        findViewById<Button>(R.id.token_save).setOnClickListener { saveToken() }
        findViewById<Button>(R.id.btn_done).setOnClickListener {
            focusId?.let { runTaskAction(it, ApiClient::completeTask) }
        }
        findViewById<Button>(R.id.btn_snooze).setOnClickListener {
            focusId?.let { runTaskAction(it, ApiClient::snoozeTask) }
        }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { loadBoard() }
        findViewById<Button>(R.id.btn_change_token).setOnClickListener {
            TokenStore.clear(this)
            token = null
            showTokenBox()
            WidgetUpdater.requestRefresh(this)
        }
        findViewById<Button>(R.id.btn_add_widget).setOnClickListener { requestPinWidget() }

        token = TokenStore.get(this)
        if (token.isNullOrBlank()) {
            showTokenBox()
        } else {
            showBoard()
            loadBoard()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
            Toast.makeText(
                this,
                getString(R.string.widget_pin_unsupported),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showTokenBox() {
        tokenBox.visibility = View.VISIBLE
        boardBox.visibility = View.GONE
    }

    private fun showBoard() {
        tokenBox.visibility = View.GONE
        boardBox.visibility = View.VISIBLE
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
        loadBoard()
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

    private fun loadBoard() {
        val t = token ?: return
        scope.launch {
            val board = try {
                withContext(Dispatchers.IO) { ApiClient.getBoard(t) }
            } catch (_: UnauthorizedException) {
                onTokenRejected()
                return@launch
            } catch (_: Exception) {
                null
            }
            if (board == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.board_unreachable),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            focusId = board.focus?.id
            if (board.focus != null) {
                focusTitle.text = board.focus.title
                val detail = listOfNotNull(board.focus.detail, formatDue(board.focus.dueAt))
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                focusDetail.text = detail
                findViewById<Button>(R.id.btn_done).isEnabled = true
                findViewById<Button>(R.id.btn_snooze).isEnabled = true
            } else {
                focusTitle.text = getString(R.string.all_clear)
                focusDetail.text = ""
                findViewById<Button>(R.id.btn_done).isEnabled = false
                findViewById<Button>(R.id.btn_snooze).isEnabled = false
            }
            doneCount.text = getString(R.string.done_today, board.doneToday)
            loadTasks()
        }
    }

    private fun loadTasks() {
        val t = token ?: return
        scope.launch {
            val tasks = try {
                withContext(Dispatchers.IO) { ApiClient.getTasks(t) }
            } catch (_: UnauthorizedException) {
                onTokenRejected()
                return@launch
            } catch (_: Exception) {
                null
            }
            if (tasks != null) populateTasks(tasks)
        }
    }

    /** Small list — inflate one item_task view per task, no RecyclerView. */
    private fun populateTasks(tasks: List<BoardTask>) {
        taskList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (task in tasks) {
            val view = inflater.inflate(R.layout.item_task, taskList, false)
            view.findViewById<TextView>(R.id.task_title).text = task.title
            val detail = listOfNotNull(task.detail, formatDue(task.dueAt))
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            view.findViewById<TextView>(R.id.task_detail).text = detail
            val actionButton = view.findViewById<Button>(R.id.task_done)
            if (task.status == "done") {
                // Done tasks keep an Undo, so a stray tap is recoverable.
                view.findViewById<TextView>(R.id.task_title).alpha = 0.5f
                actionButton.text = getString(R.string.undo)
                actionButton.setBackgroundResource(R.drawable.btn_ghost)
                actionButton.setOnClickListener { runTaskAction(task.id, ApiClient::reopenTask) }
            } else {
                actionButton.setOnClickListener { runTaskAction(task.id, ApiClient::completeTask) }
            }
            taskList.addView(view)
        }
    }

    /** Done / Not now / Undo: call the API, then reload the app and widget. */
    private fun runTaskAction(id: Int, call: (String, Int) -> Boolean) {
        val t = token ?: return
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { call(t, id) }
            } catch (_: UnauthorizedException) {
                onTokenRejected()
                return@launch
            }
            if (ok) {
                loadBoard()
                WidgetUpdater.requestRefresh(this@MainActivity)
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.action_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
