package com.healthdataet.utdcredentials.ui.screens

import org.json.JSONObject

/**
 * Round 48i(+): shared automation core for BOTH the single quick-login
 * screen (UpToDateLoginScreen.kt) and the sequential batch runner
 * (SequentialLoginScreen.kt) -- originally each screen had its own
 * fill/submit logic (single: fill-only, manual submit; batch: fill+submit
 * once), until the user's own live test caught the real cause of every
 * batch attempt failing: uptodate.com's login is a TWO-STEP flow --
 * username + "Continue" first, THEN a separate page/step for password +
 * "Sign in" -- with an occasional "Please complete your profile" popup in
 * between (dismissed via "Ask Again Tomorrow", per the user's own
 * instruction). Once that was fixed for the batch runner, the same
 * automation was asked for on the single-credential screen too, so this
 * file exists to hold the one true version instead of two copies drifting
 * apart.
 */

const val UPTODATE_LOGIN_URL = "https://www.uptodate.com/login"

// How long to let ONE login attempt run before giving up and reporting it
// as a timeout -- a CAPTCHA, an unexpected verification step, or a slow
// connection all look the same from here (no clear success/failure
// signal), so a hard ceiling keeps a stuck attempt from hanging forever
// (and, in the batch runner, from blocking every credential behind it).
// The real login needs room for two round trips (username step, then
// password step) plus the occasional profile popup, hence 35s rather than
// the original single-step assumption's 25s.
const val ATTEMPT_TIMEOUT_MS = 35_000L

// How often the inspect-and-act script re-checks the page. Deliberately a
// tight poll rather than a single one-shot check after submit:
// uptodate.com moving from the username step to the password step does
// not necessarily fire a fresh WebViewClient.onPageFinished (it can render
// the password field in via its own JS without a full navigation), so this
// keeps looking on a timer instead of waiting for a page-load event that
// might never come a second time.
const val INSPECT_INTERVAL_MS = 1200L

/**
 * Called repeatedly (see INSPECT_INTERVAL_MS) and, each time, looks at
 * whatever is ACTUALLY on screen right now and does the one next right
 * thing:
 *   1. If uptodate.com's "Please complete your profile" nag is showing,
 *      dismiss it via "Ask Again Tomorrow" so it never blocks the real
 *      flow.
 *   2. Else if a password field is visible, fill it in and click Sign In
 *      (the password step).
 *   3. Else if a not-yet-filled username field is visible, fill it in and
 *      click Continue (the username step).
 *   4. Else (no recognized field left to act on) -- this is treated as the
 *      destination page: scan its visible text for an obvious error
 *      keyword and report back what URL we ended up on.
 * The caller only needs to act on step 4's answer; steps 1-3 just mean
 * "keep polling, something changed". This handles the flow whether
 * uptodate.com does a real page navigation between steps or just swaps
 * the form in with JS, without needing to guess which. */
fun inspectAndActScript(username: String, password: String): String {
    val escapedUser = username.replace("\\", "\\\\").replace("\"", "\\\"")
    val escapedPass = password.replace("\\", "\\\\").replace("\"", "\\\"")
    return """
        (function() {
            function visible(el) {
                if (!el) return false;
                var rect = el.getBoundingClientRect();
                return !!(rect.width || rect.height) && el.offsetParent !== null;
            }
            function clickSubmitNear(el) {
                var btn = document.querySelector('button[type="submit"], input[type="submit"]');
                if (btn && visible(btn)) { btn.click(); return; }
                var form = el ? el.form : null;
                if (form) {
                    if (typeof form.requestSubmit === 'function') { form.requestSubmit(); }
                    else { form.submit(); }
                }
            }

            // Step: the "Please complete your profile" popup some
            // credentials show mid-flow -- just dismiss it and keep going.
            var allClickable = document.querySelectorAll('button, a, input[type="button"]');
            for (var k = 0; k < allClickable.length; k++) {
                var t = (allClickable[k].innerText || allClickable[k].value || '').trim();
                if (t.indexOf('Ask Again Tomorrow') !== -1 && visible(allClickable[k])) {
                    allClickable[k].click();
                    return JSON.stringify({status: 'dismissed_popup', url: window.location.href});
                }
            }

            // Step: password field visible -> this is the second step of
            // the real flow -- fill it and click Sign In.
            var passField = document.querySelector('input[type="password"]');
            if (passField && visible(passField)) {
                passField.focus();
                passField.value = "$escapedPass";
                passField.dispatchEvent(new Event('input', { bubbles: true }));
                passField.dispatchEvent(new Event('change', { bubbles: true }));
                clickSubmitNear(passField);
                return JSON.stringify({status: 'submitted_password', url: window.location.href});
            }

            // Step: username field visible and not yet filled -> this is
            // the first step -- fill it and click Continue.
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
            if (userField && !userField.value) {
                userField.focus();
                userField.value = "$escapedUser";
                userField.dispatchEvent(new Event('input', { bubbles: true }));
                userField.dispatchEvent(new Event('change', { bubbles: true }));
                clickSubmitNear(userField);
                return JSON.stringify({status: 'submitted_username', url: window.location.href});
            }

            // Neither step's field is present/actionable any more --
            // treat this as the destination page and look for an obvious
            // error message in whatever's currently visible.
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

/** evaluateJavascript's callback hands back the JSON-encoded form of
 * whatever the script returned -- since inspectAndActScript itself returns
 * a JSON.stringify'd string, the raw callback value is that string, quoted
 * and escaped a second time by evaluateJavascript's own contract. This
 * undoes that outer layer before parsing; if anything about the real page
 * doesn't match what was anticipated, this falls back to an "unknown"
 * status so the caller just keeps polling rather than guessing. */
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
    "dismissed_popup" -> "Dismissed a profile-completion popup, continuing..."
    else -> "Working through login steps..."
}
