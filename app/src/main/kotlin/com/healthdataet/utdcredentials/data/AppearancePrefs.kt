package com.healthdataet.utdcredentials.data

import android.content.Context
import android.content.SharedPreferences

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

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    /** One of ACCENT_ORDER's keys. */
    var accentKey: String
        get() = prefs.getString(KEY_ACCENT, ACCENT_INDIGO) ?: ACCENT_INDIGO
        set(value) = prefs.edit().putString(KEY_ACCENT, value).apply()

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
    }
}
