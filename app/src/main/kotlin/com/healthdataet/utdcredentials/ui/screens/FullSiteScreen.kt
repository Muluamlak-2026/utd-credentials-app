package com.healthdataet.utdcredentials.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.healthdataet.utdcredentials.data.PollIntervalPrefs
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
    // Round 48: a visible loading indicator while a page is in flight --
    // previously a page navigation (tapping into any admin section) showed
    // nothing at all in between, which read as the app being unresponsive
    // on a slow connection. isPageLoading drives a thin progress bar
    // pinned to the top of the WebView; loadProgress is WebChromeClient's
    // real navigation progress (0f-1f) so the bar actually reflects how
    // far the page load has gotten, not just a generic spinner.
    var isPageLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf(0f) }
    // Round 48p: counts consecutive main-frame load failures for the retry
    // logic below -- reset to 0 the moment a page finishes loading
    // successfully, so it only ever reflects a CURRENT losing streak, never
    // a stale count from an earlier, already-recovered blip.
    var errorRetryCount by remember { mutableStateOf(0) }

    // Round 48f: the header (title + notification/refresh/settings/logout
    // icons) is now a slim compact bar instead of Material3's default
    // TopAppBar height, and auto-hides while scrolling the site content
    // down (more screen for the actual admin panel), reappearing the
    // moment you scroll back up -- a touchscreen has no mouse to "hover",
    // so scroll-direction is the mobile equivalent of that ask. Driven by
    // the WebView's own onScrollChange below, since the page content lives
    // entirely inside it, not in a Compose-scrollable list this screen
    // could attach a NestedScrollConnection to directly.
    var headerVisible by remember { mutableStateOf(true) }

    // Round 48: the WebView never had a file-picker wired up at all --
    // every "Choose Files" input on the site (message attachments, bulk
    // import) silently did nothing when tapped, because Android's WebView
    // needs WebChromeClient.onShowFileChooser explicitly implemented to
    // even show a picker; without it there's no crash, no error, just
    // nothing happening, which is exactly the "silent" symptom. This
    // launcher is what onShowFileChooser below hands off to, and its
    // result is what completes the pending file-input callback.
    var pendingFileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        pendingFileChooserCallback?.onReceiveValue(uris.toTypedArray())
        pendingFileChooserCallback = null
    }

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
                        // This is also what bumps the shared unread-badge
                        // counter now (Round 42, see
                        // SessionManager.unreadNotificationCount) -- the
                        // re-sync just below picks that up, the same way it
                        // picks up whatever the WorkManager backstop or FCM
                        // added while this screen wasn't the one polling.
                        NotificationChannels.postSystemNotification(
                            context, title, body, n.optString("category"), id.toInt()
                        )
                    }
                    if (id > maxId) maxId = id
                }
                session.lastNotificationId = maxId
                notifItems = items + notifItems
            }
        }
        // Round 42: re-sync the visible badge from the shared counter on
        // every poll tick (success or failure alike, and even when this
        // particular poll found nothing new) -- this is what actually
        // surfaces anything NotificationPollWorker or
        // UtdFirebaseMessagingService posted while this exact screen wasn't
        // the one doing the polling, since all three paths now share one
        // counter instead of each keeping their own.
        notificationCount = session.unreadNotificationCount
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

        // Round 48h: fires immediately, then repeats at whatever interval
        // is currently set in PollIntervalPrefs.foregroundSeconds (default
        // 30s, changeable from Notifications settings -- a REAL setting,
        // not cosmetic: the value is re-read at the START of every single
        // loop iteration below, so changing it in Settings takes effect on
        // the very next tick, no app restart needed). The WorkManager
        // worker (NotificationPollWorker) is the backstop for when the app
        // isn't open at all -- Android enforces a hard 15-minute floor on
        // that one, which no app can go below; this foreground loop has no
        // such platform limit since it only runs while the screen is
        // actually open.
        val pollPrefs = PollIntervalPrefs(context)
        while (isActive) {
            try {
                pollNotifications()
            } catch (e: Exception) {
                // A single failed poll (offline, server hiccup) must never
                // kill the loop -- just try again next tick.
            }
            delay(pollPrefs.foregroundSeconds.toLong() * 1000L)
        }
    }

    Scaffold(
        topBar = {
            // Round 48f: a slim 44dp custom header (Material3's TopAppBar
            // enforces its own ~64dp height regardless of any height
            // modifier applied to it, so a real compact bar means not
            // using that composable) that slides away while scrolling the
            // WebView content down, and slides back on scroll-up -- see
            // headerVisible above / the WebView's onScrollChange below.
            AnimatedVisibility(
                visible = headerVisible,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                // Round 48f fix: the app draws edge-to-edge (see
                // enableEdgeToEdge() in MainActivity), which is exactly why
                // this custom bar needs its own explicit status-bar inset --
                // Material3's TopAppBar applies this automatically, but a
                // hand-built Row does not, so without this line the header
                // drew straight under the phone's clock/battery/signal
                // icons. Applied to the Surface (not just the Row) so the
                // header's background color still fills all the way up
                // behind the status bar, matching how a normal colored app
                // bar looks -- only the actual content shifts down.
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "UTD Credentials",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                modifier = Modifier.size(36.dp),
                                onClick = {
                                    showNotifDialog = true
                                    notificationCount = 0
                                    // Clear the shared counter too, not just the local
                                    // display -- otherwise the next poll's re-sync
                                    // (above) would immediately bring the badge right
                                    // back with the same already-seen count.
                                    session.unreadNotificationCount = 0
                                }
                            ) {
                                BadgedBox(badge = {
                                    if (notificationCount > 0) Badge { Text(notificationCount.toString()) }
                                }) {
                                    Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                                }
                            }
                            IconButton(
                                modifier = Modifier.size(36.dp),
                                onClick = { webViewRef?.reload() }
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                            }
                            IconButton(
                                modifier = Modifier.size(36.dp),
                                onClick = onOpenAppSettings
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = "App Settings")
                            }
                            IconButton(
                                modifier = Modifier.size(36.dp),
                                onClick = {
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
                                }
                            ) {
                                Icon(Icons.Filled.ExitToApp, contentDescription = "Log out")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize(),
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

                    // Round 48p: see AndroidFileSaverBridge's own doc comment --
                    // this is what lets exportToCSV() (and anything similar
                    // added later) save a client-built file directly instead
                    // of routing it through DownloadManager, which can never
                    // carry POST-originated content.
                    addJavascriptInterface(AndroidFileSaverBridge(ctx), "AndroidFileSaver")

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // Round 48f: hide the compact header on scroll-down,
                    // bring it back on scroll-up -- a small buffer (12px)
                    // avoids it flickering on the tiny scroll jitters some
                    // pages fire continuously. Only reacts once real
                    // scrolling is happening (scrollY > 0 already covers
                    // "scrolled down at all"; the very top always keeps it
                    // visible so it doesn't hide itself immediately on a
                    // page that loads already scrolled to 0,0).
                    setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                        when {
                            scrollY <= 0 -> headerVisible = true
                            scrollY > oldScrollY + 12 -> headerVisible = false
                            scrollY < oldScrollY - 12 -> headerVisible = true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            loadProgress = newProgress / 100f
                        }

                        // Round 48: without this override, tapping any
                        // "Choose Files" input on the site does nothing at
                        // all -- no error, just silence, because Android's
                        // WebView requires this exact callback to launch a
                        // picker for an HTML file input. Any previously
                        // pending callback is released with null first
                        // (Android's own documented requirement) so a
                        // second file input tapped before the first
                        // finishes can never leak or hang either request.
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            pendingFileChooserCallback?.onReceiveValue(null)
                            pendingFileChooserCallback = filePathCallback
                            return try {
                                val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() }
                                val mimeType = if (acceptTypes?.size == 1) acceptTypes[0] else "*/*"
                                fileChooserLauncher.launch(mimeType)
                                true
                            } catch (e: Exception) {
                                pendingFileChooserCallback = null
                                false
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isPageLoading = true
                            loadProgress = 0f
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            isPageLoading = false
                            errorRetryCount = 0
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
                        //
                        // Round 48p: on a weak connection a single transient
                        // hiccup (one dropped packet, a momentary signal
                        // drop) was enough to immediately replace the whole
                        // page with this error screen, even though the very
                        // next attempt a second later would have succeeded
                        // fine -- this is very likely most of what "apk page
                        // reloading unstabilities" on weak network describes.
                        // Now the first two main-frame failures in a row
                        // auto-retry (a short, increasing delay, so a
                        // genuinely flaky connection gets more room each
                        // time) instead of giving up immediately; the static
                        // error page with its manual Retry link only shows
                        // once a THIRD consecutive failure confirms this
                        // isn't just a passing blip.
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request.isForMainFrame) {
                                isPageLoading = false
                                errorRetryCount++
                                if (errorRetryCount <= 2) {
                                    val retryUrl = request.url?.toString() ?: (session.baseUrl + "/admin/login")
                                    view.postDelayed({
                                        isPageLoading = true
                                        view.loadUrl(retryUrl)
                                    }, errorRetryCount * 1500L)
                                } else {
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
                    }

                    // Round 48: Export buttons (Users/Credentials/etc.) on
                    // the site trigger a normal browser-style file download
                    // -- a WebView never handles those on its own without
                    // this listener, so tapping Export previously did
                    // nothing at all. Handed off to the system's own
                    // Download Manager, with the site's session cookie
                    // attached (the export routes are login-protected), so
                    // the file lands in the phone's real Downloads folder
                    // with a normal "download complete" notification.
                    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        try {
                            val request = DownloadManager.Request(Uri.parse(url))
                            CookieManager.getInstance().getCookie(url)?.let {
                                request.addRequestHeader("Cookie", it)
                            }
                            request.addRequestHeader("User-Agent", userAgent)
                            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                            request.setMimeType(mimeType)
                            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                            val downloadManager = context.getSystemService(DownloadManager::class.java)
                            downloadManager?.enqueue(request)
                            Toast.makeText(context, "Downloading $fileName…", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Couldn't start the download", Toast.LENGTH_SHORT).show()
                        }
                    }

                    loadUrl(session.baseUrl + "/admin/login")
                    webViewRef = this
                }
            }
        )
        if (isPageLoading) {
            LinearProgressIndicator(
                progress = loadProgress.coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
        }
        }
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
 * Round 48p (contrast/stability follow-up): fixes Export CSV reporting
 * "Download unsuccessful" on-device even though the button itself no longer
 * shows an error. Root cause: the earlier fix had the Credentials Hub page
 * POST the CSV text to a new server route, matching every other export
 * button on the site -- but Android's DownloadManager (which
 * setDownloadListener below hands every download off to) NEVER actually
 * reuses the request that got it there. `DownloadManager.Request(Uri.
 * parse(url))` always issues its OWN fresh GET request to that URL, with no
 * POST body at all, regardless of how the WebView originally navigated
 * there -- so a POST-only export route just 405s on that silent internal
 * GET, and the download always fails. This isn't fixable from the
 * DownloadManager side; a WebView download listener can never carry POST
 * data through to DownloadManager.
 *
 * The real fix: skip DownloadManager entirely for anything the page itself
 * generates client-side (CSV text built in JS, not fetched from a URL).
 * This bridge is exposed to the page's JS as `window.AndroidFileSaver` --
 * exportToCSV() in credentials_management.html calls
 * `AndroidFileSaver.saveTextFile(...)` directly with the already-built CSV
 * text when it's available (i.e. only inside this app's own WebView, never
 * in a normal desktop/mobile browser session on the same site), and this
 * writes the file straight to the phone's Downloads folder with no
 * network request, no DownloadManager, and no way for a GET-vs-POST
 * mismatch to ever come up again. The existing form-POST path stays as
 * the fallback for anyone opening the site in a real browser instead of
 * this app, where a POST response with Content-Disposition downloads
 * completely normally (this GET-only limitation is specific to Android's
 * WebView + DownloadManager pairing, not to browsers in general).
 */
class AndroidFileSaverBridge(private val context: Context) {
    @JavascriptInterface
    fun saveTextFile(filename: String, content: String, mimeType: String) {
        val safeName = filename.ifBlank { "download.csv" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore did not return a Uri")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not open an output stream")
            } else {
                // Pre-Android 10: no scoped-storage MediaStore.Downloads API --
                // write directly to the public Downloads directory instead,
                // covered by the WRITE_EXTERNAL_STORAGE permission this app
                // already declares (maxSdkVersion=28, see AndroidManifest.xml).
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                File(downloadsDir, safeName).writeText(content, Charsets.UTF_8)
            }
            (context as? Activity)?.runOnUiThread {
                Toast.makeText(context, "Saved $safeName to Downloads", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            (context as? Activity)?.runOnUiThread {
                Toast.makeText(context, "Couldn't save $safeName: ${e.message}", Toast.LENGTH_SHORT).show()
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
