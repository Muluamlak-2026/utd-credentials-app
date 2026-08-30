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
import org.json.JSONObject

private const val UPTODATE_LOGIN_URL = "https://www.uptodate.com/login"

// How long to wait on a single credential before giving up and reporting it
// as a timeout rather than hanging the whole run forever -- a CAPTCHA, an
// unexpected 2FA step, or a genuinely slow connection all look the same
// from here (no clear success/failure signal), so a hard ceiling per
// attempt is what keeps a batch of N credentials from turning into an
// infinite wait on credential #1.
private const val ATTEMPT_TIMEOUT_MS = 25_000L

// After the submit script fires, give the destination page a moment to
// actually finish redirecting/rendering before reading it -- some login
// flows bounce through an intermediate page first.
private const val POST_SUBMIT_SETTLE_MS = 3_000L

/** Same defensive multi-selector fill approach as UpToDateLoginScreen's
 * autofillScript -- duplicated here (rather than shared) so this file's
 * unattended, auto-SUBMITTING flow stays clearly separate from that
 * screen's deliberately fill-only, manual-submit one. */
private fun fillScript(username: String, password: String): String {
    val escapedUser = username.replace("\\", "\\\\").replace("\"", "\\\"")
    val escapedPass = password.replace("\\", "\\\\").replace("\"", "\\\"")
    return """
        (function() {
            function fillOne(selectors, value) {
                for (var i = 0; i < selectors.length; i++) {
                    var el = document.querySelector(selectors[i]);
                    if (el) {
                        el.focus();
                        el.value = value;
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return true;
                    }
                }
                return false;
            }
            var userSelectors = [
                'input[name="username"]', 'input#username',
                'input[name="email"]', 'input#email',
                'input[type="email"]',
                'input[autocomplete="username"]',
                'input[name="j_username"]'
            ];
            var passSelectors = [
                'input[name="password"]', 'input#password',
                'input[type="password"]',
                'input[autocomplete="current-password"]',
                'input[name="j_password"]'
            ];
            fillOne(userSelectors, "$escapedUser");
            fillOne(passSelectors, "$escapedPass");
        })();
    """.trimIndent()
}

/** Best-effort submit -- tries a real submit button first, falls back to
 * submitting the form directly. This is the one deliberate difference from
 * UpToDateLoginScreen: that screen never auto-submits (the admin taps Log
 * In themselves); this one must, because the whole point of a sequential
 * batch run is going through many credentials unattended. */
private fun submitScript(): String = """
    (function() {
        var btn = document.querySelector(
            'button[type="submit"], input[type="submit"], button#login-submit'
        );
        if (btn) { btn.click(); return true; }
        var form = document.querySelector('form');
        if (form) {
            if (typeof form.requestSubmit === 'function') { form.requestSubmit(); }
            else { form.submit(); }
            return true;
        }
        return false;
    })();
""".trimIndent()

/** Runs after a post-submit navigation to read where we actually ended up
 * and whether the page is showing an obvious error message. Returns a JSON
 * string (double-encoded by evaluateJavascript's own callback contract --
 * see interpretCheckResult) rather than anything more structured, since
 * this is a best-effort heuristic against a real third-party page whose
 * exact markup can't be verified from outside a live login attempt. */
private const val CHECK_SCRIPT = """
    (function() {
        var bodyText = (document.body ? document.body.innerText : '').toLowerCase();
        var errorHit = null;
        var needles = ['incorrect', 'invalid', 'does not match', 'try again', 'failed', 'locked out', 'error'];
        for (var i = 0; i < needles.length; i++) {
            if (bodyText.indexOf(needles[i]) !== -1) { errorHit = needles[i]; break; }
        }
        return JSON.stringify({url: window.location.href, errorHit: errorHit});
    })();
"""

sealed class LoginAttemptOutcome {
    object Success : LoginAttemptOutcome()
    data class Failed(val reason: String) : LoginAttemptOutcome()
    object TimedOut : LoginAttemptOutcome()
    object Skipped : LoginAttemptOutcome()
}

data class LoginAttemptResult(
    val credential: CredentialEntry,
    val outcome: LoginAttemptOutcome
)

/** evaluateJavascript's callback hands back the JSON-encoded form of
 * whatever the script returned -- since CHECK_SCRIPT itself returns a
 * JSON.stringify'd string, the raw callback value is that string, quoted
 * and escaped a second time by evaluateJavascript's own contract. This
 * undoes that outer layer before parsing; if anything about the real page
 * doesn't match what was anticipated, this falls back to the URL captured
 * at onPageFinished and treats the result as ambiguous/failed rather than
 * guessing success. */
private fun interpretCheckResult(raw: String?, urlAtPageFinished: String?): LoginAttemptOutcome {
    val unwrapped = raw?.trim()
        ?.removeSurrounding("\"")
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")
    val json = try {
        if (!unwrapped.isNullOrBlank() && unwrapped != "null") JSONObject(unwrapped) else null
    } catch (e: Exception) {
        null
    }
    val finalUrl = json?.optString("url")?.takeIf { it.isNotBlank() } ?: urlAtPageFinished ?: ""
    val errorHit = json?.optString("errorHit")?.takeIf { it.isNotBlank() && it != "null" }
    val stillOnLogin = finalUrl.contains("login", ignoreCase = true)
    return when {
        errorHit != null -> LoginAttemptOutcome.Failed("page shows \"$errorHit\"")
        !stillOnLogin -> LoginAttemptOutcome.Success
        else -> LoginAttemptOutcome.Failed(
            "still on the login page after submitting -- may need a CAPTCHA/verification step done by hand"
        )
    }
}

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
    var submitted by remember { mutableStateOf(false) }

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
                        if (!submitted) {
                            onStatusChange("Filling credentials...")
                            val fill = fillScript(username, password)
                            view.evaluateJavascript(fill, null)
                            view.postDelayed({
                                view.evaluateJavascript(fill, null)
                                view.postDelayed({
                                    submitted = true
                                    onStatusChange("Submitting...")
                                    view.evaluateJavascript(submitScript(), null)
                                }, 500)
                            }, 700)
                        } else {
                            onStatusChange("Checking result...")
                            view.postDelayed({
                                view.evaluateJavascript(CHECK_SCRIPT) { rawResult ->
                                    resolveOnce(interpretCheckResult(rawResult, url))
                                }
                            }, POST_SUBMIT_SETTLE_MS)
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
 * Success/failure detection is necessarily a best-effort heuristic (see
 * interpretCheckResult) since uptodate.com's exact markup can't be
 * verified from outside a live attempt -- a CAPTCHA or 2FA step would
 * likely show up here as a "still on the login page" failure, which is
 * the honest, safe read rather than a false "success".
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
