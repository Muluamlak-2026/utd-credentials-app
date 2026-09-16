package com.healthdataet.utdcredentials.ui.screens

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.MotionEvent
import kotlinx.coroutines.*

private const val ATTEMPT_TIMEOUT_MS = 20_000L
private const val SETTLE_POLL_MS = 350L
private const val MAX_SETTLE_POLLS = 10
private const val TAP_SETTLE_MS = 500L

data class LoginAttemptResult(
    val status: String,
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

suspend fun startLoginAutomation(
    webView: WebView,
    username: String,
    password: String,
    onStatusChange: (String) -> Unit,
    onComplete: (LoginAttemptResult) -> Unit,
    scope: CoroutineScope
) {
    val startTime = System.currentTimeMillis()

    try {
        onStatusChange("Starting login automation...")

        onStatusChange("Filling username...")
        fillField(webView, "username", username)
        delay(TAP_SETTLE_MS)

        onStatusChange("Checking for popups...")
        val cookieResult = waitForAndHandlePopup(webView, "cookie")
        if (cookieResult) {
            delay(500)
        }

        val touResult = waitForAndHandlePopup(webView, "tou")
        if (touResult) {
            delay(500)
        }

        var continueAttempts = 0
        while (continueAttempts < 2 && isTimeWithinLimit(startTime, ATTEMPT_TIMEOUT_MS)) {
            val hasContinue = inspectAndAct(webView, onStatusChange)
            if (hasContinue == "need_continue") {
                onStatusChange("Clicking Continue button...")
                delay(TAP_SETTLE_MS)
                continueAttempts++
            } else {
                break
            }
            delay(SETTLE_POLL_MS)
        }

        onStatusChange("Filling password...")
        fillField(webView, "password", password)
        delay(TAP_SETTLE_MS)

        onStatusChange("Submitting password...")
        submitField(webView)
        delay(500)

        var signInAttempts = 0
        while (signInAttempts < 3 && isTimeWithinLimit(startTime, ATTEMPT_TIMEOUT_MS)) {
            val action = inspectAndAct(webView, onStatusChange)

            when (action) {
                "need_signin" -> {
                    onStatusChange("Found Sign In button - attempting click...")
                    
                    val coords = getSignInButtonCoordinates(webView)
                    
                    if (coords != null) {
                        if (signInAttempts == 0) {
                            onStatusChange("Attempt 1: Native tap on Sign In button...")
                            nativeTap(webView, coords.first, coords.second)
                        } else if (signInAttempts == 1) {
                            onStatusChange("Attempt 2: Native tap with offset...")
                            nativeTap(webView, coords.first + 3, coords.second + 3)
                        } else if (signInAttempts == 2) {
                            onStatusChange("Attempt 3: JavaScript click...")
                            clickSignInViaJavaScript(webView)
                        }
                    } else {
                        onStatusChange("Could not locate Sign In button, trying JavaScript click...")
                        clickSignInViaJavaScript(webView)
                    }
                    
                    signInAttempts++
                    delay(TAP_SETTLE_MS)
                }
                "wrong_credentials" -> {
                    onComplete(LoginAttemptResult("FAILED", "WRONG_CREDENTIALS"))
                    return
                }
                "account_locked" -> {
                    onComplete(LoginAttemptResult("FAILED", "ACCOUNT_LOCKED"))
                    return
                }
                "success" -> {
                    onComplete(LoginAttemptResult("SUCCESS", "Login completed"))
                    return
                }
                else -> {
                    delay(SETTLE_POLL_MS)
                }
            }

            if (System.currentTimeMillis() - startTime > ATTEMPT_TIMEOUT_MS) break
        }

        onComplete(LoginAttemptResult("FAILED", "TIMEOUT -- Sign In button not clicked in time"))

    } catch (e: Exception) {
        onComplete(LoginAttemptResult("FAILED", "ERROR: ${e.message}"))
    }
}

private fun isTimeWithinLimit(startTime: Long, limitMs: Long): Boolean {
    return System.currentTimeMillis() - startTime < limitMs
}

private suspend fun inspectAndAct(
    webView: WebView,
    onStatusChange: (String) -> Unit
): String {
    var result = ""
    val job = CompletableDeferred<String>()

    webView.evaluateJavascript("""
        (function() {
            const bodyText = document.body.innerText.toLowerCase();

            if (bodyText.includes('invalid') && (bodyText.includes('username') || bodyText.includes('password'))) {
                return JSON.stringify({ action: 'wrong_credentials' });
            }

            if (bodyText.includes('account locked') || bodyText.includes('locked due')) {
                return JSON.stringify({ action: 'account_locked' });
            }

            const touOverlay = document.querySelector('[role="dialog"]');
            if (touOverlay && bodyText.includes('terms')) {
                return JSON.stringify({ action: 'need_tou' });
            }

            const buttons = Array.from(document.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"]'));
            const signInBtn = buttons.find(b => {
                const text = b.innerText?.toLowerCase() || b.value?.toLowerCase() || '';
                return text.includes('sign in') || text.includes('login') || text.includes('submit');
            });
            if (signInBtn && signInBtn.offsetParent !== null) {
                return JSON.stringify({ action: 'need_signin' });
            }

            const continueBtn = Array.from(document.querySelectorAll('button')).find(b =>
                b.innerText.toLowerCase().includes('continue'));
            if (continueBtn && continueBtn.offsetParent !== null) {
                return JSON.stringify({ action: 'need_continue' });
            }

            if (document.title.includes('Dashboard') || document.title.includes('Admin') ||
                document.querySelector('[class*="dashboard"], [class*="admin"]')) {
                return JSON.stringify({ action: 'success' });
            }

            return JSON.stringify({ action: 'waiting' });
        })()
    """) { jsonResult ->
        try {
            val data = org.json.JSONObject(jsonResult)
            result = data.getString("action")
            job.complete(result)
        } catch (e: Exception) {
            job.complete("waiting")
        }
    }

    return try {
        withTimeoutOrNull(2000) { job.await() } ?: "waiting"
    } catch (e: Exception) {
        "waiting"
    }
}

private suspend fun waitForAndHandlePopup(webView: WebView, popupType: String): Boolean {
    repeat(5) {
        val hasPopup = detectPopup(webView, popupType)
        if (hasPopup) {
            clickPopupButton(webView, popupType)
            delay(500)
            return true
        }
        delay(300)
    }
    return false
}

private suspend fun detectPopup(webView: WebView, popupType: String): Boolean {
    var detected = false
    val job = CompletableDeferred<Boolean>()

    val searchText = when (popupType) {
        "cookie" -> "cookie|consent"
        "tou" -> "terms|agreement|accept terms"
        else -> ""
    }

    webView.evaluateJavascript("""
        (function() {
            const overlay = document.querySelector('[role="dialog"], .modal, [class*="popup"]');
            if (overlay && overlay.offsetParent !== null) {
                const text = overlay.innerText.toLowerCase();
                return text.includes('$searchText');
            }
            return false;
        })()
    """) { result ->
        detected = result.toBoolean()
        job.complete(detected)
    }

    return try {
        withTimeoutOrNull(2000) { job.await() } ?: false
    } catch (e: Exception) {
        false
    }
}

private fun clickPopupButton(webView: WebView, popupType: String) {
    webView.evaluateJavascript("""
        (function() {
            const overlay = document.querySelector('[role="dialog"], .modal, [class*="popup"]');
            if (overlay) {
                const buttons = Array.from(overlay.querySelectorAll('button'));
                const btn = buttons.find(b => b.innerText.toLowerCase().includes('accept') ||
                                              b.innerText.toLowerCase().includes('agree') ||
                                              b.innerText.toLowerCase().includes('ok'));
                if (btn) btn.click();
            }
        })()
    """) {}
}

private fun fillField(webView: WebView, fieldType: String, value: String) {
    val selector = when (fieldType) {
        "username" -> "input[type='text'], input[type='email'], input[name*='user'], input[name*='login']"
        "password" -> "input[type='password']"
        else -> ""
    }

    webView.evaluateJavascript("""
        (function() {
            const field = document.querySelector('$selector');
            if (field) {
                field.value = '$value';
                field.dispatchEvent(new Event('input', { bubbles: true }));
                field.dispatchEvent(new Event('change', { bubbles: true }));
            }
        })()
    """) {}
}

private fun submitField(webView: WebView) {
    webView.evaluateJavascript("""
        (function() {
            const form = document.querySelector('form');
            if (form) form.submit();
        })()
    """) {}
}

private fun getSignInButtonCoordinates(webView: WebView): Pair<Float, Float>? {
    var coords: Pair<Float, Float>? = null

    webView.evaluateJavascript("""
        (function() {
            const buttons = Array.from(document.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"]'));
            const signInBtn = buttons.find(b => {
                const text = b.innerText?.toLowerCase() || b.value?.toLowerCase() || '';
                return text.includes('sign in') || text.includes('login') || text.includes('submit');
            });
            
            if (signInBtn && signInBtn.offsetParent !== null) {
                const rect = signInBtn.getBoundingClientRect();
                const x = window.scrollX + rect.left + rect.width / 2;
                const y = window.scrollY + rect.top + rect.height / 2;
                return JSON.stringify({ x: x, y: y });
            }
            return null;
        })()
    """) { result ->
        try {
            if (result != null && result != "null") {
                val json = org.json.JSONObject(result)
                coords = Pair(json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
            }
        } catch (e: Exception) {
            coords = null
        }
    }

    return coords
}

private fun clickSignInViaJavaScript(webView: WebView) {
    webView.evaluateJavascript("""
        (function() {
            const buttons = Array.from(document.querySelectorAll('button, [role="button"], input[type="button"], input[type="submit"]'));
            const signInBtn = buttons.find(b => {
                const text = b.innerText?.toLowerCase() || b.value?.toLowerCase() || '';
                return text.includes('sign in') || text.includes('login') || text.includes('submit');
            });
            
            if (signInBtn) {
                signInBtn.click();
                signInBtn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                signInBtn.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
                signInBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
            }
        })()
    """) {}
}

private fun nativeTap(webView: WebView, x: Float, y: Float) {
    val downTime = System.currentTimeMillis()

    val downEvent = MotionEvent.obtain(
        downTime,
        downTime,
        MotionEvent.ACTION_DOWN,
        x, y,
        0
    )
    webView.dispatchTouchEvent(downEvent)
    downEvent.recycle()

    Thread.sleep(70)

    val moveEvent = MotionEvent.obtain(
        downTime,
        System.currentTimeMillis(),
        MotionEvent.ACTION_MOVE,
        x + 1f, y,
        0
    )
    webView.dispatchTouchEvent(moveEvent)
    moveEvent.recycle()

    Thread.sleep(10)

    val upEvent = MotionEvent.obtain(
        downTime,
        System.currentTimeMillis(),
        MotionEvent.ACTION_UP,
        x + 1f, y,
        0
    )
    webView.dispatchTouchEvent(upEvent)
    upEvent.recycle()
}
