package tech.mourid.museboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/**
 * Builds the Focus widget (layout/widget_focus.xml) and drives refreshes.
 *
 * Refresh sources: an AlarmManager tick every 30 minutes (re-armed on
 * boot and on every onUpdate), an explicit broadcast to
 * [WidgetRefreshReceiver] on widget add and after every action. Every tap
 * goes through [WidgetActionReceiver] and stays on the home screen: Later
 * swaps Done for time chips in the widget itself ([showLaterChoices]), and
 * Done / a chosen time / Undo update from [BoardCache] first
 * ([showOptimistic]), then call the API.
 *
 * Framework APIs only (no AndroidX), so the app builds with
 * build-manual.sh as well as Gradle.
 */
object WidgetUpdater {
    const val ACTION_DONE = "tech.mourid.museboard.ACTION_DONE"
    const val ACTION_SNOOZE = "tech.mourid.museboard.ACTION_SNOOZE"
    const val ACTION_REFRESH = "tech.mourid.museboard.ACTION_REFRESH"
    const val ACTION_LATER_SHOW = "tech.mourid.museboard.ACTION_LATER_SHOW"
    const val ACTION_LATER_CANCEL = "tech.mourid.museboard.ACTION_LATER_CANCEL"
    const val ACTION_UNDO = "tech.mourid.museboard.ACTION_UNDO"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_CHOICE = "later_choice"
    const val EXTRA_FROM_CHECK = "from_check"

    private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L
    private const val ALARM_REQUEST_CODE = 1001
    private const val REFRESH_REQUEST_CODE = 1002

    /** From this height (dp) the widget also shows the "Then" list. A 4×2
     *  is about 170dp on One UI (85dp rows), so this is usually 4 rows. */
    private const val TALL_MIN_HEIGHT_DP = 260f

    private val UP_ROWS = intArrayOf(R.id.mb_up_1, R.id.mb_up_2, R.id.mb_up_3)
    private val UP_TITLES = intArrayOf(R.id.mb_up_1_title, R.id.mb_up_2_title, R.id.mb_up_3_title)
    private val UP_WHENS = intArrayOf(R.id.mb_up_1_when, R.id.mb_up_2_when, R.id.mb_up_3_when)
    private val TEXT_VIEWS = intArrayOf(
        R.id.mb_meta,
        R.id.mb_count,
        R.id.mb_step,
        R.id.mb_task,
        R.id.mb_then,
    )
    private val LATER_CHIPS = intArrayOf(R.id.mb_later_1, R.id.mb_later_2, R.id.mb_later_3)

    /** Android 12+ uses layout-v31 where Done is a native CheckBox. */
    private val CHECKBOX = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val DONE_VIEW = if (CHECKBOX) R.id.mb_check else R.id.mb_btn_done

    private val HIDEABLE_VIEWS = intArrayOf(
        DONE_VIEW,
        R.id.mb_btn_later,
        R.id.mb_then_row,
        R.id.mb_undo,
        R.id.mb_open,
        R.id.mb_later_row,
        R.id.mb_upnext,
    ) + UP_ROWS + LATER_CHIPS

    // --- Refresh scheduling --------------------------------------------------

    /** Fetch the board now, in the background. */
    fun requestRefresh(context: Context) {
        context.sendBroadcast(Intent(context, WidgetRefreshReceiver::class.java))
    }

