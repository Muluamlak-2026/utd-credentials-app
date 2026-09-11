package com.healthdataet.utdcredentials.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.data.offline.LocalBackupManager
import com.healthdataet.utdcredentials.data.offline.OfflineDatabase
import com.healthdataet.utdcredentials.data.offline.SyncRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Round 57: the home screen for the whole offline-data feature -- entry
 * points to Offline Users / Offline Credentials / Sync Conflicts, a manual
 * "Sync Now" (in case the admin doesn't want to wait for reconnection to
 * trigger it automatically), and the local on-device backup ("Backup Now"
 * always overwrites the one existing backup file -- see
 * LocalBackupManager's own doc comment for exactly what that captures and
 * why it's separate from the website's own full-SQL backup/restore).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBackupScreen(
    onBack: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenCredentials: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenFullBackup: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { SessionManager(context) }

    var backupInfo by remember { mutableStateOf<LocalBackupManager.BackupInfo?>(null) }
    var pendingCount by remember { mutableStateOf(0) }
    var conflictCount by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        backupInfo = LocalBackupManager.peek(context)
        val db = OfflineDatabase.get(context)
        pendingCount = db.userDao().getPending().size + db.credentialDao().getPending().size
        conflictCount = db.conflictDao().count()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Data & Local Backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (pendingCount == 0) "Everything is synced." else "$pendingCount change(s) waiting to sync.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (conflictCount > 0) {
                        Text(
                            "$conflictCount conflict(s) need your decision.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        Button(
                            enabled = !busy && session.isLoggedIn,
                            onClick = {
                                busy = true
                                statusMessage = null
                                scope.launch {
                                    val ok = SyncRepository.fullSync(context, session)
                                    statusMessage = if (ok) "Sync complete." else "Sync failed – check your connection."
                                    busy = false
                                    refreshTick++
                                }
                            }
                        ) { Text("Sync Now") }
                    }
                    if (busy) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    }
                    statusMessage?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Offline data", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Add or edit users and credentials with no connection at all. Changes save on this phone immediately and sync to the site automatically once you're back online.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenUsers, modifier = Modifier.fillMaxWidth()) { Text("Offline Users") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenCredentials, modifier = Modifier.fillMaxWidth()) { Text("Offline Credentials") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenConflicts, modifier = Modifier.fillMaxWidth()) {
                        Text(if (conflictCount > 0) "Sync Conflicts ($conflictCount)" else "Sync Conflicts")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Full Site Backup (Server)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Round 58: pick Users, Credentials, the full database SQL dump, and/or the site's source code -- any combination -- download it from the site's own backup system, and restore the database piece straight back.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onOpenFullBackup, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Full Site Backup")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Local Backup (this device's offline data)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    val info = backupInfo
                    Text(
                        if (info == null) "No backup on this phone yet."
                        else "Last backup: ${formatTime(info.timestamp)} – ${info.userCount} users, ${info.credentialCount} credentials.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "One file on this phone, saved inside the app's own storage. Every \"Backup Now\" replaces it – there's never more than one.",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                statusMessage = null
                                scope.launch {
                                    val result = LocalBackupManager.backupNow(context)
                                    statusMessage = if (result != null)
                                        "Backup saved: ${result.userCount} users, ${result.credentialCount} credentials."
                                    else "Backup failed – try again."
                                    busy = false
                                    refreshTick++
                                }
                            }
                        ) { Text("Backup Now") }
                        Spacer(modifier = Modifier.height(0.dp))
                        TextButton(
                            enabled = !busy && backupInfo != null,
                            onClick = { showRestoreConfirm = true }
                        ) { Text("Restore") }
                    }
                }
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore from local backup?") },
            text = {
                Text("This replaces everything currently stored offline on this phone with what's in the last backup. Anything added or edited since that backup will be lost. This does not change anything on the site itself.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    busy = true
                    scope.launch {
                        val ok = LocalBackupManager.restoreFromBackup(context)
                        statusMessage = if (ok) "Restored from backup." else "Restore failed."
                        busy = false
                        refreshTick++
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") } }
        )
    }
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMillis))
