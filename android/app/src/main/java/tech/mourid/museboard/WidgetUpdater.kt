package tech.mourid.museboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/**
 * Builds the Focus widget (layout/widget_focus.xml) and drives refreshes.
 *
 * Refresh sources: an AlarmManager tick every 30 minutes (re-armed on
 * boot and on every onUpdate), an explicit broadcast to
 * [WidgetRefreshReceiver] on widget add and after every action. Done and
 * Later go through [WidgetActionReceiver], which first shows the result
 * from [BoardCache] ([showOptimistic]) and then calls the API.
 *
 * Framework APIs only (no AndroidX), so the app builds with
 * build-manual.sh as well as Gradle.
 */
object WidgetUpdater {
    const val ACTION_DONE = "tech.mourid.museboard.ACTION_DONE"
    const val ACTION_SNOOZE = "tech.mourid.museboard.ACTION_SNOOZE"
    const val ACTION_REFRESH = "tech.mourid.museboard.ACTION_REFRESH"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_UNTIL = "until"

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
    private val HIDEABLE_VIEWS = intArrayOf(
        R.id.mb_btn_done,
        R.id.mb_btn_later,
        R.id.mb_upnext,
    ) + UP_ROWS

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
        BoardCache.setNotice(
            context,
            if (done) {
                context.getString(R.string.done_feedback, doneToday)
            } else {
                context.getString(R.string.later_feedback, formatWhen(untilMillis?.let(::toIso)))
            },
        )
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
            views.setViewVisibility(R.id.mb_btn_done, View.VISIBLE)
            views.setViewVisibility(R.id.mb_btn_later, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.mb_btn_done,
                actionPendingIntent(context, ACTION_DONE, focus.id),
            )
            views.setOnClickPendingIntent(R.id.mb_btn_later, laterPendingIntent(context, focus))
        } else {
            views.setTextViewText(R.id.mb_step, context.getString(R.string.all_clear))
            views.setTextViewText(R.id.mb_task, context.getString(R.string.all_clear_detail))
        }
        val next = board.next
        views.setTextViewText(
            R.id.mb_then,
            notice ?: if (next != null) context.getString(R.string.then, thenLine(next)) else "",
        )

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

    // --- Intents ---------------------------------------------------------------

    /** Done (widget button or reminder action) for [taskId]. */
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

    /** The broadcast LaterActivity sends once a time is picked. */
    fun snoozeIntent(context: Context, taskId: Int, untilMillis: Long): Intent =
        Intent(context, WidgetActionReceiver::class.java)
            .setAction(ACTION_SNOOZE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_UNTIL, untilMillis)

    private fun laterPendingIntent(context: Context, task: BoardTask): PendingIntent {
        val intent = Intent(context, LaterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_TASK_ID, task.id)
            .putExtra(LaterActivity.EXTRA_TITLE, task.title)
        return PendingIntent.getActivity(
            context,
            task.id,
            intent,
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
