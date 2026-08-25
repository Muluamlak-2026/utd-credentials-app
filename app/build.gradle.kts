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
        versionCode = 1
        versionName = "1.0"
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
