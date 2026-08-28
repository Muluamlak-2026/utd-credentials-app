package com.healthdataet.utdcredentials.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        // The server sends DATA-ONLY messages on purpose (see admin/push.py's
        // comment) precisely so this always runs, in every app state --
        // title/body/type all come from `data`, never from `notification`.
        val title = message.notification?.title ?: message.data["title"] ?: "UTD Credentials"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        val serverType = message.data["type"]

        // Round 32: when the server includes the admin_notifications row id
        // (data["notif_id"], added in push.py alongside this round's Kotlin
        // changes), use it as the stable system-notification id AND advance
        // the same lastNotificationId watermark the poll path uses -- so an
        // event delivered via FCM first is never shown again a few seconds
        // later when the next poll also sees it. Older server builds that
        // don't send notif_id yet fall back to a random id (still shows
        // correctly, just without cross-path dedup).
        val notifIdField = message.data["notif_id"]?.toLongOrNull()
        val session = SessionManager(applicationContext)
        if (notifIdField != null && notifIdField > session.lastNotificationId) {
            session.lastNotificationId = notifIdField
        }
        val systemNotifId = notifIdField?.toInt() ?: System.currentTimeMillis().toInt()

        NotificationChannels.postSystemNotification(applicationContext, title, body, serverType, systemNotifId)
    }
}
