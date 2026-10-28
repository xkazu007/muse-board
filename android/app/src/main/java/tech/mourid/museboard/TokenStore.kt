package tech.mourid.museboard

import android.content.Context
import android.content.SharedPreferences

/**
 * Board token storage, plain SharedPreferences (MODE_PRIVATE).
 *
 * v1 deliberately skips EncryptedSharedPreferences: the encrypted store
 * needs the androidx.security:security-crypto library, which this build
 * does not carry (see README — the VM Gradle build is blocked, so the app
 * is built dependency-free). The token is a single random bearer token,
 * not a user password, and the file is private to the app. If encrypted
 * storage is wanted later, swap this object back to
 * EncryptedSharedPreferences and re-add the dependency.
 */
object TokenStore {
    private const val PREFS = "muse_board"
    private const val KEY_TOKEN = "board_token"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun set(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }
}
