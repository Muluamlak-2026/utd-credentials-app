package com.healthdataet.utdcredentials

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.healthdataet.utdcredentials.push.NotificationChannels
import com.healthdataet.utdcredentials.push.NotificationPollWorker
import com.healthdataet.utdcredentials.ui.AppNavHost
import com.healthdataet.utdcredentials.ui.theme.UtdCredentialsTheme

// Round 32: FragmentActivity (not plain ComponentActivity) -- androidx.biometric
// 1.1.0's BiometricPrompt needs a FragmentActivity/Fragment host to attach its
// internal dialog fragment to; FragmentActivity itself still extends
// ComponentActivity, so setContent/registerForActivityResult below are
// unaffected.
//
// 1.4.1: THIS was the real crash-on-launch root cause, finally captured
// verbatim by v1.4.0's new CrashHandler instead of guessed at blind:
//   java.lang.IllegalArgumentException: Can only use lower 16 bits for
//   requestCode -- at FragmentActivity.checkForValidRequestCode, called from
//   ComponentActivity's activityResultRegistry, called from THIS file's
//   requestNotificationPermission.launch() below.
// Root cause: androidx.biometric 1.1.0 transitively pulls in a very old
// androidx.fragment (1.2.x), and that old FragmentActivity rejects any
// request code with bits above 16 set. The modern Activity Result API
// (registerForActivityResult, used below) deliberately generates request
// codes ABOVE that range on purpose, so they can never collide with a
// hand-picked legacy code -- a real, documented AndroidX incompatibility
// between an old transitive androidx.fragment and the modern Activity
// Result API, not a bug in this app's own logic. Fixed by pinning a
// current androidx.fragment-ktx directly in build.gradle.kts (see its
// dependencies block) so Gradle resolves the fixed version instead of
// biometric's old transitive one. The try/catch below is added on top as
// a second line of defense, same as every other pre-UI call in this
// method -- so even an unrelated future OEM/platform quirk here degrades
// to "no notification permission" instead of another crash-on-launch.
class MainActivity : FragmentActivity() {

    // Android 13+ requires this to be requested at runtime before any
    // notification can show -- harmless to ask up front; if denied, push
    // notifications just silently don't display (everything else in the
    // app still works normally).
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
                // See the class doc comment above -- this specific call is
                // the confirmed root cause of the earlier crash-on-launch.
                // Fixed at the dependency level, but guarded here too: worst
                // case without the permission is push notifications don't
                // show, which is infinitely better than the app not opening.
            }
        }

        // Round 48c: proactively ask to be exempted from battery
        // optimization -- on many phones (aggressive OEM battery/RAM
        // managers especially), NOT being exempted is exactly what kills
        // this app's whole process seconds after it's backgrounded (the
        // same underlying restriction behind the app-lock "locks
        // immediately" symptom -- see AppLockPrefs.lastBackgroundedAt) and
        // what delays or silently drops push notifications and the
        // ~15-minute background poll below. Shows the system's own
        // permission dialog; if denied, everything still works, just less
        // reliably in the background. Only asks again on a later launch
        // if it's still not exempted -- once granted, isIgnoringBatteryOptimizations
        // is true forever and this whole block is skipped.
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            // Not every OEM honors this the same way -- never worth
            // crashing over; the app just stays less reliable in the
            // background without it.
        }

        // Create the 5 per-category channels (registration / trial start /
        // expiry / payment / general) as early as possible, each with its
        // own distinct default sound -- so even a push that arrives before
        // the admin ever opens Notification Sounds still rings correctly.
        // Wrapped: this runs before setContent, i.e. before any UI shows --
        // an uncaught exception here previously meant an instant crash on
        // every launch with no screen ever appearing (fixed round: some OEM
        // builds throw reading the system default ringtone/alarm URI).
        try {
            NotificationChannels.ensureAll(applicationContext)
        } catch (e: Exception) {
            // Notification setup is never worth crashing the whole app over.
        }

        // Round 32: the ~15-minute WorkManager backstop that keeps alerting
        // even when the app isn't open at all -- see NotificationPollWorker's
        // doc comment. Safe/cheap to call on every launch (KEEP policy).
        try {
            NotificationPollWorker.schedule(applicationContext)
        } catch (e: Exception) {
            // Same reasoning -- background poll scheduling must never block
            // the app from actually opening.
        }

        setContent {
            UtdCredentialsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
