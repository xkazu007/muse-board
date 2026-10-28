package tech.mourid.museboard

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * Long-press on the app icon: "Done: <first step>" and "Later: 1 hour" for
 * the current focus (Android app shortcuts, ShortcutManager). Synced after
 * every board refresh; only rewritten when the focus changes, since the
 * system rate-limits background updates.
 */
object ShortcutSync {
    private const val PREFS = "muse_board_shortcuts"
    private const val KEY_LAST = "last"
    private const val MAX_LABEL = 40

    /** [board] null = signed out: remove the shortcuts. */
    fun sync(context: Context, board: BoardResponse?) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val focus = board?.focus
        val step = focus?.let { it.firstStep ?: it.title }
        val key = if (focus == null) "" else "${focus.id}|$step"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST, null) == key) return
        if (manager.isRateLimitingActive) return

        if (focus == null || step == null) {
            manager.removeAllDynamicShortcuts()
        } else {
            val done = ShortcutInfo.Builder(context, "done")
                .setShortLabel(context.getString(R.string.done_label))
                .setLongLabel(clip(context.getString(R.string.shortcut_done, step)))
                .setIcon(Icon.createWithResource(context, R.drawable.mb_shortcut_done))
                .setIntent(ShortcutActionActivity.intent(context, WidgetUpdater.ACTION_DONE, focus.id))
                .setRank(0)
                .build()
            val later = ShortcutInfo.Builder(context, "later")
                .setShortLabel(context.getString(R.string.later))
                .setLongLabel(context.getString(R.string.shortcut_later))
                .setIcon(Icon.createWithResource(context, R.drawable.mb_shortcut_later))
                .setIntent(
                    ShortcutActionActivity.intent(context, WidgetUpdater.ACTION_SNOOZE, focus.id)
                        .putExtra(WidgetUpdater.EXTRA_CHOICE, LaterChoice.HOUR.name),
                )
                .setRank(1)
                .build()
            manager.dynamicShortcuts = listOf(done, later)
        }
        prefs.edit().putString(KEY_LAST, key).apply()
    }

    private fun clip(text: String): String =
        if (text.length <= MAX_LABEL) text else text.take(MAX_LABEL - 1).trimEnd() + "…"
}

/** Invisible: forwards a shortcut tap to [WidgetActionReceiver] (the same
 *  path as the widget), shows a short toast, and closes. */
class ShortcutActionActivity : android.app.Activity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        val taskId = intent.getIntExtra(WidgetUpdater.EXTRA_TASK_ID, -1)
        if (taskId > 0 && (action == WidgetUpdater.ACTION_DONE || action == WidgetUpdater.ACTION_SNOOZE)) {
            sendBroadcast(
                Intent(this, WidgetActionReceiver::class.java)
                    .setAction(action)
                    .putExtras(intent),
            )
            val text = if (action == WidgetUpdater.ACTION_DONE) {
                getString(R.string.shortcut_done_toast)
            } else {
                getString(R.string.later_feedback, formatWhen(toIso(LaterChoice.HOUR.at())))
            }
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        fun intent(context: Context, action: String, taskId: Int): Intent =
            Intent(context, ShortcutActionActivity::class.java)
                .setAction(action)
                .putExtra(WidgetUpdater.EXTRA_TASK_ID, taskId)
    }
}
