package com.healthdataet.utdcredentials.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.healthdataet.utdcredentials.R
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.data.offline.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Round 57: runs one offline-data sync cycle (pull + push, see
 * SyncRepository.fullSync) as a genuine foreground service -- a persistent,
 * low-priority "Syncing offline data..." notification while it's active,
 * per the admin's explicit request for reliable background sync that isn't
 * just quietly killed the moment the app isn't on screen. Started by
 * [ConnectivitySyncTrigger] the instant the network returns (if there's
 * anything pending to push) and by [SyncWorker]'s periodic backstop; stops
 * itself the moment that one sync cycle finishes, successful or not --
 * this is never a long-running always-on service, just a brief visible
 * window around each actual sync attempt.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification("Syncing offline data…"))

        scope.launch {
            try {
                val session = SessionManager(applicationContext)
                if (session.isLoggedIn) {
                    SyncRepository.fullSync(applicationContext, session)
                }
            } catch (e: Exception) {
                // Best-effort background sync -- a failed cycle is silently
                // retried by the next reconnect or the periodic backstop;
                // nothing here should ever crash the service.
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("UTD Credentials")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "offline_sync"
        private const val NOTIFICATION_ID = 9001

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            // LOW importance, deliberately separate from the 5 alert
            // channels in NotificationChannels.kt -- this is routine sync
            // housekeeping, not something that should ring or vibrate.
            val channel = NotificationChannel(CHANNEL_ID, "Offline data sync", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        /** Safe to call any number of times -- Android coalesces repeated
         * starts of the same service, and each run's [onStartCommand] stops
         * itself as soon as its own sync cycle completes. */
        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
