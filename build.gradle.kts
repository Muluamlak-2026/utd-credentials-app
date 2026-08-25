// Root build file -- declares plugin versions once; app/build.gradle.kts
// applies them without repeating a version (standard "plugins block"
// convention, keeps the two files from ever disagreeing on a version).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}
