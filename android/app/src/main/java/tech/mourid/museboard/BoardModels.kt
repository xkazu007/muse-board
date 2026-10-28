package tech.mourid.museboard

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class BoardTask(
    val id: Int,
    val title: String,
    val detail: String?,
    /** The two-minute starter; shown big when present. */
    val firstStep: String?,
    val dueAt: String?,
    /** "When" of the plan: the phone reminds at this time. */
    val planAt: String?,
    /** "Where" of the plan, e.g. "desk". */
    val planWhere: String?,
    val position: Int,
    val status: String,
)

data class BoardResponse(
    val date: String,
    val focus: BoardTask?,
    /** Becomes the focus when the current one is done or snoozed. */
    val next: BoardTask?,
    val upcoming: List<BoardTask>,
    /** Tasks with a plan in the next 36 hours, for reminders. */
    val plans: List<BoardTask>,
    val doneToday: Int,
)

/** The board's zone. Device tz data decides the offset (UTC+0 since
 *  2026-09-20), so times match the phone's clock. */
val CASABLANCA: TimeZone = TimeZone.getTimeZone("Africa/Casablanca")

/** The string at [key], or null when it is missing, JSON null or empty.
 *  (optString alone turns JSON null into the text "null".) */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun parseTask(obj: JSONObject): BoardTask = BoardTask(
    id = obj.getInt("id"),
    title = obj.getString("title"),
    detail = obj.stringOrNull("detail"),
    firstStep = obj.stringOrNull("first_step"),
    dueAt = obj.stringOrNull("due_at"),
    planAt = obj.stringOrNull("plan_at"),
    planWhere = obj.stringOrNull("plan_where"),
    position = obj.optInt("position", 0),
    status = obj.optString("status", "open"),
)

private fun JSONObject.taskOrNull(key: String): BoardTask? =
    if (isNull(key)) null else parseTask(getJSONObject(key))

private fun JSONObject.tasks(key: String): List<BoardTask> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
}

fun parseBoard(obj: JSONObject): BoardResponse = BoardResponse(
    date = obj.optString("date", ""),
    focus = obj.taskOrNull("focus"),
    next = obj.taskOrNull("next"),
    upcoming = obj.tasks("upcoming"),
    plans = obj.tasks("plans"),
    doneToday = obj.optInt("doneToday", 0),
)

// --- Time ------------------------------------------------------------------

private fun isoParser() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

/** Epoch millis of an API timestamp, or null when unparseable. */
fun parseIso(iso: String?): Long? {
    if (iso.isNullOrEmpty()) return null
    return try {
        isoParser().parse(iso)?.time
    } catch (_: Exception) {
        null
    }
}

/** An API timestamp ("2026-10-28T21:00:00.000Z") for epoch millis. */
fun toIso(millis: Long): String = isoParser().format(Date(millis))

private fun casablanca(pattern: String) = SimpleDateFormat(pattern, Locale.UK).apply {
    timeZone = CASABLANCA
}

private fun dayIndex(millis: Long): Long {
    val offset = CASABLANCA.getOffset(millis)
    return Math.floorDiv(millis + offset, 86_400_000L)
}

/** Short and relative, in Casablanca: "21:00", "Tomorrow 09:00",
 *  "Fri 12:00" (this week), "28 Nov". "" when unparseable. */
fun formatWhen(iso: String?, now: Long = System.currentTimeMillis()): String {
    val t = parseIso(iso) ?: return ""
    return when (dayIndex(t) - dayIndex(now)) {
        0L -> casablanca("HH:mm").format(Date(t))
        1L -> "Tomorrow " + casablanca("HH:mm").format(Date(t))
        in 2L..6L -> casablanca("EEE HH:mm").format(Date(t))
        else -> casablanca("d MMM").format(Date(t))
    }
}

/** Kept for the app's task list: "Wed 28 Oct, 16:00", or "". */
fun formatDue(iso: String?): String {
    val t = parseIso(iso) ?: return ""
    return casablanca("EEE d MMM, HH:mm").format(Date(t))
}

/** The focus line above the step: "Now · 21:00 · desk", else the
 *  deadline ("Now · by 23:59"). */
fun focusMeta(task: BoardTask): String {
    val parts = mutableListOf("Now")
    when {
        task.planAt != null -> parts.add(formatWhen(task.planAt))
        task.dueAt != null -> parts.add("by " + formatWhen(task.dueAt))
    }
    task.planWhere?.let { parts.add(it) }
    return parts.filter { it.isNotEmpty() }.joinToString(" · ")
}

/** "NutriSynth prep, Fri 12:00" for the "Then:" line. */
fun thenLine(task: BoardTask): String {
    val whenText = formatWhen(task.planAt ?: task.dueAt)
    return if (whenText.isEmpty()) task.title else "${task.title}, $whenText"
}

/** "Later" choices in Casablanca time: in 1 hour, tonight 20:00 (only
 *  before 19:00), tomorrow 09:00. Pairs of label resource and epoch millis. */
fun laterChoices(now: Long = System.currentTimeMillis()): List<Pair<Int, Long>> {
    val choices = mutableListOf(R.string.later_in_hour to now + 3_600_000L)
    val cal = Calendar.getInstance(CASABLANCA).apply { timeInMillis = now }
    if (cal.get(Calendar.HOUR_OF_DAY) < 19) {
        val tonight = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        choices.add(R.string.later_tonight to tonight.timeInMillis)
    }
    val tomorrow = (cal.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    choices.add(R.string.later_tomorrow to tomorrow.timeInMillis)
    return choices
}
