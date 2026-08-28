package com.healthdataet.utdcredentials.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.SessionManager
import java.util.concurrent.TimeUnit

/**
 * The background half of Round 32's notification-reliability fix. The
 * previous build only ever polled once per FullSiteScreen composition and
 * never posted a real system notification either way -- so nothing rang
 * unless the app happened to be open AND Firebase happened to be
 * configured. This worker is the backstop for "app isn't open at all":
 * Android's own floor for a PeriodicWorkRequest is 15 minutes, so this is
 * the slow-but-always-on path; FullSiteScreen's foreground loop polls far
 * more often while the app is actually visible.
 *
 * Shares the exact same `lastNotificationId` watermark (SessionManager,
 * plain SharedPreferences -- readable/writable from any process/component)
 * as the foreground loop and UtdFirebaseMessagingService, so an event seen
 * by any one of the three paths is never re-shown by another.
 */
class NotificationPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val session = SessionManager(applicationContext)
        val token = session.apiToken ?: return Result.success() // not logged in -- nothing to poll for

        return try {
            val result = ApiClient(session.baseUrl).pollNotifications(token, session.lastNotificationId)
            if (result.ok && result.json != null) {
                val arr = result.json.optJSONArray("notifications")
                if (arr != null) {
                    var maxId = session.lastNotificationId
                    for (i in 0 until arr.length()) {
                        val n = arr.optJSONObject(i) ?: continue
                        val id = n.optLong("id", 0L)
                        // Already shown via FCM or an earlier poll -- see
                        // UtdFirebaseMessagingService's notif_id handling.
                        if (id <= session.lastNotificationId) continue
                        val title = n.optString("title").ifBlank { "UTD Credentials" }
                        val body = n.optString("body").ifBlank { "New notification" }
                        val category = n.optString("category")
                        NotificationChannels.postSystemNotification(
                            applicationContext, title, body, category, id.toInt()
                        )
                        if (id > maxId) maxId = id
                    }
                    session.lastNotificationId = maxId
                }
                Result.success()
            } else {
                // Network reachable, server said no -- not worth an
                // aggressive retry; the next scheduled run will try again.
                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "utd_notification_poll"

        /** Safe to call on every app start -- enqueueUniquePeriodicWork with
         * KEEP means this is a no-op if the periodic work is already
         * scheduled from a previous launch. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NotificationPollWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
