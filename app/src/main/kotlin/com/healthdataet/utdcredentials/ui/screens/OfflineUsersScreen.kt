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
import com.healthdataet.utdcredentials.data.offline.OfflineDatabase
import com.healthdataet.utdcredentials.data.offline.OfflineUser
import kotlinx.coroutines.launch

/**
 * Round 57: view/add/edit clients with NO network required at all -- the
 * screen reads/writes the local Room mirror directly (OfflineDatabase),
 * never the live API. Any add/edit here just flips [OfflineUser.dirty] (or
 * [OfflineUser.pendingCreate] for a brand-new one); the actual push to the
 * server happens later, automatically, the moment connectivity returns
 * (see ConnectivitySyncTrigger) or the periodic backstop runs (SyncWorker)
 * -- nothing on this screen itself talks to the network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineUsersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { OfflineDatabase.get(context).userDao() }

    var users by remember { mutableStateOf<List<OfflineUser>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<OfflineUser?>(null) }
    var showAddNew by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        loading = true
        users = dao.getAll()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline Users") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddNew = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add user")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                users.isEmpty() -> Text(
                    "No users stored offline yet. They'll appear here once synced, or tap + to add one.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(users, key = { it.localId }) { user ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = { editing = user }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(user.fullName, style = MaterialTheme.typography.titleMedium)
                                    if (user.dirty || user.pendingCreate) {
                                        Text(
                                            if (user.pendingCreate) "Not synced yet" else "Edited – pending sync",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                                Text(user.phone, style = MaterialTheme.typography.bodyMedium)
                                if (!user.notes.isNullOrBlank()) {
                                    Text(user.notes, style = MaterialTheme.typography.bodySmall)
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
        UserEditDialog(
            initial = editTarget,
            onDismiss = { editing = null },
            onSave = { fullName, phone, notes ->
                scope.launch {
                    dao.update(editTarget.copy(
                        fullName = fullName, phone = phone, notes = notes,
                        dirty = true, lastLocalEditAt = System.currentTimeMillis()
                    ))
                    editing = null
                    refreshTick++
                }
            },
            // Round 58: offline delete, added per explicit admin request
            // (Round 57 deliberately left this out). A row never yet synced
            // (pendingCreate) has nothing on the server to delete -- just
            // remove it here and now; anything else is hidden immediately
            // and queued for the actual server-side delete on next sync
            // (see SyncRepository.pushPending).
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
        UserEditDialog(
            initial = null,
            onDismiss = { showAddNew = false },
            onSave = { fullName, phone, notes ->
                scope.launch {
                    dao.upsert(OfflineUser(
                        fullName = fullName, phone = phone, notes = notes,
                        pendingCreate = true, dirty = true,
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
private fun UserEditDialog(
    initial: OfflineUser?,
    onDismiss: () -> Unit,
    onSave: (fullName: String, phone: String, notes: String?) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var fullName by remember { mutableStateOf(initial?.fullName ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add User (offline)" else "Edit User") },
        text = {
            Column {
                OutlinedTextField(
                    value = fullName, onValueChange = { fullName = it },
                    label = { Text("Full name") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
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
                // Round 58: offline delete -- only offered when editing an
                // existing row (there's nothing to delete on a not-yet-saved
                // "Add User" dialog).
                if (onDelete != null) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.padding(top = 12.dp)
                    ) { Text("Delete this user", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(fullName.trim(), phone.trim(), notes.trim().ifBlank { null }) },
                enabled = fullName.isNotBlank() && phone.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this user?") },
            text = {
                Text("This removes them here now, and on the site the next time this phone syncs. This can't be undone from the phone.")
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
