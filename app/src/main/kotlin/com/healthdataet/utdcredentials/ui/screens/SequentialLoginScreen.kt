package com.healthdataet.utdcredentials.ui.screens

import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
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
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*

data class SequentialLoginItem(
    val username: String,
    val password: String,
    val status: String = "PENDING",
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun SequentialLoginScreen(
    credentials: List<Pair<String, String>>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var items by remember { mutableStateOf(
        credentials.mapIndexed { index, (user, pass) ->
            SequentialLoginItem(user, pass)
        }
    )}
    
    var isPaused by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    
    val sharedPrefs = remember { 
        context.getSharedPreferences("utd_sequential", Context.MODE_PRIVATE)
    }
    
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    
    LaunchedEffect(Unit) {
        isPaused = sharedPrefs.getBoolean("is_paused", false)
    }
    
    LaunchedEffect(isPaused, isRunning) {
        webViewRef?.let { webView ->
            try {
                val view = webView
                val params = view.layoutParams
                if (params is WindowManager.LayoutParams) {
                    if (isPaused && isRunning) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        sharedPrefs.edit().putBoolean("is_paused", true).apply()
                    } else if (!isPaused && isRunning) {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                        sharedPrefs.edit().putBoolean("is_paused", false).apply()
                    } else if (!isRunning) {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                        sharedPrefs.edit().remove("is_paused").apply()
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
    
    val runLoginSequence = {
        scope.launch {
            isRunning = true
            currentIndex = 0
            
            items.forEachIndexed { index, item ->
                if (!isRunning) return@forEachIndexed
                
                while (isPaused && isRunning) {
                    delay(500)
                }
                
                if (!isRunning) return@forEachIndexed
                
                currentIndex = index
                items = items.toMutableList().apply {
                    set(index, items[index].copy(status = "RUNNING"))
                }
                
                webViewRef?.let { webView ->
                    val result = withTimeoutOrNull(25_000L) {
                        suspendCancellableCoroutine<LoginAttemptResult> { continuation ->
                            startLoginAutomation(
                                webView = webView,
                                username = item.username,
                                password = item.password,
                                onStatusChange = { },
                                onComplete = { result ->
                                    continuation.resume(result)
                                },
                                scope = scope
                            )
                        }
                    } ?: LoginAttemptResult("FAILED", "TIMEOUT")
                    
                    items = items.toMutableList().apply {
                        val statusText = when (result.status) {
                            "SUCCESS" -> "SUCCESS"
                            "FAILED" -> when (result.reason) {
                                "WRONG_CREDENTIALS" -> "WRONG_CREDENTIALS"
                                "ACCOUNT_LOCKED" -> "ACCOUNT_LOCKED"
                                else -> "TIMEOUT"
                            }
                            else -> result.status
                        }
                        set(index, items[index].copy(
                            status = statusText,
                            reason = result.reason
                        ))
                    }
                }
                
                delay(1000)
            }
            
            isRunning = false
            sharedPrefs.edit().remove("is_paused").apply()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sequential Login (Round 65)", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Button(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                        Text("← Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1F1F1F)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .background(Color(0xFF121212))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = runLoginSequence,
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start")
                }
                
                Button(
                    onClick = { isPaused = !isPaused },
                    enabled = isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isPaused) "Resume" else "Pause")
                }
                
                Button(
                    onClick = {
                        isRunning = false
                        isPaused = false
                        currentIndex = 0
                        sharedPrefs.edit().remove("is_paused").apply()
                        items = items.map { it.copy(status = "PENDING", reason = "") }
                    },
                    enabled = isRunning || isPaused,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
                ) {
                    Text("Stop")
                }
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${items.count { it.status == "SUCCESS" }}/${items.size} Success",
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${items.count { it.status == "FAILED" || it.status == "TIMEOUT" || it.status == "WRONG_CREDENTIALS" || it.status == "ACCOUNT_LOCKED" }}/${items.size} Failed",
                    color = Color(0xFFFF9800),
                    fontWeight = FontWeight.Bold
                )
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items) { index, item ->
                    val bgColor = when (item.status) {
                        "SUCCESS" -> Color(0xFF1B5E20)
                        "RUNNING" -> Color(0xFF0D47A1)
                        "FAILED", "TIMEOUT", "WRONG_CREDENTIALS", "ACCOUNT_LOCKED" -> Color(0xFF5D0000)
                        else -> Color(0xFF2F2F2F)
                    }
                    
                    val statusText = when (item.status) {
                        "WRONG_CREDENTIALS" -> "❌ Wrong Credentials"
                        "ACCOUNT_LOCKED" -> "🔒 Account Locked"
                        "TIMEOUT" -> "⏱ Timeout"
                        "SUCCESS" -> "✓ Success"
                        "FAILED" -> "❌ Failed"
                        "RUNNING" -> "⏳ Running..."
                        else -> "◯ Pending"
                    }
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor),
                        colors = CardDefaults.cardColors(
                            containerColor = bgColor
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${index + 1}. ${item.username}",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                                Text(
                                    statusText,
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                            if (item.reason.isNotEmpty()) {
                                Text(
                                    "Reason: ${item.reason}",
                                    color = Color(0xFFBBBBBB),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
