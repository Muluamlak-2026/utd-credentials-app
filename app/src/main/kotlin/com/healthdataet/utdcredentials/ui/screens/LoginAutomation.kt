package com.healthdataet.utdcredentials.ui.screens

import android.webkit.WebView
import org.json.JSONObject

const val UPTODATE_LOGIN_URL = "https://www.uptodate.com/login"
const val ATTEMPT_TIMEOUT_MS = 40_000L
const val INSPECT_INTERVAL_MS = 1200L
const val SUBMIT_SETTLE_MS = 2500L

fun inspectAndActScript(username: String, password: String): String {
    val escapedUser = username.replace("\\", "\\\\").replace("\"", "\\\"")
    val escapedPass = password.replace("\\", "\\\\").replace("\"", "\\\"")
    return """
        (function() {
            function visible(el) {
                if (!el) return false;
                var rect = el.getBoundingClientRect();
                if (!(rect.width || rect.height)) return false;
                if (el.offsetParent === null) return false;
                var style = window.getComputedStyle(el);
                if (!style) return true;
                if (style.visibility === 'hidden') return false;
                if (style.display === 'none') return false;
                var opacity = parseFloat(style.opacity);
                if (!isNaN(opacity) && opacity === 0) return false;
                return true;
            }

            function findButtonByText(labels) {
                var candidates = document.querySelectorAll(
                    'button, a, input[type="button"], input[type="submit"], [role="button"]'
                );
                for (var i = 0; i < candidates.length; i++) {
                    var el = candidates[i];
                    if (!visible(el)) continue;
                    var t = (el.innerText || el.value || el.getAttribute('aria-label') || '')
                        .trim().toLowerCase();
                    for (var j = 0; j < labels.length; j++) {
                        if (t.indexOf(labels[j]) !== -1) { return el; }
                    }
                }
                return null;
            }

            function clickSubmit(el, labels) {
                var byText = findButtonByText(labels);
                if (byText) { byText.click(); return; }
                var btn = document.querySelector('button[type="submit"], input[type="submit"]');
                if (btn && visible(btn)) { btn.click(); return; }
                var form = el ? el.form : null;
                if (form) {
                    if (typeof form.requestSubmit === 'function') { form.requestSubmit(); }
                    else { form.submit(); }
                }
            }

            // Priority 1: the "Your Privacy" cookie-consent modal. Must be
            // dismissed before anything else can be reliably found/clicked,
            // since it can visually sit on top of the real form.
            var acceptCookies = findButtonByText(['accept all cookies', 'accept all', 'accept cookies']);
            if (acceptCookies) {
                acceptCookies.click();
                return JSON.stringify({status: 'accepted_cookies', url: window.location.href});
            }

            // Priority 2: the "Please complete your profile" popup.
            var askLater = findButtonByText(['ask again tomorrow']);
            if (askLater) {
                askLater.click();
                return JSON.stringify({status: 'dismissed_popup', url: window.location.href});
            }

            // uptodate.com's login page has turned out to vary: sometimes a
            // true two-step flow (username-only page, then a separate
            // password-only page), and sometimes both fields on one page at
            // once. Rather than assuming either shape, every poll looks at
            // whatever fields actually exist right now and fills in
            // whichever ones are empty -- this works for both shapes.
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
            var passField = document.querySelector('input[type="password"]');
            var passFieldUsable = passField && visible(passField);

            if (passFieldUsable) {
                // The password field is on screen -- this is the final
                // step, whether or not a username field is showing
                // alongside it on the same page. A page-scoped flag (reset
                // automatically on real navigation, since window is a fresh
                // object then) guarantees Sign In is only ever clicked
                // once per page, no matter how many times this poll runs.
                if (window.__utdSignInClicked) {
                    return JSON.stringify({status: 'waiting_password_result', url: window.location.href});
                }
                var userReady = !userField || !!userField.value;
                if (userField && !userField.value) {
                    userField.focus();
                    userField.value = "$escapedUser";
                    userField.dispatchEvent(new Event('input', { bubbles: true }));
                    userField.dispatchEvent(new Event('change', { bubbles: true }));
                    userReady = true;
                }
                if (passField.value !== "$escapedPass") {
                    passField.focus();
                    passField.value = "$escapedPass";
                    passField.dispatchEvent(new Event('input', { bubbles: true }));
                    passField.dispatchEvent(new Event('change', { bubbles: true }));
                }
                if (userReady && passField.value === "$escapedPass") {
                    clickSubmit(passField, ['sign in', 'log in', 'submit']);
                    window.__utdSignInClicked = true;
                    return JSON.stringify({status: 'submitted_password', url: window.location.href});
                }
                return JSON.stringify({status: 'filling_password_step', url: window.location.href});
            }

            if (userField && !userField.value) {
                if (window.__utdContinueClicked) {
                    return JSON.stringify({status: 'waiting_username_result', url: window.location.href});
                }
                userField.focus();
                userField.value = "$escapedUser";
                userField.dispatchEvent(new Event('input', { bubbles: true }));
                userField.dispatchEvent(new Event('change', { bubbles: true }));
                clickSubmit(userField, ['continue', 'next']);
                window.__utdContinueClicked = true;
                return JSON.stringify({status: 'submitted_username', url: window.location.href});
            }

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

data class InspectResult(val status: String, val url: String?, val errorHit: String?)

fun interpretInspectResult(raw: String?): InspectResult {
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

fun outcomeFromFinal(result: InspectResult): LoginAttemptOutcome {
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

fun statusLabelFor(status: String): String = when (status) {
    "submitted_username" -> "Username submitted, moving to the password step..."
    "submitted_password" -> "Password submitted, checking result..."
    "waiting_password_result" -> "Password submitted, waiting for the page to respond..."
    "waiting_username_result" -> "Username submitted, waiting for the page to respond..."
    "filling_password_step" -> "Filling in the password step..."
    "dismissed_popup" -> "Dismissed a profile-completion popup, continuing..."
    "accepted_cookies" -> "Accepted the cookie notice, continuing..."
    else -> "Working through login steps..."
}

fun startLoginAutomation(
    view: WebView,
    username: String,
    password: String,
    isResolved: () -> Boolean,
    onStatus: (String) -> Unit,
    onResolved: (LoginAttemptOutcome) -> Unit
) {
    val finalConfirmationsNeeded = 2
    var consecutiveFinal = 0

    fun poll(delayMs: Long) {
        view.postDelayed({
            if (isResolved()) return@postDelayed
            view.evaluateJavascript(inspectAndActScript(username, password)) { raw ->
                if (isResolved()) return@evaluateJavascript
                val result = interpretInspectResult(raw)
                if (result.status == "final") {
                    consecutiveFinal++
                    if (consecutiveFinal >= finalConfirmationsNeeded) {
                        onResolved(outcomeFromFinal(result))
                    } else {
                        poll(INSPECT_INTERVAL_MS)
                    }
                } else {
                    consecutiveFinal = 0
                    onStatus(statusLabelFor(result.status))
                    val nextDelay = if (result.status == "submitted_username" || result.status == "submitted_password") {
                        SUBMIT_SETTLE_MS
                    } else {
                        INSPECT_INTERVAL_MS
                    }
                    poll(nextDelay)
                }
            }
        }, delayMs)
    }

    poll(INSPECT_INTERVAL_MS)
}
