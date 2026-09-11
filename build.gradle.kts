// Root build file -- declares plugin versions once; app/build.gradle.kts
// applies them without repeating a version (standard "plugins block"
// convention, keeps the two files from ever disagreeing on a version).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    // Round 57: Room's annotation processor, for the offline users/credentials
    // database. KSP (not the older kapt) since this project is already on
    // Kotlin 2.0 -- version pinned to the exact Kotlin version it's built
    // against, per KSP's own versioning scheme (<kotlin-version>-<ksp-version>).
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}
