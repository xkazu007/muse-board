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

    fun updateAllNoToken(context: Context) {
        val views = RemoteViews(context.packageName, R.layout.widget_muse_board)
        views.setTextViewText(R.id.widget_date, "")
        views.setTextViewText(R.id.widget_focus_title, "Open Muse Board to sign in")
        views.setTextViewText(R.id.widget_focus_detail, "Paste your board token once in the app.")
        views.setViewVisibility(R.id.widget_btn_done, View.GONE)
        views.setViewVisibility(R.id.widget_btn_snooze, View.GONE)
        views.setViewVisibility(R.id.widget_waiting, View.GONE)
        views.setViewVisibility(R.id.widget_upcoming_1, View.GONE)
        views.setViewVisibility(R.id.widget_upcoming_2, View.GONE)
        views.setViewVisibility(R.id.widget_upcoming_3, View.GONE)
        views.setTextViewText(R.id.widget_done_count, "")
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        push(context, views)
    }

    fun updateAllError(context: Context) {
        val views = RemoteViews(context.packageName, R.layout.widget_muse_board)
        views.setTextViewText(R.id.widget_focus_title, "Couldn't reach the board")
        views.setTextViewText(R.id.widget_focus_detail, "Tap to retry.")
        views.setViewVisibility(R.id.widget_btn_done, View.GONE)
        views.setViewVisibility(R.id.widget_btn_snooze, View.GONE)
        views.setViewVisibility(R.id.widget_waiting, View.GONE)
        views.setViewVisibility(R.id.widget_upcoming_1, View.GONE)
        views.setViewVisibility(R.id.widget_upcoming_2, View.GONE)
        views.setViewVisibility(R.id.widget_upcoming_3, View.GONE)
        views.setTextViewText(R.id.widget_done_count, "")
        views.setOnClickPendingIntent(R.id.widget_root, refreshPendingIntent(context))
        push(context, views)
    }

    private fun push(context: Context, views: RemoteViews) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, MuseBoardWidget::class.java)
        manager.updateAppWidget(component, views)
    }

    private fun buildViews(context: Context, board: BoardResponse): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_muse_board)
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
            val waiting = isAfterEightPm()
            views.setViewVisibility(
                R.id.widget_waiting,
                if (waiting) View.VISIBLE else View.GONE,
            )
        } else {
            views.setTextViewText(R.id.widget_focus_title, "All clear — nothing left today.")
            views.setTextViewText(R.id.widget_focus_detail, "")
            views.setViewVisibility(R.id.widget_btn_done, View.GONE)
            views.setViewVisibility(R.id.widget_btn_snooze, View.GONE)
            views.setViewVisibility(R.id.widget_waiting, View.GONE)
        }

        val slots = listOf(
            R.id.widget_upcoming_1,
            R.id.widget_upcoming_2,
            R.id.widget_upcoming_3,
        )
        for (i in slots.indices) {
            if (i < board.upcoming.size) {
                val t = board.upcoming[i]
                val due = formatDue(t.dueAt)
                views.setTextViewText(
                    slots[i],
                    "• " + t.title + if (due.isNotEmpty()) " — $due" else "",
                )
                views.setViewVisibility(slots[i], View.VISIBLE)
            } else {
                views.setViewVisibility(slots[i], View.GONE)
            }
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
