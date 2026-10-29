package tech.mourid.museboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The detail page's dynamic content: blocks Muse writes into a task
 * (`blocks` in the API). Headings, text (links tappable), link buttons,
 * checklist items the owner ticks, and toggles that expand. Built with
 * plain framework views, in the board's tokens.
 *
 * The checklist is the task's subtasks, and the owner edits it too:
 * "+ Add step" under the last item (Enter adds and opens the next, an empty
 * Enter closes), long-press an item to rename or delete it.
 *
 * [onChange] gets the whole list after any change, to save it back.
 */
class TaskBlocks(
    private val activity: Activity,
    private val container: LinearLayout,
    private val onChange: (JSONArray) -> Unit,
) {
    private var blocks = JSONArray()
    /** Toggles opened on this visit, by index, kept across a refresh. */
    private val open = mutableSetOf<Int>()
    /** The "+ Add step" input is open (kept across a re-render). */
    private var adding = false

    fun show(json: String?) {
        blocks = try {
            if (json.isNullOrEmpty()) JSONArray() else JSONArray(json)
        } catch (_: Exception) {
            JSONArray()
        }
        render()
    }

    private fun render() {
        container.removeAllViews()
        var afterLastCheck = -1
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "heading" -> container.addView(heading(b.optString("text")))
                "text" -> container.addView(body(b.optString("text"), top = 8))
                "button" -> container.addView(button(b.optString("label"), b.optString("url")))
                "check" -> {
                    container.addView(check(i, b))
                    afterLastCheck = container.childCount
                }
                "toggle" -> container.addView(toggle(i, b))
                // A type this version doesn't know yet: skipped, not shown broken.
            }
        }
        // "+ Add step" (or its input) right under the checklist, else last.
        val at = if (afterLastCheck >= 0) afterLastCheck else container.childCount
        container.addView(if (adding) stepInput() else addStepRow(), at)
        container.visibility = View.VISIBLE
    }

    // --- Editing the checklist ----------------------------------------------

    private fun addStepRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        background = activity.getDrawable(R.drawable.mb_ripple_rounded)
        setPadding(dp(8), 0, dp(8), 0)
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.mb_ic_plus_accent24)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(TextView(activity).apply {
            setText(R.string.add_step)
            setTextColor(activity.getColor(R.color.mb_accent))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        })
        pressable()
        onTap {
            adding = true
            render()
        }
    }

    private fun stepInput(): View {
        val input = EditText(activity).apply {
            setHint(R.string.step_hint)
            background = activity.getDrawable(R.drawable.mb_field_bg)
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(activity.getColor(R.color.mb_on_surface))
            setHintTextColor(activity.getColor(R.color.mb_muted))
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(MAX_STEP))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        // Each input is used once: re-rendering removes it, which also takes
        // its focus away, and that must not add the same step twice.
        var used = false
        // Enter: add it and keep going; an empty Enter closes.
        input.setOnEditorActionListener { _, _, _ ->
            if (used) return@setOnEditorActionListener true
            used = true
            val text = input.text.toString().trim()
            if (text.isEmpty()) closeInput(input) else addStep(text, keepOpen = true)
            true
        }
        // Tapping elsewhere: keep what was typed, then close.
        input.setOnFocusChangeListener { _, focused ->
            if (focused || used || !adding) return@setOnFocusChangeListener
            used = true
            val text = input.text.toString().trim()
            container.post { if (text.isEmpty()) closeInput(input) else addStep(text, keepOpen = false) }
        }
        input.post {
            input.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        return FrameLayout(activity).apply {
            setPadding(0, dp(4), 0, dp(4))
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(48)))
        }
    }

    private fun closeInput(input: EditText) {
        adding = false
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
        render()
    }

    /** A new unticked step, right after the last one (or at the end). */
    private fun addStep(text: String, keepOpen: Boolean) {
        val lastCheck = (0 until blocks.length()).lastOrNull { blocks.optJSONObject(it)?.optString("type") == "check" }
        val at = (lastCheck ?: (blocks.length() - 1)) + 1
        val step = JSONObject().put("type", "check").put("text", text).put("done", false)
        val next = JSONArray()
        for (i in 0 until blocks.length()) {
            if (i == at) next.put(step)
            next.put(blocks.get(i))
        }
        if (at >= blocks.length()) next.put(step)
        blocks = next
        adding = keepOpen
        onChange(blocks)
        render()
    }

    /** Long-press a step: Rename / Delete, in the bottom sheet. */
    private fun editStep(index: Int, b: JSONObject) {
        ActionSheet(activity, b.optString("text"))
            .add(R.drawable.mb_ic_edit24, activity.getString(R.string.step_rename)) { renameStep(b) }
            .add(R.drawable.mb_ic_delete24, activity.getString(R.string.step_delete)) {
                val next = JSONArray()
                for (i in 0 until blocks.length()) if (i != index) next.put(blocks.get(i))
                blocks = next
                onChange(blocks)
                render()
            }
            .show()
    }

    private fun renameStep(b: JSONObject) {
        val input = EditText(activity).apply {
            setText(b.optString("text"))
            setSelection(text.length)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_STEP))
        }
        val box = FrameLayout(activity).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.step_rename_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty() && text != b.optString("text")) {
                    b.put("text", text)
                    onChange(blocks)
                    render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun heading(text: String) = TextView(activity).apply {
        this.text = text
        setTextColor(activity.getColor(R.color.mb_on_surface))
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(18), dp(4), dp(2))
    }

    private fun body(text: String, top: Int) = TextView(activity).apply {
        autoLinkMask = Linkify.WEB_URLS
        this.text = text
        movementMethod = LinkMovementMethod.getInstance()
        setTextColor(activity.getColor(R.color.mb_on_surface))
        setLinkTextColor(activity.getColor(R.color.mb_accent))
        textSize = 15f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(4), dp(top), dp(4), 0)
    }

    private fun button(label: String, url: String) = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(activity.getColor(R.color.mb_on_surface))
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        background = activity.getDrawable(R.drawable.mb_button_tonal)
        foreground = activity.getDrawable(R.drawable.mb_ripple_rounded)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.mb_ic_link24, 0, 0, 0)
        compoundDrawablePadding = dp(10)
        setPadding(dp(18), 0, dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            .apply { topMargin = dp(10) }
        pressable()
        onTap { openLink(activity, url) }
    }

    private fun check(index: Int, b: JSONObject): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            background = activity.getDrawable(R.drawable.mb_ripple_rounded)
        }
        val box = CheckBox(activity).apply {
            isChecked = b.optBoolean("done")
            buttonTintList = ColorStateList.valueOf(activity.getColor(R.color.mb_accent))
            // The row takes the tap, so the whole line is the target.
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(activity).apply {
            text = b.optString("text")
            setTextColor(activity.getColor(R.color.mb_on_surface))
            textSize = 15f
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        fun paint(done: Boolean) {
            box.isChecked = done
            label.alpha = if (done) 0.55f else 1f
            label.paintFlags = if (done) label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else label.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            row.contentDescription = b.optString("text")
            row.announce(done)
        }
        paint(b.optBoolean("done"))
        row.addView(box)
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.onTap {
            val done = !b.optBoolean("done")
            b.put("done", done)
            paint(done)
            onChange(blocks)
        }
        row.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            editStep(index, b)
            true
        }
        return row
    }

    private fun toggle(index: Int, b: JSONObject): View {
        val wrap = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val chevron = ImageView(activity).apply {
            setImageResource(R.drawable.mb_ic_chevron_right24)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            background = activity.getDrawable(R.drawable.mb_ripple_rounded)
            setPadding(0, 0, dp(4), 0)
        }
        head.addView(chevron, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(4) })
        head.addView(TextView(activity).apply {
            text = b.optString("text")
            setTextColor(activity.getColor(R.color.mb_on_surface))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(10), 0, dp(10))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val content = body(b.optString("body"), top = 0).apply {
            setPadding(dp(38), 0, dp(4), dp(8))
        }
        fun paint(expanded: Boolean, animate: Boolean) {
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            val angle = if (expanded) 90f else 0f
            if (animate) chevron.animate().rotation(angle).setDuration(150).start() else chevron.rotation = angle
            head.contentDescription = b.optString("text")
            head.announce(expanded, toggle = true)
        }
        paint(index in open, animate = false)
        head.onTap {
            val expanded = index !in open
            if (expanded) open.add(index) else open.remove(index)
            paint(expanded, animate = true)
        }
        wrap.addView(head)
        wrap.addView(content)
        return wrap
    }

    /** "Done" / "Not done" (or "Expanded" / "Collapsed") for TalkBack. */
    private fun View.announce(on: Boolean, toggle: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        stateDescription = activity.getString(
            when {
                toggle && on -> R.string.block_expanded
                toggle -> R.string.block_collapsed
                on -> R.string.block_done
                else -> R.string.block_not_done
            },
        )
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        /** The API's limit for a check's text. */
        const val MAX_STEP = 200
    }
}
