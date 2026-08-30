package com.healthdataet.utdcredentials.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.PendingSequentialLogins
import com.healthdataet.utdcredentials.data.PendingUpToDateLogin
import com.healthdataet.utdcredentials.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One row from /api/v1/credentials/list -- see admin/api_routes.py's
 * list_credentials_for_login for the exact shape this mirrors. */
data class CredentialEntry(
    val source: String,
    val id: Long,
    val ucCode: String?,
    val username: String?,
    val password: String?,
    val status: String?,
    val assignedTo: String?,
    val phone: String?
)

private fun parseCredentials(json: JSONObject?): List<CredentialEntry> {
    val arr = json?.optJSONArray("credentials") ?: return emptyList()
    val out = mutableListOf<CredentialEntry>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            CredentialEntry(
                source = o.optString("source"),
                id = o.optLong("id"),
                ucCode = o.optString("uc_code").ifBlank { null },
                username = o.optString("username").ifBlank { null },
                password = o.optString("password").ifBlank { null },
                status = o.optString("status").ifBlank { null },
                assignedTo = o.optString("assigned_to").ifBlank { null },
                phone = o.optString("phone").ifBlank { null }
            )
        )
    }
    return out
}

/**
 * Round 48i: "a separate panel in which importing or selecting among a
 * sourced credential will be done" -- lets the admin search/browse the
 * exact same active/draft/pool/legacy credential list the web Credentials
 * Hub shows, then either:
 *  - hand ONE straight to UpToDateLoginScreen's WebView (the original
 *    "Log In With This Credential" button, fill-only, admin taps the real
 *    login button themselves), or
 *  - tick a CHECKBOX on several rows and run them through
 *    SequentialLoginScreen (the "Run Sequential Login" bar that appears
 *    once at least one row is checked), which logs into each one in turn,
 *    unattended, clears the session between attempts, and reports
 *    success/failure per credential -- the round 48i(+) follow-up request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialPickerScreen(
    session: SessionManager,
    onBack: () -> Unit,
    onCredentialChosen: () -> Unit,
    onRunSequential: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var credentials by remember { mutableStateOf(listOf<CredentialEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var revealedIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    fun runSearch(q: String) {
        val apiToken = session.apiToken
        if (apiToken == null) {
            errorText = "Not logged in."
            loading = false
            return
        }
        loading = true
        errorText = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient(session.baseUrl).listCredentials(apiToken, q)
            }
            loading = false
            if (result.ok && result.json != null) {
                val fetched = parseCredentials(result.json)
                credentials = fetched
                // Drop any selection that no longer matches the current
                // search results, so "Run Sequential Login (N)" never
                // silently refers to rows the admin can't currently see.
                val fetchedIds = fetched.map { it.id }.toSet()
                selectedIds = selectedIds.filter { it in fetchedIds }.toSet()
            } else {
                errorText = result.error ?: "Could not load credentials."
            }
        }
    }

    LaunchedEffect(Unit) { runSearch("") }

    val loginableCount = credentials.count {
        it.id in selectedIds && !it.username.isNullOrBlank() && !it.password.isNullOrBlank()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UpToDate Quick Login") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "${selectedIds.size} selected",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (loginableCount != selectedIds.size) {
                                Text(
                                    "${selectedIds.size - loginableCount} missing a username/password " +
                                        "and will be skipped",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Row {
                            TextButton(onClick = { selectedIds = emptySet() }) { Text("Clear") }
                            Spacer(Modifier.width(4.dp))
                            Button(
                                onClick = {
                                    val chosen = credentials.filter {
                                        it.id in selectedIds &&
                                            !it.username.isNullOrBlank() && !it.password.isNullOrBlank()
                                    }
                                    if (chosen.isNotEmpty()) {
                                        PendingSequentialLogins.queue = chosen
                                        onRunSequential()
                                    } else {
                                        errorText = "None of the selected credentials have both a username and password."
                                    }
                                },
                                enabled = loginableCount > 0
                            ) {
                                Text("Run Sequential Login ($loginableCount)")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Pick a credential to open it straight in uptodate.com's own login " +
                    "page with the username and password already filled in, or check " +
                    "several and run them one after another automatically with a " +
                    "success/failure report for each.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search by name, phone, UC-code, or username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { runSearch(query) }) { Text("Search") }
            }

            Spacer(Modifier.height(8.dp))

            if (credentials.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        selectedIds = credentials
                            .filter { !it.username.isNullOrBlank() && !it.password.isNullOrBlank() }
                            .map { it.id }
                            .toSet()
                    }) { Text("Select All") }
                    TextButton(onClick = { selectedIds = emptySet() }) { Text("Select None") }
                }
            }

            if (errorText != null) {
                Text(errorText ?: "", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (credentials.isEmpty()) {
                Text(
                    "No credentials found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn {
                    items(credentials) { cred ->
                        val revealed = revealedIds.contains(cred.id)
                        val checked = selectedIds.contains(cred.id)
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { isChecked ->
                                                selectedIds = if (isChecked) selectedIds + cred.id else selectedIds - cred.id
                                            }
                                        )
                                        Text(
                                            cred.ucCode ?: "(no UC-code)",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                    Text(
                                        "${cred.source}${cred.status?.let { " · $it" } ?: ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("Username: ${cred.username ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Password: " + if (revealed) (cred.password ?: "-") else "••••••••",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(onClick = {
                                        revealedIds = if (revealed) revealedIds - cred.id else revealedIds + cred.id
                                    }) {
                                        Text(if (revealed) "Hide" else "Show")
                                    }
                                }
                                if (cred.assignedTo != null || cred.phone != null) {
                                    Text(
                                        "Assigned: ${cred.assignedTo ?: "-"}${cred.phone?.let { " · $it" } ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val u = cred.username
                                        val p = cred.password
                                        if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
                                            PendingUpToDateLogin.username = u
                                            PendingUpToDateLogin.password = p
                                            onCredentialChosen()
                                        } else {
                                            errorText = "This credential has no username/password on file."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Log In With This Credential")
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
