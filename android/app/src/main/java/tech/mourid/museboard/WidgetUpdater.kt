package tech.mourid.museboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * Builds the widget's RemoteViews and drives refreshes.
 *
 * Refresh sources: an AlarmManager tick every 30 minutes (re-armed on
 * boot), an explicit broadcast to [WidgetRefreshReceiver] on widget add /
 * tap / after a button action. Button taps go through
 * WidgetActionReceiver, which calls the API and then requests a refresh
 * here.
 *
 * Note: this used to be WorkManager. It was swapped for AlarmManager so
 * the app builds with zero AndroidX dependencies (framework APIs only) —
 * same 30-minute cadence, same behaviour.
 */
object WidgetUpdater {
    const val ACTION_DONE = "tech.mourid.museboard.ACTION_DONE"
    const val ACTION_SNOOZE = "tech.mourid.museboard.ACTION_SNOOZE"
    const val ACTION_REFRESH = "tech.mourid.museboard.ACTION_REFRESH"
    const val EXTRA_TASK_ID = "task_id"

    private const val REFRESH_INTERVAL_MS = 30L * 60L * 1000L
    private const val ALARM_REQUEST_CODE = 1001
    private const val REFRESH_REQUEST_CODE = 1002

    private val UPCOMING_SLOTS = intArrayOf(
        R.id.widget_upcoming_1,
        R.id.widget_upcoming_2,
        R.id.widget_upcoming_3,
    )
    private val TEXT_VIEWS = intArrayOf(
        R.id.widget_date,
        R.id.widget_focus_title,
        R.id.widget_focus_detail,
        R.id.widget_done_count,
    )
    private val HIDEABLE_VIEWS = intArrayOf(
        R.id.widget_waiting,
        R.id.widget_btn_done,
        R.id.widget_btn_snooze,
        R.id.widget_up_next_divider,
        R.id.widget_up_next_label,
    ) + UPCOMING_SLOTS

    /** Fetch the board now, in the background. */
    fun requestRefresh(context: Context) {
        val intent = Intent(context, WidgetRefreshReceiver::class.java)
        context.sendBroadcast(intent)
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

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun updateAll(context: Context, board: BoardResponse) {
        push(context, buildViews(context, board))
    }

    /** First paint of a newly placed widget, until the first refresh lands.
     *  Tapping retries, so a lost refresh never leaves it stuck. */
    fun showLoading(context: Context, appWidgetIds: IntArray) {
        val views = statusViews(
            context,
            context.getString(R.string.widget_loading),
            context.getString(R.string.widget_tap_refresh),
            refreshPendingIntent(context),
        )
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetIds, views)
    }

    fun updateAllNoToken(context: Context) {
        push(
            context,
            statusViews(
                context,
                "Open Muse Board to sign in",
                "Paste your board token once in the app.",
                openAppPendingIntent(context),
            ),
        )
    }

    fun updateAllTokenRejected(context: Context) {
        push(
            context,
            statusViews(
                context,
                "Board token rejected",
                "Open Muse Board and paste your token again.",
                openAppPendingIntent(context),
            ),
        )
    }

    fun updateAllError(context: Context) {
        push(
            context,
            statusViews(
                context,
                "Couldn't reach the board",
                "Tap to retry.",
                refreshPendingIntent(context),
            ),
        )
    }

    private fun push(context: Context, views: RemoteViews) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, MuseBoardWidget::class.java)
        manager.updateAppWidget(component, views)
    }

    /**
     * Every dynamic view reset to empty / hidden. The launcher applies an
     * update on top of the views already on screen (it does not re-inflate
     * the layout), so each state must start from here and set everything.
     */
    private fun baseViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_muse_board)
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
        views.setTextViewText(R.id.widget_focus_title, title)
        views.setTextViewText(R.id.widget_focus_detail, detail)
        views.setOnClickPendingIntent(R.id.widget_root, onClick)
        return views
    }

    private fun buildViews(context: Context, board: BoardResponse): RemoteViews {
        val views = baseViews(context)
        views.setTextViewText(R.id.widget_date, board.date)
        views.setOnClickPendingIntent(R.id.widget_root, refreshPendingIntent(context))

        val focus = board.focus
        if (focus != null) {
            views.setTextViewText(R.id.widget_focus_title, focus.title)
            val detail = listOfNotNull(focus.detail, formatDue(focus.dueAt))
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            views.setTextViewText(R.id.widget_focus_detail, detail)
            views.setViewVisibility(R.id.widget_btn_done, View.VISIBLE)
            views.setViewVisibility(R.id.widget_btn_snooze, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_btn_done,
                actionPendingIntent(context, ACTION_DONE, focus.id),
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_snooze,
                actionPendingIntent(context, ACTION_SNOOZE, focus.id),
            )
            if (isAfterEightPm()) views.setViewVisibility(R.id.widget_waiting, View.VISIBLE)
        } else {
            views.setTextViewText(R.id.widget_focus_title, context.getString(R.string.all_clear))
        }

        val upcoming = board.upcoming.take(UPCOMING_SLOTS.size)
        if (upcoming.isNotEmpty()) {
            views.setViewVisibility(R.id.widget_up_next_divider, View.VISIBLE)
            views.setViewVisibility(R.id.widget_up_next_label, View.VISIBLE)
        }
        upcoming.forEachIndexed { i, t ->
            val due = formatDue(t.dueAt)
            views.setTextViewText(
                UPCOMING_SLOTS[i],
                "• " + t.title + if (due.isNotEmpty()) " — $due" else "",
            )
            views.setViewVisibility(UPCOMING_SLOTS[i], View.VISIBLE)
        }

        views.setTextViewText(R.id.widget_done_count, "Done today: ${board.doneToday}")
        return views
    }

    private fun actionPendingIntent(context: Context, action: String, taskId: Int): PendingIntent {
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

    private fun refreshPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java)
            .setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            REFRESH_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
