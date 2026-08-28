package com.healthdataet.utdcredentials.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.data.NotificationPrefs
import com.healthdataet.utdcredentials.data.SoundPrefs
import com.healthdataet.utdcredentials.push.NotificationChannels

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
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    // Bumped after every pick so the displayed sound names recompute.
    var refreshTick by remember { mutableStateOf(0) }

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

            Spacer(Modifier.height(12.dp))
            Text(
                "You can also change these later from Android's own Settings -> " +
                    "Apps -> UTD Credentials -> Notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
