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
// infinite wait on credential #1. Bumped from the original 25s: the real
// uptodate.com login is TWO steps (username+Continue, then a separate
// password+Sign-in page), not one, so a single attempt needs enough room
// for two round trips plus the popup uptodate.com sometimes shows in
// between (see INSPECT_INTERVAL_MS below).
private const val ATTEMPT_TIMEOUT_MS = 35_000L

// How often the inspect-and-act script re-checks the page. Deliberately a
// tight poll rather than a single one-shot check after submit: uptodate.com
// moving from the username step to the password step does not necessarily
// fire a fresh WebViewClient.onPageFinished (it can render the password
// field in via its own JS without a full navigation), so this keeps
// looking on a timer instead of waiting for a page-load event that might
// never come a second time.
private const val INSPECT_INTERVAL_MS = 1200L

/**
 * Round 48i(+): the real uptodate.com login turned out to be a TWO-STEP
 * flow -- username + "Continue" first, THEN a separate page/step for
 * password + "Sign in" -- not the single combined form the original
 * fill-everything-at-once script assumed. That mismatch is exactly what
 * was causing every sequential attempt to report "still on the login page"
 * (this script filled username, clicked whatever it found, then checked
 * for a result far too early -- before the password step had even
 * appeared, let alone been filled in).
 *
 * This single script is now called repeatedly (see INSPECT_INTERVAL_MS)
 * and, each time, looks at whatever is ACTUALLY on screen right now and
 * does the one next right thing:
 *   1. If uptodate.com's "Please complete your profile" nag is showing,
 *      dismiss it via "Ask Again Tomorrow" so it never blocks the real
 *      flow (per the user's own instruction on how to handle it).
 *   2. Else if a password field is visible, fill it in and click Sign In
 *      (the password step).
 *   3. Else if an not-yet-filled username field is visible, fill it in and
 *      click Continue (the username step).
 *   4. Else (no recognized field left to act on) -- this is treated as the
 *      destination page: scan its visible text for an obvious error
 *      keyword and report back what URL we ended up on.
 * Kotlin only needs to act on step 4's answer; steps 1-3 just mean "keep
 * polling, something changed". This handles the flow whether uptodate.com
 * does a real page navigation between steps or just swaps the form in
 * with JS, without needing to guess which. */
private fun inspectAndActScript(username: String, password: String): String {
    val escapedUser = username.replace("\\", "\\\\").replace("\"", "\\\"")
    val escapedPass = password.replace("\\", "\\\\").replace("\"", "\\\"")
    return """
        (function() {
            function visible(el) {
                if (!el) return false;
                var rect = el.getBoundingClientRect();
                return !!(rect.width || rect.height) && el.offsetParent !== null;
            }
            function clickSubmitNear(el) {
                var btn = document.querySelector('button[type="submit"], input[type="submit"]');
                if (btn && visible(btn)) { btn.click(); return; }
                var form = el ? el.form : null;
                if (form) {
                    if (typeof form.requestSubmit === 'function') { form.requestSubmit(); }
                    else { form.submit(); }
                }
            }

            // Step: the "Please complete your profile" popup some
            // credentials show mid-flow -- just dismiss it and keep going,
            // per the confirmed instruction to click "Ask Again Tomorrow".
            var allClickable = document.querySelectorAll('button, a, input[type="button"]');
            for (var k = 0; k < allClickable.length; k++) {
                var t = (allClickable[k].innerText || allClickable[k].value || '').trim();
                if (t.indexOf('Ask Again Tomorrow') !== -1 && visible(allClickable[k])) {
                    allClickable[k].click();
                    return JSON.stringify({status: 'dismissed_popup', url: window.location.href});
                }
            }

            // Step: password field visible -> this is the second step of
            // the real flow -- fill it and click Sign In.
            var passField = document.querySelector('input[type="password"]');
            if (passField && visible(passField)) {
                passField.focus();
                passField.value = "$escapedPass";
                passField.dispatchEvent(new Event('input', { bubbles: true }));
                passField.dispatchEvent(new Event('change', { bubbles: true }));
                clickSubmitNear(passField);
                return JSON.stringify({status: 'submitted_password', url: window.location.href});
            }

            // Step: username field visible and not yet filled -> this is
            // the first step -- fill it and click Continue.
            var userSelectors = [
                'input[name="username"]', 'input#username',
                'input[name="email"]', 'input#email',
                'input[type="email"]',
                'input[autocomplete="username"]',
                'input[name="j_username"]'
            ];
            var userField = null;
            for (var i = 0; i < userSelectors.length; i++) {
                var candidate = document.querySelector(userSelectors[i]);
                if (candidate && visible(candidate)) { userField = candidate; break; }
            }
            if (userField && !userField.value) {
                userField.focus();
                userField.value = "$escapedUser";
                userField.dispatchEvent(new Event('input', { bubbles: true }));
                userField.dispatchEvent(new Event('change', { bubbles: true }));
                clickSubmitNear(userField);
                return JSON.stringify({status: 'submitted_username', url: window.location.href});
            }

            // Neither step's field is present/actionable any more --
            // treat this as the destination page and look for an obvious
            // error message in whatever's currently visible.
            var bodyText = (document.body ? document.body.innerText : '').toLowerCase();
            var errorHit = null;
            var needles = ['incorrect', 'invalid', 'does not match', 'try again', 'failed', 'locked out', 'error'];
            for (var j = 0; j < needles.length; j++) {
                if (bodyText.indexOf(needles[j]) !== -1) { errorHit = needles[j]; break; }
            }
            return JSON.stringify({status: 'final', url: window.location.href, errorHit: errorHit});
        })();
    """.trimIndent()
}

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

