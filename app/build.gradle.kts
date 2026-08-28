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
        versionCode = 4
        versionName = "1.3"
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
