package com.healthdataet.utdcredentials

import android.Manifest
import android.os.Build
import android.os.Bundle
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
// unaffected. androidx.fragment is already on the classpath transitively via
// the biometric dependency, no new Gradle dependency needed.
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
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
