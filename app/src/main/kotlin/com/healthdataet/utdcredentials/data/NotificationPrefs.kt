package com.healthdataet.utdcredentials.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Round 33: per-category enable/disable, on top of the existing per-category
 * SOUND choice (SoundPrefs). "Add inbuilt notification/vibration/sound
 * options for the 4 actions with a method to select to receive
 * notification from all those or only from selected" -- this is that
 * selection. Every category defaults to enabled (true), matching the
 * app's existing out-of-the-box behavior, so nobody who never opens this
 * screen loses any alerts they already had.
 *
 * Checked by NotificationChannels.postSystemNotification -- the single
 * shared function FCM, the foreground poll loop, and the WorkManager
 * background worker all call -- so a category turned off here is silent
 * across all three delivery paths at once, not just one of them.
 */
class NotificationPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("utd_credentials_notification_prefs", Context.MODE_PRIVATE)

    fun isEnabled(category: String): Boolean = prefs.getBoolean(keyFor(category), true)

    fun setEnabled(category: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyFor(category), enabled).apply()
    }

    private fun keyFor(category: String) = "enabled_$category"
}
