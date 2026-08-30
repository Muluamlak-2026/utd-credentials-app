package com.healthdataet.utdcredentials.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Round 48h: real, working settings for how often this app checks the
 * server for new notifications -- not a cosmetic slider, an actual value
 * both poll loops read.
 *
 * Two completely separate numbers, because Android treats foreground and
 * background checking totally differently:
 *
 * - [foregroundSeconds] controls FullSiteScreen's own poll loop, which only
 *   runs while the app is actually open on screen. This has no real lower
 *   limit imposed by Android -- [MIN_FOREGROUND_SECONDS] here is just a
 *   sane floor to avoid hammering the server pointlessly. Changing this
 *   takes effect on the very next tick of that loop, no restart needed.
 *
 * - [backgroundMinutes] controls NotificationPollWorker, which is what
 *   checks for new notifications while the app is closed/backgrounded.
 *   Android's WorkManager enforces a HARD, documented, OS-level minimum of
 *   15 minutes for ANY app's periodic background work -- this is not a
 *   limitation of this app, and no app on the Play Store can go below it,
 *   full stop (Android does this to protect battery life across the whole
 *   phone). [MIN_BACKGROUND_MINUTES] enforces that floor here so this
 *   setting can never silently be set to a value Android would ignore
 *   anyway -- you CAN raise it above 15 (e.g. to 30 or 60) if you'd rather
 *   trade background responsiveness for battery, but never lower.
 */
class PollIntervalPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("utd_credentials_poll_interval", Context.MODE_PRIVATE)

    var foregroundSeconds: Int
        get() = prefs.getInt(KEY_FOREGROUND_SECONDS, DEFAULT_FOREGROUND_SECONDS)
        set(value) = prefs.edit()
            .putInt(KEY_FOREGROUND_SECONDS, value.coerceIn(MIN_FOREGROUND_SECONDS, MAX_FOREGROUND_SECONDS))
            .apply()

    var backgroundMinutes: Int
        get() = prefs.getInt(KEY_BACKGROUND_MINUTES, DEFAULT_BACKGROUND_MINUTES)
        set(value) = prefs.edit()
            .putInt(KEY_BACKGROUND_MINUTES, value.coerceIn(MIN_BACKGROUND_MINUTES, MAX_BACKGROUND_MINUTES))
            .apply()

    /** The background interval NotificationPollWorker is CURRENTLY actually
     * scheduled at -- separate from [backgroundMinutes] (what the admin
     * asked for) because a running WorkManager periodic request can't just
     * be nudged to a new interval; it has to be cancelled and re-enqueued.
     * Comparing these two is how [com.healthdataet.utdcredentials.push.NotificationPollWorker.schedule]
     * knows whether a reschedule is actually needed, so opening the app
     * doesn't reset the periodic timer on every single launch when nothing
     * changed. */
    var appliedBackgroundMinutes: Int
        get() = prefs.getInt(KEY_APPLIED_BACKGROUND_MINUTES, 0)
        set(value) = prefs.edit().putInt(KEY_APPLIED_BACKGROUND_MINUTES, value).apply()

    companion object {
        private const val KEY_FOREGROUND_SECONDS = "foreground_seconds"
        private const val KEY_BACKGROUND_MINUTES = "background_minutes"
        private const val KEY_APPLIED_BACKGROUND_MINUTES = "applied_background_minutes"

        const val DEFAULT_FOREGROUND_SECONDS = 30
        const val MIN_FOREGROUND_SECONDS = 10
        const val MAX_FOREGROUND_SECONDS = 300

        const val DEFAULT_BACKGROUND_MINUTES = 15
        // Android's real, hard, documented OS-level floor for ANY app's
        // PeriodicWorkRequest -- see androidx.work.PeriodicWorkRequest's own
        // MIN_PERIODIC_INTERVAL_MILLIS. WorkManager silently clamps anything
        // lower than this up to 15 minutes regardless of what's requested,
        // for every app on the platform, not just this one.
        const val MIN_BACKGROUND_MINUTES = 15
        const val MAX_BACKGROUND_MINUTES = 180
    }
}
