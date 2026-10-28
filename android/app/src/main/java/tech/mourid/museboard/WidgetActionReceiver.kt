package tech.mourid.museboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles widget button taps: Done / Not now / refresh. Network work runs
 *  off the main thread via goAsync; the widget refreshes afterwards. */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val token = TokenStore.get(context)
                val taskId = intent.getIntExtra(WidgetUpdater.EXTRA_TASK_ID, -1)
                if (!token.isNullOrBlank() && taskId > 0) {
                    when (intent.action) {
                        WidgetUpdater.ACTION_DONE -> ApiClient.completeTask(token, taskId)
                        WidgetUpdater.ACTION_SNOOZE -> ApiClient.snoozeTask(token, taskId)
                    }
                }
            } catch (_: Exception) {
                // Refresh below shows the retry state; never crash the receiver.
            } finally {
                WidgetUpdater.requestRefresh(context)
                pendingResult.finish()
            }
        }
    }
}
