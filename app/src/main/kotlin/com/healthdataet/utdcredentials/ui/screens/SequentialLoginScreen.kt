package com.healthdataet.utdcredentials.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.healthdataet.utdcredentials.data.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

data class SequentialLoginItem(
    val username: String,
    val password: String,
    val status: String = "PENDING",
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Runs stored UpToDate credentials one at a time in the same WebView.
 *
 * The 25-second timeout begins only at the first actual Sign In activation.
 * Pause freezes that timeout. "Pass to next" skips the current credential
 * immediately rather than waiting for its timeout.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequentialLoginScreen(
    credentials: List<Pair<String, String>>,
    onBack: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var items by remember {
        mutableStateOf(credentials.map { (user, pass) -> SequentialLoginItem(user, pass) })
    }
    var isPaused by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(-1) }
    var statusText by remember { mutableStateOf("Ready") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var skipSignal by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var stopSignal by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }

    BackHandler(enabled = isRunning) {
        isRunning = false
        stopSignal?.complete(Unit)
    }

    val runLoginSequence: () -> Unit = {
        if (!isRunning) {
            scope.launch {
                isRunning = true
                statusText = "Starting sequential login..."
                try {
                    for (index in items.indices) {
                        if (!isRunning) break

                        while (isPaused && isRunning) {
                            statusText = "Paused"
                            delay(200)
                        }
                        if (!isRunning) break

                        currentIndex = index
                        val item = items[index]
                        if (item.status != "PENDING") continue

                        items = items.toMutableList().apply {
                            set(index, item.copy(status = "RUNNING", reason = ""))
                        }
                        statusText = "Login §{index + 1}/§{items.size}: opening UpToDate..."

                        val skip = CompletableDeferred<Unit>()
                        val stop = CompletableDeferred<Unit>()
                        skipSignal = skip
                        stopSignal = stop

                        val result = coroutineScope {
                            val outcome = CompletableDeferred<LoginAttemptOutcome>()
                            val webView = webViewRef

                            if (webView == null) {
                                outcome.complete(LoginAttemptOutcome.Failed("Login WebView is not ready"))
                            } else {
                                webView.loadUrl(UPTODATE_LOGIN_URL)
                                delay(800)

                                startLoginAutomation(
                                    view = webView,
                                    username = item.username,
                                    password = item.password,
                                    isResolved = { outcome.isCompleted || !isRunning },
                                    isPaused = { isPaused },
                                    onStatus = { statusText = it },
                                    onResolved = { resolved ->
                                        if (!outcome.isCompleted) outcome.complete(resolved)
                                    }
                                )
                            }

                            select<LoginAttemptOutcome> {
                                outcome.onAwait { it }
                                skip.onAwait { LoginAttemptOutcome.Skipped }
                                stop.onAwait { LoginAttemptOutcome.Skipped }
                            }
                        }

                        val wasStopped = !isRunning && !skip.isCompleted
                        items = items.toMutableList().apply {
                            val mapped = when (result) {
                                is LoginAttemptOutcome.Success -> "SUCCESS"
                                is LoginAttemptOutcome.Failed -> "FAILED"
                                is LoginAttemptOutcome.TimedOut -> "TIMEOUT"
                                is LoginAttemptOutcome.Skipped -> "SKIPPED"
                            }
                            val reason = when (result) {
                                is LoginAttemptOutcome.Success -> "Login completed"
                                is LoginAttemptOutcome.Failed -> result.reason
                                is LoginAttemptOutcome.TimedOut -> "No clear result within the 25-second Sign In window"
                                is LoginAttemptOutcome.Skipped -> if (wasStopped) "Sequence stopped" else "Passed to next login"
                            }
                            set(index, items[index].copy(status = mapped, reason = reason))
                        }

                        reportLoginOutcome(
                            scope = scope,
                            session = SessionManager(context),
                            sourceId = null,
                            outcome = result,
                            context = context,
                            username = item.username
                        )

                        skipSignal = null
                        stopSignal = null
                        if (wasStopped) break
                        delay(300)
                    }
                } finally {
                    isRunning = false
                    isPaused = false
                    currentIndex = -1
                    skipSignal = null
                    stopSignal = null
                    statusText = "Sequential login finished"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sequential Login", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Button(onClick = onBack, enabled = !isRunning) { Text("← Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .background(Color(0xFF121212))
        ) {
            Text(
                statusText,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Button(
                    onClick = runLoginSequence,
                    enabled = !isRunning && items.any { it.status == "PENDING" },
                    modifier = Modifier.weight(1f)
                ) { Text("Start") }

                Button(
                    onClick = { isPaused = !isPaused },
                    enabled = isRunning,
                    modifier = Modifier.weight(1f)
                ) { Text(if (isPaused) "Resume" else "Pause") }

                Button(
                    onClick = {
                        if (isRunning) {
                            skipSignal?.complete(Unit)
                            val idx = currentIndex
                            if (idx >= 0 && idx < items.size) {
                                items = items.toMutableList().apply {
                                    set(idx, items[idx].copy(status = "SKIPPED", reason = "Passed to next login"))
                                }
                            }
                            statusText = "Passing to next login..."
                        }
                    },
                    enabled = isRunning,
                    modifier = Modifier.weight(1.2f)
                ) { Text("Pass to next") }

                Button(
                    onClick = {
                        isRunning = false
                        stopSignal?.complete(Unit)
                        statusText = "Stopping..."
                    },
                    enabled = isRunning,
                    modifier = Modifier.weight(.8f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("Stop") }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("§{items.count { it.status == "SUCCESS" }}/§{items.size} Success", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                Text("§{items.count { it.status in setOf("FAILED", "TIMEOUT", "WRONG_CREDENTIALS", "ACCOUNT_LOCKED") }}/§{items.size} Failed", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                Text("§{items.count { it.status == "SKIPPED" }}/§{items.size} Skipped", color = Color(0xFFFFC107), fontWeight = FontWeight.Bold)
            }

            if (isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }

            // Keep the automation WebView alive but visually collapsed. It
            // still receives page loads and touch/JS automation; the result
            // list remains the useful visible UI.
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(1.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {}
                        webViewRef = this
                    }
                }
            )

            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(items) { index, item ->
                    val bgColor = when (item.status) {
                        "SUCCESS" -> Color(0xFF1B5E20)
                        "RUNNING" -> Color(0xFF0D47A1)
                        "FAILED", "TIMEOUT", "WRONG_CREDENTIALS", "ACCOUNT_LOCKED" -> Color(0xFF5D0000)
                        "SKIPPED" -> Color(0xFF5A4500)
                        else -> Color(0xFF2F2F2F)
                    }
                    val statusLabel = when (item.status) {
                        "WRONG_CREDENTIALS" -> "Wrong Credentials"
                        "ACCOUNT_LOCKED" -> "Account Locked"
                        "TIMEOUT" -> "Timeout"
                        "SUCCESS" -> "✓ Success"
                        "FAILED" -> "Failed"
                        "SKIPPED" -> "Skipped"
                        "RUNNING" -> "Running..."
                        else -> "Pending"
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = bgColor)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("§{index + 1}. §{item.username}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                Text(statusLabel, color = Color.White, fontSize = 12.sp)
                            }
                            if (item.reason.isNotEmpty()) {
                                Text(
                                    item.reason,
                                    color = if (item.status == "SUCCESS") Color(0xFFB9F6CA) else Color(0xFFFFCDD2),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
