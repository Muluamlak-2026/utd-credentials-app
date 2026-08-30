package com.healthdataet.utdcredentials.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.healthdataet.utdcredentials.data.PendingUpToDateLogin

private const val UPTODATE_LOGIN_URL = "https://www.uptodate.com/login"

/** Builds the best-effort autofill script for whatever field(s) actually
 * exist on the CURRENT page -- uptodate.com's real login flow may ask for
 * username and password on one combined page, or as separate steps; this
 * runs on every single page load inside this screen (see onPageFinished
 * below) so whichever step is showing right now gets whatever field(s) it
 * has filled in. Tries several selector patterns per field since the
 * exact real markup can't be verified from outside a live login attempt --
 * if uptodate.com's actual field names/ids differ from all of these, only
 * this one function needs updating. Deliberately fills only -- never
 * auto-submits/clicks a login button -- so a change of flow, a CAPTCHA, or
 * a 2FA step on their side never gets silently bypassed or broken; the
 * admin still taps the real "Log In" button themselves once the fields
 * are filled. */
private fun autofillScript(username: String, password: String): String {
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

/**
 * Round 48i: the WebView half of the UpToDate quick-login feature --
 * CredentialPickerScreen hands off exactly one username/password pair via
 * PendingUpToDateLogin, this screen loads uptodate.com's real login page
 * and fills them in automatically. Consumed once on first load; a manual
 * refresh (the toolbar's Refresh icon) re-fills using the same pair in
 * case the page reloads or the fields render in after a delay, without
 * needing to go back and re-pick the same credential again.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpToDateLoginScreen(onBack: () -> Unit) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var isPageLoading by remember { mutableStateOf(true) }
    // Consumed once, up front -- a later manual "Refresh"/re-navigation
    // reuses this same in-memory copy so the admin doesn't have to go back
    // to the picker just to re-trigger the fill.
    val credential = remember { PendingUpToDateLogin.consume() }

    BackHandler(enabled = canGoBack) {
        webViewRef?.let { if (it.canGoBack()) it.goBack() }
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
                        credential?.let { (u, p) ->
                            webViewRef?.evaluateJavascript(autofillScript(u, p), null)
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-fill")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
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

                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isPageLoading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                isPageLoading = false
                                canGoBack = view.canGoBack()
                                // Runs on EVERY page load inside this screen
                                // (initial login page, and any step/redirect
                                // after it) -- autofillScript only fills
                                // whatever field(s) actually exist on the
                                // page currently showing, so a multi-step
                                // login flow gets each step filled as it
                                // appears. A second delayed attempt covers
                                // pages that render their form fields in via
                                // JS slightly after the page "finishes".
                                credential?.let { (u, p) ->
                                    val script = autofillScript(u, p)
                                    view.evaluateJavascript(script, null)
                                    view.postDelayed({ view.evaluateJavascript(script, null) }, 800)
                                }
                            }
                        }

                        loadUrl(UPTODATE_LOGIN_URL)
                        webViewRef = this
                    }
                }
            )
            if (isPageLoading) {
                // Box's default child alignment is top-start, so a plain
                // fillMaxWidth() bar here pins to the top edge, matching
                // FullSiteScreen's own loading-bar placement.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
