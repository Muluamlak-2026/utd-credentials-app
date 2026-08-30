package com.healthdataet.utdcredentials.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.healthdataet.utdcredentials.data.PendingUpToDateLogin
import kotlinx.coroutines.delay

/**
 * Round 48i: the WebView half of the UpToDate quick-login feature --
 * CredentialPickerScreen hands off exactly one username/password pair via
 * PendingUpToDateLogin, this screen loads uptodate.com's real login page.
 *
 * Round 48i(+): originally this screen only filled the field(s) in and
 * left the admin to tap Continue/Sign In themselves -- once the sequential
 * batch runner's automation (SequentialLoginScreen.kt / LoginAutomation.kt)
 * was fixed to handle uptodate.com's real two-step flow (username +
 * Continue, then a separate password + Sign In step, with an occasional
 * "complete your profile" popup dismissed via "Ask Again Tomorrow"), the
 * same fully-automated flow was asked for here too. This screen now shares
 * that exact automation core (see LoginAutomation.kt) instead of its own
 * fill-only script -- it fills AND submits both steps unattended, the same
 * way the batch runner does for each credential.
 *
 * The WebView stays fully interactive throughout: if something the
 * automation can't handle shows up (a CAPTCHA, a 2FA prompt), the admin
 * can just take over by hand right where it stopped -- this screen never
 * disables the page or blocks touches while it works. A status banner at
 * the top shows what's happening / what happened; the toolbar's Refresh
 * icon reloads the page and starts the automation over from scratch using
 * the same in-memory credential (no need to go back and re-pick it).
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpToDateLoginScreen(onBack: () -> Unit) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var isPageLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("Loading login page...") }
    var outcome by remember { mutableStateOf<LoginAttemptOutcome?>(null) }
    var resolved by remember { mutableStateOf(false) }
    // Bumping this restarts the whole automated attempt (fresh cookies,
    // fresh WebView, fresh timeout) -- used by the toolbar's Refresh icon,
    // and reuses the SAME in-memory credential rather than needing the
    // admin to go back and re-pick it.
    var attemptGeneration by remember { mutableStateOf(0) }

    // Consumed once, up front -- Refresh re-runs the automation with this
    // same in-memory copy rather than re-consuming (which would already be
    // empty the second time).
    val credential = remember { PendingUpToDateLogin.consume() }
    val username = credential?.first
    val password = credential?.second

    fun resolveOnce(result: LoginAttemptOutcome) {
        if (!resolved) {
            resolved = true
            outcome = result
        }
    }

    BackHandler(enabled = canGoBack) {
        webViewRef?.let { if (it.canGoBack()) it.goBack() }
    }

    LaunchedEffect(attemptGeneration) {
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            resolveOnce(LoginAttemptOutcome.Skipped)
            return@LaunchedEffect
        }
        delay(ATTEMPT_TIMEOUT_MS)
        resolveOnce(LoginAttemptOutcome.TimedOut)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UpToDate Login") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        resolved = false
                        outcome = null
                        statusText = "Restarting..."
                        attemptGeneration++
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Restart login")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val currentOutcome = outcome
            if (currentOutcome != null || username.isNullOrBlank() || password.isNullOrBlank()) {
                val (label, isError) = when (currentOutcome) {
                    is LoginAttemptOutcome.Success -> "Logged in successfully" to false
                    is LoginAttemptOutcome.Failed -> "Login failed -- ${currentOutcome.reason}" to true
                    is LoginAttemptOutcome.TimedOut -> "No clear result in time -- check the page below" to true
                    is LoginAttemptOutcome.Skipped -> "This credential has no username/password on file" to true
                    null -> "This credential has no username/password on file" to true
                }
                Surface(
                    color = if (isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        label,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Surface(tonalElevation = 2.dp) {
                    Text(
                        statusText,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                    key(attemptGeneration) {
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

                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(
                                            view: WebView,
                                            url: String?,
                                            favicon: android.graphics.Bitmap?
                                        ) {
                                            super.onPageStarted(view, url, favicon)
                                            isPageLoading = true
                                        }

                                        override fun onPageFinished(view: WebView, url: String?) {
                                            super.onPageFinished(view, url)
                                            isPageLoading = false
                                            canGoBack = view.canGoBack()
                                            // Only kick the automation loop
                                            // off once per attempt -- if
                                            // uptodate.com navigates again
                                            // mid-flow (the real step-2
                                            // page), the already-running
                                            // loop notices on its own next
                                            // tick instead of a second loop
                                            // starting alongside it.
                                            if (!loopStarted) {
                                                loopStarted = true
                                                statusText = "Working through login steps..."
                                                startLoginAutomation(
                                                    view = view,
                                                    username = username,
                                                    password = password,
                                                    isResolved = { resolved },
                                                    onStatus = { statusText = it },
                                                    onResolved = { resolveOnce(it) }
                                                )
                                            }
                                        }
                                    }

                                    loadUrl(UPTODATE_LOGIN_URL)
                                    webViewRef = this
                                }
                            }
                        )
                    }
                }
                if (isPageLoading) {
                    // Box's default child alignment is top-start, so a
                    // plain fillMaxWidth() bar here pins to the top edge.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
