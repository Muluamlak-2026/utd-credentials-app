package com.healthdataet.utdcredentials.ui.screens

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.LoginHistoryStore
import com.healthdataet.utdcredentials.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val UPTODATE_LOGIN_URL = "https://www.uptodate.com/login"
// Round 48p: bumped 55s -> 75s -- on a weak/slow connection the page itself
// (not just this app's own tap/click retries) can simply take longer to
// load and settle between steps, and the old 55s ceiling could time out an
// attempt that was still genuinely in progress, reporting it as a false
// "timed out" failure. This only affects how long a stuck attempt is
// allowed to keep trying before giving up -- it does not slow down any
// attempt that finishes normally.
const val ATTEMPT_TIMEOUT_MS = 75_000L
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
                return JSON.stringify({status: 'need_tap_cookies', url: window.location.href, x: c1.x, y: c1.y, vw: window.innerWidth, vh: window.innerHeight});
            }

            // Priority 2: the "Please complete your profile" popup.
            var askLater = findButtonByText(['ask again tomorrow']);
            if (askLater) {
                var c2 = centerOf(askLater);
                return JSON.stringify({status: 'need_tap_popup', url: window.location.href, x: c2.x, y: c2.y, vw: window.innerWidth, vh: window.innerHeight});
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
                var maxSignInAttempts = 3;
                var signInAttempts = window.__utdSignInAttempts || 0;
                if (window.__utdSignInTapRequested) {
                    // A tap was already sent. Verify it actually had an
                    // effect before deciding what to do next: if Sign In
                    // is no longer findable, the page is most likely
                    // already navigating -- just wait for the result. If
                    // it's STILL sitting right there, the previous attempt
                    // most likely never registered -- retry, up to
                    // maxSignInAttempts total, rather than silently waiting
                    // out the whole attempt timeout on a tap that never
                    // landed.
                    //
                    // Round 48n: the retries no longer just repeat the same
                    // coordinate tap -- attempt 2 switches to a completely
                    // different, coordinate-free mechanism (focus the
                    // button, then Android sends a real ENTER key event),
                    // since a repeated identical tap is unlikely to succeed
                    // where the first one already failed. Attempt 3 falls
                    // back to a tap again, offset a few px from dead-center
                    // in case an overlapping element (a hover/focus ring,
                    // a sticky header) was intercepting the exact center.
                    var stillThere = findButtonByText(['sign in', 'log in', 'submit']);
                    if (!stillThere || signInAttempts >= maxSignInAttempts) {
                        return JSON.stringify({status: 'waiting_password_result', url: window.location.href});
                    }
                    if (!isViewportStable()) {
                        return JSON.stringify({status: 'settling_before_signin', url: window.location.href});
                    }
                    window.__utdSignInAttempts = signInAttempts + 1;
                    if (signInAttempts === 1) {
                        stillThere.focus();
                        return JSON.stringify({status: 'need_key_signin', url: window.location.href});
                    }
                    var retryC = centerOf(stillThere);
                    return JSON.stringify({status: 'need_tap_signin', url: window.location.href, x: retryC.x + 6, y: retryC.y + 6, vw: window.innerWidth, vh: window.innerHeight});
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
                        return JSON.stringify({status: 'need_tap_signin', url: window.location.href, x: c3.x, y: c3.y, vw: window.innerWidth, vh: window.innerHeight});
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
                var maxContinueAttempts = 3;
                var continueAttempts = window.__utdContinueAttempts || 0;
                if (window.__utdContinueTapRequested) {
                    // Same verify-then-retry pattern as Sign In above: if
                    // Continue is no longer findable, the page is likely
                    // already moving on -- wait. If it's still right
                    // there, the previous attempt probably missed -- retry,
                    // switching mechanism on attempt 2 (focus + native
                    // ENTER key instead of another coordinate tap), up to
                    // maxContinueAttempts.
                    var continueStillThere = findButtonByText(['continue', 'next']);
                    if (!continueStillThere || continueAttempts >= maxContinueAttempts) {
                        return JSON.stringify({status: 'waiting_username_result', url: window.location.href});
                    }
                    if (!isViewportStable()) {
                        return JSON.stringify({status: 'settling_before_continue', url: window.location.href});
                    }
                    window.__utdContinueAttempts = continueAttempts + 1;
                    if (continueAttempts === 1) {
                        continueStillThere.focus();
                        return JSON.stringify({status: 'need_key_continue', url: window.location.href});
                    }
                    var retryC4 = centerOf(continueStillThere);
                    return JSON.stringify({status: 'need_tap_continue', url: window.location.href, x: retryC4.x + 6, y: retryC4.y + 6, vw: window.innerWidth, vh: window.innerHeight});
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
                    return JSON.stringify({status: 'need_tap_continue', url: window.location.href, x: c4.x, y: c4.y, vw: window.innerWidth, vh: window.innerHeight});
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
    val tapY: Double?,
    val viewportWidth: Double?,
    val viewportHeight: Double?
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
        tapY = json?.let { if (it.has("y")) it.optDouble("y") else null }?.takeIf { !it.isNaN() },
        viewportWidth = json?.let { if (it.has("vw")) it.optDouble("vw") else null }?.takeIf { it > 0 && !it.isNaN() },
        viewportHeight = json?.let { if (it.has("vh")) it.optDouble("vh") else null }?.takeIf { it > 0 && !it.isNaN() }
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
    "need_key_continue" -> "Continue didn't respond to a tap -- trying a keyboard Enter instead..."
    "need_key_signin" -> "Sign In didn't respond to a tap -- trying a keyboard Enter instead..."
    else -> "Working through login steps..."
}

/**
 * Dispatches a real, OS-level touch tap (ACTION_DOWN, then a same-spot
 * ACTION_MOVE, then ACTION_UP after a short real hold) at the given page
 * coordinates (CSS px, from getBoundingClientRect) on the given WebView.
 * Unlike anything a JS-injected script can produce, this event is meant to
 * be indistinguishable from an actual finger tap -- necessary because this
 * page's real buttons ignore a synthetic .click() and even a full JS
 * pointerdown/mousedown/mouseup/click event sequence. WebView.getScale()
 * converts CSS px into the WebView's own local view-pixel coordinate space
 * (accounting for the page's current zoom level).
 *
 * Round 48l: earlier rounds dispatched ACTION_DOWN immediately followed by
 * ACTION_UP at the exact same instant (both timestamped "now") -- a real
 * finger tap always has a brief hold (tens of milliseconds) and virtually
 * always a tiny bit of finger movement in between, which is what many
 * touch/click handlers (including, apparently, this page's) key off of
 * rather than the coordinates alone. This version holds the touch down for
 * ~70ms and inserts an ACTION_MOVE of 1px before lifting, so the event
 * sequence looks like a real tap rather than an instantaneous synthetic
 * one. No Android permission is involved anywhere in this: dispatchTouchEvent
 * is a plain View API the app calls on its OWN WebView instance in its own
 * process -- functionally identical to how the WebView already receives
 * every real finger tap the admin makes on screen elsewhere in the app, not
 * a system-wide input-injection capability, so nothing extra needed to be
 * (or could have been) requested at install time.
 */
fun nativeTap(view: WebView, cssX: Double, cssY: Double, viewportWidth: Double?, viewportHeight: Double?) {
    // getScale() is NOT a CSS-pixel -> physical-pixel conversion. On many
    // Android WebViews it is already reflected in how Chromium lays out the
    // CSS viewport. Multiplying rect coordinates by view.scale therefore
    // double-scales them (the observed tap lands at the far-right edge).
    // Map the DOM viewport proportionally into the actual WebView view.
    val vw = viewportWidth?.takeIf { it > 0.0 } ?: view.width.toDouble()
    val vh = viewportHeight?.takeIf { it > 0.0 } ?: view.height.toDouble()
    val x = (cssX * view.width / vw).toFloat().coerceIn(0f, view.width.toFloat() - 1f)
    val y = (cssY * view.height / vh).toFloat().coerceIn(0f, view.height.toFloat() - 1f)
    val downTime = SystemClock.uptimeMillis()

    val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    downEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
    view.dispatchTouchEvent(downEvent)
    downEvent.recycle()

    view.postDelayed({
        val moveTime = SystemClock.uptimeMillis()
        val moveEvent = MotionEvent.obtain(downTime, moveTime, MotionEvent.ACTION_MOVE, x + 1f, y + 1f, 0)
        moveEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(moveEvent)
        moveEvent.recycle()

        view.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val upEvent = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x + 1f, y + 1f, 0)
            upEvent.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            view.dispatchTouchEvent(upEvent)
            upEvent.recycle()
        }, 40L)
    }, 30L)
}

