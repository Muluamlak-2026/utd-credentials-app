package com.healthdataet.utdcredentials.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.util.CrashHandler
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * "Diagnostics" -- reached from App Settings. Lets an admin (or whoever's
 * remotely helping them, without needing ADB/logcat on the actual device)
 * read the on-device log of any past crash CrashHandler caught, in place,
 * no computer required. Empty by default; only ever has content if
 * something actually went wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val logs = remember(refreshTick) { CrashHandler.listLogs(context) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedIndex != null) selectedIndex = null else onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val selected = selectedIndex?.let { logs.getOrNull(it) }
        if (selected != null) {
            val text = remember(selected) {
                try { selected.readText() } catch (e: Exception) { "Could not read this log file." }
            }
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Surface(modifier = Modifier.fillMaxSize(), tonalElevation = 1.dp) {
                    SelectionContainer {
                        Text(
                            text,
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    "Nothing to fix here unless something actually crashed. Any unexpected " +
                        "error this app hits is saved below automatically, so it can be read " +
                        "and shared right from the phone -- no computer or cable needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                if (logs.isEmpty()) {
                    Text("No crash logs saved on this device.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val formatter = remember { SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.US) }
                    logs.forEachIndexed { index, file ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(formatter.format(file.lastModified()), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Tap to view full details",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        CrashHandler.clearLogs(context)
                        refreshTick++
                    }) {
                        Text("Clear all logs")
                    }
                }
            }
        }
    }
}
