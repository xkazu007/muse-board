package tech.mourid.museboard

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A bottom sheet in the board's design (layout/action_sheet.xml), framework
 * only. Used for the long-press menu and for Later, so every choice looks
 * the same wherever it comes from. Rows close the sheet, then run.
 */
class ActionSheet(private val activity: Activity, title: String, subtitle: String? = null) {

    private val dialog = Dialog(activity)
    private val rows: LinearLayout
    private var onCancel: (() -> Unit)? = null
    private var chose = false

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.action_sheet)
        dialog.findViewById<TextView>(R.id.sheet_title).text = title
        dialog.findViewById<TextView>(R.id.sheet_subtitle).apply {
            text = subtitle
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        rows = dialog.findViewById(R.id.sheet_rows)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.MuseBoard_SheetAnimation)
        }
        dialog.setOnDismissListener { if (!chose) onCancel?.invoke() }
    }

    /** A row: icon, label, optional hint on the right (e.g. "20:00"). */
    fun add(icon: Int, label: String, hint: String? = null, action: () -> Unit): ActionSheet {
        val row = activity.layoutInflater.inflate(R.layout.action_sheet_row, rows, false)
        row.findViewById<ImageView>(R.id.sheet_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.sheet_label).text = label
        row.findViewById<TextView>(R.id.sheet_hint).text = hint.orEmpty()
        row.setOnClickListener {
            chose = true
            dialog.dismiss()
            action()
        }
        rows.addView(row)
        return this
    }

    /** Runs when the sheet closes without a choice (tap outside, back). */
    fun onCancel(action: () -> Unit): ActionSheet {
        onCancel = action
        return this
    }

    fun show() = dialog.show()
}
