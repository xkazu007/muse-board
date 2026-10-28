package tech.mourid.museboard

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * The app's own light / dark choice (issue #11): Auto (follow the phone),
 * Light or Dark. The widget is not affected: it always follows the
 * launcher.
 *
 * Android 12+: UiModeManager.setApplicationNightMode, which the system
 * remembers and applies to every screen of the app (and recreates them).
 * Older: each screen applies it in attachBaseContext via [wrap].
 */
object AppTheme {
    enum class Mode { AUTO, LIGHT, DARK }

    private const val PREFS = "muse_board_ui"
    private const val KEY = "theme"

    fun get(context: Context): Mode =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { name -> Mode.values().firstOrNull { it.name == name } }
            ?: Mode.AUTO

    /** Auto → Light → Dark → Auto. */
    fun cycle(activity: Activity) {
        val next = Mode.values()[(get(activity).ordinal + 1) % Mode.values().size]
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.name).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSystemService(UiModeManager::class.java).setApplicationNightMode(
                when (next) {
                    Mode.AUTO -> UiModeManager.MODE_NIGHT_AUTO
                    Mode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    Mode.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        } else {
            activity.recreate()
        }
    }

    /** Before Android 12: a context with the chosen night mode, for
     *  attachBaseContext. Android 12+ returns [base] (the system does it). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return base
        val mode = get(base)
        if (mode == Mode.AUTO) return base
        val config = Configuration(base.resources.configuration)
        val night = if (mode == Mode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    fun icon(mode: Mode): Int = when (mode) {
        Mode.AUTO -> R.drawable.mb_ic_theme_auto24
        Mode.LIGHT -> R.drawable.mb_ic_theme_light24
        Mode.DARK -> R.drawable.mb_ic_theme_dark24
    }

    fun label(mode: Mode): Int = when (mode) {
        Mode.AUTO -> R.string.theme_auto
        Mode.LIGHT -> R.string.theme_light
        Mode.DARK -> R.string.theme_dark
    }
}
