package com.healthdataet.utdcredentials.ui.screens

import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val UPTODATE_LOGIN_URL = "https://www.uptodate.com/login"
const val ATTEMPT_TIMEOUT_MS = 55_000L
const val INSPECT_INTERVAL_MS = 1200L
const val SUBMIT_SETTLE_MS = 2500L
const val TAP_SETTLE_MS = 1800L
// How often to re-check while WATCHING for the keyboard-close reflow to
// settle (isViewportStable() in the script) -- fast enough to notice the
// instant it's actually done rather than trusting one blind fixed wait.
const val SETTLE_POLL_MS = 350L

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

            function centerOf(el) {
                var rect = el.getBoundingClientRect();
                return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
            }

            // Filling a field with .focus() opens the real on-screen
            // keyboard, which resizes/reflows the whole page -- reading a
            // button's position before that settles is what made earlier
            // taps land on stale coordinates. Rather than trusting one
            // fixed guess at how long that takes, this actually WATCHES
            // the viewport: it keeps polling (fast, every ~350ms) until
            // the visual viewport height hasn't changed for two polls in a
            // row (the keyboard animation is genuinely done), and gives up
            // waiting after 10 polls (~3.5s) so a page that never quite
            // settles can't stall the whole attempt forever.
            function isViewportStable() {
                var h = window.visualViewport ? window.visualViewport.height : window.innerHeight;
                window.__utdSettlePolls = (window.__utdSettlePolls || 0) + 1;
                if (window.__utdLastViewportH === h) {
                    window.__utdViewportStableCount = (window.__utdViewportStableCount || 0) + 1;
                } else {
                    window.__utdViewportStableCount = 0;
                    window.__utdLastViewportH = h;
                }
                if (window.__utdViewportStableCount >= 2) return true;
                if (window.__utdSettlePolls >= 10) return true;
                return false;
            }

            // A plain element.click() (and even a synthetic
            // pointerdown/mousedown/mouseup/click sequence dispatched from
            // JS) turned out to do nothing on this page's real Sign
            // In/Continue buttons -- their handler apparently only reacts
            // to a genuinely trusted touch, which JS alone can never
            // produce. So instead of clicking here, this script just finds
            // the right button and reports its on-screen coordinates; the
            // Android side then dispatches an actual native touch tap at
            // that exact spot, indistinguishable from a real finger tap.

            // Priority 1: the "Your Privacy" cookie-consent modal.
            var acceptCookies = findButtonByText(['accept all cookies', 'accept all', 'accept cookies']);
            if (acceptCookies) {
                var c1 = centerOf(acceptCookies);
                return JSON.stringify({status: 'need_tap_cookies', url: window.location.href, x: c1.x, y: c1.y});
            }

            // Priority 2: the "Please complete your profile" popup.
            var askLater = findButtonByText(['ask again tomorrow']);
            if (askLater) {
                var c2 = centerOf(askLater);
                return JSON.stringify({status: 'need_tap_popup', url: window.location.href, x: c2.x, y: c2.y});
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
                // alongside it on the same page.
                var maxSignInAttempts = 2;
                var signInAttempts = window.__utdSignInAttempts || 0;
                if (window.__utdSignInTapRequested) {
                    // A tap was already sent. Verify it actually had an
                    // effect before deciding what to do next: if Sign In
                    // is no longer findable, the page is most likely
                    // already navigating -- just wait for the result. If
                    // it's STILL sitting right there, the previous tap
                    // most likely never registered -- retry, up to
                    // maxSignInAttempts total, rather than silently waiting
                    // out the whole attempt timeout on a tap that never
                    // landed.
                    var stillThere = findButtonByText(['sign in', 'log in', 'submit']);
                    if (!stillThere || signInAttempts >= maxSignInAttempts) {
                        return JSON.stringify({status: 'waiting_password_result', url: window.location.href});
                    }
                    if (!isViewportStable()) {
                        return JSON.stringify({status: 'settling_before_signin', url: window.location.href});
                    }
                    window.__utdSignInAttempts = signInAttempts + 1;
                    var retryC = centerOf(stillThere);
                    return JSON.stringify({status: 'need_tap_signin', url: window.location.href, x: retryC.x, y: retryC.y});
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
                    if (!window.__utdPasswordBlurred) {
                        window.__utdPasswordBlurred = true;
                        if (document.activeElement && document.activeElement.blur) {
                            document.activeElement.blur();
                        }
                    }
                    if (!isViewportStable()) {
                        return JSON.stringify({status: 'settling_before_signin', url: window.location.href});
                    }
                    var signInBtn = findButtonByText(['sign in', 'log in', 'submit']);
                    if (signInBtn) {
                        window.__utdSignInTapRequested = true;
                        window.__utdSignInAttempts = 1;
                        var c3 = centerOf(signInBtn);
                        return JSON.stringify({status: 'need_tap_signin', url: window.location.href, x: c3.x, y: c3.y});
                    }
                    // Button not found yet this poll (page may still be
                    // settling) -- don't latch, try again next poll.
                    return JSON.stringify({status: 'filling_password_step', url: window.location.href});
                }
                return JSON.stringify({status: 'filling_password_step', url: window.location.href});
            }

            if (userField && !userField.value) {
                if (window.__utdContinueTapRequested) {
                    return JSON.stringify({status: 'waiting_username_result', url: window.location.href});
                }
                userField.focus();
                userField.value = "$escapedUser";
                userField.dispatchEvent(new Event('input', { bubbles: true }));
                userField.dispatchEvent(new Event('change', { bubbles: true }));
                return JSON.stringify({status: 'settling_before_continue', url: window.location.href});
            }

            if (userField && userField.value === "$escapedUser") {
                var maxContinueAttempts = 2;
                var continueAttempts = window.__utdContinueAttempts || 0;
                if (window.__utdContinueTapRequested) {
                    // Same verify-then-retry pattern as Sign In above: if
                    // Continue is no longer findable, the page is likely
                    // already moving on -- wait. If it's still right
                    // there, the previous tap probably missed -- retry, up
                    // to maxContinueAttempts.
                    var continueStillThere = findButtonByText(['continue', 'next']);
                    if (!continueStillThere || continueAttempts >= maxContinueAttempts) {
                        return JSON.stringify({status: 'waiting_username_result', url: window.location.href});
                    }
                    if (!isViewportStable()) {
                        return JSON.stringify({status: 'settling_before_continue', url: window.location.href});
                    }
                    window.__utdContinueAttempts = continueAttempts + 1;
                    var retryC4 = centerOf(continueStillThere);
                    return JSON.stringify({status: 'need_tap_continue', url: window.location.href, x: retryC4.x, y: retryC4.y});
                }
                // First time reaching this step: blur whatever's focused
                // (closes the keyboard the .focus() fill just opened) and
                // wait for the resulting reflow to genuinely settle before
                // trusting Continue's on-screen position.
                if (!window.__utdUsernameBlurred) {
                    window.__utdUsernameBlurred = true;
                    if (document.activeElement && document.activeElement.blur) {
                        document.activeElement.blur();
                    }
                }
                if (!isViewportStable()) {
                    return JSON.stringify({status: 'settling_before_continue', url: window.location.href});
                }
                var continueBtn = findButtonByText(['continue', 'next']);
                if (continueBtn) {
                    window.__utdContinueTapRequested = true;
                    window.__utdContinueAttempts = 1;
                    var c4 = centerOf(continueBtn);
                    return JSON.stringify({status: 'need_tap_continue', url: window.location.href, x: c4.x, y: c4.y});
                }
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

data class InspectResult(
    val status: String,
    val url: String?,
    val errorHit: String?,
    val tapX: Double?,
    val tapY: Double?
)

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
        errorHit = json?.optString("errorHit")?.takeIf { it.isNotBlank() && it != "null" },
        tapX = json?.let { if (it.has("x")) it.optDouble("x") else null }?.takeIf { !it.isNaN() },
        tapY = json?.let { if (it.has("y")) it.optDouble("y") else null }?.takeIf { !it.isNaN() }
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
    "need_tap_continue" -> "Tapping Continue..."
    "need_tap_signin" -> "Tapping Sign In..."
    "need_tap_cookies" -> "Accepting the cookie notice..."
    "need_tap_popup" -> "Dismissing a profile-completion popup..."
    "waiting_password_result" -> "Password submitted, waiting for the page to respond..."
    "waiting_username_result" -> "Username submitted, waiting for the page to respond..."
    "filling_password_step" -> "Filling in the password step..."
    "settling_before_continue" -> "Closing the keyboard before tapping Continue..."
    "settling_before_signin" -> "Closing the keyboard before tapping Sign In..."
    else -> "Working through login steps..."
}

