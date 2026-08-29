package com.healthdataet.utdcredentials.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.healthdataet.utdcredentials.MainActivity
import com.healthdataet.utdcredentials.R
import com.healthdataet.utdcredentials.data.NotificationPrefs
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.data.SoundPrefs

/**
 * One Android notification channel per alert category -- this is what
 * actually makes "New Registration" ring differently from "Payment
 * Submitted" on the phone; a single generic channel can only ever have one
 * sound. Android only lets a channel's sound be set ONCE, at creation --
 * changing it later means deleting and re-creating the channel, which is
 * exactly what [recreateChannel] does when the admin picks a new one in
 * SoundSettingsScreen.
 *
 * Categories map to the server's push `type` field (see admin/push.py
 * callers: bot/registration.py, bot/trial.py, bot/payment.py,
 * cron/trial_expiry.py, cron/renewal_alert.py) via [channelIdForServerType].
 */
object NotificationChannels {

    const val CATEGORY_REGISTRATION = "registration"
    const val CATEGORY_TRIAL_START = "trial_start"
    const val CATEGORY_EXPIRY = "expiry" // covers both trial_end + subscription_expired
    const val CATEGORY_PAYMENT = "payment"
    const val CATEGORY_GENERAL = "general" // mismatch + anything unrecognized

    val CATEGORY_ORDER = listOf(
        CATEGORY_REGISTRATION, CATEGORY_TRIAL_START, CATEGORY_EXPIRY, CATEGORY_PAYMENT, CATEGORY_GENERAL
    )

    /** Maps the server's `data.type` push field to one of the 5 channels
     * above -- 'trial_end' (unpaid trial lapsing) and 'subscription_expired'
     * (paid plan lapsing) both fold into the one "Expiry" channel, since a
     * single "expiry" sound covering that whole family is what was asked
     * for, rather than two separately-configurable ones. */
    fun channelIdForServerType(serverType: String?): String = when (serverType) {
        "registration" -> CATEGORY_REGISTRATION
        "trial_start" -> CATEGORY_TRIAL_START
        "trial_end", "subscription_expired" -> CATEGORY_EXPIRY
        "payment" -> CATEGORY_PAYMENT
        else -> CATEGORY_GENERAL
    }

    fun labelFor(category: String): String = when (category) {
        CATEGORY_REGISTRATION -> "New Registrations"
        CATEGORY_TRIAL_START -> "Trial Started"
        CATEGORY_EXPIRY -> "Expiry Alerts (trial & subscription)"
        CATEGORY_PAYMENT -> "Payment Submitted"
        else -> "General Alerts"
    }

