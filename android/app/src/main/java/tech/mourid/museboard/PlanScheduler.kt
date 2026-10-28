package tech.mourid.museboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * One reminder per planned task, at its plan time ("when · where"). Synced
 * after every board refresh from the board's `plans` (open tasks planned in
 * the next 36 hours): new plans are armed, moved plans re-armed, and done or
 * removed ones cancelled.
 *
 * Inexact alarms (setAndAllowWhileIdle): no exact-alarm permission needed;
 * Android may deliver a few minutes late in Doze. A plan added by the agent
 * shows up on the next refresh (at most 30 minutes, or any widget tap).
 */
object PlanScheduler {
    private const val PREFS = "muse_board_plans"
    private const val KEY_IDS = "armed_ids"

    fun sync(context: Context, plans: List<BoardTask>) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val wanted = plans.mapNotNull { task ->
            parseIso(task.planAt)?.takeIf { it > now }?.let { task to it }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val armed = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
        val wantedIds = wanted.map { it.first.id }.toSet()
        for (id in armed - wantedIds) alarm.cancel(pendingIntent(context, id, null))
        for ((task, at) in wanted) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context, task.id, task))
        }
        prefs.edit().putStringSet(KEY_IDS, wantedIds.map { it.toString() }.toSet()).apply()
    }

    fun cancelAll(context: Context) = sync(context, emptyList())

    /** One PendingIntent per task, told apart by its data URI (extras don't
     *  count for matching, so cancel works without them). */
    private fun pendingIntent(context: Context, id: Int, task: BoardTask?): PendingIntent {
        val intent = Intent(context, PlanReminderReceiver::class.java)
            .setData(Uri.parse("museboard://plan/$id"))
        if (task != null) {
            intent.putExtra(PlanReminderReceiver.EXTRA_TITLE, task.title)
                .putExtra(PlanReminderReceiver.EXTRA_STEP, task.firstStep)
                .putExtra(PlanReminderReceiver.EXTRA_WHERE, task.planWhere)
        }
        return PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
