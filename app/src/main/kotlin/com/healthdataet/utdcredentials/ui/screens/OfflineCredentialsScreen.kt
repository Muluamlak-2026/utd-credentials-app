package com.healthdataet.utdcredentials.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.data.offline.OfflineCredential
import com.healthdataet.utdcredentials.data.offline.OfflineDatabase
import kotlinx.coroutines.launch

/**
 * Round 57: offline counterpart to OfflineUsersScreen -- same pattern,
 * same local-only read/write, same "syncs automatically later" model. See
 * that screen's doc comment for the full reasoning; not repeated here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineCredentialsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { OfflineDatabase.get(context).credentialDao() }

    var credentials by remember { mutableStateOf<List<OfflineCredential>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<OfflineCredential?>(null) }
    var showAddNew by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        loading = true
        credentials = dao.getAll()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Credentials") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddNew = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add credential")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                credentials.isEmpty() -> Text(
                    "No credentials stored offline yet. They'll appear here once synced, or tap + to add one.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(credentials, key = { it.localId }) { cred ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = { editing = cred }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(cred.username, style = MaterialTheme.typography.titleMedium)
                                    if (cred.dirty || cred.pendingCreate) {
                                        Text(
                                            if (cred.pendingCreate) "Not synced yet" else "Edited – pending sync",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                                Text("Status: ${cred.status ?: "–"}", style = MaterialTheme.typography.bodySmall)
                                if (!cred.credentialIdName.isNullOrBlank()) {
                                    Text(cred.credentialIdName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val editTarget = editing
    if (editTarget != null) {
        CredentialEditDialog(
            initial = editTarget,
            onDismiss = { editing = null },
            onSave = { username, password, email, notes ->
                scope.launch {
                    dao.update(editTarget.copy(
                        username = username, password = password, email = email, notes = notes,
                        dirty = true, lastLocalEditAt = System.currentTimeMillis()
                    ))
                    editing = null
                    refreshTick++
                }
            },
            // Round 58: offline delete -- same rule as OfflineUsersScreen's
            // matching callback (see its comment for the full reasoning).
            onDelete = {
                scope.launch {
                    if (editTarget.pendingCreate) {
                        dao.delete(editTarget)
                    } else {
                        dao.update(editTarget.copy(pendingDelete = true, dirty = false))
                    }
                    editing = null
                    refreshTick++
                }
            }
        )
    }

    if (showAddNew) {
        CredentialEditDialog(
            initial = null,
            onDismiss = { showAddNew = false },
            onSave = { username, password, email, notes ->
                scope.launch {
                    dao.upsert(OfflineCredential(
                        username = username, password = password, email = email, notes = notes,
                        status = "available", pendingCreate = true, dirty = true,
                        lastLocalEditAt = System.currentTimeMillis()
                    ))
                    showAddNew = false
                    refreshTick++
                }
            }
        )
    }
}

@Composable
private fun CredentialEditDialog(
    initial: OfflineCredential?,
    onDismiss: () -> Unit,
    onSave: (username: String, password: String, email: String?, notes: String?) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Credential (offline)" else "Edit Credential") },
        text = {
            Column {
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Text(
                    "Saved on this phone now; syncs to the site automatically the next time you're online.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (onDelete != null) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.padding(top = 12.dp)
                    ) { Text("Delete this credential", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(username.trim(), password.trim(), email.trim().ifBlank { null }, notes.trim().ifBlank { null }) },
                enabled = username.isNotBlank() && password.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this credential?") },
            text = {
                Text("This removes it here now, and on the site the next time this phone syncs. This can't be undone from the phone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}
