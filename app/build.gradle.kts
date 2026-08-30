plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
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
        versionCode = 15
        versionName = "1.4.9"
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
}
