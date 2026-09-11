package com.healthdataet.utdcredentials.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.data.offline.SyncRepository
import java.util.concurrent.TimeUnit

/**
 * Round 57: the periodic backstop for offline sync -- same role
 * NotificationPollWorker plays for notifications. [ConnectivitySyncTrigger]
 * already syncs the moment the network comes back, which covers the
 * common case; this exists for the times that callback doesn't fire (the
 * network was already up when a change was made offline, a missed
 * callback, etc.) so pending changes never wait indefinitely for the next
 * explicit reconnect event. Constrained to NetworkType.CONNECTED -- if
 * there's no network when this fires, it's a guaranteed no-op, so there's
 * no point spending battery starting the foreground service for it.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val session = SessionManager(applicationContext)
            if (!session.isLoggedIn) return Result.success()
            if (!SyncRepository.hasPendingChanges(applicationContext)) return Result.success()
            SyncForegroundService.start(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "utd_offline_sync_backstop"
        private const val INTERVAL_MINUTES = 30L // Android's own floor for PeriodicWorkRequest is 15

        /** Safe to call on every app start -- enqueueUniquePeriodicWork with
         * KEEP means this only actually schedules once; later calls are a
         * no-op if it's already running. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
