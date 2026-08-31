package com.healthdataet.utdcredentials.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.healthdataet.utdcredentials.data.NotificationPrefs
import com.healthdataet.utdcredentials.data.PollIntervalPrefs
import com.healthdataet.utdcredentials.data.SoundPrefs
import com.healthdataet.utdcredentials.push.NotificationChannels
import com.healthdataet.utdcredentials.push.NotificationPollWorker

/**
 * Lets the admin pick a distinct sound for each of the 5 alert categories
 * (New Registrations / Trial Started / Expiry / Payment Submitted /
 * General) using Android's own system ringtone picker -- so the full
 * device sound library is available, not just a short hardcoded list.
 * Picking one here deletes and re-creates that category's notification
 * channel with the new sound (see NotificationChannels.recreateChannel --
 * Android only allows a channel's sound to be set once otherwise).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val soundPrefs = remember { SoundPrefs(context) }
    val notificationPrefs = remember { NotificationPrefs(context) }
    val pollPrefs = remember { PollIntervalPrefs(context) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    // Bumped after every pick so the displayed sound names recompute.
    var refreshTick by remember { mutableStateOf(0) }

    // Round 48h: real, working poll-interval controls -- text fields hold
    // the raw typed string (so a user can freely clear/retype a number
    // without it being clamped mid-keystroke), and only commit to
    // PollIntervalPrefs (which does the actual clamping) once the field
    // loses focus or a valid number is present. Seeded from the currently
    // saved values, not hardcoded demo numbers.
    var foregroundText by remember { mutableStateOf(pollPrefs.foregroundSeconds.toString()) }
    var backgroundText by remember { mutableStateOf(pollPrefs.backgroundMinutes.toString()) }

    // Round 48p (6th update): the one thing that silently defeats every
    // category toggle/sound below at once. Android 13+ requires the
    // POST_NOTIFICATIONS runtime permission before ANY system notification
    // can show, whether it arrives via FCM, the foreground poll, or the
    // WorkManager backstop -- they all funnel through
    // NotificationChannels.postSystemNotification's one notify() call.
    // MainActivity asks for this once on first launch, but if it was ever
    // denied there, or the OS's per-app Notifications switch gets turned
    // off later (by the user, or an OEM "clean up unused permissions"
    // sweep), nothing throws and nothing logs anywhere this app can see --
    // NotificationManagerCompat.notify() just silently does nothing. That
    // matches the reported symptom exactly ("push alerts never arrive, not
    // even silently") even with DND off and battery optimization already
    // unrestricted, so this is the one check that actually tells apart
    // "blocked before it ever reaches the app's own notification code" from
    // every other possible cause, instead of guessing. Re-checked on every
    // resume (same pattern as CredentialPickerScreen's accessibility-helper
    // card) since this can be flipped from outside the app at any time.
    var notificationsEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val category = pendingCategory
        pendingCategory = null
        if (category != null && result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            soundPrefs.setSoundUri(category, uri?.toString())
            NotificationChannels.recreateChannel(context, category, uri)
            refreshTick++
        }
    }

    fun launchPicker(category: String) {
        pendingCategory = category
        val current = soundPrefs.getSoundUri(category)?.let { Uri.parse(it) }
            ?: NotificationChannels.defaultSoundFor(context, category)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }
        picker.launch(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = if (notificationsEnabled) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (notificationsEnabled) "System notifications: ON" else "System notifications: OFF",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (notificationsEnabled) {
                                "Every category below can ring/vibrate/show normally."
                            } else {
                                "This is why alerts never arrive -- Android is silently blocking all of them, before any category below even matters."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!notificationsEnabled) {
                        TextButton(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (e: Exception) {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                                .setData(Uri.parse("package:" + context.packageName))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } catch (e2: Exception) {
                                        // Nothing more this screen can do -- the text
                                        // above already says exactly what to look for.
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("Enable") }
                    }
                }
            }

            Text(
                "Turn each kind of alert on or off, and pick its sound from your " +
                    "phone's own sound picker. A category that's off stays silent " +
                    "whether the alert arrives instantly or through the periodic " +
                    "background check.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))

            NotificationChannels.CATEGORY_ORDER.forEach { category ->
                val soundName = remember(refreshTick, category) {
                    NotificationChannels.currentSoundName(context, soundPrefs, category)
                }
                val enabled = remember(refreshTick, category) { notificationPrefs.isEnabled(category) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(NotificationChannels.labelFor(category), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (enabled) soundName else "Off",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            notificationPrefs.setEnabled(category, checked)
                            refreshTick++
                        }
                    )
                    if (enabled) {
                        TextButton(onClick = { launchPicker(category) }) {
                            Text("Choose")
                        }
                    }
                }
                HorizontalDivider()
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Check Frequency", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Real settings, not a demo -- these are the actual values both " +
                    "checks use. Lower means you hear about new activity sooner, " +
                    "at the cost of a bit more battery/data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = foregroundText,
                    onValueChange = { text ->
                        foregroundText = text
                        val parsed = text.toIntOrNull()
                        if (parsed != null) pollPrefs.foregroundSeconds = parsed
                    },
                    label = { Text("While app is open") },
                    suffix = { Text("sec") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${PollIntervalPrefs.MIN_FOREGROUND_SECONDS}–${PollIntervalPrefs.MAX_FOREGROUND_SECONDS}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "How often the app checks for new activity while you actually " +
                    "have it open on screen. No real floor here beyond avoiding " +
                    "pointlessly hammering the server -- takes effect on the " +
                    "very next check, no restart needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = backgroundText,
                    onValueChange = { text ->
                        backgroundText = text
                        val parsed = text.toIntOrNull()
                        if (parsed != null) {
                            pollPrefs.backgroundMinutes = parsed
                            NotificationPollWorker.schedule(context)
                        }
                    },
                    label = { Text("While app is closed") },
                    suffix = { Text("min") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(160.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "min ${PollIntervalPrefs.MIN_BACKGROUND_MINUTES}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "How often the app checks in the background while closed. " +
                    "${PollIntervalPrefs.MIN_BACKGROUND_MINUTES} minutes is a hard " +
                    "floor Android itself enforces for every app's background " +
                    "checks on the whole platform -- not a limit this app chose, " +
                    "and nothing (this app or any other) can go lower. You CAN " +
                    "raise this above ${PollIntervalPrefs.MIN_BACKGROUND_MINUTES} " +
                    "if you'd rather trade background responsiveness for battery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                "You can also change on/off and sound later from Android's own " +
                    "Settings -> Apps -> UTD Credentials -> Notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