/**
 * Dispatches a real, OS-level touch tap (ACTION_DOWN then ACTION_UP) at the
 * given page coordinates (CSS px, from getBoundingClientRect) on the given
 * WebView. Unlike anything a JS-injected script can produce, this event is
 * indistinguishable from an actual finger tap -- necessary because this
 * page's real buttons ignore a synthetic .click() and even a full JS
 * pointerdown/mousedown/mouseup/click event sequence. WebView.getScale()
 * converts CSS px into the WebView's own local view-pixel coordinate space
 * (accounting for the page's current zoom level).
 */
fun nativeTap(view: WebView, cssX: Double, cssY: Double) {
    val scale = if (view.scale > 0f) view.scale else 1f
    val x = (cssX * scale).toFloat()
    val y = (cssY * scale).toFloat()
    val downTime = SystemClock.uptimeMillis()
    val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    view.dispatchTouchEvent(downEvent)
    downEvent.recycle()
    val upTime = SystemClock.uptimeMillis()
    val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
    view.dispatchTouchEvent(upEvent)
    upEvent.recycle()
}

/**
 * Round 48k: fire-and-forget reports one attempt's outcome back to the
 * panel (ApiClient.reportLoginAttempt) so the Credentials Hub's "Sign-In
 * Test" column can show when this credential was last tried and what
 * happened. Shared by both UpToDateLoginScreen and SequentialLoginScreen
 * so the outcome-to-status/reason mapping can never drift between them.
 * [sourceId] is the exact (source, id) pair the credential was handed with
 * from /api/v1/credentials/list -- null skips reporting entirely (nothing
 * to key the result against). Never surfaces a failure back to the caller:
 * a network hiccup here must never affect the login flow itself, only the
 * Hub's visibility into it.
 */
