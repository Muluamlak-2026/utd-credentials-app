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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.healthdataet.utdcredentials.data.offline.SyncConflict
import com.healthdataet.utdcredentials.data.offline.SyncRepository
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Round 57: per the admin's explicit choice ("ask me each time"), this
 * screen is the ONLY place a sync conflict is ever resolved -- a record
 * that changed both on this phone (offline) and on the server while it was
 * offline. Nothing is ever auto-merged or auto-picked; every conflict sits
 * here, untouched, until the admin picks a side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncConflictScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { OfflineDatabase.get(context).conflictDao() }

    var conflicts by remember { mutableStateOf<List<SyncConflict>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        loading = true
        conflicts = dao.getAll()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Conflicts (${conflicts.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                conflicts.isEmpty() -> Text(
                    "No conflicts. Everything you've edited offline has either synced cleanly or hasn't been pushed yet.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(conflicts, key = { it.id }) { conflict ->
                        ConflictCard(
                            conflict = conflict,
                            onKeepMine = {
                                scope.launch {
                                    SyncRepository.resolveKeepLocal(context, conflict)
                                    refreshTick++
                                }
                            },
                            onKeepTheirs = {
                                scope.launch {
                                    SyncRepository.resolveKeepServer(context, conflict)
                                    refreshTick++
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(conflict: SyncConflict, onKeepMine: () -> Unit, onKeepTheirs: () -> Unit) {
    val local = remember(conflict) { JSONObject(conflict.localFieldsJson) }
    val server = remember(conflict) { JSONObject(conflict.serverFieldsJson) }
    val label = if (conflict.entity == "user") "User" else "Credential"

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("$label – changed on the site while this phone was offline",
                style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Round 58: an offline DELETE conflict has no field values to
            // show for "your side" -- the local action itself was deleting
            // the record, tagged _action="delete" by SyncRepository.
            if (local.optString("_action") == "delete") {
                Text(
                    "You deleted this on your phone while offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text("Your offline version:", style = MaterialTheme.typography.labelMedium)
                FieldRows(local)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Current version on the site:", style = MaterialTheme.typography.labelMedium)
            FieldRows(server, excludeKeys = setOf("updated_at"))

            val isDelete = local.optString("_action") == "delete"
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(onClick = onKeepTheirs) {
                    Text(if (isDelete) "Don't delete -- keep site version" else "Keep site version")
                }
                Button(onClick = onKeepMine) {
                    Text(if (isDelete) "Delete anyway" else "Keep mine")
                }
            }
        }
    }
}

@Composable
private fun FieldRows(fields: JSONObject, excludeKeys: Set<String> = emptySet()) {
    Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
        fields.keys().forEach { key ->
            if (key in excludeKeys) return@forEach
            val value = fields.optString(key)
            if (value.isNotBlank()) {
                Text("$key: $value", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
