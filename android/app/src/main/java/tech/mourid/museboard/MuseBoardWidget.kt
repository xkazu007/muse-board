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
        WidgetUpdater.requestRefresh(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdater.cancelPeriodic(context)
    }
}
