plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    // Round 57: Room's compiler (generates OfflineDatabase's DAO
    // implementations at build time).
    id("com.google.devtools.ksp")
}

// Round 48e: a real, on-device way to prove which commit is actually
// installed. Every round from 43 through 48-final shipped real code
// changes without ever bumping versionCode/versionName (still "8"/"1.4.2"
// since Round 42) -- meaning Settings -> Apps -> UTD Credentials -> version
// could never distinguish an old install from a new one, and a debug-
// keystore install replaces the app silently with no "Update" prompt on
// this device either. Combined, there was no way to confirm from the phone
// alone whether a just-installed APK actually contains the intended
// commit. gitShaForBuild() below reads the real commit Gradle is building
// from (works both in Termux's local clone and in the GitHub Actions
// runner's checkout) and bakes it into BuildConfig, shown on-screen by
// AppSettingsScreen -- see that file for where.
fun gitShaForBuild(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.ifEmpty { "unknown" }
} catch (e: Exception) {
    "unknown"
}

android {
    namespace = "com.healthdataet.utdcredentials"
    compileSdk = 34

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.healthdataet.utdcredentials"
        minSdk = 26
        targetSdk = 34
        // Round 33 (per-category notification on/off + App Settings/appearance)
        // shipped without a version bump -- bumping here so "Update" in
        // Android's installer (and Settings -> Apps -> UTD Credentials ->
        // version) actually reflects that this is a newer build than the
        // very first install, instead of every round looking like "1.0"
        // forever regardless of how many updates have actually shipped.
        //
        // Round 35: bumped again even though no Kotlin source changed. This
        // app's admin panel screen (FullSiteScreen.kt) is a WebView onto the
        // live site -- the new Dashboard "Needs Approval" list and the
        // click-to-copy/profile-link consistency fixes from Round 35 show up
        // automatically the next time the WebView loads, with zero app code
        // changes needed. The bump exists only so "Update" on the phone
        // still means something each round.
        //
        // Round 37: same story again. Verified phone-number changes, the
        // Settings -> Account Self-Service toggles, and the Mini App's
        // animated reveal popups are all either admin-panel (WebView) or
        // Telegram Mini App / bot-chat surfaces -- none of them touch this
        // app's native code, which only ever talks to /api/v1/login,
        // /api/v1/logout, /api/v1/device-token and
        // /api/v1/notifications/poll (see data/ApiClient.kt), none of which
        // changed. Bumped purely so "Update" still means something.
        //
        // 1.3.1 hotfix: this device's first-ever run of Round 32/33's
        // notification channel setup (NotificationChannels.ensureAll,
        // called unconditionally in MainActivity.onCreate before any UI
        // shows) crashed instantly on launch -- RingtoneManager.
        // getActualDefaultRingtoneUri() throws on some OEM builds (seen on
        // this user's device) when reading the system ringtone/alarm URI.
        // Fixed by wrapping that lookup (and the channel-creation loop, and
        // both onCreate call sites) in try/catch so a missing/unreadable
        // default sound degrades to a silent channel instead of crashing
        // the whole app before it can even show the login screen.
        //
        // 1.4.0: the 1.3.1 guard alone wasn't enough to stop this device's
        // crash-on-launch, meaning something else uncaught is also at
        // fault -- rather than guess a third specific line, this release
        // adds a real global safety net: a custom Application
        // (UtdCredentialsApp) installs a Thread.setDefaultUncaughtExceptionHandler
        // (util/CrashHandler.kt) BEFORE any Activity even starts. From now
        // on, ANY uncaught exception anywhere in the app writes a full
        // stack trace to a local file and relaunches straight into
        // CrashReportActivity showing it -- selectable/copyable, right on
        // the phone -- instead of the OS's bare "keeps stopping" dialog.
        // Past crash logs stay readable afterward too, from the new
        // Diagnostics entry under App Settings. This turns any future
        // crash into something fixable from a single screenshot, with no
        // ADB/Wireless Debugging pairing needed ever again. Also adds a
        // friendly "Retry" page in the WebView (FullSiteScreen) instead of
        // a blank screen when the server can't be reached.
        // 1.4.1: v1.4.0's own new CrashHandler just did its job -- instead
        // of another instant close, this device showed a full readable
        // stack trace, and it named the exact real root cause:
        //   IllegalArgumentException: Can only use lower 16 bits for
        //   requestCode, thrown from FragmentActivity.checkForValidRequestCode
        //   when MainActivity's POST_NOTIFICATIONS permission request runs.
        // This is a known, documented AndroidX incompatibility: androidx.
        // biometric 1.1.0 (needed for the app-lock fingerprint feature)
        // transitively pulls in a very old androidx.fragment release whose
        // FragmentActivity rejects any request code with bits set above
        // 16 -- but the modern Activity Result API used for the
        // notification-permission prompt deliberately generates request
        // codes ABOVE that range so they can never collide with a
        // hand-picked legacy one. Old fragment + new activity result API on
        // the same FragmentActivity = guaranteed crash the very first time
        // that permission prompt fires, on every device, every time. Fixed
        // by pinning a current androidx.fragment-ktx explicitly below so
        // Gradle resolves the fixed version instead of biometric's old
        // transitive one (see dependencies block), plus a try/catch around
        // the call itself in MainActivity.kt as a second line of defense.
        // v1.4.2 (Round 42): theme selection now actually applies app-wide
        // live (was persisting correctly but never observable to Compose,
        // so only the Settings screen's own preview swatch ever visibly
        // changed -- see ui/theme/Theme.kt / data/AppearancePrefs.kt), and
        // the in-app bell badge now shares one real counter across FCM/poll/
        // WorkManager instead of three disconnected pieces of state (see
        // data/SessionManager.kt / push/NotificationChannels.kt).
        // Round 48e: bumped again purely so this build is distinguishable
        // on-device (Settings -> Apps -> UTD Credentials -> version, and
        // the new Build line on App Settings -- see AppSettingsScreen.kt)
        // from every prior round since 42 that never bumped this. Also
        // bakes in the actual git commit being built (see gitShaForBuild()
        // above) as the definitive answer to "is this really the new code."
        // Round 48f: compact auto-hiding header (FullSiteScreen.kt) --
        // bumped per the Round 48e lesson: every code change from now on
        // gets a bump, no exceptions, so the Build line is always trustworthy.
        // Round 48g: fixed the header drawing under the status bar
        // (clock/battery/signal) -- see FullSiteScreen.kt.
        // Round 48h: real, working poll-interval settings (foreground
        // seconds + background minutes) -- see PollIntervalPrefs.kt,
        // NotificationPollWorker.kt, FullSiteScreen.kt, SoundSettingsScreen.kt.
        // Round 48i: UpToDate quick-login -- a new panel to pick a stored
        // credential and auto-fill it into uptodate.com's real login page
        // (CredentialPickerScreen.kt, UpToDateLoginScreen.kt), plus a
        // sequential batch mode that logs into several in a row, clears the
        // session between each, and reports success/failure per credential
        // (SequentialLoginScreen.kt).
        // Round 48i hotfix: the sequential batch's first version assumed
        // uptodate.com's login was one combined username+password form --
        // it's actually two separate steps (username+Continue, THEN a
        // separate password+Sign In page), which is exactly why every
        // batch attempt was reporting "still on the login page" without
        // ever having a chance to fill the password. SequentialLoginScreen
        // now re-inspects the actual page on a timer and handles whichever
        // step is currently showing (including uptodate.com's occasional
        // "complete your profile" popup, dismissed automatically) instead
        // of assuming a single fill-then-submit pass.
        // Round 48i hotfix 2: the same two-step automation is now shared
        // with the single quick-login screen (LoginAutomation.kt) -- it no
        // longer stops after filling the username and waiting for the
        // admin to tap Continue/Sign In themselves; it fills AND submits
        // both steps unattended, the same as the batch runner does per
        // credential, while staying fully touchable so the admin can take
        // over by hand if something it can't handle (CAPTCHA/2FA) shows up.
        // Round 48i hotfix 3: found the real reason password entry never
        // happened even after hotfix 1/2 -- inspectAndActScript only ever
        // looked for a button with the literal type="submit" attribute to
        // click Continue/Sign In, but uptodate.com's real buttons don't
        // necessarily carry that attribute (many modern login pages wire
        // the click up with their own JS instead of a native form submit).
        // So Continue never actually got clicked, the page just sat there
        // unchanged, and the very next check saw "no field left to fill"
        // and wrongly reported a final failure almost immediately -- which
        // matches exactly what was seen ("fails early", password never
        // reached). Fixed by finding the real button by its VISIBLE TEXT
        // ("Continue" / "Sign In" / "Ask Again Tomorrow") instead of that
        // attribute, plus two safety nets: a longer settle delay after
        // clicking Continue/Sign In before the next check (the old check
        // could fire before the real page had even finished navigating),
        // and requiring two consecutive "nothing left to fill" readings
        // before trusting a final failure instead of one.
        // Round 48i hotfix 4: the hotfix-2 build failure's real cause,
        // confirmed from the actual GitHub Actions compiler log --
        // UpToDateLoginScreen.kt used "12.dp" / "2.dp" but never imported
        // androidx.compose.ui.unit.dp, so the Kotlin compiler rejected it
        // outright ("Unresolved reference 'dp'") before the app could even
        // be assembled. A one-line missing import, now added; every other
        // screen file already had it.
        // Round 48i hotfix 5: from live on-device testing of 1.4.11 --
        // (1) uptodate.com can show a "Your Privacy" cookie-consent modal
        // that was never handled at all, now dismissed via "Accept All
        // Cookies" as the very first priority check; (2) the password step
        // had no "already submitted" guard, so Sign In was re-clicked on
        // every ~1.2-2.5s poll while stuck on that page, causing repeated
        // reloads -- now only fills/clicks once, then just waits; (3) once
        // any password field exists in the DOM the username-fill branch is
        // now skipped entirely, fixing the empty username field getting
        // incorrectly filled after the password step was already reached;
        // (4) visible() now also checks computed visibility/display/opacity
        // instead of just layout box size, reducing decoy-field false hits.
        // Round 48i hotfix 6: hotfix 5's rule (3) above assumed a strict
        // two-step flow and was wrong -- live testing showed uptodate.com's
        // page can have BOTH username and password fields visible at once,
        // and that rule was blocking username from ever being filled
        // whenever a password field existed on the same page, causing an
        // instant false "login failed". Replaced with logic that looks at
        // whichever fields actually exist on each poll and fills whichever
        // ones are empty (works for one combined page or a real two-step
        // flow), and swapped the "already submitted" guard for a per-page
        // JS flag (window.__utdSignInClicked / __utdContinueClicked) so
        // Sign In/Continue is clicked exactly once per page no matter how
        // many polls run, instead of gating on field values alone.
        // Round 48i hotfix 7: fields now fill correctly (both username and
        // password), but Sign In never actually fires -- confirmed from
        // live testing (it waits the full timeout, then reports "still on
        // the login page"). Root cause: a plain element.click() doesn't
        // trigger this button's real handler (same class of problem as the
        // hotfix-3 Continue-button bug, deeper this time -- the framework
        // apparently listens for real pointer/mouse gestures, not the
        // .click() DOM method). Fixed by firing a full synthetic gesture
        // (pointerdown/mousedown/pointerup/mouseup/click) at the target
        // element instead of just calling .click(). Also: the "clicked
        // once" guard now only latches when a button was actually found
        // and clicked, so a poll where nothing was clickable yet retries
        // instead of waiting out the whole timeout in silence.
        // Round 48i hotfix 8: hotfix 7's synthetic pointer/mouse gesture
        // STILL didn't trigger Sign In (confirmed live: fields fill, then
        // 25s of "waiting for the page to respond", then a full-page
        // navigation/loading spinner appears with no final success). Real
        // root cause: this button's handler apparently requires a
        // genuinely trusted touch event -- something no amount of
        // JS-dispatched pointerdown/mousedown/mouseup/click can ever
        // produce, since JS-dispatched events are always untrusted.
        // Switched approach entirely: the script no longer tries to click
        // anything in JS at all. It finds the target button (Accept
        // Cookies / Ask Again Tomorrow / Continue / Sign In) and reports
        // its on-screen coordinates back to Kotlin, which then dispatches
        // a REAL native Android touch (MotionEvent ACTION_DOWN + ACTION_UP)
        // directly at the WebView via view.dispatchTouchEvent() -- this is
        // indistinguishable from an actual finger tap and can't be
        // filtered out the way a synthetic JS event can.
        // Round 48i hotfix 9: hotfix 8's native tap worked for Continue and
        // Accept-Cookies (confirmed live), but Sign In was still missed --
        // the screenshots showed the on-screen keyboard visibly open right
        // as each field was filled (from the script's own .focus() call).
        // Opening/closing that keyboard resizes and reflows the whole
        // page, so a button position read before that reflow settles can
        // be stale by the time the tap actually lands -- most likely why
        // Sign In (further down the page, more affected by the keyboard)
        // kept getting missed while Continue (higher up) mostly landed.
        // Fixed by blurring the just-filled field and waiting one extra
        // poll for the keyboard-close reflow to finish BEFORE reading the
        // button's position, for both Continue and Sign In.
        // ATTEMPT_TIMEOUT_MS bumped 40s -> 45s to give the extra settle
        // step room.
        // Round 48k: two changes in one build, per explicit user request
        // (keep hotfix 9's approach, make it more reliable, AND add
        // Sign-In Test tracking):
        // (1) Click reliability, still on the same native-tap approach
        // (preserved, not replaced): the fixed "wait ~1.8s and hope the
        // keyboard finished" guess is replaced with isViewportStable() --
        // an actual watch on window.visualViewport.height, polled every
        // ~350ms, that only proceeds once the height has been unchanged
        // for 2 consecutive checks (capped at ~3.5s so a page that never
        // quite settles can't stall forever). On top of that, both
        // Continue and Sign In now verify their own tap: if the same
        // button is still sitting there on the next poll, the previous tap
        // is treated as missed and retried (up to 2 attempts total) rather
        // than silently waiting out the whole attempt timeout on a tap
        // that never registered. ATTEMPT_TIMEOUT_MS bumped 45s -> 55s for
        // the added retry headroom.
        // (2) New: every automated sign-in attempt (single-credential
        // screen AND each step of the sequential batch) now reports its
        // outcome back to the panel via the new
        // /api/v1/credentials/report-login-attempt endpoint, so the
        // Credentials Hub table's new "Sign-In Test" column shows when a
        // credential was last tried and what happened -- success, failed/
        // timed-out with the reason, skipped, incomplete-credentials, or
        // untested/not-tried-yet. Fire-and-forget: any problem reporting
        // this never affects the login flow itself.
        // Round 48l: (1) native tap now holds down ~70ms with a 1px move
        // before lifting (was instant down+up at the same timestamp) --
        // real finger taps always have a brief hold + tiny movement, which
        // is apparently what uptodate.com's Sign In/Continue handlers key
        // off of; no extra Android permission is or was ever needed for
        // this (dispatchTouchEvent is a plain View API on the app's own
        // WebView, not a system-wide input capability). (2) UI chrome
        // (status banner, progress bar, sequential-run header/result list)
        // shrunk so the WebView gets more of the screen. (3) New on-device
        // Login History screen (History icon on the credential picker) --
        // ordered/success/fail tallies + per-credential expandable attempt
        // lists, purely local (LoginHistoryStore), separate from the
        // website's Sign-In Test column. (4) Credential picker now pages
        // through the pool 200-at-a-time ("Load next 200") instead of
        // always hard-capping at the first 200.
        // Round 48l hotfix: build 24 failed to compile -- History,
        // DeleteOutline, ExpandLess, ExpandMore all come from the
        // material-icons-EXTENDED artifact, which this project doesn't
        // depend on (only the small material-icons-core set, which is all
        // ArrowBack/Refresh/etc used elsewhere actually needed). Replaced
        // all four with plain text glyphs/labels instead of adding that
        // dependency.
        // Round 48n: (1a) removed the intro paragraph on the UpToDate Quick
        // Login picker -- admin-only screen, self-explanatory. (1b) search
        // field shrunk to a genuine single line (the long label used to
        // wrap 2-3 lines). (1c) replaced "Load next 200" with a real
        // page-size (100/200) + page-number picker -- selections now
        // persist across pages. (1d) each credential card shows its own
        // last Sign-In Test result, synced from the same data the web
        // Hub's column reads. (1e) footer/results kept clear of the
        // phone's gesture nav bar (navigationBarsPadding), buttons/text
        // resized down to fit comfortably above it. (2) Sign In/Continue's
        // SECOND retry attempt now uses a completely different mechanism
        // -- focus the button, then a real ENTER key event -- instead of
        // repeating the same coordinate tap a third time; a THIRD attempt
        // (if still stuck) taps again, offset a few px from dead-center.
        // Round 48p (final round, per explicit request to close this
        // project): (1) THE definitive Sign In/Continue click fix -- a new
        // UtdClickAccessibilityService walks the real accessibility node
        // tree Chromium exposes for the page (the same tree TalkBack reads)
        // and calls performAction(ACTION_CLICK) directly on the button's own
        // node, sidestepping coordinates and synthetic-event mechanics
        // entirely; tried FIRST for Sign In/Continue specifically (never
        // cookies/popup, which already worked), falling back to the
        // existing tap/key-press chain if it's off or doesn't find the
        // button. Requires the admin to manually enable it once in
        // Settings > Accessibility (Android requires this for every
        // accessibility service, no silent path exists) -- a new status
        // card on the credential picker screen shows ON/OFF and links
        // straight to that settings screen. Scoped via packageNames so it
        // can only ever see this app's own content, nothing else on the
        // phone. (2) Export CSV on the Credentials Hub page was failing
        // with "Couldn't start the download" -- root cause: it used a
        // blob: URL, which Android's DownloadManager can only ever reject
        // (it only fetches plain http/https URLs), unlike every other
        // working export button on the site which already used a real URL.
        // Fixed on the website side to POST through a normal server route
        // instead, matching those. (3) Recovered the existing (but
        // Users-panel-only) "clear this client's bot conversation" action
        // and added it in two more places: a bulk "Delete Chat History"
        // button on the Credentials Hub table (for selected rows with a
        // linked client), and a single button on each client's own profile
        // page next to Message History -- website-only, no app changes.
        // (4) Weak-network stability: ATTEMPT_TIMEOUT_MS raised 55s -> 75s
        // (a slow-but-still-working page load was being cut off and
        // wrongly reported as a timed-out login test); ApiClient's OkHttp
        // timeouts raised 15s -> 30s for the same reason on every server
        // call (login test reporting, credential list, etc.); FullSiteScreen
        // now auto-retries the first two consecutive main-frame load
        // failures (with a short increasing delay) before showing the
        // "can't reach the server" screen, instead of giving up on the very
        // first transient blip -- likely the real cause of the reported
        // "page reloading instabilities" on a weak connection.
        // Round 48p hotfix 2 (contrast pass, in response to the user's
        // explicit "make the backgrounds and page contents contrasting"
        // request when closing the project): Material3's default onPrimary
        // (white) doesn't actually read clearly on every one of this app's
        // 5 selectable accent colors -- measured white-on-Green at ~3.3:1
        // and white-on-Orange at ~3.6:1, both under the 4.5:1 WCAG AA
        // minimum for normal text, meaning filled Button/FAB labels in
        // those themes were genuinely hard to read. AppearancePrefs now
        // computes, per accent, whichever of pure black/white has the
        // higher measured contrast against that exact color (via the same
        // relative-luminance formula WCAG itself defines) and Theme.kt
        // passes that as onPrimary explicitly, instead of trusting
        // Material3's one-size-fits-all default. No accent color itself
        // changed -- only the text drawn on top of it, and only where it
        // was actually hard to read.
        // Round 48p hotfix 3: Export CSV was still failing on-device
        // ("Download unsuccessful") even after hotfix 2's POST-to-server
        // fix -- root cause is that Android's DownloadManager (which every
        // WebView download goes through) always issues its own fresh GET
        // request no matter how the page got there, so a POST-only export
        // route just 405s on that silent internal GET every time. Fixed
        // for real this time: a new AndroidFileSaverBridge exposes
        // window.AndroidFileSaver to the page's JS, and exportToCSV() now
        // hands the CSV text straight to it -- written directly to
        // Downloads with no network request and no DownloadManager
        // involved at all, so this exact failure mode can't recur. See
        // AndroidFileSaverBridge's own doc comment in FullSiteScreen.kt.
        // Round 48p (6th update): investigated "push alerts never arrive,
        // not even silently" (registration/payment/trial/broadcast). The
        // server side (admin/push.py, and every caller in registration.py/
        // payment.py/trial.py/the cron/ scripts) still always queues to
        // admin_notifications first, poll-based and Firebase-independent --
        // unchanged, and confirmed still wired correctly. What's new here:
        // SoundSettingsScreen (App Settings -> Notifications) now shows a
        // real "System notifications: ON/OFF" status card at the top,
        // exactly like the existing Auto-click helper card, backed by
        // NotificationManagerCompat.areNotificationsEnabled() -- the actual
        // OS-level gate that sits in front of FCM, the foreground poll, AND
        // the WorkManager backstop alike (they all funnel through
        // NotificationChannels.postSystemNotification's one notify() call).
        // If POST_NOTIFICATIONS was ever denied (at the first-launch prompt,
        // or later via the OS's own per-app Notifications switch), nothing
        // anywhere throws or logs -- notify() just silently does nothing,
        // which matches the reported symptom exactly even with DND off and
        // battery optimization already unrestricted. This card turns that
        // from a guess into a direct, on-screen answer, with a one-tap
        // "Enable" button straight to this app's system notification
        // settings when it's off.
        // Round 49: Sign In/Continue STILL failed to register even with the
        // Accessibility Service confirmed ON (round 48p's ACTION_CLICK
        // node-click escalation) -- reported as a consistent TIMEOUT, the
        // real login page visibly still sitting there with the button
        // untouched. ACTION_CLICK depends on Chromium having already
        // exposed the WebView's DOM as an accessibility node tree, which
        // isn't guaranteed to have happened for a given WebView instance;
        // nativeTap (dispatchTouchEvent, same-process) produces a
        // MotionEvent with no real touchscreen input-device source, which
        // some touch handlers can tell apart from a genuine tap. This adds
        // a further escalation ABOVE both: AccessibilityService.dispatchGesture,
        // a real gesture injected through the actual system input pipeline
        // (the same mechanism TalkBack/Switch Access use) at the exact
        // on-screen point the login page's JS already computed -- no node
        // tree needed, and indistinguishable from a real finger tap at the
        // OS level. Requires android:canPerformGestures="true" (added to
        // utd_accessibility_service_config.xml this round -- without it,
        // dispatchGesture silently does nothing even with the service
        // otherwise fully enabled). Falls back to the existing nativeTap
        // automatically whenever the service isn't active or the gesture
        // isn't accepted, so nothing changes for anyone who hasn't turned
        // the Accessibility Service on. Every status line during a login
        // attempt now also tags which mechanism actually fired (e.g.
        // "[accessibility: gesture tap]" / "[in-app tap]") so a future
        // screenshot of a stuck attempt says which mechanism ran instead of
        // just the generic step name.
        // Round 54: Pause/Resume control added to the Sequential Login screen
        // (SequentialLoginScreen.kt) -- freezes the batch where it is without
        // losing any gathered results, resumes cleanly from the same queue
        // position. Pure batch control flow; no change to the click mechanism.
        // Round 55: per-attempt give-up timeout shortened 75s -> 15s
        // (LoginAutomation.kt ATTEMPT_TIMEOUT_MS) so the batch moves on
        // quickly from any credential that isn't tapped, instead of parking
        // 75s on it. Both changes fold into this single build/deploy; no
        // existing feature removed.
        // Round 57: offline users/credentials database (Room), add/edit
        // while fully disconnected, sync-with-conflict-prompt on reconnect,
        // and an on-device local backup file -- see data/offline/ and the
        // new Local Backup screen off App Settings.
        // Round 58: (1) offline DELETE added to Offline Users/Credentials
        // (Round 57 deliberately left this out; now added per explicit
        // admin request), with the same conflict-check/prompt as an edit --
        // see OfflineEntities.kt's pendingDelete, SyncRepository.kt's
        // delete handling, and admin/api_routes.py's matching server-side
        // delete branches. (2) New Full Site Backup screen (server-backed,
        // separate from the existing lightweight Local Backup) -- pick
        // Users/Credentials/Database/Code, any combination, downloaded via
        // new /api/v1/backup/* routes that reuse the web admin panel's own
        // Round 25 backup/restore engine (no new backup logic anywhere);
        // the stored file is encrypted at rest (Android Keystore) since it
        // can contain the full database and, if selected, the site's
        // source code. Only the database piece is restorable from the
        // phone -- see FullSiteBackupManager.kt's doc comment for why code
        // restore isn't offered (the web panel doesn't support that
        // either). (3) A persistent "You're offline -- Edit Offline"
        // banner now shows over the live admin panel WebView itself
        // whenever the phone has no connection (data/offline/
        // NetworkStatus.kt), plus an "Edit Offline Instead" link on the
        // existing "can't reach the admin panel" fallback page -- both
        // jump straight to the native offline screens without touching
        // the site's own HTML/JS at all.
        versionCode = 35
        versionName = "1.4.29"
        buildConfigField("String", "GIT_SHA", "\"${gitShaForBuild()}\"")
    }

    // Round 48e: THE likely real root cause of "every fix builds fine and
    // 'installs' but nothing ever actually changes on the phone." No
    // signingConfig existed anywhere in this project and no debug.keystore
    // was ever committed to the repo -- which meant every single build was
    // signed with the Android Gradle Plugin's DEFAULT debug keystore,
    // auto-generated on demand at ~/.android/debug.keystore. That's fine
    // for one machine used repeatedly, but GitHub Actions' ubuntu-latest
    // runners are thrown away after every run with no persisted home
    // directory -- so EVERY workflow run auto-generated a BRAND NEW random
    // debug key, meaning every app-debug.apk this project has ever produced
    // via GitHub Actions was signed differently from the one before it.
    // Android refuses to install an app over an existing install of the
    // SAME package name signed with a DIFFERENT key -- it fails outright
    // ("App not installed") rather than updating, and critically, that
    // failure can be very easy to miss in Termux's `termux-open` flow,
    // which just hands off to the system installer and doesn't itself
    // report success/failure back to the terminal. The result: every round
    // since whichever one first drifted to a new random key could have
    // silently failed to install, leaving whatever old build was already
    // on the phone completely untouched -- which matches "nothing ever
    // changes" exactly.
    //
    // Fixed by committing app/debug.keystore to the repo (standard Android
    // debug alias/passwords, harmless to check in -- this is exactly what
    // the stock ~/.android/debug.keystore already is, just persisted) and
    // pointing the debug build at it explicitly, so every future build --
    // Termux-local or GitHub Actions -- signs with this SAME key forever.
    //
    // ONE-TIME MANUAL STEP STILL REQUIRED: this only fixes builds from now
    // on. Whatever is currently installed on the phone was very likely
    // signed with one of the old random keys, so the very next install
    // attempt of a build using THIS keystore will also fail as a signature
    // mismatch unless the old app is uninstalled first. See this round's
    // deploy notes.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Round 32: reliable notifications need a background poll even when the
    // app isn't open (WorkManager), site-password auto-fill needs encrypted
    // on-device storage, and the app-lock feature needs BiometricPrompt for
    // fingerprint unlock.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")

    // 1.4.1: pinned explicitly to override the very old androidx.fragment
    // that androidx.biometric:1.1.0 pulls in transitively -- that old
    // version is the confirmed cause of the "Can only use lower 16 bits
    // for requestCode" crash-on-launch (see the versionCode changelog
    // comment above and MainActivity.kt's class doc comment for the full
    // story). Gradle resolves a single version per artifact across the
    // whole dependency graph, so declaring it here directly forces this
    // fixed version to win over biometric's outdated request.
    implementation("androidx.fragment:fragment-ktx:1.8.3")

    // Firebase Cloud Messaging -- push notifications for new payments /
    // registrations, exactly like the previous build. Requires a REAL
    // app/google-services.json for the "com.healthdataet.utdcredentials"
    // package, registered in the Firebase console (see TERMUX_SETUP.md,
    // Part D). The placeholder file checked into this repo lets the app
    // build and run WITHOUT push until that's done.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Talks to the panel's existing /api/v1 JSON API (admin/api_routes.py)
    // for login + push device-token registration + notification polling.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Round 57: local offline database (users/credentials mirror + pending
    // sync queue + conflict records) for the new Offline Data / Local
    // Backup feature. room-ktx adds Kotlin coroutine (suspend fun DAO
    // methods) support on top of plain room-runtime.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
