package com.healthdataet.utdcredentials.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.PendingWebLogin
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.data.SiteCredsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen(session: SessionManager, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val siteCredsStore = remember { SiteCredsStore(context) }
    var baseUrl by remember { mutableStateOf(session.baseUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Round 32 ("site password save"): on by default -- most admins want the
    // embedded web panel to auto-fill every time, and can turn it off here
    // or clear it later from Security settings.
    var rememberSitePassword by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("UTD Credentials", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text("Sign in with your admin panel account", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Admin panel URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberSitePassword, onCheckedChange = { rememberSitePassword = it })
            Text("Save password for automatic web login", style = MaterialTheme.typography.bodySmall)
        }

        if (errorText != null) {
            Spacer(Modifier.height(12.dp))
            Text(errorText ?: "", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val trimmedUrl = baseUrl.trim().trimEnd('/')
                if (trimmedUrl.isBlank() || username.isBlank() || password.isBlank()) {
                    errorText = "Fill in the admin panel URL, username, and password."
                    return@Button
                }
                loading = true
                errorText = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ApiClient(trimmedUrl).login(username, password)
                    }
                    loading = false
                    if (result.ok && result.json != null) {
                        session.baseUrl = trimmedUrl
                        session.apiToken = result.json.optString("token")
                        // Consumed once by FullSiteScreen to auto-fill and
                        // submit the embedded site's own login form -- so
                        // the admin doesn't have to type their password a
                        // second time for the WebView's separate session.
                        PendingWebLogin.username = username
                        PendingWebLogin.password = password
                        // Round 32: persist (or forget) the site password so
                        // FullSiteScreen can keep auto-filling /admin/login
                        // on every later visit too, not just this one.
                        if (rememberSitePassword) {
                            siteCredsStore.save(username, password)
                        } else {
                            siteCredsStore.clear()
                        }
                        onLoggedIn()
                    } else {
                        errorText = result.error ?: "Login failed -- check your username and password."
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Log In")
            }
        }
    }
}
