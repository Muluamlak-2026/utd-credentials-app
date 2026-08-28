package com.healthdataet.utdcredentials.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.firebase.messaging.FirebaseMessaging
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.PendingWebLogin
import com.healthdataet.utdcredentials.data.SessionManager
import com.healthdataet.utdcredentials.data.SiteCredsStore
import com.healthdataet.utdcredentials.push.NotificationChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The whole app, really -- a WebView showing the live admin panel, plus a
 * slim top bar (notifications / refresh / logout). Every actual admin
 * feature (Users, Credentials Hub, Broadcast, Backup, all of it) lives on
 * the real site and just renders here; this screen adds nothing on top of
 * it except push-notification plumbing the site itself can't do alone.
 *
 * WebView settings below deliberately mirror the site's own mobile CSS
 * instead of rendering it desktop-size and shrinking it to fit -- that
 * mismatch (not this screen's settings) was the actual cause of the
 * "extremely zoomed in / dropdowns and buttons cut off" complaints on the
 * previous build. useWideViewPort + overview mode + textZoom=100 make the
 * WebView behave like a real mobile browser viewport, which is exactly
 * what the panel's responsive CSS already targets.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSiteScreen(
    session: SessionManager,
    onLoggedOut: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val context = LocalContext.current
    val siteCredsStore = remember { SiteCredsStore(context) }
    val scope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var notificationCount by remember { mutableStateOf(0) }
    var showNotifDialog by remember { mutableStateOf(false) }
    var notifItems by remember { mutableStateOf(listOf<String>()) }

    BackHandler(enabled = canGoBack) {
        webViewRef?.let { if (it.canGoBack()) it.goBack() }
    }

    // Round 32: this used to run exactly once (LaunchedEffect(Unit), no
    // loop) and only ever updated the in-app badge/dialog above -- it never
    // called NotificationManager.notify() at all. That combination is the
    // confirmed root cause of "inbuilt notification system doesn't alert
    // me": nothing rang or showed in the system tray unless Firebase also
    // happened to be configured. Now it (a) actually posts a real system
    // notification per new item, through the exact same
    // NotificationChannels logic FCM and the WorkManager backstop use, and
    // (b) skips anything with id <= lastNotificationId, so an item already
    // shown via FCM (see UtdFirebaseMessagingService's notif_id handling)
    // or the background worker is never shown a second time here.
    suspend fun pollNotifications() {
        val token = session.apiToken ?: return
        val result = withContext(Dispatchers.IO) {
            ApiClient(session.baseUrl).pollNotifications(token, session.lastNotificationId)
        }
        if (result.ok && result.json != null) {
            val arr: JSONArray = result.json.optJSONArray("notifications") ?: JSONArray()
            if (arr.length() > 0) {
                val items = mutableListOf<String>()
                var maxId = session.lastNotificationId
                for (i in 0 until arr.length()) {
                    val n = arr.optJSONObject(i) ?: continue
                    val id = n.optLong("id", 0L)
                    val title = n.optString("title").ifBlank { "UTD Credentials" }
                    val body = n.optString("body").ifBlank { "New notification" }
                    items.add(body)
                    if (id > session.lastNotificationId) {
                        NotificationChannels.postSystemNotification(
                            context, title, body, n.optString("category"), id.toInt()
                        )
                    }
                    if (id > maxId) maxId = id
                }
                session.lastNotificationId = maxId
                notifItems = items + notifItems
                notificationCount += items.size
            }
        }
    }

    LaunchedEffect(Unit) {
        // Make sure THIS device's current FCM token is registered even if
        // onNewToken never fires again this session (e.g. it was already
        // generated before this login happened).
        val apiToken = session.apiToken
        if (apiToken != null) {
            try {
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                session.fcmToken = fcmToken
                withContext(Dispatchers.IO) {
                    ApiClient(session.baseUrl).registerDeviceToken(apiToken, fcmToken)
                }
            } catch (e: Exception) {
                // Push is optional -- the app works fully without it (e.g.
                // before Firebase is configured for real, see
                // TERMUX_SETUP.md Part D).
            }
        }

        // Repeating foreground poll -- fires immediately, then every 30s
        // for as long as this screen is on-screen and composed. The
        // WorkManager worker (NotificationPollWorker, every ~15 min) is
        // the backstop for when the app isn't open at all; this is the
        // fast path for while an admin is actually using the app.
        while (isActive) {
            try {
                pollNotifications()
            } catch (e: Exception) {
                // A single failed poll (offline, server hiccup) must never
                // kill the loop -- just try again next tick.
            }
            delay(30_000L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UTD Credentials") },
                actions = {
                    IconButton(onClick = {
                        showNotifDialog = true
                        notificationCount = 0
                    }) {
                        BadgedBox(badge = {
                            if (notificationCount > 0) Badge { Text(notificationCount.toString()) }
                        }) {
                            Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                        }
                    }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenAppSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "App Settings")
                    }
                    IconButton(onClick = {
                        val apiToken = session.apiToken
                        val fcmToken = session.fcmToken
                        scope.launch {
                            if (apiToken != null) {
                                withContext(Dispatchers.IO) {
                                    ApiClient(session.baseUrl).logout(apiToken, fcmToken)
                                }
                            }
                            CookieManager.getInstance().removeAllCookies(null)
                            session.clear()
                            onLoggedOut()
                        }
                    }) {
                        Icon(Icons.Filled.ExitToApp, contentDescription = "Log out")
                    }
                }
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
                    settings.textZoom = 100
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(true)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            canGoBack = view.canGoBack()
                            // Round 32: auto-fill + submit the web login form
                            // every time this screen lands on /admin/login --
                            // not just once right after a fresh native login.
                            // PendingWebLogin.consume() still wins when it has
                            // something (the just-typed password, freshest
                            // and guaranteed correct); SiteCredsStore is the
                            // persisted fallback for every later visit --
                            // session-expiry redirects, app restarts, etc.
                            if (url != null && url.contains("/admin/login")) {
                                val pending = PendingWebLogin.consume() ?: siteCredsStore.get()
                                if (pending != null) {
                                    val (u, p) = pending
                                    val escapedUser = u.replace("\\", "\\\\").replace("\"", "\\\"")
                                    val escapedPass = p.replace("\\", "\\\\").replace("\"", "\\\"")
                                    val js = "(function(){" +
                                        "var f=document.querySelector('form');" +
                                        "if(!f)return;" +
                                        "var uField=f.querySelector('input[name=\"username\"]');" +
                                        "var pField=f.querySelector('input[name=\"password\"]');" +
                                        "if(uField&&pField){" +
                                        "uField.value=\"$escapedUser\";" +
                                        "pField.value=\"$escapedPass\";" +
                                        "f.submit();" +
                                        "}" +
                                        "})();"
                                    view.evaluateJavascript(js, null)
                                }
                            }
                        }

                        // A friendly "can't reach the server" page instead of
                        // the WebView's own blank white screen (or a cryptic
                        // browser error page) whenever the main page itself
                        // fails to load -- a sub-resource failing (one image,
                        // one script) must never replace real page content,
                        // hence the isForMainFrame check.
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request.isForMainFrame) {
                                view.loadDataWithBaseURL(
                                    null,
                                    connectionErrorHtml(session.baseUrl + "/admin/login", error.description?.toString()),
                                    "text/html",
                                    "utf-8",
                                    null
                                )
                            }
                        }
                    }
                    loadUrl(session.baseUrl + "/admin/login")
                    webViewRef = this
                }
            }
        )
    }

    if (showNotifDialog) {
        Dialog(onDismissRequest = { showNotifDialog = false }) {
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    Text("Recent notifications", style = MaterialTheme.typography.titleMedium)
                    if (notifItems.isEmpty()) {
                        Text("Nothing new.", modifier = Modifier.padding(top = 8.dp))
                    } else {
                        notifItems.take(20).forEach { msg ->
                            Text("• $msg", modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        }
                    }
                    TextButton(
                        onClick = { showNotifDialog = false },
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/**
 * A plain, dependency-free HTML string (no JS bridge needed) shown in
 * place of the WebView's own blank/cryptic error page whenever the main
 * admin panel page fails to load -- offline, DNS hiccup, server restart,
 * etc. Tapping "Retry" is a normal link tap: the WebView's default
 * behavior navigates it like any other link, so no special handling is
 * needed to actually retry.
 */
private fun connectionErrorHtml(retryUrl: String, detail: String?): String {
    val safeDetail = (detail ?: "").replace("<", "&lt;").replace(">", "&gt;")
    return """
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body style="font-family:sans-serif;padding:32px 24px;text-align:center;color:#333;">
            <div style="font-size:48px;margin-bottom:8px;">&#128268;</div>
            <h2 style="margin:0 0 8px;">Can't reach the admin panel</h2>
            <p style="color:#666;margin:0 0 24px;">
                Check your internet connection, then try again.
                ${if (safeDetail.isNotBlank()) "<br><small>($safeDetail)</small>" else ""}
            </p>
            <a href="$retryUrl" style="display:inline-block;padding:12px 28px;background:#6366F1;
                color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;">Retry</a>
        </body>
        </html>
    """.trimIndent()
}
