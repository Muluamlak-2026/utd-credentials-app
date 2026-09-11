package com.healthdataet.utdcredentials

import android.app.Application
import com.healthdataet.utdcredentials.data.offline.ConnectivitySyncTrigger
import com.healthdataet.utdcredentials.push.SyncWorker
import com.healthdataet.utdcredentials.util.CrashHandler

/**
 * Custom Application so CrashHandler.install() runs before ANY Activity
 * does -- including before MainActivity.onCreate, which is where the
 * previous OEM-specific RingtoneManager crash actually happened. Installing
 * it here means the safety net is in place for literally every line of app
 * code that ever runs, not just what happens to come after MainActivity's
 * own onCreate.
 */
class UtdCredentialsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // Round 57: registered process-wide (not per-Activity) so offline
        // sync fires the instant connectivity returns even while the app is
        // only backgrounded, not just while a screen is on-screen; the
        // periodic WorkManager backstop is the safety net for anything that
        // callback misses.
        ConnectivitySyncTrigger.register(this)
        SyncWorker.schedule(this)
    }
}
