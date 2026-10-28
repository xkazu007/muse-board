package tech.mourid.museboard

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class BoardTask(
    val id: Int,
    val title: String,
    val detail: String?,
    val dueAt: String?,
    val position: Int,
    val status: String,
)

data class BoardResponse(
    val date: String,
    val focus: BoardTask?,
    val upcoming: List<BoardTask>,
    val doneToday: Int,
)

private val CASABLANCA: TimeZone = TimeZone.getTimeZone("Africa/Casablanca")

fun parseTask(obj: JSONObject): BoardTask {
    val detail = obj.optString("detail").takeIf { it.isNotEmpty() }
    val dueAt = obj.optString("due_at").takeIf { it.isNotEmpty() }
    return BoardTask(
        id = obj.getInt("id"),
        title = obj.getString("title"),
        detail = detail,
        dueAt = dueAt,
        position = obj.optInt("position", 0),
        status = obj.optString("status", "open"),
    )
}

fun parseBoard(obj: JSONObject): BoardResponse {
    val focus = if (obj.isNull("focus")) null else parseTask(obj.getJSONObject("focus"))
    val upcoming = ArrayList<BoardTask>()
    val arr = obj.optJSONArray("upcoming")
    if (arr != null) {
        for (i in 0 until arr.length()) upcoming.add(parseTask(arr.getJSONObject(i)))
    }
    return BoardResponse(
        date = obj.optString("date", ""),
        focus = focus,
        upcoming = upcoming,
        doneToday = obj.optInt("doneToday", 0),
    )
}

/** "Wed 28 Oct, 16:00" in Casablanca time, or "" when unparseable. */
fun formatDue(iso: String?): String {
    if (iso.isNullOrEmpty()) return ""
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        parser.timeZone = TimeZone.getTimeZone("UTC")
        val parsed = parser.parse(iso) ?: return ""
        val out = SimpleDateFormat("EEE d MMM, HH:mm", Locale.UK)
        out.timeZone = CASABLANCA
        out.format(parsed)
    } catch (_: Exception) {
        ""
    }
}

/** True once it is 20:00 or later in Casablanca — the "still waiting" state. */
fun isAfterEightPm(): Boolean {
    val cal = Calendar.getInstance(CASABLANCA)
    return cal.get(Calendar.HOUR_OF_DAY) >= 20
}
