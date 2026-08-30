package com.healthdataet.utdcredentials.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.healthdataet.utdcredentials.data.LoginHistoryEntry
import com.healthdataet.utdcredentials.data.LoginHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun statusLabel(status: String): String = when (status) {
    "success" -> "SUCCESS"
    "failed" -> "FAILED"
    "timeout" -> "TIMED OUT"
    "skipped" -> "SKIPPED"
    else -> status.uppercase(Locale.getDefault())
}

/** One credential's full attempt history, newest first, plus its own
 * ordered/success/fail tally -- what LoginHistoryScreen groups by. */
private data class HistoryGroup(
    val key: String,
    val label: String,
    val entries: List<LoginHistoryEntry>
) {
    val ordered get() = entries.size
    val successCount get() = entries.count { it.status == "success" }
    val failCount get() = entries.count { it.status == "failed" || it.status == "timeout" }
}

/**
 * Round 48l: "let login actions be saved in the apk same page with it's
 * history button so that how much ordered, how much success and failure
 * with their date of action". Reads LoginHistoryStore (purely on-device,
 * see that file's doc comment for why this is deliberately separate from
 * the website Hub's per-credential Sign-In Test column) and shows:
 *  - an overall tally card (total ordered / succeeded / failed) up top;
 *  - one expandable group per credential below, each showing its own
 *    tally and, once expanded, every individual attempt with its date and
 *    result -- "list out each groups... in professional ui and manner".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var allEntries by remember { mutableStateOf(LoginHistoryStore.all(context)) }
    var expandedKeys by remember { mutableStateOf(setOf<String>()) }

    val groups = remember(allEntries) {
        allEntries
            .groupBy { "${it.source}:${it.credId}" }
            .map { (key, entries) ->
                val label = entries.first().let { it.ucCode?.takeIf { s -> s.isNotBlank() } ?: it.username ?: key }
                HistoryGroup(key, label, entries.sortedByDescending { it.timestampMs })
            }
            .sortedByDescending { it.entries.first().timestampMs }
    }

    val totalOrdered = allEntries.size
    val totalSuccess = allEntries.count { it.status == "success" }
    val totalFail = allEntries.count { it.status == "failed" || it.status == "timeout" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Login History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (allEntries.isNotEmpty()) {
                        // Icons.Filled.DeleteOutline is only in the
                        // material-icons-extended artifact, not a project
                        // dependency here -- a text action avoids adding it.
                        TextButton(onClick = {
                            LoginHistoryStore.clear(context)
                            allEntries = emptyList()
                        }) { Text("Clear") }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (allEntries.isEmpty()) {
                Text(
                    "No login attempts recorded on this device yet -- run a single or " +
                        "sequential login and results will start appearing here.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Scaffold
            }

            Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TallyStat("Ordered", totalOrdered, MaterialTheme.colorScheme.onSurface)
                    TallyStat("Success", totalSuccess, MaterialTheme.colorScheme.primary)
                    TallyStat("Failed", totalFail, MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groups, key = { it.key }) { group ->
                    val expanded = expandedKeys.contains(group.key)
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedKeys = if (expanded) expandedKeys - group.key else expandedKeys + group.key
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(group.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${group.ordered} tried · ${group.successCount} success · ${group.failCount} failed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Icons.Filled.ExpandLess/ExpandMore are only in
                            // material-icons-extended -- a plain glyph
                            // avoids adding that dependency for this.
                            Text(
                                if (expanded) "▲" else "▼",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (expanded) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                                group.entries.forEach { e ->
                                    val color = when (e.status) {
                                        "success" -> MaterialTheme.colorScheme.primary
                                        "skipped" -> MaterialTheme.colorScheme.onSurfaceVariant
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            dateFmt.format(Date(e.timestampMs)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            statusLabel(e.status) + (e.reason?.let { " -- $it" } ?: ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TallyStat(label: String, value: Int, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
