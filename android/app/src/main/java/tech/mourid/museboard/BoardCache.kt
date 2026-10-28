package tech.mourid.museboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The last board fetched, plus a short-lived notice ("✓ Done. 2 today.").
 *
 * The cache lets a Done / Later tap update the widget instantly, before the
 * network round trip (delay hurts most with ADHD; see docs/research.md).
 * The notice outlives the refresh that follows the tap, so the feedback
 * stays on screen for [NOTICE_MS] instead of flashing for a second.
 */
object BoardCache {
    private const val PREFS = "muse_board_cache"
    private const val KEY_BOARD = "board_json"
    private const val KEY_NOTICE = "notice"
    private const val KEY_NOTICE_UNTIL = "notice_until"
    private const val NOTICE_MS = 60_000L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, json: String) {
        prefs(context).edit().putString(KEY_BOARD, json).apply()
    }

    fun load(context: Context): BoardResponse? {
        val json = prefs(context).getString(KEY_BOARD, null) ?: return null
        return try {
            parseBoard(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun setNotice(context: Context, text: String) {
        prefs(context).edit()
            .putString(KEY_NOTICE, text)
            .putLong(KEY_NOTICE_UNTIL, System.currentTimeMillis() + NOTICE_MS)
            .apply()
    }

    /** The current notice, or null once it has expired. */
    fun notice(context: Context): String? {
        val p = prefs(context)
        if (System.currentTimeMillis() > p.getLong(KEY_NOTICE_UNTIL, 0L)) return null
        return p.getString(KEY_NOTICE, null)
    }
}