    /** A distinct built-in system sound per category by default, so all
     * five are audibly different out of the box with no bundled audio file
     * needed -- the admin can still override any of them from
     * SoundSettingsScreen (or Android's own per-channel notification
     * settings, Settings -> Apps -> UTD Credentials -> Notifications). */
    fun defaultSoundFor(context: Context, category: String): Uri? = try {
        when (category) {
            CATEGORY_REGISTRATION ->
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
            CATEGORY_TRIAL_START ->
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            CATEGORY_EXPIRY ->
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            CATEGORY_PAYMENT -> secondNotificationSoundOrDefault(context)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    } catch (e: Exception) {
        // Some OEM builds (notably some Samsung/One UI versions) can throw
        // a SecurityException reading the system ringtone/alarm URI here.
        // This runs unconditionally on every app launch (MainActivity.onCreate
        // -> NotificationChannels.ensureAll), BEFORE any UI is shown -- an
        // uncaught exception here previously meant an instant crash on start,
        // with no chance to even see the login screen. A missing/unavailable
        // default sound is not worth crashing the whole app over: the channel
        // still gets created below, just silent, and the admin can pick a
        // sound manually from Sound Settings afterward.
        null
    }

    private fun secondNotificationSoundOrDefault(context: Context): Uri? {
        return try {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
            if (manager.cursor.count > 1) {
                manager.getRingtoneUri(1)
            } else {
                RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
            }
        } catch (e: Exception) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
    }

    /** Creates all 5 channels if they don't exist yet, using either the
     * admin's saved choice (SoundPrefs) or the category's distinct
     * default. Safe to call on every app start -- (re)creating an
     * already-existing channel with the same id is a no-op on Android. */
    fun ensureAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val prefs = SoundPrefs(context)
            for (category in CATEGORY_ORDER) {
                try {
                    if (manager.getNotificationChannel(category) != null) continue
                    val saved = prefs.getSoundUri(category)?.let { Uri.parse(it) }
                    createChannel(context, manager, category, saved ?: defaultSoundFor(context, category))
                } catch (e: Exception) {
                    // One category's channel failing to create must never
                    // block the rest, and must never crash the app on start.
                }
            }
        } catch (e: Exception) {
            // Belt-and-suspenders: this runs unconditionally on every app
            // launch before any UI is shown -- nothing here is worth
            // crashing the whole app over.
        }
    }

    /** Deletes and re-creates one category's channel with a new sound --
     * the only way Android allows a channel's sound to change after it's
     * been created once. */
    fun recreateChannel(context: Context, category: String, soundUri: Uri?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(category)
        createChannel(context, manager, category, soundUri)
    }

    private fun createChannel(context: Context, manager: NotificationManager, category: String, soundUri: Uri?) {
        val channel = NotificationChannel(category, labelFor(category), NotificationManager.IMPORTANCE_HIGH)
        if (soundUri != null) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            channel.setSound(soundUri, attrs)
        }
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds and shows one real system notification -- the single place
     * that actually calls NotificationManager.notify(), so FCM delivery
     * (UtdFirebaseMessagingService), the foreground poll loop, and the
     * WorkManager background poll worker all ring/vibrate/display exactly
     * the same way instead of three subtly different implementations
     * (Round 32 -- this used to live only inside the FCM service, which is
     * the root cause of "notification system doesn't alert me": polling
     * only ever updated in-app state and never called this).
     *
     * [notificationId] should be stable per source event (e.g. the
     * server's admin_notifications.id, or the FCM message's own hash) so
     * the same event showing up twice (once via FCM, once via the next
     * poll) updates/replaces one system notification instead of stacking
     * a duplicate -- see FullSiteScreen/NotificationPollWorker's dedup
     * notes for how [notificationId] is derived.
     */
    fun postSystemNotification(context: Context, title: String, body: String, serverType: String?, notificationId: Int) {
        val channelId = channelIdForServerType(serverType)
        // Round 33: per-category on/off, checked before anything else --
        // a disabled category is silent across FCM, the foreground poll
        // loop, and the WorkManager backstop alike, since all three call
        // this one function.
        if (!NotificationPrefs(context).isEnabled(channelId)) return

        // Round 42: bump the in-app bell's shared badge counter here too --
        // same reasoning as the enable check above, this is the one place
        // FCM/poll/WorkManager all funnel through, so this is the one place
        // that can update a counter all three actually share (see
        // SessionManager.unreadNotificationCount; FullSiteScreen reads it
        // instead of keeping its own disconnected local count). Deliberately
        // BEFORE the notify() call/try-catch below: the in-app badge needs
        // no OS permission at all, so it must not depend on the system-tray
        // notify() call actually succeeding (e.g. POST_NOTIFICATIONS denied)
        // -- otherwise a denied permission would silently break the badge
        // the exact same way as the bug this fixes.
        SessionManager(context).incrementUnreadNotificationCount()

        ensureAll(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission was denied (Android 13+) -- the
            // rest of the app still works fully without system alerts.
        }
    }

    /** Human-readable name of whatever sound is currently assigned to a
     * category (saved choice, or the distinct default) -- for
     * SoundSettingsScreen to show next to each "Choose" button. */
    fun currentSoundName(context: Context, prefs: SoundPrefs, category: String): String {
        val saved = prefs.getSoundUri(category)?.let { Uri.parse(it) }
        val uri = saved ?: defaultSoundFor(context, category) ?: return "Silent"
        return try {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Default"
        } catch (e: Exception) {
            "Default"
        }
    }
}