/**
 * Round 49: tries a real, system-injected touch (via the Accessibility
 * Service's dispatchGesture -- see UtdClickAccessibilityService.tapAtScreenPoint's
 * own doc comment for why this is a meaningfully different mechanism from
 * both the ACTION_CLICK node-click and nativeTap above) at the exact same
 * [cssX]/[cssY] page coordinates the login page's JS already computed.
 * dispatchGesture works in absolute SCREEN coordinates, not CSS/view-local
 * ones, so this adds the WebView's own on-screen position (getLocationOnScreen)
 * to the same scale-adjusted offset nativeTap already uses. Returns false
 * (never throws) whenever the service isn't active or the OS didn't accept
 * the gesture for dispatch -- the caller falls back to nativeTap in that
 * case exactly as before this round, so nothing regresses when the admin
 * hasn't turned the Accessibility Service on.
 */
fun accessibilityTap(
    view: WebView,
    cssX: Double,
    cssY: Double,
    viewportWidth: Double?,
    viewportHeight: Double?
): Boolean {
    if (!com.healthdataet.utdcredentials.accessibility.UtdClickAccessibilityService.isActive()) return false
    return try {
        val vw = viewportWidth?.takeIf { it > 0.0 } ?: view.width.toDouble()
        val vh = viewportHeight?.takeIf { it > 0.0 } ?: view.height.toDouble()
        val localX = (cssX * view.width / vw).toFloat().coerceIn(0f, view.width.toFloat() - 1f)
        val localY = (cssY * view.height / vh).toFloat().coerceIn(0f, view.height.toFloat() - 1f)
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        com.healthdataet.utdcredentials.accessibility.UtdClickAccessibilityService.tapAtScreenPoint(
            loc[0] + localX, loc[1] + localY
        )
    } catch (e: Exception) {
        false
    }
}

