package com.healthdataet.utdcredentials.data

import android.content.Context

/**
 * Per-category chosen notification sound, stored as a Uri string (null =
 * "use this category's distinct built-in default" -- see
 * push.NotificationChannels.defaultSoundFor). Set by SoundSettingsScreen
 * whenever the admin picks a sound via the system ringtone picker.
 */
class SoundPrefs(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("utd_credentials_sounds", Context.MODE_PRIVATE)

    fun getSoundUri(category: String): String? = prefs.getString(KEY_PREFIX + category, null)

    fun setSoundUri(category: String, uriString: String?) {
        prefs.edit().putString(KEY_PREFIX + category, uriString).apply()
    }

    companion object {
        private const val KEY_PREFIX = "sound_"
    }
}
