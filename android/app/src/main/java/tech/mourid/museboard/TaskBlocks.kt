package tech.mourid.museboard

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
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
 * [onChange] gets the whole list after a tick, to save it back.
 */
class TaskBlocks(
    private val activity: Activity,
    private val container: LinearLayout,
    private val onChange: (JSONArray) -> Unit,
) {
    private var blocks = JSONArray()
    /** Toggles opened on this visit, by index, kept across a refresh. */
    private val open = mutableSetOf<Int>()

    fun show(json: String?) {
        blocks = try {
            if (json.isNullOrEmpty()) JSONArray() else JSONArray(json)
        } catch (_: Exception) {
            JSONArray()
        }
        container.removeAllViews()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "heading" -> container.addView(heading(b.optString("text")))
                "text" -> container.addView(body(b.optString("text"), top = 8))
                "button" -> container.addView(button(b.optString("label"), b.optString("url")))
                "check" -> container.addView(check(b))
                "toggle" -> container.addView(toggle(i, b))
                // A type this version doesn't know yet: skipped, not shown broken.
            }
        }
        container.visibility = if (container.childCount > 0) View.VISIBLE else View.GONE
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
        setOnClickListener { openLink(activity, url) }
    }

    private fun check(b: JSONObject): View {
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
        row.setOnClickListener {
            val done = !b.optBoolean("done")
            b.put("done", done)
            paint(done)
            if (done) row.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            onChange(blocks)
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
        head.setOnClickListener {
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
}
