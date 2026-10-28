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

    fun getBoard(token: String): BoardResponse? {
        val body = get(token, "$BASE_URL/api/board") ?: return null
        return parseBoard(JSONObject(body))
    }

    fun getTasks(token: String): List<BoardTask>? {
        val body = get(token, "$BASE_URL/api/tasks") ?: return null
        val arr = JSONObject(body).optJSONArray("tasks") ?: return emptyList()
        val out = ArrayList<BoardTask>()
        for (i in 0 until arr.length()) out.add(parseTask(arr.getJSONObject(i)))
        return out
    }

    fun completeTask(token: String, id: Int): Boolean =
        postAction(token, id, "complete")

    fun snoozeTask(token: String, id: Int): Boolean =
        postAction(token, id, "snooze")

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

    private fun postAction(token: String, id: Int, action: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$BASE_URL/api/tasks/$id/$action").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $token")
                // Empty body: the API only needs the id + action in the path.
                setRequestProperty("Content-Length", "0")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            // Touch the output stream so the empty POST is actually sent.
            connection.outputStream.use { it.flush() }
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
