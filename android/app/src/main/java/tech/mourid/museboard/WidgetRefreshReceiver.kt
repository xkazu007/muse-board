package tech.mourid.museboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fetches the board and pushes it to every widget instance.
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
                    WidgetUpdater.updateAllNoToken(context)
                } else {
                    val board = ApiClient.getBoard(token)
                    if (board == null) {
                        WidgetUpdater.updateAllError(context)
                    } else {
                        WidgetUpdater.updateAll(context, board)
                    }
                }
            } catch (_: Exception) {
                WidgetUpdater.updateAllError(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
