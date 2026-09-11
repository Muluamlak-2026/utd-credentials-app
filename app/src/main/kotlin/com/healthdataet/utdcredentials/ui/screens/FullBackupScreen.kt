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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.healthdataet.utdcredentials.data.offline.FullSiteBackupManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Round 58: "database and code backup and restore through the apk" --
 * lets the admin pick exactly which pieces of a site backup to pull down
 * (Users, Credentials, the full database SQL dump, the site's source
 * code), store the result on this phone, and restore the database piece
 * straight back to the site. See FullSiteBackupManager's doc comment for
 * the full reasoning (reuses the web panel's own Round 25 backup engine,
 * encrypts the stored file, and why code isn't restorable from here).
 *
 * Deliberately a separate screen from LocalBackupScreen -- that one is the
 * lightweight JSON snapshot of just this device's own offline data; this
 * one is the heavier, server-generated bundle. Reached from LocalBackupScreen
 * so both live under the one "Offline Data & Local Backup" entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { SessionManager(context) }

    var includeUsers by remember { mutableStateOf(true) }
    var includeCredentials by remember { mutableStateOf(true) }
    var includeDatabase by remember { mutableStateOf(true) }
    var includeCode by remember { mutableStateOf(true) }

    var meta by remember { mutableStateOf<FullSiteBackupManager.BackupMeta?>(null) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        meta = FullSiteBackupManager.peek(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Full Site Backup") },
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
            Text(
                "Downloads a backup straight from the site's own backup system (the same one behind the admin panel's Backup page) and stores it on this phone. Pick exactly what to include -- each piece on its own, or all together.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("What to include", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    CheckRow("Users", includeUsers) { includeUsers = it }
                    CheckRow("Credentials", includeCredentials) { includeCredentials = it }
                    CheckRow("Database (full SQL dump)", includeDatabase) { includeDatabase = it }
                    CheckRow("Site source code", includeCode) { includeCode = it }
                    if (includeCode) {
                        Text(
                            "Code is for safekeeping/inspection only -- restoring it onto the server isn't automated here (the web admin panel itself has never supported that either); that stays a deliberate File Manager step.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        enabled = !busy && session.isLoggedIn &&
                            (includeUsers || includeCredentials || includeDatabase || includeCode),
                        onClick = {
                            busy = true
                            statusMessage = null
                            scope.launch {
                                val error = FullSiteBackupManager.downloadAndStore(
                                    context, session, includeUsers, includeCredentials, includeDatabase, includeCode
                                )
                                statusMessage = error ?: "Backup downloaded and stored on this phone."
                                busy = false
                                refreshTick++
                            }
                        }
                    ) { Text("Download Backup") }
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
                    Text("Stored on this phone", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    val m = meta
                    if (m == null) {
                        Text("No backup downloaded yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Last downloaded: ${formatTime(m.timestamp)} (${formatSize(m.sizeBytes)})",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Includes: " + listOfNotNull(
                                "Users".takeIf { m.includedUsers },
                                "Credentials".takeIf { m.includedCredentials },
                                "Database".takeIf { m.includedDatabase },
                                "Code".takeIf { m.includedCode },
                            ).joinToString(", ").ifBlank { "(nothing)" },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Stored encrypted (Android Keystore) -- unreadable if copied off this phone.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        enabled = !busy && meta?.includedDatabase == true,
                        onClick = { showRestoreConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Restore Database From This Backup", color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Restoring users/credentials to the site uses the separate Local Backup + Sync Now on the previous screen -- this button here is database-only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    if (showRestoreConfirm) {
        var typedConfirm by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore the entire database?") },
            text = {
                Column {
                    Text(
                        "This REPLACES every table on the live site with what's in this stored backup's database dump. Everything added or changed on the site since that backup was downloaded will be lost. This cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Type RESTORE to confirm:", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = typedConfirm, onValueChange = { typedConfirm = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typedConfirm.trim() == "RESTORE",
                    onClick = {
                        showRestoreConfirm = false
                        busy = true
                        statusMessage = null
                        scope.launch {
                            statusMessage = FullSiteBackupManager.restoreDatabase(context, session)
                            busy = false
                        }
                    }
                ) { Text("Restore Database", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
    }
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMillis))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
