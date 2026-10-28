package tech.mourid.museboard

import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import java.util.Calendar
import java.util.Locale

/** A Dialog shown as a bottom sheet in the board's design: full width,
 *  pinned to the bottom, sliding up. [layout] draws its own background
 *  (mb_sheet_bg). With [keyboard] the sheet rises above the keyboard. */
fun bottomSheet(activity: Activity, layout: Int, keyboard: Boolean = false): Dialog {
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(layout)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
        setWindowAnimations(R.style.MuseBoard_SheetAnimation)
        if (keyboard) {
            @Suppress("DEPRECATION")
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
            )
        }
    }
    return dialog
}

/** A picked date-time: [local] is the offset-less Casablanca wall time the
 *  API expects ("2026-10-29T21:00"), [millis] the same instant. */
data class PickedTime(val local: String, val millis: Long)

/**
 * The phone's own date picker, then its time picker (native, One UI on
 * Samsung). Starts at [initialMillis], or the next full hour. Times are
 * Casablanca wall time, matching the API.
 */
fun pickDateTime(activity: Activity, initialMillis: Long?, onPicked: (PickedTime) -> Unit) {
    val start = Calendar.getInstance(CASABLANCA).apply {
        if (initialMillis != null) {
            timeInMillis = initialMillis
        } else {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
        }
    }
    DatePickerDialog(
        activity,
        { _, year, month, day ->
            TimePickerDialog(
                activity,
                { _, hour, minute ->
                    val picked = Calendar.getInstance(CASABLANCA).apply {
                        clear()
                        set(year, month, day, hour, minute)
                    }
                    val local = String.format(
                        Locale.US, "%04d-%02d-%02dT%02d:%02d", year, month + 1, day, hour, minute,
                    )
                    onPicked(PickedTime(local, picked.timeInMillis))
                },
                start.get(Calendar.HOUR_OF_DAY),
                start.get(Calendar.MINUTE),
                true,
            ).show()
        },
        start.get(Calendar.YEAR),
        start.get(Calendar.MONTH),
        start.get(Calendar.DAY_OF_MONTH),
    ).show()
}

/** A typed link as the API wants it: "zoom.us/j/1" → "https://zoom.us/j/1".
 *  Blank → null (clears it). Not a web address → null and [ok] false. */
data class NormalizedLink(val url: String?, val ok: Boolean)

fun normalizeLink(typed: String): NormalizedLink {
    val t = typed.trim()
    if (t.isEmpty()) return NormalizedLink(null, true)
    // Another scheme ("javascript:", "mailto:") is refused, not prefixed;
    // a dot before the colon means host:port ("zoom.us:443/x"), which is fine.
    if (!t.contains("://") && Regex("^[a-z][a-z0-9+-]*:", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
        return NormalizedLink(null, false)
    }
    val withScheme = if (t.contains("://")) t else "https://$t"
    val ok = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(withScheme)
    return NormalizedLink(if (ok) withScheme else null, ok)
}

/** Opens a task's go-to link (or a link from its notes) in the browser. A
 *  bare "zoom.us/j/1" gets https://. */
fun openLink(context: Context, url: String) {
    val uri = Uri.parse(if (url.contains("://")) url else "https://$url")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
        Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show()
    }
}