    /** Repeating 30-minute refresh. Inexact: no exact-alarm permission needed. */
    fun schedulePeriodic(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + REFRESH_INTERVAL_MS,
            REFRESH_INTERVAL_MS,
            alarmPendingIntent(context),
        )
    }

    fun cancelPeriodic(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(alarmPendingIntent(context))
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, WidgetRefreshReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // --- States ----------------------------------------------------------------

    fun updateAll(context: Context, board: BoardResponse) {
        val notice = BoardCache.notice(context)
        push(context) { tall -> boardViews(context, board, tall, notice) }
    }

    /** First paint of a newly placed widget, until the first refresh lands.
     *  Tapping retries, so a lost refresh never leaves it stuck. */
    fun showLoading(context: Context, appWidgetIds: IntArray) {
        val views = responsive {
            statusViews(
                context,
                context.getString(R.string.widget_loading),
                context.getString(R.string.widget_tap_refresh),
                refreshPendingIntent(context),
            )
        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetIds, views)
    }

    fun updateAllNoToken(context: Context) = push(context) {
        statusViews(
            context,
            "Open Muse Board to sign in",
            "Paste your board token once in the app.",
            openAppPendingIntent(context),
        )
    }

    fun updateAllTokenRejected(context: Context) = push(context) {
        statusViews(
            context,
            "Board token rejected",
            "Open Muse Board and paste your token again.",
            openAppPendingIntent(context),
        )
    }

    fun updateAllError(context: Context) = push(context) {
        statusViews(
            context,
            "Couldn't reach the board",
            "Tap to retry.",
            refreshPendingIntent(context),
        )
    }

    /**
     * Done / Later tapped on [taskId]: show the result now, from the cached
     * board, before the API call. The next task moves up and the feedback
     * line says what happened; the real board replaces this a moment later.
     * Skipped (returns false) when the cache doesn't have [taskId] as focus,
     * e.g. Done from a reminder for a task further down the queue.
     */
    fun showOptimistic(context: Context, taskId: Int, done: Boolean, untilMillis: Long?): Boolean {
        val cached = BoardCache.load(context)
        val doneToday = (cached?.doneToday ?: 0) + if (done) 1 else 0
        if (done) {
            BoardCache.setNotice(context, context.getString(R.string.done_feedback, doneToday), undoTaskId = taskId)
        } else {
            BoardCache.setNotice(
                context,
                context.getString(R.string.later_feedback, formatWhen(untilMillis?.let(::toIso))),
            )
        }
        if (cached == null || cached.focus?.id != taskId) return false
        val next = cached.next
        val moved = cached.copy(
            focus = next,
            next = null,
            upcoming = cached.upcoming.filter { it.id != taskId && it.id != next?.id },
            doneToday = doneToday,
        )
        updateAll(context, moved)
        return true
    }

    /** Later tapped: the widget swaps Done for time chips, in place. Back
     *  (or any refresh) returns to the normal view. */
    fun showLaterChoices(context: Context, taskId: Int) {
        val cached = BoardCache.load(context)
        val focus = cached?.focus
        if (cached == null || focus == null || focus.id != taskId) {
            requestRefresh(context)
            return
        }
        push(context) { laterViews(context, cached, focus) }
    }

    /** Back from the Later chips, or Undo's first paint: the cached board. */
    fun showCached(context: Context) {
        val cached = BoardCache.load(context)
        if (cached != null) updateAll(context, cached) else requestRefresh(context)
    }

    /** The API call behind an optimistic update failed: back to the cached
     *  (real) board, saying so. */
    fun showSaveFailed(context: Context) {
        BoardCache.setNotice(context, context.getString(R.string.save_failed))
        val cached = BoardCache.load(context)
        if (cached != null) updateAll(context, cached) else updateAllError(context)
    }

    private fun push(context: Context, build: (tall: Boolean) -> RemoteViews) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, MuseBoardWidget::class.java)
        manager.updateAppWidget(component, responsive(build))
    }

    /** Android 12+: one layout per size, the launcher picks the one that
     *  fits (taller = with the "Then" list). Older: the compact one. */
    private fun responsive(build: (tall: Boolean) -> RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return build(false)
        return RemoteViews(
            mapOf(
                SizeF(100f, 80f) to build(false),
                SizeF(100f, TALL_MIN_HEIGHT_DP) to build(true),
            ),
        )
    }

    /**
     * Every dynamic view reset to empty / hidden. The launcher applies an
     * update on top of the views already on screen (it does not re-inflate
     * the layout), so each state must start from here and set everything.
     */
    private fun baseViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_focus)
        for (id in TEXT_VIEWS) views.setTextViewText(id, "")
        for (id in HIDEABLE_VIEWS) views.setViewVisibility(id, View.GONE)
        // A checked box would carry over to the next task: always start clear.
        if (CHECKBOX) views.setCompoundButtonChecked(R.id.mb_check, false)
        return views
    }

    /** A message-only state: title, one detail line, tap action. */
    private fun statusViews(
        context: Context,
        title: String,
        detail: String,
        onClick: PendingIntent,
    ): RemoteViews {
        val views = baseViews(context)
        views.setTextViewText(R.id.mb_step, title)
        views.setTextViewText(R.id.mb_task, detail)
        views.setOnClickPendingIntent(android.R.id.background, onClick)
        return views
    }

    private fun boardViews(
        context: Context,
        board: BoardResponse,
        tall: Boolean,
        notice: String?,
    ): RemoteViews {
        val views = baseViews(context)
        views.setOnClickPendingIntent(android.R.id.background, openAppPendingIntent(context))
        views.setTextViewText(R.id.mb_count, context.getString(R.string.done_count_short, board.doneToday))

        val focus = board.focus
        if (focus != null) {
            views.setTextViewText(R.id.mb_meta, focusMeta(focus))
            views.setTextViewText(R.id.mb_step, focus.firstStep ?: focus.title)
            views.setTextViewText(
                R.id.mb_task,
                if (focus.firstStep != null) focus.title else focus.detail.orEmpty(),
            )
            views.setViewVisibility(DONE_VIEW, View.VISIBLE)
            views.setViewVisibility(R.id.mb_btn_later, View.VISIBLE)
            views.setTextViewText(R.id.mb_btn_later, context.getString(R.string.later))
            if (CHECKBOX) {
                // The launcher ticks the box itself (instant, animated), then
                // fires this; WidgetActionReceiver lets the tick play out.
                views.setOnCheckedChangeResponse(
                    R.id.mb_check,
                    RemoteViews.RemoteResponse.fromPendingIntent(checkPendingIntent(context, focus.id)),
                )
            } else {
                views.setOnClickPendingIntent(
                    R.id.mb_btn_done,
                    actionPendingIntent(context, ACTION_DONE, focus.id),
                )
            }
            views.setOnClickPendingIntent(
                R.id.mb_btn_later,
                actionPendingIntent(context, ACTION_LATER_SHOW, focus.id),
            )
        } else {
            views.setTextViewText(R.id.mb_step, context.getString(R.string.all_clear))
            views.setTextViewText(R.id.mb_task, context.getString(R.string.all_clear_detail))
        }
        // Then line: feedback after an action, else what comes next (the
        // next task, or when a snoozed one comes back).
        val next = board.next
        val comingBack = board.plans.firstOrNull()
        val thenText = notice ?: when {
            next != null -> context.getString(R.string.then, thenLine(next))
            focus == null && comingBack != null -> context.getString(R.string.next_plan, thenLine(comingBack))
            else -> null
        }
        if (thenText != null) {
            views.setViewVisibility(R.id.mb_then_row, View.VISIBLE)
            views.setTextViewText(R.id.mb_then, thenText)
        }
        val undoId = if (notice != null) BoardCache.undoTaskId(context) else null
        if (undoId != null) {
            views.setViewVisibility(R.id.mb_undo, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.mb_undo, actionPendingIntent(context, ACTION_UNDO, undoId))
        } else {
            // Go-to link (issue #12): small, on the Then line, never the
            // main action. Undo takes the spot while it's showing.
            val url = focus?.url
            val open = url?.let { openUrlPendingIntent(context, focus.id, it) }
            if (open != null) {
                views.setViewVisibility(R.id.mb_then_row, View.VISIBLE)
                views.setViewVisibility(R.id.mb_open, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.mb_open, open)
            }
        }

        val upcoming = board.upcoming.take(UP_ROWS.size)
        if (tall && upcoming.isNotEmpty()) {
            views.setViewVisibility(R.id.mb_upnext, View.VISIBLE)
            upcoming.forEachIndexed { i, t ->
                views.setViewVisibility(UP_ROWS[i], View.VISIBLE)
                views.setTextViewText(UP_TITLES[i], t.title)
                views.setTextViewText(UP_WHENS[i], formatWhen(t.planAt ?: t.dueAt))
            }
        }
        return views
    }

    /** The Later state: "Come back to it…", the task, and one chip per
     *  choice where Done was; the Later button becomes Back. */
    private fun laterViews(context: Context, board: BoardResponse, focus: BoardTask): RemoteViews {
        val views = baseViews(context)
        views.setTextViewText(R.id.mb_meta, context.getString(R.string.later))
        views.setTextViewText(R.id.mb_count, context.getString(R.string.done_count_short, board.doneToday))
        views.setTextViewText(R.id.mb_step, context.getString(R.string.later_title))
        views.setTextViewText(R.id.mb_task, focus.title)
        views.setViewVisibility(R.id.mb_later_row, View.VISIBLE)
        LaterChoice.offered().forEachIndexed { i, choice ->
            views.setViewVisibility(LATER_CHIPS[i], View.VISIBLE)
            views.setTextViewText(LATER_CHIPS[i], context.getString(choice.chip))
            views.setOnClickPendingIntent(LATER_CHIPS[i], laterChipPendingIntent(context, focus.id, choice))
        }
        val back = actionPendingIntent(context, ACTION_LATER_CANCEL, focus.id)
        views.setViewVisibility(R.id.mb_btn_later, View.VISIBLE)
        views.setTextViewText(R.id.mb_btn_later, context.getString(R.string.back))
        views.setOnClickPendingIntent(R.id.mb_btn_later, back)
        views.setOnClickPendingIntent(android.R.id.background, back)
        return views
    }

    // --- Intents ---------------------------------------------------------------

    /** A widget or reminder action (Done, Later, Back, Undo) on [taskId]. */
    fun actionPendingIntent(context: Context, action: String, taskId: Int): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            taskId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The checkbox's Done. Mutable (Android 12+ only) because the launcher
     *  fills in the checked state; the intent is explicit, so that's safe.
     *  [EXTRA_FROM_CHECK] tells the receiver to wait for the tick animation. */
    private fun checkPendingIntent(context: Context, taskId: Int): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java)
            .setAction(ACTION_DONE)
            .setData(Uri.parse("museboard://check/$taskId"))
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_FROM_CHECK, true)
        return PendingIntent.getBroadcast(
            context,
            taskId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /** One Later choice (widget chip, reminder action, icon shortcut):
     *  snooze [taskId] with [choice], resolved at tap time.
     *  The data URI keeps the three chips' PendingIntents apart (extras
     *  alone don't). */
    fun laterChipPendingIntent(context: Context, taskId: Int, choice: LaterChoice): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java)
            .setAction(ACTION_SNOOZE)
            .setData(Uri.parse("museboard://later/$taskId/${choice.name}"))
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_CHOICE, choice.name)
        return PendingIntent.getBroadcast(
            context,
            taskId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Opens a task's go-to link in the browser, or null if it isn't a
     *  web address. */
    private fun openUrlPendingIntent(context: Context, taskId: Int, url: String): PendingIntent? {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return null
        return PendingIntent.getActivity(
            context,
            taskId,
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun refreshPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REFRESH_REQUEST_CODE,
        Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openAppPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
