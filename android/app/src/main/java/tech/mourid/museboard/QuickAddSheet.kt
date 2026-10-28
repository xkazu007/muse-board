package tech.mourid.museboard

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject

/**
 * Quick-add from the phone (issue #6): title + first step (both required,
 * the AGENTS.md rule shown under the field), optional when / where. Saves
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
    private val error: TextView = dialog.findViewById(R.id.add_task_error)
    private val saveButton: Button = dialog.findViewById(R.id.add_task_save)
    private var planLocal: String? = null

    init {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSaveEnabled()
        }
        title.addTextChangedListener(watcher)
        step.addTextChangedListener(watcher)
        whenButton.setOnClickListener {
            pickDateTime(activity, null) { picked ->
                planLocal = picked.local
                whenButton.text = formatWhen(toIso(picked.millis))
            }
        }
        saveButton.setOnClickListener { submit() }
        updateSaveEnabled()
    }

    fun show() {
        dialog.show()
        title.requestFocus()
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
