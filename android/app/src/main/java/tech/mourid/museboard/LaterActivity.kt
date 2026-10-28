package tech.mourid.museboard

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The widget's "Later": a small dialog asking WHEN (in 1 hour, tonight,
 * tomorrow). A vague "not now" becomes a concrete new plan, and the phone
 * reminds at that time. The choice goes to [WidgetActionReceiver], which
 * updates the widget instantly and calls the API.
 */
class LaterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getIntExtra(WidgetUpdater.EXTRA_TASK_ID, -1)
        if (taskId <= 0) {
            finish()
            return
        }
        setContentView(R.layout.activity_later)
        findViewById<TextView>(R.id.later_task).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val options = findViewById<LinearLayout>(R.id.later_options)
        val height = (48 * resources.displayMetrics.density).toInt()
        for ((label, at) in laterChoices()) {
            val button = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(label)
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { choose(taskId, at) }
            }
            options.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))
        }
        findViewById<Button>(R.id.later_cancel).setOnClickListener { finish() }
    }

    private fun choose(taskId: Int, untilMillis: Long) {
        sendBroadcast(
            WidgetUpdater.snoozeIntent(this, taskId, untilMillis),
        )
        finish()
    }

    companion object {
        const val EXTRA_TITLE = "title"
    }
}
