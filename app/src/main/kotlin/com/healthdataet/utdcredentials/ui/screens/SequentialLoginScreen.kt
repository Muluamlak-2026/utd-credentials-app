package com.healthdataet.utdcredentials.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.healthdataet.utdcredentials.data.PendingSequentialLogins
import kotlinx.coroutines.delay

// UPTODATE_LOGIN_URL, ATTEMPT_TIMEOUT_MS, INSPECT_INTERVAL_MS,
// inspectAndActScript, LoginAttemptOutcome, InspectResult,
// interpretInspectResult, outcomeFromFinal and statusLabelFor all now live
// in LoginAutomation.kt, shared with UpToDateLoginScreen.kt's single-
// credential automated flow -- see that file's doc comment for why.

data class LoginAttemptResult(
    val credential: CredentialEntry,
    val outcome: LoginAttemptOutcome
)

/** One unattended login attempt: fresh WebView, cleared cookies/cache (the
 * actual "log out" from whatever the previous credential's attempt left
 * behind), fill, submit, then read the result. Wrapped in
 * key(currentIndex) by the caller so a brand new instance -- and a brand
 * new timeout -- is created per credential, and the previous one is fully
 * torn down (WebView destroyed, timeout cancelled) the moment this one is
 * done. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginAttemptRunner(
    credential: CredentialEntry,
    onStatusChange: (String) -> Unit,
    onDone: (LoginAttemptOutcome) -> Unit
) {
    var resolved by remember { mutableStateOf(false) }

    fun resolveOnce(outcome: LoginAttemptOutcome) {
        if (!resolved) {
            resolved = true
            onDone(outcome)
        }
    }

    LaunchedEffect(Unit) {
        delay(ATTEMPT_TIMEOUT_MS)
        resolveOnce(LoginAttemptOutcome.TimedOut)
    }

    val username = credential.username
    val password = credential.password

    if (username.isNullOrBlank() || password.isNullOrBlank()) {
        LaunchedEffect(Unit) { resolveOnce(LoginAttemptOutcome.Skipped) }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            var loopStarted = false

            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                // The actual "log out" between credentials: wipe any
                // cookie/session state the previous attempt left behind
                // before this one even starts loading the login page.
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                clearCache(true)
                clearHistory()

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onStatusChange("Loading login page...")
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // Only kick the poll loop off once -- if
                        // uptodate.com navigates again mid-flow (the real
                        // step-2 page), the already-running loop notices
                        // that on its own next tick rather than starting a
                        // second loop alongside it (which could double
                        // click/submit).
                        if (!loopStarted) {
                            loopStarted = true
                            onStatusChange("Working through login steps...")
                            startLoginAutomation(
                                view = view,
                                username = username,
                                password = password,
                                isResolved = { resolved },
                                onStatus = onStatusChange,
                                onResolved = { resolveOnce(it) }
                            )
                        }
                    }
                }

                loadUrl(UPTODATE_LOGIN_URL)
            }
        }
    )
}

@Composable
private fun ResultRow(result: LoginAttemptResult) {
    val label: String
    val color: androidx.compose.ui.graphics.Color
    when (val outcome = result.outcome) {
        is LoginAttemptOutcome.Success -> {
            label = "SUCCESS"
            color = MaterialTheme.colorScheme.primary
        }
        is LoginAttemptOutcome.Failed -> {
            label = "FAILED -- ${outcome.reason}"
            color = MaterialTheme.colorScheme.error
        }
        is LoginAttemptOutcome.TimedOut -> {
            label = "TIMEOUT -- no clear result in time"
            color = MaterialTheme.colorScheme.error
        }
        is LoginAttemptOutcome.Skipped -> {
            label = "SKIPPED -- no username/password on file"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    result.credential.ucCode ?: result.credential.username ?: "(unknown)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            }
            Text(
                "Username: ${result.credential.username ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Round 48i follow-up: "sequential auto login, log out with successful or
 * failure report of login for each pair of credentials" -- consumes the
 * batch CredentialPickerScreen queued via PendingSequentialLogins, then
 * works through it one credential at a time: load the real uptodate.com
 * login page, fill it in, submit it, read whether it looks like it
 * succeeded or failed, clear the session, and move to the next. A live
 * report builds up below as each attempt finishes; a "Stop" action in the
 * top bar ends the run early without losing the results gathered so far.
 *
 * uptodate.com's real login turned out to be a two-step flow (username +
 * Continue, then a separate password + Sign In step, with an occasional
 * "complete your profile" popup in between) -- inspectAndActScript handles
 * all of that by re-checking what's actually on screen on a timer and
 * acting on whichever step is currently showing, rather than assuming one
 * fill-everything-then-submit-once pass.
 *
 * Success/failure detection is still necessarily a best-effort heuristic
 * since uptodate.com's exact markup can't be verified from outside a live
 * attempt -- a genuine CAPTCHA or 2FA step would still show up here as a
 * "still on the login page" failure, which is the honest, safe read rather
 * than a guessed false "success".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequentialLoginScreen(onBack: () -> Unit) {
    val queue = remember { PendingSequentialLogins.consume().orEmpty() }
    var results by remember { mutableStateOf(listOf<LoginAttemptResult>()) }
    var currentIndex by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("Starting...") }
    var stopRequested by remember { mutableStateOf(false) }

    val finished = queue.isEmpty() || currentIndex >= queue.size || stopRequested
    val successCount = results.count { it.outcome is LoginAttemptOutcome.Success }
    val failCount = results.count {
        it.outcome is LoginAttemptOutcome.Failed || it.outcome is LoginAttemptOutcome.TimedOut
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sequential Login") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!finished) {
                        TextButton(onClick = { stopRequested = true }) { Text("Stop") }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (queue.isEmpty()) {
                Text(
                    "No credentials were selected -- go back and check at least one.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (finished) {
                        Text(
                            "Finished: $successCount succeeded, $failCount failed, " +
                                "${results.size - successCount - failCount} skipped, " +
                                "out of ${results.size} attempted",
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (stopRequested && currentIndex < queue.size) {
                            Text(
                                "Stopped early -- ${queue.size - currentIndex} credential(s) were not attempted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val currentCred = queue[currentIndex]
                        Text(
                            "Attempt ${currentIndex + 1} of ${queue.size}: " +
                                (currentCred.ucCode ?: currentCred.username ?: "credential"),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()

                if (!finished) {
                    Box(modifier = Modifier.weight(0.6f).fillMaxWidth()) {
                        key(currentIndex) {
                            LoginAttemptRunner(
                                credential = queue[currentIndex],
                                onStatusChange = { statusText = it },
                                onDone = { outcome ->
                                    results = results + LoginAttemptResult(queue[currentIndex], outcome)
                                    currentIndex += 1
                                }
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(if (finished) 1f else 0.4f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    items(results.reversed()) { r -> ResultRow(r) }
                }
            }
        }
    }
}
