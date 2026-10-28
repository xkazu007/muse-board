package tech.mourid.museboard

import android.animation.AnimatorInflater
import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout

/**
 * Touch feedback, One UI style: every tap answers at once with a light
 * haptic tick (it follows the phone's own "touch feedback" setting) and
 * a small press-in (animator/mb_press), on top of the ripple.
 */

/** A click listener with a haptic tick before [action]. */
fun View.onTap(action: (View) -> Unit) = setOnClickListener { v ->
    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    action(v)
}

/** Gives [this] the press-in animation; returns it for chaining. */
fun <T : View> T.pressable(): T {
    stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.mb_press)
    return this
}

/**
 * A rounded card that holds a list of rows, with thin inset dividers
 * between them (like the Clock app's alarm list). Rows are added with
 * [addCardRow]; the card clips them to its corners, so swipes stay inside.
 */
fun Activity.newListCard(parent: LinearLayout, topMargin: Int = 0): LinearLayout {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.mb_card_bg)
        clipToOutline = true
    }
    parent.addView(card, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin })
    return card
}

/** Adds [row] to [card], with a divider before it when it isn't the first. */
fun Activity.addCardRow(card: LinearLayout, row: View) {
    if (card.childCount > 0) {
        val inset = (20 * resources.displayMetrics.density).toInt()
        val hairline = maxOf(1, (0.75f * resources.displayMetrics.density).toInt())
        card.addView(
            View(this).apply { setBackgroundColor(getColor(R.color.mb_border)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hairline).apply {
                marginStart = inset
                marginEnd = inset
            },
        )
    }
    (row.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
    card.addView(row)
}