/**
 * Round 48n: the second Sign In/Continue retry attempt (see
 * inspectAndActScript's verify-then-retry blocks) uses this instead of
 * another coordinate tap -- a completely different, coordinate-free
 * mechanism for the exact same problem (this page's Sign In/Continue not
 * reacting to anything JS alone can produce). The JS side first calls
 * `.focus()` on the button so it's the actual DOM-focused element, then
 * Android dispatches a real hardware-style ENTER key event (ACTION_DOWN +
 * ACTION_UP for KEYCODE_ENTER) straight at the WebView. Browsers (including
 * WebView's underlying engine) treat Enter/Space on a focused button as a
 * genuine, trusted activation -- generated by the engine itself, not by
 * JS -- exactly like a real keyboard would, so this sidesteps the whole
 * on-screen-coordinate/scale question entirely: it doesn't matter where the
 * button visually is, only that it's focused.
 */
fun nativeEnterKeyPress(view: WebView) {
    val eventTime = SystemClock.uptimeMillis()
    val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)
    view.dispatchKeyEvent(downEvent)
    val upEvent = KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0)
    view.dispatchKeyEvent(upEvent)
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
 *
 * Round 48l: also appends to the on-device LoginHistoryStore (a purely
 * local log, separate from the website's per-credential Sign-In Test
 * column) so the new in-app History screen has something to show --
 * ucCode/username let the History list read out identities without another
 * network round-trip. [context] can be omitted by call sites that don't
 * have one handy, in which case only the server-side report happens.
 */