private data class InspectResult(val status: String, val url: String?, val errorHit: String?)

/** evaluateJavascript's callback hands back the JSON-encoded form of
 * whatever the script returned -- since inspectAndActScript itself returns
 * a JSON.stringify'd string, the raw callback value is that string, quoted
 * and escaped a second time by evaluateJavascript's own contract. This
 * undoes that outer layer before parsing; if anything about the real page
 * doesn't match what was anticipated, this falls back to an "unknown"
 * status so the caller just keeps polling rather than guessing. */
private fun interpretInspectResult(raw: String?): InspectResult {
    val unwrapped = raw?.trim()
        ?.removeSurrounding("\"")
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")
    val json = try {
        if (!unwrapped.isNullOrBlank() && unwrapped != "null") JSONObject(unwrapped) else null
    } catch (e: Exception) {
        null
    }
    return InspectResult(
        status = json?.optString("status")?.takeIf { it.isNotBlank() } ?: "unknown",
        url = json?.optString("url")?.takeIf { it.isNotBlank() },
        errorHit = json?.optString("errorHit")?.takeIf { it.isNotBlank() && it != "null" }
    )
}

private fun outcomeFromFinal(result: InspectResult): LoginAttemptOutcome {
    val finalUrl = result.url ?: ""
    val stillOnLogin = finalUrl.contains("login", ignoreCase = true)
    return when {
        result.errorHit != null -> LoginAttemptOutcome.Failed("page shows \"${result.errorHit}\"")
        !stillOnLogin -> LoginAttemptOutcome.Success
        else -> LoginAttemptOutcome.Failed(
            "still on the login page after the full username+password sequence -- may need a CAPTCHA/verification step done by hand"
        )
    }
}

private fun statusLabelFor(status: String): String = when (status) {
    "submitted_username" -> "Username submitted, moving to the password step..."
    "submitted_password" -> "Password submitted, checking result..."
    "dismissed_popup" -> "Dismissed a profile-completion popup, continuing..."
    else -> "Working through login steps..."
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

            // Repeatedly inspects the page and acts on whatever step is
            // currently showing (see inspectAndActScript's doc comment) --
            // self-reschedules on a timer rather than waiting on
            // onPageFinished a second time, since uptodate.com's own move
            // from the username step to the password step doesn't
            // necessarily fire a fresh page-load event.
            fun scheduleInspect(view: WebView) {
                view.postDelayed({
                    if (resolved) return@postDelayed
                    view.evaluateJavascript(inspectAndActScript(username, password)) { raw ->
                        if (resolved) return@evaluateJavascript
                        val result = interpretInspectResult(raw)
                        if (result.status == "final") {
                            resolveOnce(outcomeFromFinal(result))
                        } else {
                            onStatusChange(statusLabelFor(result.status))
                            scheduleInspect(view)
                        }
                    }
                }, INSPECT_INTERVAL_MS)
            }

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
                            scheduleInspect(view)
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
