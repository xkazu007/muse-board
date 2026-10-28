package tech.mourid.museboard

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Quick-add from the phone (issue #6): title + first step (both required,
 * the AGENTS.md rule shown under the field), optional when / where, time
 * estimate and link. Saves
 * through [save]; the task lands at the end of the queue, and Muse can
 * enrich it later.
 */
class QuickAddSheet(
    private val activity: Activity,
    private val save: (JSONObject, onResult: (Boolean) -> Unit) -> Unit,
) {
    private val dialog = bottomSheet(activity, R.layout.quick_add_sheet, keyboard = true)
    private val title: EditText = dialog.findViewById(R.id.add_task_title)
    private val step: EditText = dialog.findViewById(R.id.add_task_step)
    private val whenButton: TextView = dialog.findViewById(R.id.add_task_when)
    private val where: EditText = dialog.findViewById(R.id.add_task_where)
    private val link: EditText = dialog.findViewById(R.id.add_task_link)
    private val error: TextView = dialog.findViewById(R.id.add_task_error)
    private val saveButton: Button = dialog.findViewById(R.id.add_task_save)
    private var planLocal: String? = null
    private var estimate: Int? = null
    private val estimateChips: LinearLayout = dialog.findViewById(R.id.add_task_estimate)

    init {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSaveEnabled()
        }
        title.addTextChangedListener(watcher)
        step.addTextChangedListener(watcher)
        whenButton.onTap {
            pickDateTime(activity, null) { picked ->
                planLocal = picked.local
                whenButton.text = formatWhen(toIso(picked.millis))
            }
        }
        saveButton.onTap { submit() }
        // The keyboard's Done on the last field adds the task too.
        link.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE && saveButton.isEnabled) {
                submit()
                true
            } else {
                false
            }
        }
        showEstimateChips()
        updateSaveEnabled()
    }

    fun show() {
        dialog.show()
        title.requestFocus()
    }

    /** "Time" then 5 min / 15 min / 30 min / 1 h; the picked one is yellow. */
    private fun showEstimateChips() {
        estimateChips.removeAllViews()
        val density = activity.resources.displayMetrics.density
        estimateChips.addView(TextView(activity).apply {
            setText(R.string.add_estimate)
            setTextColor(activity.getColor(R.color.mb_muted))
            textSize = 14f
            setPadding((4 * density).toInt(), 0, (8 * density).toInt(), 0)
        })
        for (min in listOf(5, 15, 30, 60)) {
            val selected = min == estimate
            estimateChips.addView(TextView(activity).apply {
                text = formatMinutes(min)
                gravity = Gravity.CENTER
                textSize = 14f
                // Background first: an inset drawable brings its own padding,
                // which would replace ours (the squashed chips of 2.9).
                background = activity.getDrawable(
                    if (selected) R.drawable.mb_chip_selected_inset else R.drawable.mb_chip_tonal_inset,
                )
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                setTextColor(activity.getColor(if (selected) R.color.mb_on_primary else R.color.mb_on_surface))
                pressable()
                onTap {
                    estimate = if (selected) null else min
                    showEstimateChips()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * density).toInt()).apply {
                marginEnd = (6 * density).toInt()
            })
        }
    }

    private fun updateSaveEnabled() {
        val ready = title.text.isNotBlank() && step.text.isNotBlank()
        saveButton.isEnabled = ready
        saveButton.alpha = if (ready) 1f else 0.4f
    }

    private fun submit() {
        val fields = JSONObject()
            .put("title", title.text.toString().trim())
            .put("first_step", step.text.toString().trim())
        planLocal?.let { fields.put("plan_at", it) }
        where.text.toString().trim().takeIf { it.isNotEmpty() }?.let { fields.put("plan_where", it) }
        estimate?.let { fields.put("estimate_min", it) }
        val typedLink = normalizeLink(link.text.toString())
        if (!typedLink.ok) {
            error.setText(R.string.link_invalid)
            error.visibility = View.VISIBLE
            return
        }
        typedLink.url?.let { fields.put("url", it) }
        saveButton.isEnabled = false
        error.visibility = View.GONE
        save(fields) { ok ->
            if (ok) {
                dialog.dismiss()
            } else {
                error.setText(R.string.add_failed)
                error.visibility = View.VISIBLE
                updateSaveEnabled()
            }
        }
    }
}
