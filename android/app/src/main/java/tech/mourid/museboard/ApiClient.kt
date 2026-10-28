package tech.mourid.museboard

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for the Muse Board API at board.mourid.tech, using
 * only the framework HttpURLConnection (no third-party HTTP library).
 * All calls carry `Authorization: Bearer <token>`. A 401 throws
 * [UnauthorizedException] so callers can ask for a new token; any other
 * non-2xx response or network failure returns null / false — callers show
 * a retry state.
 */
class UnauthorizedException : Exception("board token rejected")

object ApiClient {
    const val BASE_URL = "https://board.mourid.tech"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** The board as raw JSON (cached by [BoardCache]), or null. */
    fun getBoardJson(token: String): String? = get(token, "$BASE_URL/api/board")

    fun getBoard(token: String): BoardResponse? =
        getBoardJson(token)?.let { parseBoard(JSONObject(it)) }

    fun getTasks(token: String): List<BoardTask>? {
        val body = get(token, "$BASE_URL/api/tasks") ?: return null
        val arr = JSONObject(body).optJSONArray("tasks") ?: return emptyList()
        val out = ArrayList<BoardTask>()
        for (i in 0 until arr.length()) out.add(parseTask(arr.getJSONObject(i)))
        return out
    }

    /** One task (the detail page), or null. */
    fun getTask(token: String, id: Int): BoardTask? =
        get(token, "$BASE_URL/api/tasks/$id")?.let { parseTask(JSONObject(it).getJSONObject("task")) }

    /** New task from the phone (quick-add). [fields]: the API's task fields. */
    fun createTask(token: String, fields: JSONObject): Boolean =
        post(token, "/api/tasks", fields.toString())

    /** Edit a task. POST to /api/tasks/:id is the API's PATCH alias:
     *  HttpURLConnection can't send PATCH on every Android version. */
    fun updateTask(token: String, id: Int, fields: JSONObject): Boolean =
        post(token, "/api/tasks/$id", fields.toString())

    fun completeTask(token: String, id: Int): Boolean =
        postAction(token, id, "complete")

    /** "Later": hide the task until [untilMillis] and re-plan it then. */
    fun snoozeTask(token: String, id: Int, untilMillis: Long): Boolean =
        postAction(token, id, "snooze", JSONObject().put("until", toIso(untilMillis)).toString())

    fun reopenTask(token: String, id: Int): Boolean =
        postAction(token, id, "reopen")

    private fun get(token: String, url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw UnauthorizedException()
            if (code !in 200..299) return null
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (e: UnauthorizedException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun postAction(token: String, id: Int, action: String, json: String? = null): Boolean =
        post(token, "/api/tasks/$id/$action", json)

    private fun post(token: String, path: String, json: String?): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val body = (json ?: "").toByteArray(Charsets.UTF_8)
            if (json != null) connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw UnauthorizedException()
            code in 200..299
        } catch (e: UnauthorizedException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }
}