fun reportLoginOutcome(
    scope: CoroutineScope,
    session: SessionManager,
    sourceId: Pair<String, Long>?,
    outcome: LoginAttemptOutcome
) {
    val token = session.apiToken ?: return
    val (source, id) = sourceId ?: return
    val (status, reason) = when (outcome) {
        is LoginAttemptOutcome.Success -> "success" to null
        is LoginAttemptOutcome.Failed -> "failed" to outcome.reason
        is LoginAttemptOutcome.TimedOut -> "timeout" to "No clear result within the attempt timeout"
        is LoginAttemptOutcome.Skipped -> "skipped" to null
    }
    scope.launch {
        withContext(Dispatchers.IO) {
            try {
                ApiClient(session.baseUrl).reportLoginAttempt(token, source, id, status, reason)
            } catch (e: Exception) {
                // Best-effort telemetry only -- never let this affect the
                // login flow itself.
            }
        }
    }
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
    val tapStatuses = setOf("need_tap_cookies", "need_tap_popup", "need_tap_continue", "need_tap_signin")

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
                    if (result.status in tapStatuses && result.tapX != null && result.tapY != null) {
                        nativeTap(view, result.tapX, result.tapY)
                        val nextDelay = if (result.status == "need_tap_continue" || result.status == "need_tap_signin") {
                            SUBMIT_SETTLE_MS
                        } else {
                            TAP_SETTLE_MS
                        }
                        poll(nextDelay)
                    } else {
                        val nextDelay = when (result.status) {
                            "submitted_username" -> SUBMIT_SETTLE_MS
                            "settling_before_continue", "settling_before_signin" -> SETTLE_POLL_MS
                            else -> INSPECT_INTERVAL_MS
                        }
                        poll(nextDelay)
                    }
                }
            }
        }, delayMs)
    }

    poll(INSPECT_INTERVAL_MS)
}
