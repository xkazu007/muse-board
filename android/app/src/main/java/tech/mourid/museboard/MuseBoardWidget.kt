package tech.mourid.museboard

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** The true home-screen widget. All content comes from the board API via
 *  WidgetRefreshReceiver; this provider only schedules the refreshes. */
class MuseBoardWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetUpdater.showLoading(context, appWidgetIds)
        // Re-arm the 30-minute tick here too, not only in onEnabled: a force
        // stop (or One UI putting the app to sleep) cancels the alarm, and
        // onEnabled never runs again for a widget that is already placed.
        // Re-arming replaces the existing alarm, so this is safe to repeat.
        WidgetUpdater.schedulePeriodic(context)
        WidgetUpdater.requestRefresh(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdater.cancelPeriodic(context)
    }
}