fun reportLoginOutcome(
    scope: CoroutineScope,
    session: SessionManager,
    sourceId: Pair<String, Long>?,
    outcome: LoginAttemptOutcome,
    context: Context? = null,
    ucCode: String? = null,
    username: String? = null
) {
    val (status, reason) = when (outcome) {
        is LoginAttemptOutcome.Success -> "success" to null
        is LoginAttemptOutcome.Failed -> "failed" to outcome.reason
        is LoginAttemptOutcome.TimedOut -> "timeout" to "No clear result within the attempt timeout"
        is LoginAttemptOutcome.Skipped -> "skipped" to null
    }

    if (context != null && sourceId != null) {
        val (source, id) = sourceId
        try {
            LoginHistoryStore.record(context, source, id, ucCode, username, status, reason)
        } catch (e: Exception) {
            // Local logging only -- never let this affect the login flow.
        }
    }

    val token = session.apiToken ?: return
    val (source, id) = sourceId ?: return
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
    // Round 48n: the coordinate-free fallback -- see nativeEnterKeyPress's
    // own doc comment for why a focus+ENTER key event is a genuinely
    // different mechanism from a tap, not just a repeat of the same one.
    val keyStatuses = setOf("need_key_continue", "need_key_signin")

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
                    // Round 48p: for Sign In/Continue specifically (never
                    // cookies/popup, which already work fine on every
                    // attempt) -- if the admin has enabled the
                    // Accessibility Service fallback, try a real
                    // ACTION_CLICK on the button's own accessibility node
                    // FIRST, on every single dispatch (first attempt and
                    // every retry), before falling back to whatever the JS
                    // status already asked for (a coordinate tap or a key
                    // press). See UtdClickAccessibilityService's doc
                    // comment for why this is the most reliable mechanism
                    // when it's available, and why it costs nothing to try
                    // first when it isn't (isActive() is a cheap null
                    // check, so this is a no-op unless the admin actually
                    // turned the service on).
                    val signInLike = result.status == "need_tap_signin" || result.status == "need_key_signin"
                    val continueLike = result.status == "need_tap_continue" || result.status == "need_key_continue"
                    // Round 50: coordinate tap is the primary mechanism for
                    // Sign In / Continue. The accessibility node-click path can
                    // report success even when the WebView page does not actually
                    // submit, which leaves the login page stuck until timeout.
                    // The coordinate mapping now uses the DOM viewport dimensions,
                    // so it must be allowed to run first.
                    val accessibilityHandled = false

                    // Round 49: appended to every status line so a screenshot
                    // during a stuck/timed-out attempt actually reveals which
                    // click mechanism was tried, instead of every attempt
                    // showing the same generic "Password submitted, waiting
                    // for the page to respond..." regardless of what was
                    // really attempted underneath.
                    var mechanismTag = ""

                    if (accessibilityHandled) {
                        mechanismTag = " [accessibility: node click]"
                        onStatus(statusLabelFor(result.status) + mechanismTag)
                        poll(SUBMIT_SETTLE_MS)
                    } else if (result.status in tapStatuses && result.tapX != null && result.tapY != null) {
                        // Round 49: try a real system-injected touch through
                        // the Accessibility Service FIRST (see
                        // accessibilityTap's doc comment) -- only falls back
                        // to the in-process nativeTap when the service isn't
                        // active or the OS didn't accept the gesture.
                        val didAccessibilityTap = accessibilityTap(view, result.tapX, result.tapY, result.viewportWidth, result.viewportHeight)
                        mechanismTag = if (didAccessibilityTap) " [accessibility: gesture tap]" else " [in-app tap]"
                        if (!didAccessibilityTap) {
                            nativeTap(view, result.tapX, result.tapY, result.viewportWidth, result.viewportHeight)
                        }
                        onStatus(statusLabelFor(result.status) + mechanismTag)
                        val nextDelay = if (result.status == "need_tap_continue" || result.status == "need_tap_signin") {
                            SUBMIT_SETTLE_MS
                        } else {
                            TAP_SETTLE_MS
                        }
                        poll(nextDelay)
                    } else if (result.status in keyStatuses) {
                        nativeEnterKeyPress(view)
                        onStatus(statusLabelFor(result.status) + " [in-app key press]")
                        poll(SUBMIT_SETTLE_MS)
                    } else {
                        onStatus(statusLabelFor(result.status))
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
