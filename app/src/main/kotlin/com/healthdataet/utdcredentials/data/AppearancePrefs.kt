package com.healthdataet.utdcredentials.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Round 33's "brightness, background, app logo" ask, scoped to what an
 * app can safely and reliably control on its own: a system/light/dark
 * theme choice and an accent color -- both applied instantly, in-app, with
 * no special permissions. True system-wide screen brightness needs the
 * WRITE_SETTINGS special permission (a separate manual grant in Android's
 * own Settings app, unusual for a niche admin tool) and a dynamically
 * swappable launcher icon needs activity-alias plumbing that risks
 * breaking the icon Android already has cached for this app -- both are
 * called out as descoped in this round's delivery notes rather than
 * silently attempted and half-working.
 */
class AppearancePrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("utd_credentials_appearance_prefs", Context.MODE_PRIVATE)

    init {
        // Round 42 fix: every AppearancePrefs instance -- there are several,
        // one per screen/composable that touches appearance -- syncs the
        // shared, process-wide StateFlows below from whatever is actually
        // on disk. That's what lets ui/theme/Theme.kt's root composable
        // observe changes made from a completely different instance (e.g.
        // AppSettingsScreen's) via collectAsState(), instead of only ever
        // seeing the value that was current when IT was constructed.
        // Re-assigning a StateFlow to its current value is a no-op for
        // collectors (StateFlow conflates by equality), so this is safe to
        // run on every construction, not just the first.
        themeModeFlow.value = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        accentKeyFlow.value = prefs.getString(KEY_ACCENT, ACCENT_INDIGO) ?: ACCENT_INDIGO
    }

    /**
     * Round 42 fix: previously these read/wrote SharedPreferences directly.
     * That persisted correctly, but nothing about a plain SharedPreferences
     * read is observable to Compose -- so ui/theme/Theme.kt's root
     * MaterialTheme wrapper, which read these same properties, had no way
     * to know a value had changed and never recomposed. The only thing
     * that visibly updated was AppSettingsScreen's own local preview
     * (its `refreshTick` remember-state), which is the "only demo colour
     * changes" bug. Routing both properties through the shared StateFlows
     * below -- still backed by, and still persisted to, the same
     * SharedPreferences -- makes a change collectAsState()-observable
     * from any screen, live, with no restart required.
     */
    var themeMode: String
        get() = themeModeFlow.value
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value).apply()
            themeModeFlow.value = value
        }

    /** One of ACCENT_ORDER's keys. */
    var accentKey: String
        get() = accentKeyFlow.value
        set(value) {
            prefs.edit().putString(KEY_ACCENT, value).apply()
            accentKeyFlow.value = value
        }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_ACCENT = "accent_key"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        val THEME_ORDER = listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)
        fun themeLabel(mode: String) = when (mode) {
            THEME_LIGHT -> "Light"
            THEME_DARK -> "Dark"
            else -> "Match system"
        }

        const val ACCENT_INDIGO = "indigo"
        const val ACCENT_BLUE = "blue"
        const val ACCENT_GREEN = "green"
        const val ACCENT_ORANGE = "orange"
        const val ACCENT_RED = "red"
        val ACCENT_ORDER = listOf(ACCENT_INDIGO, ACCENT_BLUE, ACCENT_GREEN, ACCENT_ORANGE, ACCENT_RED)
        fun accentLabel(key: String) = when (key) {
            ACCENT_BLUE -> "Blue"
            ACCENT_GREEN -> "Green"
            ACCENT_ORANGE -> "Orange"
            ACCENT_RED -> "Red"
            else -> "Indigo"
        }
        /** ARGB int (not a Compose Color, to keep this file free of any
         * Compose dependency) -- Theme.kt maps these to real Color values. */
        fun accentArgb(key: String): Long = when (key) {
            ACCENT_BLUE -> 0xFF2563EB
            ACCENT_GREEN -> 0xFF16A34A
            ACCENT_ORANGE -> 0xFFEA580C
            ACCENT_RED -> 0xFFDC2626
            else -> 0xFF6366F1
        }

        // Round 42 fix: process-wide (one per app process, not one per
        // AppearancePrefs instance -- companion object members are shared
        // across every instance) so a write made through ANY instance is
        // visible, live, to a collectAsState() call anywhere else in the
        // Compose tree. Kept private + exposed as read-only StateFlow so
        // nothing outside this class can push a value that skips writing
        // through to SharedPreferences via the setters above.
        private val themeModeFlow = MutableStateFlow(THEME_SYSTEM)
        private val accentKeyFlow = MutableStateFlow(ACCENT_INDIGO)

        val themeModeState: StateFlow<String> get() = themeModeFlow
        val accentKeyState: StateFlow<String> get() = accentKeyFlow
    }
}
