package tech.mourid.museboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build

/**
 * Fires at a task's plan time (armed by [PlanScheduler]) and posts one
 * notification: the first step as the title, "where · task" below, with a
 * Done action. One per plan, never repeated: frequent alerts get ignored.
 */
class PlanReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment?.toIntOrNull() ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val step = intent.getStringExtra(EXTRA_STEP)
        val where = intent.getStringExtra(EXTRA_WHERE)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context, manager)

        val text = listOfNotNull(where, if (step != null) title else null).joinToString(" · ")
        val done = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.mb_ic_notification),
            context.getString(R.string.done_label),
            WidgetUpdater.actionPendingIntent(context, WidgetUpdater.ACTION_DONE, id),
        ).build()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.mb_ic_notification)
            .setContentTitle(step ?: title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(done)
            .build()
        manager.notify(notificationId(id), notification)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_STEP = "first_step"
        const val EXTRA_WHERE = "plan_where"
        private const val CHANNEL_ID = "plans"

        private fun notificationId(taskId: Int) = 10_000 + taskId

        /** Clears a task's reminder (it was marked done). */
        fun cancel(context: Context, taskId: Int) {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(taskId))
        }

        private fun ensureChannel(context: Context, manager: NotificationManager) {
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_plans),
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.description = context.getString(R.string.channel_plans_desc)
            manager.createNotificationChannel(channel)
        }
    }
}
