package tech.mourid.museboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Fetches the board, caches it ([BoardCache], for instant Done / Later),
 * pushes it to every widget instance and re-syncs the plan reminders
 * ([PlanScheduler]).
 *
 * Triggered by: the 30-minute AlarmManager tick (see
 * [WidgetUpdater.schedulePeriodic]), an explicit refresh request
 * ([WidgetUpdater.requestRefresh]), and BOOT_COMPLETED (re-arms the alarm
 * after a reboot). Network work runs off the main thread via goAsync.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            WidgetUpdater.schedulePeriodic(context)
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = TokenStore.get(context)
                if (token.isNullOrBlank()) {
                    BoardCache.clear(context)
                    PlanScheduler.cancelAll(context)
                    WidgetUpdater.updateAllNoToken(context)
                } else {
                    val json = ApiClient.getBoardJson(token)
                    if (json == null) {
                        showLastKnown(context)
                    } else {
                        val board = parseBoard(JSONObject(json))
                        BoardCache.save(context, json)
                        WidgetUpdater.updateAll(context, board)
                        PlanScheduler.sync(context, board.plans)
                    }
                }
            } catch (_: UnauthorizedException) {
                BoardCache.clear(context)
                PlanScheduler.cancelAll(context)
                WidgetUpdater.updateAllTokenRejected(context)
            } catch (_: Exception) {
                showLastKnown(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Network trouble: keep showing the last board fetched (it is still
     *  useful); the error state only when there is none. */
    private fun showLastKnown(context: Context) {
        val cached = BoardCache.load(context)
        if (cached != null) WidgetUpdater.updateAll(context, cached) else WidgetUpdater.updateAllError(context)
    }
}
