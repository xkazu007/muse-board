package tech.mourid.museboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Done (widget or reminder), Later (from [LaterActivity]) and refresh.
 *
 * Done and Later update the widget first, from the cache, then call the
 * API off the main thread (goAsync). Success: a refresh brings the real
 * board. Failure: the widget goes back to the real board and says the
 * change wasn't saved.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getIntExtra(WidgetUpdater.EXTRA_TASK_ID, -1)
        val untilMillis = intent.getLongExtra(WidgetUpdater.EXTRA_UNTIL, -1L)
        val call: ((String) -> Boolean)? = when {
            taskId <= 0 -> null
            intent.action == WidgetUpdater.ACTION_DONE -> {
                PlanReminderReceiver.cancel(context, taskId)
                WidgetUpdater.showOptimistic(context, taskId, done = true, untilMillis = null)
                ({ token -> ApiClient.completeTask(token, taskId) })
            }
            intent.action == WidgetUpdater.ACTION_SNOOZE && untilMillis > 0 -> {
                WidgetUpdater.showOptimistic(context, taskId, done = false, untilMillis = untilMillis)
                ({ token -> ApiClient.snoozeTask(token, taskId, untilMillis) })
            }
            else -> null
        }
        if (call == null) {
            WidgetUpdater.requestRefresh(context)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = TokenStore.get(context)
                val ok = !token.isNullOrBlank() && call(token)
                if (ok) WidgetUpdater.requestRefresh(context) else WidgetUpdater.showSaveFailed(context)
            } catch (_: UnauthorizedException) {
                WidgetUpdater.requestRefresh(context)
            } catch (_: Exception) {
                WidgetUpdater.showSaveFailed(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
