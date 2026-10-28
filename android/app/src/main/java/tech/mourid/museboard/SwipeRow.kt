package tech.mourid.museboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * A row you can swipe, framework-only (no AndroidX ItemTouchHelper).
 *
 * Children: [0] the background with two labels (`swipe_label_start`, shown
 * when swiping right; `swipe_label_end`, shown when swiping left), [1] the
 * foreground content. Swipe right past [THRESHOLD] of the width runs
 * [onSwipeRight] (Done), swipe left runs [onSwipeLeft] (Later); a side with
 * no action doesn't move. Haptic tick when crossing the threshold, like
 * One UI's swipe-to-call. Taps and long-presses on the foreground still
 * work: the row only takes over once the finger clearly moves sideways.
 */
class SwipeRow(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    var onSwipeRight: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null

    /** Background colour behind a right swipe (Done) and a left swipe (Later). */
    var rightColor = 0
    var leftColor = 0
    var cornerRadius = 0f

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var pastThreshold = false

    private val backLayer: View get() = getChildAt(0)
    private val frontLayer: View get() = getChildAt(1)

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                pastThreshold = false
            }
            MotionEvent.ACTION_MOVE -> if (startsDrag(e)) return true
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !startsDrag(e)) return true
                val dx = allowed(e.x - downX)
                frontLayer.translationX = dx
                paintBackground(dx)
                val past = abs(dx) > width * THRESHOLD
                if (past != pastThreshold) {
                    pastThreshold = past
                    haptic(past)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = frontLayer.translationX
                if (dragging && abs(dx) > width * THRESHOLD) commit(dx) else settle()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                settle()
                dragging = false
            }
        }
        return true
    }

    /** Back to rest, e.g. after a Later dialog was cancelled. */
    fun reset() {
        frontLayer.animate().cancel()
        frontLayer.translationX = 0f
        paintBackground(0f)
    }

    private fun startsDrag(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f && allowed(dx) != 0f) {
            dragging = true
            downX += if (dx > 0) slop else -slop // no jump at the start
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return false
    }

    private fun allowed(dx: Float): Float = when {
        dx > 0 && onSwipeRight != null -> dx
        dx < 0 && onSwipeLeft != null -> dx
        else -> 0f
    }

    private fun paintBackground(dx: Float) {
        val bg = backLayer
        if (dx == 0f) {
            bg.background = null
            return
        }
        bg.background = GradientDrawable().apply {
            cornerRadius = this@SwipeRow.cornerRadius
            setColor(if (dx > 0) rightColor else leftColor)
        }
        bg.findViewById<TextView>(R.id.swipe_label_start)?.visibility = if (dx > 0) View.VISIBLE else View.INVISIBLE
        bg.findViewById<TextView>(R.id.swipe_label_end)?.visibility = if (dx < 0) View.VISIBLE else View.INVISIBLE
    }

    private fun commit(dx: Float) {
        val action = if (dx > 0) onSwipeRight else onSwipeLeft
        frontLayer.animate()
            .translationX(if (dx > 0) width.toFloat() else -width.toFloat())
            .setDuration(160)
            .withEndAction { action?.invoke() }
            .start()
    }

    private fun settle() {
        frontLayer.animate()
            .translationX(0f)
            .setDuration(180)
            .withEndAction { paintBackground(0f) }
            .start()
    }

    private fun haptic(activate: Boolean) {
        val constant = when {
            Build.VERSION.SDK_INT >= 34 && activate -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            activate -> HapticFeedbackConstants.VIRTUAL_KEY
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        performHapticFeedback(constant)
    }

    private companion object {
        const val THRESHOLD = 0.33f
    }
}
