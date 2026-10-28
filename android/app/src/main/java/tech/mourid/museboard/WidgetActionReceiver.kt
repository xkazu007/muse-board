package tech.mourid.museboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every widget tap (and the reminder's Done). Nothing here leaves the home
 * screen:
 *
 * - Later / Back: swap Done for the time chips and back, from the cache.
 * - Done, a Later chip, Undo: update the widget first, from the cache, then
 *   call the API off the main thread (goAsync). Success: a refresh brings
 *   the real board. Failure: back to the real board, saying so.
 * - Refresh: fetch the board.
 *
 * Done from the Android 12+ checkbox waits [TICK_MS] before swapping in the
 * next task: the launcher has already ticked the box, and the swap would
 * otherwise cut its animation off.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getIntExtra(WidgetUpdater.EXTRA_TASK_ID, -1)
        if (taskId <= 0) {
            WidgetUpdater.requestRefresh(context)
            return
        }
        // Shown before the API call: the optimistic update, or nothing.
        var show: suspend () -> Unit = {}
        val call: (String) -> Boolean = when (intent.action) {
            WidgetUpdater.ACTION_LATER_SHOW -> {
                WidgetUpdater.showLaterChoices(context, taskId)
                return
            }
            WidgetUpdater.ACTION_LATER_CANCEL -> {
                WidgetUpdater.showCached(context)
                return
            }
            WidgetUpdater.ACTION_DONE -> {
                PlanReminderReceiver.cancel(context, taskId)
                val wait = if (intent.getBooleanExtra(WidgetUpdater.EXTRA_FROM_CHECK, false)) TICK_MS else 0L
                show = {
                    delay(wait)
                    WidgetUpdater.showOptimistic(context, taskId, done = true, untilMillis = null)
                }
                ({ token -> ApiClient.completeTask(token, taskId) })
            }
            WidgetUpdater.ACTION_SNOOZE -> {
                val choice = intent.getStringExtra(WidgetUpdater.EXTRA_CHOICE)
                    ?.let { name -> LaterChoice.values().firstOrNull { it.name == name } }
                    ?: LaterChoice.TOMORROW
                val until = choice.at()
                PlanReminderReceiver.cancel(context, taskId)
                WidgetUpdater.showOptimistic(context, taskId, done = false, untilMillis = until)
                ({ token -> ApiClient.snoozeTask(token, taskId, until) })
            }
            WidgetUpdater.ACTION_UNDO -> {
                BoardCache.setNotice(context, context.getString(R.string.undone))
                WidgetUpdater.showCached(context)
                ({ token -> ApiClient.reopenTask(token, taskId) })
            }
            else -> {
                WidgetUpdater.requestRefresh(context)
                return
            }
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                show()
                val token = TokenStore.get(context)
                val ok = !token.isNullOrBlank() && call(token)
                if (!ok) WidgetUpdater.showSaveFailed(context)
                // Also after a failure: a stale target (a task already done
                // elsewhere) gets the real board and fresh shortcuts.
                WidgetUpdater.requestRefresh(context)
            } catch (_: UnauthorizedException) {
                WidgetUpdater.requestRefresh(context)
            } catch (_: Exception) {
                WidgetUpdater.showSaveFailed(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TICK_MS = 420L
    }
}
