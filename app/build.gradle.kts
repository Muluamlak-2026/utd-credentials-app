plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.healthdataet.utdcredentials"
    compileSdk = 34

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
        versionCode = 8
        versionName = "1.4.2"
    }

    buildTypes {
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

    buildFeatures {
        compose = true
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
