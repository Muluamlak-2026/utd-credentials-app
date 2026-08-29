package com.healthdataet.utdcredentials.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Holds the admin panel base URL, the bearer token from /api/v1/login, the
 * last-seen notification id (for /api/v1/notifications/poll), and the
 * device's current FCM token -- plain SharedPreferences, since nothing
 * stored here is the admin's password (that's only ever held in memory
 * long enough to submit the native login and, once, the embedded web
 * login -- see PendingWebLogin).
 */
class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("utd_credentials_session", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trimEnd('/')).apply()

    var apiToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var lastNotificationId: Long
        get() = prefs.getLong(KEY_LAST_NOTIF_ID, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_NOTIF_ID, value).apply()

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    /**
     * Round 42: the in-app bell badge's actual data source. Previously the
     * badge (FullSiteScreen's `notificationCount`) was a plain Compose
     * `remember` variable local to that one screen, so it only ever
     * reflected THAT screen's own foreground poll loop -- the WorkManager
     * backstop (NotificationPollWorker) and Firebase
     * (UtdFirebaseMessagingService) could both successfully show a real
     * system notification and the badge would still never move, since
     * neither of them touched that local state (and couldn't -- it isn't
     * shared, and generally isn't even alive while the app is backgrounded
     * or closed). Backed by the same shared SharedPreferences file as
     * [lastNotificationId]/[fcmToken] above, so it's readable/writable from
     * any process/component, exactly like those two already are.
     *
     * Written from [incrementUnreadNotificationCount], called once from
     * NotificationChannels.postSystemNotification -- the single function
     * FCM, the foreground poll loop, and the WorkManager worker all funnel
     * through -- so all three now feed the same counter FullSiteScreen's
     * bell displays, instead of three disconnected pieces of state.
     */
    var unreadNotificationCount: Int
        get() = prefs.getInt(KEY_UNREAD_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_UNREAD_COUNT, value).apply()

    /** Bumps [unreadNotificationCount] by one. Synchronized on the class
     * (not `this` -- a new SessionManager is constructed at every call
     * site) so two notifications landing at nearly the same moment from
     * different components (e.g. a poll tick and an FCM message) in this
     * single-process app don't race and lose an increment. */
    fun incrementUnreadNotificationCount() {
        synchronized(SessionManager::class.java) {
            unreadNotificationCount = unreadNotificationCount + 1
        }
    }

    val isLoggedIn: Boolean
        get() = !apiToken.isNullOrBlank()

    /** Logout: drop the API token, but keep baseUrl/lastNotificationId so
     * the next login doesn't have to re-enter the URL or re-fetch history
     * that was already seen. */
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_FCM_TOKEN).apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "api_token"
        private const val KEY_LAST_NOTIF_ID = "last_notification_id"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_UNREAD_COUNT = "unread_notification_count"
        const val DEFAULT_BASE_URL = "https://bot.healthdataet.com"
    }
}
