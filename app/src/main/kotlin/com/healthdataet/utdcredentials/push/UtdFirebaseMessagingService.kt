package com.healthdataet.utdcredentials.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.healthdataet.utdcredentials.MainActivity
import com.healthdataet.utdcredentials.R
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "utd_credentials_alerts"

class UtdFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val session = SessionManager(applicationContext)
        session.fcmToken = token
        val apiToken = session.apiToken ?: return
        CoroutineScope(Dispatchers.IO).launch {
            ApiClient(session.baseUrl).registerDeviceToken(apiToken, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "UTD Credentials"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Admin alerts", NotificationManager.IMPORTANCE_HIGH
            )
            manager?.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager?.notify(System.currentTimeMillis().toInt(), notification)
    }
}
